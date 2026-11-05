/*
 * Ajustes de la app.
 *
 * Ciclo de facturación, tarifa aproximada, base de la estimación, tema, datos
 * del carro, respaldo CSV y borrado de datos locales.
 */
package cr.esaver.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cr.esaver.AppViewModel
import cr.esaver.data.EstimateSource
import cr.esaver.data.ExportStore
import cr.esaver.data.ThemeMode
import cr.esaver.domain.Analytics
import cr.esaver.domain.Billing
import cr.esaver.domain.Fmt
import cr.esaver.ui.components.AppCard
import cr.esaver.ui.components.ConfirmDialog
import cr.esaver.ui.components.InfoBanner
import cr.esaver.ui.components.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val bills by vm.bills.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val effectiveRate = remember(settings, bills) { Analytics.effectiveRate(settings, bills) }
    val currentPeriod = remember(settings.cycleDay) {
        Billing.periodFor(LocalDate.now(), settings.cycleDay)
    }

    var showCycleDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteAll by rememberSaveable { mutableStateOf(false) }
    var cycleInput by rememberSaveable { mutableStateOf(settings.cycleDay.toString()) }
    var rateText by rememberSaveable { mutableStateOf("") }
    var carNameText by rememberSaveable { mutableStateOf("") }
    var batteryText by rememberSaveable { mutableStateOf("") }
    var carDirty by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings.approxCostPerKwh) {
        val current = Fmt.parseDecimal(rateText)
        if (current == null ||
            kotlin.math.abs(current - settings.approxCostPerKwh) > 0.0001
        ) {
            rateText = Fmt.editable(settings.approxCostPerKwh)
        }
    }

    LaunchedEffect(loaded, settings.carName, settings.batteryKwh) {
        if (loaded && !carDirty) {
            carNameText = if (settings.carName == "Mi carro") "" else settings.carName
            batteryText = if (settings.batteryKwh > 0.0) Fmt.editable(settings.batteryKwh) else ""
        }
    }

    if (showCycleDialog) {
        AlertDialog(
            onDismissRequest = { showCycleDialog = false },
            title = { Text("Día de corte") },
            text = {
                Column {
                    Text(
                        "Es el día del mes en que termina el periodo de facturación. " +
                            "En tu recibo aparece como «servicio brindado hasta».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = cycleInput,
                        onValueChange = { value -> cycleInput = value.filter { it.isDigit() }.take(2) },
                        label = { Text("Día (1 a 31)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        singleLine = true,
                        isError = (cycleInput.toIntOrNull() ?: 0) !in 1..31,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val day = cycleInput.toIntOrNull()
                        if (day != null && day in 1..31) {
                            showCycleDialog = false
                            scope.launch { vm.updateCycleDay(day) }
                        }
                    },
                ) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { showCycleDialog = false }) { Text("Cancelar") }
            },
        )
    }

    if (showDeleteAll) {
        ConfirmDialog(
            title = "¿Borrar todos los datos?",
            message = "Se eliminarán todas las cargas, recibos y fotos. Esta acción no se puede deshacer.",
            confirmLabel = "Borrar todo",
            onConfirm = { scope.launch { vm.deleteEverything() } },
            onDismiss = { showDeleteAll = false },
        )
    }

    if (!loaded) {
        Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionLabel("Configuración")
            Spacer(Modifier.height(4.dp))
            Text("Ajustes", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "El ciclo de facturación y el costo estimado",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            SectionLabel("Mi carro")
            Spacer(Modifier.height(2.dp))
            val parsedBattery = if (batteryText.isBlank()) 0.0 else Fmt.parseDecimal(batteryText)
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.BatteryChargingFull,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Carro y batería", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (settings.batteryKwh > 0) {
                                "Con esto calculamos las cargas completas y el % por carga"
                            } else {
                                "Agrega la capacidad para ver cuántas cargas completas llevas"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = carNameText,
                    onValueChange = {
                        carNameText = it.take(40)
                        carDirty = true
                    },
                    label = { Text("Nombre o modelo") },
                    placeholder = { Text("Ej. BYD Dolphin") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = batteryText,
                        onValueChange = {
                            batteryText = it.take(10)
                            carDirty = true
                        },
                        label = { Text("Capacidad de la batería") },
                        suffix = { Text("kWh") },
                        singleLine = true,
                        isError = batteryText.isNotBlank() && (parsedBattery == null || parsedBattery <= 0),
                        supportingText = if (batteryText.isNotBlank() && (parsedBattery == null || parsedBattery <= 0)) {
                            { Text("Escribe una capacidad mayor que cero") }
                        } else null,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val battery = if (batteryText.isBlank()) 0.0 else Fmt.parseDecimal(batteryText)
                            if (battery != null && (batteryText.isBlank() || battery > 0)) {
                                scope.launch {
                                    vm.updateCarProfile(carNameText.ifBlank { "Mi carro" }, battery)
                                    carDirty = false
                                }
                            }
                        },
                        enabled = carDirty && (batteryText.isBlank() || (parsedBattery != null && parsedBattery > 0)),
                    ) {
                        Text("Guardar")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "La capacidad útil suele ser un poco menor que la nominal del fabricante. " +
                        "Se usa para convertir tus kWh en «cargas completas» y en % de batería.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionLabel("Facturación")
            Spacer(Modifier.height(2.dp))
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.CalendarMonth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Día de corte", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Día en que termina el ciclo del recibo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { if (settings.cycleDay > 1) scope.launch { vm.updateCycleDay(settings.cycleDay - 1) } },
                        enabled = settings.cycleDay > 1,
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Restar día")
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .clickable {
                                cycleInput = settings.cycleDay.toString()
                                showCycleDialog = true
                            },
                    ) {
                        Text(
                            text = settings.cycleDay.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                    IconButton(
                        onClick = { if (settings.cycleDay < 31) scope.launch { vm.updateCycleDay(settings.cycleDay + 1) } },
                        enabled = settings.cycleDay < 31,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Sumar día")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Periodo actual: ${currentPeriod.rangeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "El recibo cubre del día ${settings.cycleDay} de un mes al " +
                        "${settings.cycleDay} del siguiente. Las cargas del día de corte " +
                        "cuentan para el periodo que inicia. Si cambias este día, se " +
                        "recalculan las cargas (los ajustes manuales se conservan).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Payments,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Costo aproximado por kWh", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Solo para estimar antes de tener el recibo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = rateText,
                        onValueChange = { value -> rateText = value.take(10) },
                        label = { Text("Colones por kWh") },
                        prefix = { Text("₡ ") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    val parsed = Fmt.parseDecimal(rateText)
                    Button(
                        onClick = {
                            parsed?.let { value ->
                                scope.launch {
                                    vm.updateApproxRate(value)
                                    rateText = Fmt.editable(value)
                                }
                            }
                        },
                        enabled = parsed != null && parsed > 0 &&
                            kotlin.math.abs((parsed ?: 0.0) - settings.approxCostPerKwh) > 0.0001,
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Guardar")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Base de la estimación",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EstimateChip(
                        label = "Manual",
                        selected = settings.estimateSource == EstimateSource.MANUAL,
                        onClick = { scope.launch { vm.updateEstimateSource(EstimateSource.MANUAL) } },
                        modifier = Modifier.weight(1f),
                    )
                    EstimateChip(
                        label = "Último recibo",
                        selected = settings.estimateSource == EstimateSource.LAST_BILL,
                        onClick = { scope.launch { vm.updateEstimateSource(EstimateSource.LAST_BILL) } },
                        modifier = Modifier.weight(1f),
                    )
                    EstimateChip(
                        label = "Promedio",
                        selected = settings.estimateSource == EstimateSource.BILL_AVERAGE,
                        onClick = { scope.launch { vm.updateEstimateSource(EstimateSource.BILL_AVERAGE) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                InfoBanner(
                    title = "Tarifa usada: ${Fmt.rate(effectiveRate.rate)}",
                    text = effectiveRate.label +
                        ". El recibo real siempre manda: cuando lo registres, el monto pasa a ser oficial.",
                )
            }
        }

        item {
            SectionLabel("Apariencia")
            Spacer(Modifier.height(2.dp))
            AppCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip(
                        label = "Sistema",
                        icon = Icons.Rounded.SettingsBrightness,
                        selected = settings.theme == ThemeMode.SYSTEM,
                        onClick = { scope.launch { vm.updateTheme(ThemeMode.SYSTEM) } },
                        modifier = Modifier.weight(1f),
                    )
                    ThemeChip(
                        label = "Claro",
                        icon = Icons.Rounded.LightMode,
                        selected = settings.theme == ThemeMode.LIGHT,
                        onClick = { scope.launch { vm.updateTheme(ThemeMode.LIGHT) } },
                        modifier = Modifier.weight(1f),
                    )
                    ThemeChip(
                        label = "Oscuro",
                        icon = Icons.Rounded.DarkMode,
                        selected = settings.theme == ThemeMode.DARK,
                        onClick = { scope.launch { vm.updateTheme(ThemeMode.DARK) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            SectionLabel("Datos")
            Spacer(Modifier.height(2.dp))
            AppCard {
                SettingActionRow(
                    icon = Icons.Rounded.Share,
                    title = "Exportar CSV",
                    subtitle = "Comparte o respalda tus cargas y recibos",
                    onClick = {
                        val uri = ExportStore.writeCsv(context, vm.csvFileName(), vm.buildCsv())
                        if (uri != null) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(intent, "Compartir respaldo de E-saver"),
                            )
                        }
                    },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                SettingActionRow(
                    icon = Icons.Rounded.RestartAlt,
                    title = "Recalcular periodos",
                    subtitle = "Vuelve a asignar las cargas según el día de corte actual",
                    onClick = { scope.launch { vm.recalculatePeriods() } },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                SettingActionRow(
                    icon = Icons.Rounded.DeleteForever,
                    title = "Borrar todos los datos",
                    subtitle = "Elimina cargas, recibos y fotos de este teléfono",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { showDeleteAll = true },
                )
            }
        }

        item {
            SectionLabel("Acerca de")
            Spacer(Modifier.height(2.dp))
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("E-saver · versión 1.2", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Tus datos viven solo en este teléfono",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "La lectura de la pantalla del cargador usa ML Kit y funciona sin " +
                        "conexión. Si la foto no se lee bien, puedes corregir los datos a mano. " +
                        "Haz respaldos con «Exportar CSV» de vez en cuando.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    val totalFullCharges = Analytics.equivalentFullCharges(
                        sessions.sumOf { it.energyKwh },
                        settings.batteryKwh,
                    )
                    Text(
                        text = "${sessions.size} cargas y ${bills.size} recibos guardados" +
                            (totalFullCharges?.let { " · ${Fmt.number(it, 1)} cargas completas" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EstimateChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun ThemeChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 0.dp else 1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
