/*
 * Formatos de la app: colones costarricenses, kWh, fechas y duraciones.
 * Se usa una sola fuente para que todas las pantallas muestren lo mismo.
 */
package cr.esaver.domain

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Formatos en colones costarricenses y español. */
object Fmt {
    private val symbols = DecimalFormatSymbols(Locale.forLanguageTag("es-CR")).apply {
        groupingSeparator = '.'
        decimalSeparator = ','
        minusSign = '-'
    }

    fun number(value: Double, decimals: Int = 0): String {
        val pattern = if (decimals <= 0) "#,##0" else "#,##0." + "0".repeat(decimals)
        return DecimalFormat(pattern, symbols).format(value)
    }

    fun compactNumber(value: Double): String {
        val pattern = "#,##0.##"
        return DecimalFormat(pattern, symbols).format(value)
    }

    fun money(value: Double, decimals: Int = 0): String {
        val safe = if (value.isFinite()) value else 0.0
        return "₡" + number(safe, decimals)
    }

    /** Monto redondeado a colones (los céntimos no se usan en la vida real). */
    fun moneyRound(value: Double): String = money(value.roundToLong().toDouble(), 0)

    fun rate(value: Double): String = money(value, 2) + "/kWh"

    fun kwh(value: Double): String = compactNumber(value) + " kWh"

    fun kwhValue(value: Double): String = compactNumber(value)

    fun date(date: LocalDate): String {
        val pattern = if (date.year == LocalDate.now().year) "d MMM" else "d MMM yyyy"
        return date.format(DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag("es-CR")))
    }

    fun dateLong(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-CR")))

    fun dateTime(dt: LocalDateTime): String {
        val pattern = if (dt.year == LocalDate.now().year) "d MMM · HH:mm" else "d MMM yyyy · HH:mm"
        return dt.format(DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag("es-CR")))
    }

    fun time(dt: LocalDateTime): String =
        dt.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))

    fun weekday(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("EEEE", Locale.forLanguageTag("es-CR")))
            .replaceFirstChar { it.uppercase() }

    fun monthYear(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("es-CR")))
            .replaceFirstChar { it.uppercase() }

    fun duration(seconds: Int?): String {
        if (seconds == null || seconds <= 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 && m > 0 -> "${h} h ${m} min"
            h > 0 -> "${h} h"
            m > 0 -> "$m min"
            else -> "${seconds} s"
        }
    }

    fun durationClock(seconds: Int?): String {
        if (seconds == null || seconds < 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    fun percent(value: Double): String = number(value * 100, 0) + "%"

    fun signedPercent(value: Double): String {
        val pct = value * 100
        val sign = if (pct > 0) "+" else ""
        return sign + number(pct, 0) + "%"
    }

    fun editable(value: Double): String {
        if (abs(value - value.roundToLong()) < 0.0001) return value.roundToLong().toString()
        return DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US)).format(value)
            .replace(",", "")
    }

    fun parseDecimal(raw: String): Double? {
        val cleaned = raw.trim().replace(" ", "").replace("₡", "")
        if (cleaned.isEmpty()) return null
        val normalized = when {
            cleaned.contains(',') && cleaned.contains('.') -> {
                if (cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.')) {
                    cleaned.replace(".", "").replace(',', '.')
                } else {
                    cleaned.replace(",", "")
                }
            }
            cleaned.contains(',') -> cleaned.replace(',', '.')
            else -> cleaned
        }
        return normalized.toDoubleOrNull()
    }

    /** Para montos: "32.390" se interpreta como treinta y dos mil. */
    fun parseMoney(raw: String): Double? {
        val cleaned = raw.trim().replace(" ", "").replace("₡", "")
        if (cleaned.isEmpty()) return null
        val normalized = when {
            cleaned.contains(',') -> cleaned.replace(".", "").replace(',', '.')
            else -> {
                val dot = cleaned.lastIndexOf('.')
                val decimals = if (dot >= 0) cleaned.length - dot - 1 else 0
                if (dot >= 0 && decimals == 3 && cleaned.length > 4) cleaned.replace(".", "")
                else cleaned
            }
        }
        return normalized.toDoubleOrNull()
    }
}
