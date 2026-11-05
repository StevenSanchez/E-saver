/*
 * Recibos de luz.
 *
 * Lista los periodos pendientes de recibo y los ya registrados, mostrando la
 * tarifa efectiva y cuánto corresponde al carro en cada uno.
 */
package cr.esaver.ui.screens

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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cr.esaver.AppViewModel
import cr.esaver.data.Bill
import cr.esaver.domain.Analytics
import cr.esaver.domain.Billing
import cr.esaver.domain.Fmt
import cr.esaver.domain.PendingPeriod
import cr.esaver.ui.components.AppCard
import cr.esaver.ui.components.EmptyState
import cr.esaver.ui.components.InfoBanner
import cr.esaver.ui.components.SectionLabel

@Composable
fun BillsScreen(
    vm: AppViewModel,
    onAddBill: (String) -> Unit,
    onOpenBill: (Long) -> Unit,
) {
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val bills by vm.bills.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    val pending = remember(sessions, bills) { Analytics.pendingPeriods(sessions, bills) }
    val rate = remember(bills, settings) { Analytics.effectiveRate(settings, bills) }

    if (!loaded) {
        Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionLabel("Facturación")
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Recibos de luz", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "El total del recibo incluye la casa y el carro",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.ReceiptLong,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        item {
            InfoBanner(
                title = "Cómo se calcula tu parte",
                text = "Tomamos el total pagado del recibo, lo dividimos entre los kWh facturados " +
                    "y lo multiplicamos por los kWh del carro en ese mismo periodo. " +
                    "Así pagas exactamente lo que consumiste.",
            )
        }

        if (bills.isEmpty() && pending.isEmpty()) {
            item {
                AppCard {
                    EmptyState(
                        icon = Icons.Rounded.ReceiptLong,
                        title = "Aún no hay recibos",
                        message = "Cuando llegue el recibo de la luz, registra el total pagado " +
                            "y los kWh facturados. Calculamos al instante cuánto te toca por el carro.",
                        actionLabel = "Registrar primer recibo",
                        onAction = { onAddBill(vm.currentPeriodKey()) },
                    )
                }
            }
        } else {
            if (pending.isNotEmpty()) {
                item {
                    SectionLabel("Periodos sin recibo")
                }
                pending.forEach { period ->
                    item(key = "pending-${period.key}") {
                        PendingBillCard(
                            pending = period,
                            estimate = period.energyKwh * rate.rate,
                            cycleDay = settings.cycleDay,
                            onAdd = { onAddBill(period.key) },
                        )
                    }
                }
            }

            if (bills.isNotEmpty()) {
                item {
                    SectionLabel("Recibos registrados")
                }
                bills.forEach { bill ->
                    item(key = "bill-${bill.id}") {
                        val carKwh = sessions
                            .filter { it.periodKey == bill.periodKey }
                            .sumOf { it.energyKwh }
                        val carAmount = if (bill.totalKwh > 0.0) {
                            bill.totalAmount / bill.totalKwh * carKwh
                        } else 0.0
                        BillCard(
                            bill = bill,
                            carKwh = carKwh,
                            carAmount = carAmount,
                            cycleDay = settings.cycleDay,
                            onClick = { onOpenBill(bill.id) },
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = { onAddBill(vm.currentPeriodKey()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("Registrar recibo")
            }
        }
    }
}

@Composable
private fun PendingBillCard(
    pending: PendingPeriod,
    estimate: Double,
    cycleDay: Int,
    onAdd: () -> Unit,
) {
    val period = remember(pending.key, cycleDay) { Billing.periodFromKey(pending.key, cycleDay) }
    AppCard {
        Row(verticalAlignment = Alignment.Top) {
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
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    text = "PENDIENTE",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Carro",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = Fmt.kwh(pending.energyKwh),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${pending.count} ${if (pending.count == 1) "carga" else "cargas"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Estimado",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = Fmt.moneyRound(estimate),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text("Registrar recibo del periodo")
        }
    }
}

@Composable
private fun BillCard(
    bill: Bill,
    carKwh: Double,
    carAmount: Double,
    cycleDay: Int,
    onClick: () -> Unit,
) {
    val period = remember(bill.periodKey, cycleDay) { Billing.periodFromKey(bill.periodKey, cycleDay) }
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Periodo ${Billing.receiptLabelFromKey(bill.periodKey)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = period.rangeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.moneyRound(bill.totalAmount),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${Fmt.kwhValue(bill.totalKwh)} kWh facturados",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(18.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Tu parte por el carro",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = Fmt.kwh(carKwh),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = Fmt.moneyRound(carAmount),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = "Tarifa efectiva: ${Fmt.rate(bill.costPerKwh)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
