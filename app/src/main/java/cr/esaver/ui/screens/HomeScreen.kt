/*
 * Inicio: resumen del periodo seleccionado.
 *
 * Muestra el consumo del carro, el monto estimado u oficial, la equivalencia en
 * baterías, el pago dividido entre casa y carro y los últimos periodos.
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cr.esaver.AppViewModel
import cr.esaver.data.ChargeSession
import cr.esaver.domain.Analytics
import cr.esaver.domain.Billing
import cr.esaver.domain.Fmt
import cr.esaver.domain.PeriodSummary
import cr.esaver.ui.components.AppCard
import cr.esaver.ui.components.EmptyState
import cr.esaver.ui.components.EnergyChart
import cr.esaver.ui.components.InfoBanner
import cr.esaver.ui.components.LocalPhoto
import cr.esaver.ui.components.PeriodSelector
import cr.esaver.ui.components.SectionLabel
import cr.esaver.ui.components.SplitBar
import cr.esaver.ui.components.StatTile
import cr.esaver.ui.components.BannerTone
import java.time.LocalDate
import kotlin.math.abs

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onAddSession: () -> Unit,
    onAddBill: (String) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenBillDetail: (Long) -> Unit,
    onEditBill: (Long) -> Unit,
    onEditSession: (Long) -> Unit,
) {
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val bills by vm.bills.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val selectedKey by vm.selectedPeriod.collectAsStateWithLifecycle()

    val summary = remember(selectedKey, sessions, bills, settings) {
        Analytics.summary(selectedKey, sessions, bills, settings)
    }
    val previousKey = remember(selectedKey) { Billing.shiftKey(selectedKey, -1) }
    val previousSummary = remember(previousKey, sessions, bills, settings) {
        Analytics.summary(previousKey, sessions, bills, settings)
    }
    val comparison = remember(summary, previousSummary) {
        Analytics.comparison(summary, previousSummary)
    }
    val bars = remember(selectedKey, sessions) {
        Analytics.periodBars(selectedKey, 6, sessions)
    }
    val fullCharges = remember(summary.energyKwh, settings.batteryKwh) {
        Analytics.equivalentFullCharges(summary.energyKwh, settings.batteryKwh)
    }
    val totalFullCharges = remember(sessions, settings.batteryKwh) {
        Analytics.equivalentFullCharges(
            sessions.sumOf { it.energyKwh },
            settings.batteryKwh,
        )
    }

    if (!loaded) {
        Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            HomeHeader(onAddSession = onAddSession)
        }
        item {
            PeriodSelector(
                periodKey = selectedKey,
                cycleDay = settings.cycleDay,
                onShift = { vm.shiftPeriod(it) },
                onReset = { vm.resetPeriodToToday() },
            )
        }
        item {
            HeroCard(summary = summary, comparison = comparison)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "Cargas",
                    value = summary.count.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Promedio",
                    value = Fmt.kwhValue(summary.avgKwh),
                    unit = "kWh",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Duración",
                    value = if (summary.durationSec > 0) Fmt.number(summary.durationSec / 3600.0, 1) else "—",
                    unit = if (summary.durationSec > 0) "h" else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (fullCharges != null) {
            item {
                BatteryCard(
                    carName = settings.carName,
                    batteryKwh = settings.batteryKwh,
                    energyKwh = summary.energyKwh,
                    fullCharges = fullCharges,
                    totalFullCharges = totalFullCharges,
                )
            }
        }
        item {
            PaymentCard(
                summary = summary,
                onAddBill = { onAddBill(selectedKey) },
                onOpenBill = { id -> onOpenBillDetail(id) },
                onEditBill = { id -> onEditBill(id) },
            )
        }
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Últimos 6 periodos", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Consumo del carro por periodo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    )
                }
                Spacer(Modifier.height(18.dp))
                if (bars.all { it.energyKwh <= 0.0 }) {
                    Text(
                        text = "Todavía no hay consumos registrados. En cuanto guardes una carga " +
                            "verás aquí la comparación entre periodos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    EnergyChart(bars = bars)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(
                    text = "Cargas del periodo",
                    modifier = Modifier.weight(1f),
                )
                if (summary.sessions.isNotEmpty()) {
                    TextButton(onClick = onOpenSessions) { Text("Ver todas") }
                }
            }
        }
        if (summary.sessions.isEmpty()) {
            item {
                AppCard {
                    EmptyState(
                        icon = Icons.Rounded.Bolt,
                        title = "Aún no hay cargas en este periodo",
                        message = "Registra lo que marca el cargador al terminar cada carga. " +
                            "También puedes tomar una foto y detectar los datos.",
                        actionLabel = "Registrar carga",
                        onAction = onAddSession,
                    )
                }
            }
        } else {
            items(summary.sessions.take(3).size) { index ->
                val session = summary.sessions[index]
                RecentSessionRow(
                    session = session,
                    cost = session.energyKwh * summary.effectiveRate,
                    batteryKwh = settings.batteryKwh,
                    onClick = { onEditSession(session.id) },
                )
                if (index < summary.sessions.take(3).lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tus datos se guardan solo en este teléfono. Periodo calculado del día " +
                    "${settings.cycleDay} al ${settings.cycleDay} de cada mes.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeHeader(onAddSession: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "E-saver",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Tu consumo eléctrico, claro",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            onClick = onAddSession,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(46.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "Nueva carga",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun HeroCard(summary: PeriodSummary, comparison: Double?) {
    val gradient = Brush.linearGradient(
        colors = listOf(Color(0xFF0A3B26), Color(0xFF15803D)),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(gradient),
    ) {
        Icon(
            Icons.Rounded.Bolt,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.08f),
            modifier = Modifier
                .size(150.dp)
                .align(Alignment.TopEnd)
                .offset(x = 32.dp, y = (-26).dp),
        )
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "CONSUMO DEL VEHÍCULO",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
                color = Color.White.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = Fmt.kwhValue(summary.energyKwh),
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                )
                Text(
                    text = " kWh",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${summary.count} ${if (summary.count == 1) "carga" else "cargas"}" +
                        if (summary.durationSec > 0) " · ${Fmt.duration(summary.durationSec)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                )
                if (comparison != null && abs(comparison) > 0.005) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = (if (comparison > 0) "▲ " else "▼ ") +
                                Fmt.percent(abs(comparison)) + " vs anterior",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
            Spacer(Modifier.height(13.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (summary.isOfficial) "SEGÚN RECIBO" else "ESTIMADO A PAGAR",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        letterSpacing = 0.8.sp,
                        color = Color.White.copy(alpha = 0.62f),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = Fmt.moneyRound(summary.amountOwed),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (summary.isOfficial) "TARIFA DEL RECIBO" else "TARIFA ESTIMADA",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        letterSpacing = 0.8.sp,
                        color = Color.White.copy(alpha = 0.62f),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = Fmt.rate(summary.effectiveRate),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentCard(
    summary: PeriodSummary,
    onAddBill: () -> Unit,
    onOpenBill: (Long) -> Unit,
    onEditBill: (Long) -> Unit,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Pago del periodo",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = CircleShape,
                color = if (summary.isOfficial) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = if (summary.isOfficial) "OFICIAL" else "ESTIMADO",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = if (summary.isOfficial) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        val bill = summary.bill
        if (bill == null) {
            Text(
                text = "Registra el recibo de ${summary.period.shortLabel} y calculamos el " +
                    "monto exacto que te toca pagar por el carro.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = Fmt.moneyRound(summary.estimateAmount),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "estimado",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = onAddBill, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("Registrar recibo del periodo")
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "TU PARTE (CARRO)",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 0.6.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = Fmt.moneyRound(summary.amountOwed),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "TOTAL DEL RECIBO",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 0.6.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = Fmt.moneyRound(bill.totalAmount),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            SplitBar(carShare = summary.carShare)
            Spacer(Modifier.height(8.dp))
            Row {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "Carro ${Fmt.kwhValue(summary.energyKwh)} kWh",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "Casa ${Fmt.kwhValue((bill.totalKwh - summary.energyKwh).coerceAtLeast(0.0))} kWh",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onOpenBill(bill.id) }, modifier = Modifier.weight(1f)) {
                    Text("Ver recibo")
                }
                Button(onClick = { onEditBill(bill.id) }, modifier = Modifier.weight(1f)) {
                    Text("Editar recibo")
                }
            }
            if (summary.overBill) {
                Spacer(Modifier.height(10.dp))
                InfoBanner(
                    text = "El consumo del carro (${Fmt.kwhValue(summary.energyKwh)} kWh) supera " +
                        "el total del recibo (${Fmt.kwhValue(bill.totalKwh)} kWh). Revisa las fechas " +
                        "de las cargas o el periodo del recibo.",
                    tone = BannerTone.WARNING,
                )
            }
        }
    }
}

@Composable
private fun BatteryCard(
    carName: String,
    batteryKwh: Double,
    energyKwh: Double,
    fullCharges: Double,
    totalFullCharges: Double?,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.BatteryChargingFull,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = carName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Batería de ${Fmt.kwh(batteryKwh)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Fmt.number(fullCharges, 1) + "×",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "cargas completas",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Con ${Fmt.kwh(energyKwh)} en este periodo has cargado el equivalente a " +
                "${Fmt.number(fullCharges, 2)} baterías completas.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (totalFullCharges != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "En total llevas ${Fmt.number(totalFullCharges, 2)} cargas completas registradas.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RecentSessionRow(
    session: ChargeSession,
    cost: Double,
    batteryKwh: Double,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp)),
        ) {
            if (session.photoPath != null) {
                LocalPhoto(session.photoPath, Modifier.size(46.dp), maxDimension = 200)
            } else {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = Fmt.date(session.dateTime.toLocalDate()),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (session.durationSec != null) append(Fmt.duration(session.durationSec))
                    if (session.voltage != null) {
                        if (isNotEmpty()) append(" · ")
                        append("${session.voltage} V")
                    }
                    Analytics.batteryPercent(session.energyKwh, batteryKwh)?.let { percent ->
                        if (isNotEmpty()) append(" · ")
                        append("$percent% batería")
                    }
                    if (isEmpty()) append("Carga registrada")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${Fmt.kwhValue(session.energyKwh)} kWh",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = Fmt.moneyRound(cost),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
    }
}
