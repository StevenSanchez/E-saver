/*
 * Alta/edición de una carga.
 *
 * Permite escribir el kWh y la duración, elegir la fecha (sin hora), asociar la
 * carga a un periodo manualmente y adjuntar/leer la foto del cargador.
 */
package cr.esaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cr.esaver.AppViewModel
import cr.esaver.CaptureMode
import cr.esaver.data.SessionDraft
import cr.esaver.domain.Analytics
import cr.esaver.domain.Billing
import cr.esaver.domain.Fmt
import cr.esaver.ui.components.ConfirmDialog
import cr.esaver.ui.components.InfoBanner
import cr.esaver.ui.components.LocalDateTimeSaver
import cr.esaver.ui.components.LocalPhoto
import cr.esaver.ui.components.NumberField
import cr.esaver.ui.components.PeriodPickerDialog
import cr.esaver.ui.components.PhotoViewerDialog
import cr.esaver.ui.components.PickerField
import cr.esaver.ui.components.SectionLabel
import cr.esaver.ui.components.BannerTone
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@Composable
fun SessionEditorScreen(
    vm: AppViewModel,
    sessionId: Long?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val bills by vm.bills.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val capture by vm.capture.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val session = remember(sessions, sessionId) {
        sessionId?.let { id -> sessions.firstOrNull { it.id == id } }
    }

    var initialized by rememberSaveable(sessionId) { mutableStateOf(false) }
    var dateTime by rememberSaveable(stateSaver = LocalDateTimeSaver) {
        mutableStateOf(LocalDateTime.now().withSecond(0).withNano(0))
    }
    var energy by rememberSaveable { mutableStateOf("") }
    var durationH by rememberSaveable { mutableStateOf("") }
    var durationM by rememberSaveable { mutableStateOf("") }
    var durationS by rememberSaveable { mutableStateOf("") }
    var voltage by rememberSaveable { mutableStateOf("") }
    var current by rememberSaveable { mutableStateOf("") }
    var power by rememberSaveable { mutableStateOf("") }
    var maxAmps by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var manualPeriod by rememberSaveable { mutableStateOf(false) }
    var periodOverride by rememberSaveable { mutableStateOf<String?>(null) }
    var ocrNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var ocrFailed by rememberSaveable { mutableStateOf(false) }

    var showCamera by rememberSaveable { mutableStateOf(false) }
    var showPhotoViewer by rememberSaveable { mutableStateOf(false) }
    var showPeriodPicker by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var energyError by rememberSaveable { mutableStateOf(false) }

    val estimateRate = remember(settings, bills) { Analytics.effectiveRate(settings, bills) }
    val autoPeriod = remember(dateTime, settings.cycleDay) {
        Billing.periodFor(dateTime.toLocalDate(), settings.cycleDay)
    }
    val shownPeriodKey = if (manualPeriod) (periodOverride ?: autoPeriod.key) else autoPeriod.key
    val shownPeriod = remember(shownPeriodKey, settings.cycleDay) {
        Billing.periodFromKey(shownPeriodKey, settings.cycleDay)
    }
    val parsedEnergy = Fmt.parseDecimal(energy)

    LaunchedEffect(loaded, sessionId, session?.id) {
        if (loaded && !initialized) {
            if (session != null) {
                dateTime = session.dateTime
                energy = Fmt.editable(session.energyKwh)
                val seconds = session.durationSec
                if (seconds != null) {
                    durationH = if (seconds / 3600 > 0) (seconds / 3600).toString() else ""
                    durationM = if ((seconds % 3600) / 60 > 0) ((seconds % 3600) / 60).toString() else ""
                    durationS = if (seconds % 60 > 0) (seconds % 60).toString() else ""
                }
                voltage = session.voltage?.toString() ?: ""
                current = session.currentA?.let { Fmt.editable(it) } ?: ""
                power = session.powerKw?.let { Fmt.editable(it) } ?: ""
                maxAmps = session.chargerMaxA?.toString() ?: ""
                notes = session.notes
                photoPath = session.photoPath
                manualPeriod = session.periodOverride != null
                periodOverride = session.periodOverride
            }
            initialized = true
        }
    }

    LaunchedEffect(capture?.id) {
        val payload = capture ?: return@LaunchedEffect
        if (payload.mode != CaptureMode.CHARGER) return@LaunchedEffect
        photoPath = payload.path
        val reading = payload.reading
        if (reading != null && reading.hasData) {
            reading.energyKwh?.let { energy = Fmt.editable(it) }
            reading.durationSec?.let { seconds ->
                durationH = if (seconds / 3600 > 0) (seconds / 3600).toString() else ""
                durationM = if ((seconds % 3600) / 60 > 0) ((seconds % 3600) / 60).toString() else ""
                durationS = if (seconds % 60 > 0) (seconds % 60).toString() else ""
            }
            reading.voltage?.let { voltage = it.toString() }
            reading.currentA?.let { current = Fmt.editable(it) }
            reading.powerKw?.let { power = Fmt.editable(it) }
            reading.chargerMaxA?.let { maxAmps = it.toString() }
            ocrNotice = buildString {
                append("Detectado: ")
                val parts = mutableListOf<String>()
                reading.energyKwh?.let { parts += Fmt.kwh(it) }
                reading.durationSec?.let { parts += Fmt.durationClock(it) }
                reading.voltage?.let { parts += "$it V" }
                reading.currentA?.let { parts += "${Fmt.compactNumber(it)} A" }
                reading.powerKw?.let { parts += "${Fmt.compactNumber(it)} kW" }
                append(parts.joinToString(" · "))
                append(". Revisa que coincida con la pantalla.")
            }
            ocrFailed = false
        } else {
            ocrNotice = "No se pudo leer la pantalla automáticamente. La foto quedó guardada; " +
                "revisa el kWh y escribe los datos a mano."
            ocrFailed = true
        }
        vm.consumeCapture()
    }

    DisposableEffect(Unit) {
        onDispose { vm.consumeCapture() }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch { vm.importFromGallery(uri, CaptureMode.CHARGER) }
        }
    }

    if (showCamera) {
        CameraScreen(
            vm = vm,
            mode = CaptureMode.CHARGER,
            onClose = { showCamera = false },
            onCaptured = { showCamera = false },
        )
    }
    if (showPhotoViewer && photoPath != null) {
        PhotoViewerDialog(path = photoPath!!, onDismiss = { showPhotoViewer = false })
    }
    if (showDatePicker) {
        cr.esaver.ui.components.DatePickerModal(
            initial = dateTime.toLocalDate(),
            onDismiss = { showDatePicker = false },
            onPick = { date ->
                dateTime = date.atTime(12, 0)
                if (!manualPeriod) periodOverride = null
            },
        )
    }
    if (showPeriodPicker) {
        PeriodPickerDialog(
            selectedKey = shownPeriodKey,
            cycleDay = settings.cycleDay,
            allowAuto = true,
            onSelect = { key ->
                manualPeriod = true
                periodOverride = key
                showPeriodPicker = false
            },
            onAuto = {
                manualPeriod = false
                periodOverride = null
            },
            onDismiss = { showPeriodPicker = false },
        )
    }
    if (showDelete && session != null) {
        ConfirmDialog(
            title = "¿Eliminar esta carga?",
            message = "Se borrará la carga de ${Fmt.kwh(session.energyKwh)} del " +
                Fmt.date(session.dateTime.toLocalDate()) + " y su foto.",
            confirmLabel = "Eliminar carga",
            onConfirm = {
                scope.launch {
                    if (vm.deleteSession(session)) onClose()
                }
            },
            onDismiss = { showDelete = false },
        )
    }

    if (!initialized) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .background(MaterialTheme.colorScheme.background),
    ) {
        EditorTopBar(
            title = if (sessionId == null) "Nueva carga" else "Editar carga",
            onBack = onClose,
            onDelete = if (sessionId != null) ({ showDelete = true }) else null,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (ocrNotice != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (ocrFailed) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = ocrNotice ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { ocrNotice = null }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Cerrar aviso")
                            }
                        }
                    }
                }
            }

            item {
                SectionLabel("Consumo")
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = energy,
                    onValueChange = {
                        energy = it.take(12)
                        if (it.isNotBlank()) energyError = false
                    },
                    label = { Text("Energía consumida") },
                    suffix = { Text("kWh") },
                    textStyle = MaterialTheme.typography.headlineSmall,
                    singleLine = true,
                    isError = energyError,
                    supportingText = if (energyError) {
                        { Text("Escribe los kWh que marcó el cargador") }
                    } else null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                val batteryPercent = parsedEnergy
                    ?.takeIf { it > 0.0 }
                    ?.let { Analytics.batteryPercent(it, settings.batteryKwh) }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Costo estimado",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = Fmt.rate(estimateRate.rate) + " · " + estimateRate.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                )
                            }
                            Text(
                                text = if (parsedEnergy != null && parsedEnergy > 0) {
                                    Fmt.money(parsedEnergy * estimateRate.rate)
                                } else "—",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (batteryPercent != null) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.BatteryChargingFull,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    text = "≈ $batteryPercent% de la batería · ${settings.carName} " +
                                        "(${Fmt.kwh(settings.batteryKwh)})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                val batteryTooSmall = settings.batteryKwh > 0.0 &&
                    parsedEnergy != null && parsedEnergy > settings.batteryKwh * 1.15
                if (batteryTooSmall) {
                    Spacer(Modifier.height(8.dp))
                    InfoBanner(
                        text = "Este consumo supera la capacidad de tu batería " +
                            "(${Fmt.kwh(settings.batteryKwh)}). Revisa si el punto decimal del " +
                            "kWh quedó bien, o actualiza la capacidad en Ajustes.",
                        tone = BannerTone.WARNING,
                    )
                }
            }

            item {
                PickerField(
                    label = "Fecha de la carga",
                    value = Fmt.date(dateTime.toLocalDate()),
                    icon = Icons.Rounded.CalendarMonth,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showDatePicker = true },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Solo se guarda la fecha. La hora de la carga no se usa; " +
                        "lo que importa para el periodo es el día.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                SectionLabel("Duración de la carga")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        value = durationH,
                        onValueChange = { durationH = it.filter { c -> c.isDigit() }.take(2) },
                        label = "Horas",
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number,
                    )
                    NumberField(
                        value = durationM,
                        onValueChange = { durationM = it.filter { c -> c.isDigit() }.take(2) },
                        label = "Minutos",
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number,
                    )
                    NumberField(
                        value = durationS,
                        onValueChange = { durationS = it.filter { c -> c.isDigit() }.take(2) },
                        label = "Segundos",
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number,
                    )
                }
            }

            item {
                SectionLabel("Periodo de facturación")
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = if (manualPeriod) "Periodo ajustado a mano" else "Periodo automático",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = shownPeriod.rangeLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = manualPeriod,
                                onCheckedChange = { checked ->
                                    manualPeriod = checked
                                    periodOverride = if (checked) shownPeriodKey else null
                                },
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { showPeriodPicker = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Rounded.CalendarMonth,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("${shownPeriod.receiptLabel} · ${shownPeriod.rangeLabel}")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "El periodo se calcula con el día de corte ${settings.cycleDay}. " +
                                "Una carga del día ${settings.cycleDay} cuenta para el periodo que inicia.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionLabel("Foto del cargador")
                Spacer(Modifier.height(6.dp))
                if (photoPath == null) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "Toma una foto de la pantalla y detectamos los datos",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        vm.consumeCapture()
                                        showCamera = true
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        Icons.Rounded.CameraAlt,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Cámara")
                                }
                                OutlinedButton(
                                    onClick = {
                                        vm.consumeCapture()
                                        galleryLauncher.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                                            ),
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        Icons.Rounded.PhotoLibrary,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Galería")
                                }
                            }
                        }
                    }
                } else {
                    Column {
                        LocalPhoto(
                            path = photoPath,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(170.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { showPhotoViewer = true },
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(
                                onClick = {
                                    vm.consumeCapture()
                                    showCamera = true
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Rounded.CameraAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Repetir")
                            }
                            TextButton(
                                onClick = {
                                    vm.consumeCapture()
                                    galleryLauncher.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                                        ),
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Rounded.PhotoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Galería")
                            }
                            TextButton(
                                onClick = { photoPath = null; ocrNotice = null },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Quitar", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            item {
                SectionLabel("Datos del cargador (opcional)")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        value = voltage,
                        onValueChange = { voltage = it.filter { c -> c.isDigit() }.take(3) },
                        label = "Voltaje",
                        suffix = "V",
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number,
                    )
                    NumberField(
                        value = current,
                        onValueChange = { current = it.take(8) },
                        label = "Corriente",
                        suffix = "A",
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        value = power,
                        onValueChange = { power = it.take(8) },
                        label = "Potencia",
                        suffix = "kW",
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        value = maxAmps,
                        onValueChange = { maxAmps = it.filter { c -> c.isDigit() }.take(3) },
                        label = "Máx. cargador",
                        suffix = "A",
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number,
                    )
                }
            }

            item {
                SectionLabel("Notas")
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(300) },
                    label = { Text("¿Algo que recordar? (opcional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Button(
                    onClick = {
                        val parsed = Fmt.parseDecimal(energy)
                        if (parsed == null || parsed <= 0) {
                            energyError = true
                            return@Button
                        }
                        if (saving) return@Button
                        saving = true
                        val seconds = listOf(durationH, durationM, durationS)
                            .map { it.toIntOrNull() ?: 0 }
                            .let { (h, m, s) -> h * 3600 + m * 60 + s }
                        scope.launch {
                            val ok = vm.saveSession(
                                SessionDraft(
                                    id = sessionId,
                                    timestamp = dateTime.toLocalDate().atTime(12, 0)
                                        .atZone(ZoneId.systemDefault())
                                        .toInstant().toEpochMilli(),
                                    energyKwh = parsed,
                                    durationSec = seconds.takeIf { it > 0 },
                                    voltage = voltage.toIntOrNull(),
                                    currentA = Fmt.parseDecimal(current),
                                    powerKw = Fmt.parseDecimal(power),
                                    chargerMaxA = maxAmps.toIntOrNull(),
                                    photoPath = photoPath,
                                    notes = notes,
                                    periodOverride = if (manualPeriod) {
                                        periodOverride ?: autoPeriod.key
                                    } else null,
                                ),
                            )
                            saving = false
                            if (ok) onClose()
                        }
                    },
                    enabled = !saving && !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (sessionId == null) "Guardar carga" else "Guardar cambios")
                }
            }
        }
    }
}

@Composable
fun EditorTopBar(
    title: String,
    onBack: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "Volver")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "Eliminar",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
