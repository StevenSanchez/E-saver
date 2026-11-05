/*
 * Cálculos del periodo: consumo del carro, tarifa efectiva del recibo, monto a
 * pagar, comparación con el periodo anterior y equivalencia en baterías.
 *
 * Es código puro (sin Android) para que sea fácil de probar y entender.
 */
package cr.esaver.domain

import cr.esaver.data.AppSettings
import cr.esaver.data.Bill
import cr.esaver.data.ChargeSession
import cr.esaver.data.EstimateSource

data class RateInfo(
    val rate: Double,
    val label: String,
    val fromBill: Boolean,
)

data class PeriodSummary(
    val period: BillingPeriod,
    val sessions: List<ChargeSession>,
    val energyKwh: Double,
    val count: Int,
    val durationSec: Int,
    val bill: Bill?,
    val estimateRate: RateInfo,
    /** Tarifa efectiva del recibo (total pagado / kWh facturados). */
    val officialRate: Double?,
    /** Lo que corresponde pagar por el carro. */
    val amountOwed: Double,
    /** Estimación con la tarifa configurada / de recibos. */
    val estimateAmount: Double,
) {
    val isOfficial: Boolean get() = bill != null
    val effectiveRate: Double get() = officialRate ?: estimateRate.rate
    val houseAmount: Double get() = (bill?.totalAmount ?: 0.0) - amountOwed
    val avgKwh: Double get() = if (count > 0) energyKwh / count else 0.0
    val avgDurationSec: Int get() = if (count > 0) durationSec / count else 0
    val carShare: Double
        get() {
            val total = bill?.totalKwh ?: 0.0
            return if (total > 0.0) (energyKwh / total).coerceIn(0.0, 1.0) else 0.0
        }
    val overBill: Boolean
        get() = bill != null && bill.totalKwh > 0.0 && energyKwh > bill.totalKwh + 0.001
}

data class PendingPeriod(
    val key: String,
    val energyKwh: Double,
    val count: Int,
)

data class PeriodBar(
    val key: String,
    val label: String,
    val fullLabel: String,
    val energyKwh: Double,
    val selected: Boolean,
)

/** Reglas de negocio y agregados que alimentan las pantallas. */
object Analytics {

    fun effectiveRate(settings: AppSettings, bills: List<Bill>): RateInfo {
        val sorted = bills.sortedByDescending { it.periodKey }
        return when (settings.estimateSource) {
            EstimateSource.MANUAL -> RateInfo(
                rate = settings.approxCostPerKwh,
                label = "Costo aproximado configurado",
                fromBill = false,
            )

            EstimateSource.LAST_BILL -> {
                val bill = sorted.firstOrNull { it.totalKwh > 0.0 }
                if (bill != null) {
                    RateInfo(
                        rate = bill.costPerKwh,
                        label = "Último recibo · ${Billing.keyLabel(bill.periodKey)}",
                        fromBill = true,
                    )
                } else {
                    RateInfo(
                        rate = settings.approxCostPerKwh,
                        label = "Costo aproximado (aún sin recibos)",
                        fromBill = false,
                    )
                }
            }

            EstimateSource.BILL_AVERAGE -> {
                val recent = sorted.filter { it.totalKwh > 0.0 }.take(6)
                val totalKwh = recent.sumOf { it.totalKwh }
                if (totalKwh > 0.0) {
                    RateInfo(
                        rate = recent.sumOf { it.totalAmount } / totalKwh,
                        label = "Promedio de tus recibos",
                        fromBill = true,
                    )
                } else {
                    RateInfo(
                        rate = settings.approxCostPerKwh,
                        label = "Costo aproximado (aún sin recibos)",
                        fromBill = false,
                    )
                }
            }
        }
    }

    fun summary(
        key: String,
        sessions: List<ChargeSession>,
        bills: List<Bill>,
        settings: AppSettings,
    ): PeriodSummary {
        val period = Billing.periodFromKey(key, settings.cycleDay)
        val inPeriod = sessions.filter { it.periodKey == key }
            .sortedByDescending { it.timestamp }
        val energy = inPeriod.sumOf { it.energyKwh }
        val duration = inPeriod.sumOf { it.durationSec ?: 0 }
        val bill = bills.firstOrNull { it.periodKey == key }
        val rate = effectiveRate(settings, bills)
        val officialRate = bill?.takeIf { it.totalKwh > 0.0 }?.costPerKwh
        val owed = when {
            bill == null || bill.totalKwh <= 0.0 -> energy * rate.rate
            else -> bill.totalAmount / bill.totalKwh * energy
        }
        return PeriodSummary(
            period = period,
            sessions = inPeriod,
            energyKwh = energy,
            count = inPeriod.size,
            durationSec = duration,
            bill = bill,
            estimateRate = rate,
            officialRate = officialRate,
            amountOwed = owed,
            estimateAmount = energy * rate.rate,
        )
    }

    fun pendingPeriods(sessions: List<ChargeSession>, bills: List<Bill>): List<PendingPeriod> {
        val billed = bills.map { it.periodKey }.toSet()
        return sessions.groupBy { it.periodKey }
            .filterKeys { it !in billed }
            .map { (key, list) -> PendingPeriod(key, list.sumOf { it.energyKwh }, list.size) }
            .sortedByDescending { it.key }
    }

    fun periodBars(
        selectedKey: String,
        count: Int,
        sessions: List<ChargeSession>,
    ): List<PeriodBar> {
        val totals = sessions.groupBy { it.periodKey }.mapValues { entry -> entry.value.sumOf { it.energyKwh } }
        return (count - 1 downTo 0).map { offset ->
            val key = Billing.shiftKey(selectedKey, -offset.toLong())
            val ym = java.time.YearMonth.parse(key)
            PeriodBar(
                key = key,
                label = Billing.monthShort(ym.monthValue),
                fullLabel = "${Billing.monthShort(ym.monthValue)} ${ym.year}",
                energyKwh = totals[key] ?: 0.0,
                selected = key == selectedKey,
            )
        }
    }

    /** Cuántas baterías completas equivalen a una cantidad de kWh. */
    fun equivalentFullCharges(energyKwh: Double, batteryKwh: Double): Double? =
        if (batteryKwh > 0.01) energyKwh / batteryKwh else null

    fun batteryPercent(energyKwh: Double, batteryKwh: Double): Int? =
        if (batteryKwh > 0.01) {
            kotlin.math.round(energyKwh / batteryKwh * 100).toInt()
        } else null

    fun totalEnergy(sessions: List<ChargeSession>): Double = sessions.sumOf { it.energyKwh }

    fun lastBill(bills: List<Bill>): Bill? = bills.maxByOrNull { it.periodKey }

    fun comparison(current: PeriodSummary, previous: PeriodSummary?): Double? {
        if (previous == null || previous.energyKwh <= 0.0 || current.energyKwh <= 0.0) return null
        return (current.energyKwh - previous.energyKwh) / previous.energyKwh
    }
}
