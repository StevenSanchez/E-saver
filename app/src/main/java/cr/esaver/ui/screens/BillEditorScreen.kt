/*
 * Alta/edición de un recibo.
 *
 * Solo pide lo necesario para calcular la tarifa efectiva: periodo, monto total
 * pagado y kWh facturados. La foto del recibo es opcional.
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
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cr.esaver.AppViewModel
import cr.esaver.CaptureMode
import cr.esaver.data.BillDraft
import cr.esaver.domain.Billing
import cr.esaver.domain.Fmt
import cr.esaver.ui.components.ConfirmDialog
import cr.esaver.ui.components.InfoBanner
import cr.esaver.ui.components.NumberField
import cr.esaver.ui.components.PeriodPickerDialog
import cr.esaver.ui.components.PhotoViewerDialog
import cr.esaver.ui.components.SectionLabel
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun BillEditorScreen(
    vm: AppViewModel,
    billId: Long?,
    initialPeriod: String?,
    onClose: () -> Unit,
) {
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val bills by vm.bills.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val capture by vm.capture.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val existingBill = remember(bills, billId) { billId?.let { id -> bills.firstOrNull { it.id == id } } }

    var initialized by rememberSaveable(billId) { mutableStateOf(false) }
    var periodKey by rememberSaveable { mutableStateOf(initialPeriod ?: vm.currentPeriodKey()) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var kwhText by rememberSaveable { mutableStateOf("") }
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }

    var showCamera by rememberSaveable { mutableStateOf(false) }
    var showPhotoViewer by rememberSaveable { mutableStateOf(false) }
    var showPeriodPicker by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var amountError by rememberSaveable { mutableStateOf(false) }
    var kwhError by rememberSaveable { mutableStateOf(false) }

    val period = remember(periodKey, settings.cycleDay) {
        Billing.periodFromKey(periodKey, settings.cycleDay)
    }
    val amount = Fmt.parseMoney(amountText)
    val totalKwh = Fmt.parseDecimal(kwhText)
    val previewRate = if (amount != null && totalKwh != null && totalKwh > 0) amount / totalKwh else null
    val carKwh = remember(periodKey, sessions) {
        sessions.filter { it.periodKey == periodKey }.sumOf { it.energyKwh }
    }
    val otherBill = remember(bills, periodKey, billId) {
        bills.firstOrNull { it.periodKey == periodKey && it.id != (billId ?: -1L) }
    }

    LaunchedEffect(loaded, existingBill?.id) {
        if (loaded && !initialized) {
            if (existingBill != null) {
                periodKey = existingBill.periodKey
                amountText = Fmt.editable(existingBill.totalAmount)
                kwhText = Fmt.editable(existingBill.totalKwh)
                photoPath = existingBill.photoPath
            } else if (initialPeriod != null) {
                periodKey = initialPeriod
            }
            initialized = true
        }
    }

    LaunchedEffect(capture?.id) {
        val payload = capture ?: return@LaunchedEffect
        if (payload.mode != CaptureMode.BILL) return@LaunchedEffect
        photoPath = payload.path
        vm.consumeCapture()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch { vm.importFromGallery(uri, CaptureMode.BILL) }
        }
    }

    if (showCamera) {
        CameraScreen(
            vm = vm,
            mode = CaptureMode.BILL,
            onClose = { showCamera = false },
            onCaptured = { showCamera = false },
        )
    }
    if (showPhotoViewer && photoPath != null) {
        PhotoViewerDialog(path = photoPath!!, onDismiss = { showPhotoViewer = false })
    }
    if (showPeriodPicker) {
        PeriodPickerDialog(
            selectedKey = periodKey,
            cycleDay = settings.cycleDay,
            onSelect = { key ->
                periodKey = key
                showPeriodPicker = false
            },
            onDismiss = { showPeriodPicker = false },
        )
    }
    if (showDelete && existingBill != null) {
        ConfirmDialog(
            title = "¿Eliminar este recibo?",
            message = "Se borrará el recibo del periodo ${period.receiptLabel}.",
            confirmLabel = "Eliminar recibo",
            onConfirm = {
                scope.launch {
                    if (vm.deleteBill(existingBill)) onClose()
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
            title = if (billId == null) "Registrar recibo" else "Editar recibo",
            onBack = onClose,
            onDelete = if (billId != null) ({ showDelete = true }) else null,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (otherBill != null) {
                item {
                    InfoBanner(
                        title = "Ese periodo ya tiene recibo",
                        text = "Ya registraste el periodo ${period.receiptLabel}. Al guardar, " +
                            "actualizarás ese recibo en lugar de crear otro.",
                        tone = cr.esaver.ui.components.BannerTone.WARNING,
                    )
                }
            }
            item {
                SectionLabel("Periodo del recibo")
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
                                    text = "Periodo ${period.receiptLabel}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = period.rangeLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { showPeriodPicker = true }) {
                                Text("Cambiar")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Elige el periodo tal como aparece en el recibo (por ejemplo 09-2026). " +
                                "El ciclo empieza el día ${settings.cycleDay}.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionLabel("Totales del recibo")
                Spacer(Modifier.height(6.dp))
                NumberField(
                    value = amountText,
                    onValueChange = {
                        amountText = it.take(14)
                        amountError = false
                    },
                    label = "Monto total pagado",
                    suffix = "₡",
                    keyboardType = KeyboardType.Decimal,
                    isError = amountError,
                    supporting = if (amountError) "Escribe el total pagado del recibo" else "Lo que pagaste en total por la luz",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                NumberField(
                    value = kwhText,
                    onValueChange = {
                        kwhText = it.take(12)
                        kwhError = false
                    },
                    label = "Consumo facturado",
                    suffix = "kWh",
                    keyboardType = KeyboardType.Decimal,
                    isError = kwhError,
                    supporting = if (kwhError) "Escribe el consumo kWh/mensual del recibo" else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Tarifa efectiva",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                                )
                                Text(
                                    text = previewRate?.let { Fmt.rate(it) } ?: "—",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Te toca por el carro",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                                )
                                Text(
                                    text = if (previewRate != null) {
                                        Fmt.moneyRound(previewRate * carKwh)
                                    } else "—",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = "Con ${Fmt.kwh(carKwh)} del carro en este periodo. " +
                                "El cálculo se actualiza al guardar.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            item {
                SectionLabel("Foto del recibo (opcional)")
                Spacer(Modifier.height(6.dp))
                if (photoPath == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                vm.consumeCapture()
                                showCamera = true
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Cámara")
                        }
                        OutlinedButton(
                            onClick = {
                                vm.consumeCapture()
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Galería")
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        cr.esaver.ui.components.LocalPhoto(
                            path = photoPath,
                            modifier = Modifier
                                .size(82.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { showPhotoViewer = true },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Foto adjunta", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Toca para ampliarla",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { photoPath = null }) {
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

            item {
                Text(
                    text = "Consejo: el recibo muestra «Servicio brindado desde/hasta». " +
                        "Ese rango es el periodo que debes elegir aquí.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Button(
                onClick = {
                    val parsedAmount = Fmt.parseMoney(amountText)
                    val parsedKwh = Fmt.parseDecimal(kwhText)
                    if (parsedAmount == null || parsedAmount <= 0) {
                        amountError = true
                        return@Button
                    }
                    if (parsedKwh == null || parsedKwh <= 0) {
                        kwhError = true
                        return@Button
                    }
                    if (saving) return@Button
                    saving = true
                    scope.launch {
                        val ok = vm.saveBill(
                            BillDraft(
                                id = billId,
                                periodKey = periodKey,
                                totalAmount = parsedAmount,
                                totalKwh = parsedKwh,
                                photoPath = photoPath,
                            ),
                        )
                        saving = false
                        if (ok) onClose()
                    }
                },
                enabled = !saving && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
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
                Text(if (billId == null) "Guardar recibo" else "Guardar cambios")
            }
        }
    }
}
