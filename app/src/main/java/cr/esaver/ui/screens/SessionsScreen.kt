/*
 * Historial de cargas de un periodo.
 *
 * Agrupa las cargas por día, muestra totales y permite abrir cada registro para
 * editarlo o eliminarlo.
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
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cr.esaver.AppViewModel
import cr.esaver.data.ChargeSession
import cr.esaver.domain.Analytics
import cr.esaver.domain.Fmt
import cr.esaver.domain.PeriodSummary
import cr.esaver.ui.components.AppCard
import cr.esaver.ui.components.EmptyState
import cr.esaver.ui.components.LocalPhoto
import cr.esaver.ui.components.PeriodSelector
import cr.esaver.ui.components.SectionLabel
import cr.esaver.ui.components.StatTile
import java.time.LocalDate

@Composable
fun SessionsScreen(
    vm: AppViewModel,
    onAddSession: () -> Unit,
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

    if (!loaded) {
        Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val grouped = remember(summary.sessions) {
        summary.sessions.groupBy { it.dateTime.toLocalDate() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionLabel("Historial")
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cargas registradas", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "Cada kWh que consumes al cargar el carro",
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
                            Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
            }
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
            PeriodTotals(
                summary = summary,
                carName = settings.carName,
                batteryKwh = settings.batteryKwh,
            )
        }
        if (summary.sessions.isEmpty()) {
            item {
                AppCard {
                    EmptyState(
                        icon = Icons.Rounded.Bolt,
                        title = "Sin cargas en este periodo",
                        message = "Agrega una carga manualmente o toma una foto de la pantalla " +
                            "del cargador para detectar los kWh automáticamente.",
                        actionLabel = "Nueva carga",
                        onAction = onAddSession,
                    )
                }
            }
        } else {
            grouped.forEach { (date, daySessions) ->
                item(key = "day-$date") {
                    DayHeader(date = date, count = daySessions.size)
                }
                daySessions.forEach { session ->
                    item(key = "session-${session.id}") {
                        SessionCard(
                            session = session,
                            estimatedCost = session.energyKwh * summary.effectiveRate,
                            batteryKwh = settings.batteryKwh,
                            onClick = { onEditSession(session.id) },
                        )
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onAddSession, modifier = Modifier.fillMaxWidth()) {
                Text("Registrar otra carga")
            }
        }
    }
}

@Composable
private fun PeriodTotals(
    summary: PeriodSummary,
    carName: String,
    batteryKwh: Double,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (summary.isOfficial) "Pago oficial del periodo" else "Estimado del periodo",
                    style = MaterialTheme.typography.labelSmall,
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
                    text = Fmt.kwh(summary.energyKwh),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${summary.count} ${if (summary.count == 1) "carga" else "cargas"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "Promedio",
                value = Fmt.kwhValue(summary.avgKwh),
                unit = "kWh",
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Speed,
            )
            StatTile(
                label = "Duración total",
                value = if (summary.durationSec > 0) Fmt.number(summary.durationSec / 3600.0, 1) else "—",
                unit = if (summary.durationSec > 0) "h" else null,
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Timer,
            )
        }
        Analytics.equivalentFullCharges(summary.energyKwh, batteryKwh)?.let { fullCharges ->
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.BatteryChargingFull,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$carName · batería de ${Fmt.kwh(batteryKwh)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "≈ ${Fmt.number(fullCharges, 2)} cargas completas",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, count: Int) {
    val today = LocalDate.now()
    val title = when (date) {
        today -> "Hoy"
        today.minusDays(1) -> "Ayer"
        else -> Fmt.weekday(date)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(7.dp))
        Text(
            text = Fmt.date(date),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count ${if (count == 1) "carga" else "cargas"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionCard(
    session: ChargeSession,
    estimatedCost: Double,
    batteryKwh: Double,
    onClick: () -> Unit,
) {
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                if (session.photoPath != null) {
                    LocalPhoto(session.photoPath, Modifier.size(52.dp), maxDimension = 240)
                } else {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(23.dp),
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
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        if (session.durationSec != null && session.durationSec > 0) {
                            append(Fmt.duration(session.durationSec))
                        }
                        session.powerKw?.let {
                            if (isNotEmpty()) append(" · ")
                            append("${Fmt.compactNumber(it)} kW")
                        }
                        session.voltage?.let {
                            if (isNotEmpty()) append(" · ")
                            append("$it V")
                        }
                        Analytics.batteryPercent(session.energyKwh, batteryKwh)?.let {
                            if (isNotEmpty()) append(" · ")
                            append("$it% batería")
                        }
                        if (isEmpty()) append("Carga registrada")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.notes.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = session.notes,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${Fmt.kwhValue(session.energyKwh)} kWh",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = Fmt.moneyRound(estimatedCost),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(18.dp),
            )
        }
    }
}
