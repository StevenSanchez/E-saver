/*
 * Detalle de un recibo.
 *
 * Explica el cálculo total/kWh × consumo del carro, muestra la parte de la casa
 * y la del carro, la foto adjunta y las acciones de editar/eliminar.
 */
package cr.esaver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cr.esaver.AppViewModel
import cr.esaver.domain.Analytics
import cr.esaver.domain.Billing
import cr.esaver.domain.Fmt
import cr.esaver.ui.components.AppCard
import cr.esaver.ui.components.ConfirmDialog
import cr.esaver.ui.components.DetailRow
import cr.esaver.ui.components.EmptyState
import cr.esaver.ui.components.InfoBanner
import cr.esaver.ui.components.LocalPhoto
import cr.esaver.ui.components.PhotoViewerDialog
import cr.esaver.ui.components.SectionLabel
import cr.esaver.ui.components.SplitBar
import kotlinx.coroutines.launch

@Composable
fun BillDetailScreen(
    vm: AppViewModel,
    billId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
) {
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val bills by vm.bills.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val bill = remember(bills, billId) { bills.firstOrNull { it.id == billId } }
    val scope = rememberCoroutineScope()

    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showPhoto by rememberSaveable { mutableStateOf(false) }

    if (!loaded) {
        Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (bill == null) {
        Column(Modifier.fillMaxWidth()) {
            DetailTopBar(
                title = "Recibo",
                subtitle = null,
                onBack = onBack,
                onEdit = null,
                onDelete = null,
            )
            AppCard(Modifier.padding(20.dp)) {
                EmptyState(
                    icon = Icons.Rounded.ReceiptLong,
                    title = "Recibo no encontrado",
                    message = "Puede que se haya eliminado.",
                    actionLabel = "Volver",
                    onAction = onBack,
                )
            }
        }
        return
    }

    val summary = remember(bill, sessions, settings) {
        Analytics.summary(bill.periodKey, sessions, bills, settings)
    }
    val carKwh = summary.energyKwh
    val houseKwh = (bill.totalKwh - carKwh).coerceAtLeast(0.0)
    val houseAmount = (bill.totalAmount - summary.amountOwed).coerceAtLeast(0.0)
    val period = remember(bill.periodKey, settings.cycleDay) {
        Billing.periodFromKey(bill.periodKey, settings.cycleDay)
    }

    if (showDelete) {
        ConfirmDialog(
            title = "¿Eliminar este recibo?",
            message = "Se borrará el recibo del periodo ${period.receiptLabel}. Las cargas del " +
                "carro se conservan y el pago volverá a mostrarse como estimado.",
            confirmLabel = "Eliminar recibo",
            onConfirm = {
                scope.launch {
                    if (vm.deleteBill(bill)) onBack()
                }
            },
            onDismiss = { showDelete = false },
        )
    }
    if (showPhoto && bill.photoPath != null) {
        PhotoViewerDialog(path = bill.photoPath, onDismiss = { showPhoto = false })
    }

    Column(Modifier.fillMaxWidth()) {
        DetailTopBar(
            title = "Recibo ${period.receiptLabel}",
            subtitle = period.rangeLabel,
            onBack = onBack,
            onEdit = { onEdit(bill.id) },
            onDelete = { showDelete = true },
        )
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ReceiptHeader(bill.totalAmount, period.receiptLabel, period.rangeLabel, bill.totalKwh, bill.costPerKwh)
            }
            item {
                AppCard {
                    Text(
                        text = "Tu parte por el carro",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 0.6.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = Fmt.moneyRound(summary.amountOwed),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = Fmt.percent(summary.carShare) + " del recibo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(13.dp))
                    SplitBar(summary.carShare)
                    Spacer(Modifier.height(9.dp))
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Carro · ${Fmt.kwh(carKwh)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = Fmt.moneyRound(summary.amountOwed),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Casa · ${Fmt.kwh(houseKwh)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = Fmt.moneyRound(houseAmount),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(7.dp))
                    DetailRow(
                        label = "Cálculo",
                        value = "${Fmt.money( bill.totalAmount)} ÷ ${Fmt.kwhValue(bill.totalKwh)} kWh " +
                            "× ${Fmt.kwhValue(carKwh)} kWh",
                    )
                    Text(
                        text = "Equivale a ${Fmt.rate(bill.costPerKwh)} por cada kWh del carro.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (summary.overBill) {
                item {
                    InfoBanner(
                        text = "Las cargas del carro en este periodo suman más kWh que el total " +
                            "facturado. Revisa las fechas de corte o alguna carga duplicada.",
                        tone = cr.esaver.ui.components.BannerTone.WARNING,
                    )
                }
            }
            if (bill.photoPath != null) {
                item {
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Foto del recibo",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { showPhoto = true }) { Text("Ampliar") }
                        }
                        Spacer(Modifier.height(10.dp))
                        LocalPhoto(
                            path = bill.photoPath,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showPhoto = true },
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onEdit(bill.id) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Editar")
                    }
                    OutlinedButton(
                        onClick = { showDelete = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Eliminar", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item {
                Text(
                    text = "El recibo es del servicio completo (casa + carro). El monto del carro " +
                        "se calcula solo con el consumo registrado dentro de las fechas del periodo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
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
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, contentDescription = "Editar")
            }
        }
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

@Composable
private fun ReceiptHeader(
    totalAmount: Double,
    periodLabel: String,
    rangeLabel: String,
    totalKwh: Double,
    rate: Double,
) {
    AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Icon(
                    Icons.Rounded.ReceiptLong,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .padding(9.dp)
                        .size(22.dp),
                )
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text(
                    text = "Servicio eléctrico · ${periodLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                Text(
                    text = rangeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "TOTAL PAGADO",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
        Text(
            text = Fmt.moneyRound(totalAmount),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Consumo facturado",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
                Text(
                    text = Fmt.kwh(totalKwh),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Tarifa efectiva",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
                Text(
                    text = Fmt.rate(rate),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
