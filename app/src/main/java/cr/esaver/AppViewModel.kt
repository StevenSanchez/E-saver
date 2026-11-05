/*
 * Estado compartido de E-saver.
 *
 * Mantiene en memoria las cargas, los recibos y los ajustes, y expone las
 * operaciones de guardado/borrado a las pantallas. La persistencia real vive
 * en EsaverRepository (SQLite) y SettingsStore (DataStore).
 */
package cr.esaver

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cr.esaver.data.AppSettings
import cr.esaver.data.Bill
import cr.esaver.data.BillDraft
import cr.esaver.data.ChargeSession
import cr.esaver.data.ChargerReading
import cr.esaver.data.EsaverRepository
import cr.esaver.data.EstimateSource
import cr.esaver.data.PhotoStore
import cr.esaver.data.SessionDraft
import cr.esaver.data.SettingsStore
import cr.esaver.data.ThemeMode
import cr.esaver.domain.Analytics
import cr.esaver.domain.Billing
import cr.esaver.domain.ChargerOcrParser
import cr.esaver.domain.Fmt
import cr.esaver.domain.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class CaptureMode { CHARGER, BILL }

data class CapturePayload(
    val id: Long = System.nanoTime(),
    val path: String,
    val reading: ChargerReading? = null,
    val mode: CaptureMode,
)

/**
 * ViewModel único de la app.
 *
 * Se comparte entre todas las rutas para que Inicio, Cargas, Recibos y Ajustes
 * vean siempre los mismos datos y el mismo periodo seleccionado.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = EsaverRepository(application)
    private val settingsStore = SettingsStore(application)
    private val photoStore = PhotoStore(application)
    private val ocrEngine = OcrEngine(application)

    val settings: StateFlow<AppSettings> = settingsStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    private val _sessions = MutableStateFlow<List<ChargeSession>>(emptyList())
    val sessions: StateFlow<List<ChargeSession>> = _sessions.asStateFlow()

    private val _bills = MutableStateFlow<List<Bill>>(emptyList())
    val bills: StateFlow<List<Bill>> = _bills.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _selectedPeriod = MutableStateFlow(Billing.todayKey(21))
    val selectedPeriod: StateFlow<String> = _selectedPeriod.asStateFlow()

    private val _capture = MutableStateFlow<CapturePayload?>(null)
    val capture: StateFlow<CapturePayload?> = _capture.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        // El periodo inicial debe usar el día de corte real, no el valor por defecto.
        viewModelScope.launch {
            runCatching { settingsStore.settings.first() }.onSuccess { stored ->
                _selectedPeriod.value = Billing.todayKey(stored.cycleDay)
            }
        }
        viewModelScope.launch {
            reload()
        }
    }

    /** Periodo de facturación que contiene la fecha de hoy. */
    fun currentPeriodKey(): String = Billing.todayKey(settings.value.cycleDay)

    /** Cambia el periodo que están viendo Inicio y Cargas. */
    fun setSelectedPeriod(key: String) {
        _selectedPeriod.value = key
    }

    /** Mueve el periodo seleccionado uno o varios meses. */
    fun shiftPeriod(months: Long) {
        _selectedPeriod.value = Billing.shiftKey(_selectedPeriod.value, months)
    }

    fun resetPeriodToToday() {
        _selectedPeriod.value = currentPeriodKey()
    }

    fun summaryFor(key: String) = Analytics.summary(
        key = key,
        sessions = _sessions.value,
        bills = _bills.value,
        settings = settings.value,
    )

    fun clearError() {
        _error.value = null
    }

    fun consumeCapture() {
        _capture.value = null
    }

    /**
     * Guarda una carga y recalcula su periodo. Si la foto cambió, borra la
     * anterior para no dejar archivos huérfanos.
     */
    suspend fun saveSession(draft: SessionDraft): Boolean {
        return runCatching {
            val cycleDay = settings.value.cycleDay
            val date = Instant.ofEpochMilli(draft.timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val key = draft.periodOverride ?: Billing.periodFor(date, cycleDay).key
            val previous = draft.id?.let { id -> _sessions.value.firstOrNull { it.id == id } }
            val entity = ChargeSession(
                id = draft.id ?: 0L,
                timestamp = draft.timestamp,
                energyKwh = draft.energyKwh,
                durationSec = draft.durationSec,
                voltage = draft.voltage,
                currentA = draft.currentA,
                powerKw = draft.powerKw,
                chargerMaxA = draft.chargerMaxA,
                photoPath = draft.photoPath,
                notes = draft.notes.trim(),
                periodKey = key,
                periodOverride = draft.periodOverride,
            )
            val saved = withContext(Dispatchers.IO) { repository.saveSession(entity) }
            if (saved <= 0L) error("No se pudo guardar la carga")
            if (previous?.photoPath != null && previous.photoPath != draft.photoPath) {
                withContext(Dispatchers.IO) { photoStore.delete(previous.photoPath) }
            }
            withContext(Dispatchers.IO) { reloadNow() }
            true
        }.getOrElse { throwable ->
            _error.value = throwable.message ?: "No se pudo guardar la carga"
            false
        }
    }

    suspend fun deleteSession(session: ChargeSession): Boolean {
        return runCatching {
            withContext(Dispatchers.IO) {
                repository.deleteSession(session.id)
                photoStore.delete(session.photoPath)
                reloadNow()
            }
            true
        }.getOrElse {
            _error.value = "No se pudo eliminar la carga"
            false
        }
    }

    /**
     * Guarda el recibo de un periodo. Solo puede existir uno por periodo: si
     * ya hay otro, se actualiza ese en lugar de duplicarlo.
     */
    suspend fun saveBill(draft: BillDraft): Boolean {
        return runCatching {
            require(draft.totalAmount > 0) { "El monto total debe ser mayor que cero" }
            require(draft.totalKwh > 0) { "El consumo del recibo debe ser mayor que cero" }
            val previous = draft.id?.let { id -> _bills.value.firstOrNull { it.id == id } }
                ?: _bills.value.firstOrNull { it.periodKey == draft.periodKey }
            val entity = Bill(
                id = draft.id ?: 0L,
                periodKey = draft.periodKey,
                totalAmount = draft.totalAmount,
                totalKwh = draft.totalKwh,
                energyAmount = draft.energyAmount,
                taxAmount = draft.taxAmount,
                fireAmount = draft.fireAmount,
                invoiceNumber = draft.invoiceNumber.trim(),
                clientNumber = draft.clientNumber.trim(),
                meterNumber = draft.meterNumber.trim(),
                readingFrom = draft.readingFrom,
                readingTo = draft.readingTo,
                photoPath = draft.photoPath,
                notes = draft.notes.trim(),
            )
            val saved = withContext(Dispatchers.IO) { repository.saveBill(entity) }
            if (saved <= 0L) error("No se pudo guardar el recibo")
            if (previous?.photoPath != null && previous.photoPath != draft.photoPath) {
                withContext(Dispatchers.IO) { photoStore.delete(previous.photoPath) }
            }
            withContext(Dispatchers.IO) { reloadNow() }
            true
        }.getOrElse { throwable ->
            _error.value = throwable.message ?: "No se pudo guardar el recibo"
            false
        }
    }

    suspend fun deleteBill(bill: Bill): Boolean {
        return runCatching {
            withContext(Dispatchers.IO) {
                repository.deleteBill(bill.id)
                photoStore.delete(bill.photoPath)
                reloadNow()
            }
            true
        }.getOrElse {
            _error.value = "No se pudo eliminar el recibo"
            false
        }
    }

    suspend fun updateCycleDay(day: Int) {
        runCatching {
            val safe = day.coerceIn(1, 31)
            settingsStore.setCycleDay(safe)
            withContext(Dispatchers.IO) {
                repository.recalculatePeriods(safe)
                reloadNow()
            }
            _selectedPeriod.value = Billing.todayKey(safe)
        }.onFailure { _error.value = "No se pudo actualizar el día de corte" }
    }

    suspend fun updateApproxRate(rate: Double) {
        if (rate > 0) settingsStore.setApproxCostPerKwh(rate)
    }

    suspend fun updateEstimateSource(source: EstimateSource) {
        settingsStore.setEstimateSource(source)
    }

    suspend fun updateTheme(mode: ThemeMode) {
        settingsStore.setTheme(mode)
    }

    suspend fun updateCarProfile(name: String, batteryKwh: Double) {
        settingsStore.setCarProfile(name, batteryKwh)
    }

    suspend fun recalculatePeriods() {
        runCatching {
            withContext(Dispatchers.IO) {
                repository.recalculatePeriods(settings.value.cycleDay)
                reloadNow()
            }
        }.onFailure { _error.value = "No se pudieron recalcular los periodos" }
    }

    suspend fun deleteEverything() {
        runCatching {
            withContext(Dispatchers.IO) {
                repository.deleteAll()
                photoStore.clearAll()
                reloadNow()
            }
            _selectedPeriod.value = currentPeriodKey()
        }.onFailure { _error.value = "No se pudieron borrar los datos" }
    }

    /**
     * Guarda la foto tomada con la cámara y, si es del cargador, ejecuta el OCR.
     * El resultado se publica en [capture] para que el editor lo aplique.
     */
    suspend fun processCapture(file: File, mode: CaptureMode): Boolean {
        _busy.value = true
        return try {
            val path = withContext(Dispatchers.IO) { photoStore.importFromFile(file) }
            withContext(Dispatchers.IO) { runCatching { file.delete() } }
            if (path == null) {
                _error.value = "No se pudo procesar la foto"
                false
            } else {
                val reading = if (mode == CaptureMode.CHARGER) {
                    val result = withContext(Dispatchers.IO) { ocrEngine.analyze(path) }
                    if (result.hasData) result else ChargerReading(rawText = result.rawText)
                } else {
                    null
                }
                _capture.value = CapturePayload(path = path, reading = reading, mode = mode)
                true
            }
        } catch (t: Throwable) {
            _error.value = "No se pudo leer la foto: ${t.message ?: "error desconocido"}"
            false
        } finally {
            _busy.value = false
        }
    }

    /** Importa una foto de la galería y hace el mismo proceso que la cámara. */
    suspend fun importFromGallery(uri: Uri, mode: CaptureMode): Boolean {
        _busy.value = true
        return try {
            val path = withContext(Dispatchers.IO) { photoStore.importFromUri(uri) }
            if (path == null) {
                _error.value = "No se pudo importar la imagen"
                false
            } else {
                val reading = if (mode == CaptureMode.CHARGER) {
                    val result = withContext(Dispatchers.IO) { ocrEngine.analyze(path) }
                    if (result.hasData) result else ChargerReading(rawText = result.rawText)
                } else {
                    null
                }
                _capture.value = CapturePayload(path = path, reading = reading, mode = mode)
                true
            }
        } catch (t: Throwable) {
            _error.value = "No se pudo leer la imagen: ${t.message ?: "error desconocido"}"
            false
        } finally {
            _busy.value = false
        }
    }

    /** Genera el CSV de cargas y recibos para exportar el respaldo. */
    fun buildCsv(): String {
        val sessions = _sessions.value.sortedBy { it.timestamp }
        val bills = _bills.value.sortedBy { it.periodKey }
        val sb = StringBuilder()
        sb.appendLine("E-saver - exportado ${java.time.LocalDate.now()}")
        sb.appendLine()
        sb.appendLine("CARGAS")
        sb.appendLine("Periodo;Fecha;Energia kWh;Duracion;Voltaje V;Corriente A;Potencia kW;Notas")
        val dtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        sessions.forEach { session ->
            val dt = session.dateTime
            sb.append(csvCell(Billing.receiptLabelFromKey(session.periodKey))).append(';')
            sb.append(dt.format(dtFormatter)).append(';')
            sb.append(String.format(Locale.US, "%.2f", session.energyKwh)).append(';')
            sb.append(csvCell(Fmt.durationClock(session.durationSec))).append(';')
            sb.append(session.voltage?.toString() ?: "").append(';')
            sb.append(session.currentA?.let { String.format(Locale.US, "%.1f", it) } ?: "").append(';')
            sb.append(session.powerKw?.let { String.format(Locale.US, "%.2f", it) } ?: "").append(';')
            sb.append(csvCell(session.notes))
            sb.appendLine()
        }
        sb.appendLine()
        sb.appendLine("RECIBOS")
        sb.appendLine("Periodo;Total CRC;kWh facturados;CRC por kWh;Consumo carro kWh;Monto carro CRC;Factura;Notas")
        bills.forEach { bill ->
            val carKwh = sessions.filter { it.periodKey == bill.periodKey }.sumOf { it.energyKwh }
            val carAmount = if (bill.totalKwh > 0) bill.totalAmount / bill.totalKwh * carKwh else 0.0
            sb.append(csvCell(Billing.receiptLabelFromKey(bill.periodKey))).append(';')
            sb.append(String.format(Locale.US, "%.2f", bill.totalAmount)).append(';')
            sb.append(String.format(Locale.US, "%.2f", bill.totalKwh)).append(';')
            sb.append(String.format(Locale.US, "%.2f", bill.costPerKwh)).append(';')
            sb.append(String.format(Locale.US, "%.2f", carKwh)).append(';')
            sb.append(String.format(Locale.US, "%.2f", carAmount)).append(';')
            sb.append(csvCell(bill.invoiceNumber)).append(';')
            sb.append(csvCell(bill.notes))
            sb.appendLine()
        }
        return sb.toString()
    }

    fun csvFileName(): String =
        "esaver_${java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))}.csv"

    private fun csvCell(value: String): String =
        "\"" + value.replace("\"", "\"\"").replace("\n", " ") + "\""

    private suspend fun reload() {
        try {
            withContext(Dispatchers.IO) { reloadNow() }
        } catch (t: Throwable) {
            _error.value = t.message ?: "No se pudieron cargar los datos"
            _loaded.value = true
        }
    }

    private fun reloadNow() {
        val sessions = repository.sessions()
        val bills = repository.bills()
        _sessions.value = sessions
        _bills.value = bills
        val used = buildSet {
            sessions.mapNotNullTo(this) { it.photoPath }
            bills.mapNotNullTo(this) { it.photoPath }
            _capture.value?.path?.let { add(it) }
        }
        photoStore.cleanup(used)
        _loaded.value = true
    }
}
