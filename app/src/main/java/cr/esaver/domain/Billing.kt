/*
 * Ciclo de facturación.
 *
 * Un periodo se identifica con el mes en que termina (por ejemplo 2026-09) y
 * abarca del día de corte de un mes al día de corte del siguiente. Las cargas
 * se asignan al periodo que contiene su fecha, con opción de forzarlo a mano.
 */
package cr.esaver.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Un periodo de facturación, por ejemplo 2026-09 = del 21 de agosto al 21 de
 * septiembre de 2026. El fin es exclusivo: una carga del 21 de septiembre a las
 * 00:00 ya pertenece al periodo siguiente.
 */
data class BillingPeriod(
    val key: String,
    val start: LocalDate,
    val end: LocalDate,
) {
    /** Etiqueta como la usa el recibo: 09-2026. */
    val receiptLabel: String
        get() = String.format(Locale.US, "%02d-%d", end.monthValue, end.year)

    /** Etiqueta corta: sep 2026. */
    val shortLabel: String
        get() = "${Billing.monthShort(end.monthValue)} ${end.year}"

    /** Rango legible: 21 ago – 21 sep 2026. */
    val rangeLabel: String
        get() = Billing.rangeLabel(start, end)

    val startMillis: Long
        get() = start.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    val endMillis: Long
        get() = end.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
}

object Billing {
    private val MONTHS = listOf(
        "ene", "feb", "mar", "abr", "may", "jun",
        "jul", "ago", "sep", "oct", "nov", "dic",
    )

    fun monthShort(month: Int): String = MONTHS[(month - 1).coerceIn(0, 11)]

    fun cutoff(month: YearMonth, cycleDay: Int): LocalDate {
        val day = cycleDay.coerceIn(1, month.lengthOfMonth())
        return month.atDay(day)
    }

    /** Periodo al que pertenece una fecha según el día de corte. */
    fun periodFor(date: LocalDate, cycleDay: Int): BillingPeriod {
        val currentMonth = YearMonth.from(date)
        val cutoffThisMonth = cutoff(currentMonth, cycleDay)
        return if (!date.isBefore(cutoffThisMonth)) {
            val end = cutoff(currentMonth.plusMonths(1), cycleDay)
            BillingPeriod(keyForEnd(end), cutoffThisMonth, end)
        } else {
            val end = cutoffThisMonth
            BillingPeriod(keyForEnd(end), cutoff(currentMonth.minusMonths(1), cycleDay), end)
        }
    }

    fun keyForEnd(end: LocalDate): String =
        String.format(Locale.US, "%04d-%02d", end.year, end.monthValue)

    fun periodFromKey(key: String, cycleDay: Int): BillingPeriod {
        val endMonth = YearMonth.parse(key)
        val end = cutoff(endMonth, cycleDay)
        val start = cutoff(endMonth.minusMonths(1), cycleDay)
        return BillingPeriod(key, start, end)
    }

    fun todayKey(cycleDay: Int): String = periodFor(LocalDate.now(), cycleDay).key

    fun shiftKey(key: String, months: Long): String =
        YearMonth.parse(key).plusMonths(months).let { ym ->
            String.format(Locale.US, "%04d-%02d", ym.year, ym.monthValue)
        }

    fun rangeLabel(start: LocalDate, end: LocalDate): String =
        if (start.year == end.year) {
            "${start.dayOfMonth} ${monthShort(start.monthValue)} – " +
                "${end.dayOfMonth} ${monthShort(end.monthValue)} ${end.year}"
        } else {
            "${start.dayOfMonth} ${monthShort(start.monthValue)} ${start.year} – " +
                "${end.dayOfMonth} ${monthShort(end.monthValue)} ${end.year}"
        }

    fun keyLabel(key: String): String {
        val ym = YearMonth.parse(key)
        return "${monthShort(ym.monthValue)} ${ym.year}"
    }

    fun receiptLabelFromKey(key: String): String {
        val ym = YearMonth.parse(key)
        return String.format(Locale.US, "%02d-%d", ym.monthValue, ym.year)
    }

    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("es-CR"))
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es-CR"))

    fun dateLabel(date: LocalDate): String =
        if (date.year == LocalDate.now().year) date.format(dayMonth) else date.format(dayMonthYear)
}
