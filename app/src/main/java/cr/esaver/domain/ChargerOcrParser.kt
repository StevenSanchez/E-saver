/*
 * Intérprete del texto que devuelve ML Kit.
 *
 * No depende de Android: recibe una lista de textos (de varias variantes de la
 * foto) y extrae kWh, duración, voltaje, corriente y potencia, validando el kWh
 * con la potencia y el tiempo cuando es posible.
 */
package cr.esaver.domain

import cr.esaver.data.ChargerReading
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/**
 * Interpreta el texto que ML Kit reconoce en la pantalla del cargador.
 * Es tolerante a errores típicos de OCR y usa la potencia y la duración para
 * validar el kWh (por ejemplo, si se perdió el punto decimal).
 */
object ChargerOcrParser {

    private val energyRegex = Regex(
        """(\d{1,3}(?:[.,]\d{1,2})?)\s*K\s*[WwUu]\s*H""",
    )
    private val bareDecimalRegex = Regex(
        """(?<![\d.,])(\d{1,3}(?:[.,]\d{1,2}))(?![\d.,]|\s*(?:A|V|K\s*[WU]|K\s*WH))""",
        RegexOption.IGNORE_CASE,
    )
    private val timeRegex = Regex(
        """(?<!\d)(\d{1,2})\s*:\s*(\d{2})\s*:\s*(\d{2})(?!\d)""",
    )
    private val shortTimeRegex = Regex(
        """(?<!\d)(\d{1,2})\s*:\s*(\d{2})(?!\d)""",
    )
    private val voltageRegex = Regex(
        """(?<!\d)(\d{2,3})\s*V(?!\w)""",
        RegexOption.IGNORE_CASE,
    )
    /** A veces el OCR lee "230V" como "2300"; esto recupera el voltaje. */
    private val voltageZeroFallbackRegex = Regex(
        """(?<!\d)(1\d{2}|2[0-4]\d|25\d)\s*[VU0](?!\d)""",
        RegexOption.IGNORE_CASE,
    )
    private val currentRegex = Regex(
        """(?<!\d)(\d{1,3}(?:[.,]\d{1,2})?)\s*A(?!\w)""",
        RegexOption.IGNORE_CASE,
    )
    private val powerRegex = Regex(
        """(?<!\d)(\d{1,2}(?:[.,]\d{1,2})?)\s*K\s*[WU](?!\s*H)(?!\w)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(texts: List<String>): ChargerReading {
        val raw = texts.filter { it.isNotBlank() }.joinToString("\n")
        if (raw.isBlank()) return ChargerReading()
        val variants = texts.filter { it.isNotBlank() }.map { it.uppercase(Locale.US) }

        val duration = variants.firstNotNullOfOrNull { parseDuration(it) }

        val voltage = variants.firstNotNullOfOrNull { text ->
            voltageRegex.findAll(text)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .firstOrNull { it in 90..300 }
        } ?: variants.firstNotNullOfOrNull { text ->
            voltageZeroFallbackRegex.findAll(text)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .firstOrNull { it in 100..260 }
        }

        val ampReadings = variants.mapNotNull { parseAmps(it) }
        val preferredAmps = ampReadings.firstOrNull { it.decimal } ?: ampReadings.firstOrNull()
        val current = preferredAmps?.current
        val chargerMax = ampReadings.mapNotNull { it.max }.maxOrNull()
        val power = variants.firstNotNullOfOrNull { text ->
            powerRegex.findAll(text)
                .mapNotNull { it.groupValues[1].toDoubleFlexible() }
                .firstOrNull { it in 0.3..40.0 }
        }

        val energy = chooseEnergy(
            variants = variants,
            powerKw = power,
            durationSec = duration,
            currentA = current,
            chargerMaxA = chargerMax,
            voltage = voltage,
        )

        return ChargerReading(
            energyKwh = energy,
            durationSec = duration,
            voltage = voltage,
            currentA = current,
            powerKw = power,
            chargerMaxA = chargerMax,
            rawText = raw,
        )
    }

    private fun chooseEnergy(
        variants: List<String>,
        powerKw: Double?,
        durationSec: Int?,
        currentA: Double?,
        chargerMaxA: Int?,
        voltage: Int?,
    ): Double? {
        val explicit = mutableListOf<EnergyCandidate>()
        variants.forEachIndexed { index, text ->
            energyRegex.findAll(text).forEach { match ->
                val token = match.groupValues[1]
                val value = token.toDoubleFlexible() ?: return@forEach
                if (value in 0.1..500.0) {
                    explicit += EnergyCandidate(value, token.contains('.') || token.contains(','), index)
                }
            }
        }
        pickEnergy(explicit, powerKw, durationSec)?.let { return it }

        // A veces ML Kit lee "22.7" pero pierde la etiqueta "kWh". En ese caso
        // buscamos un decimal suelto y descartamos corriente, potencia y voltaje.
        val bare = mutableListOf<EnergyCandidate>()
        variants.forEachIndexed { index, text ->
            bareDecimalRegex.findAll(text).forEach { match ->
                val token = match.groupValues[1]
                val value = token.toDoubleFlexible() ?: return@forEach
                val looksLikeOtherValue =
                    (currentA != null && abs(value - currentA) < 0.16) ||
                        (powerKw != null && abs(value - powerKw) < 0.16) ||
                        (voltage != null && abs(value - voltage) < 0.16) ||
                        (chargerMaxA != null && abs(value - chargerMaxA) < 0.16)
                if (value in 0.5..200.0 && !looksLikeOtherValue) {
                    bare += EnergyCandidate(value, true, index)
                }
            }
        }
        return pickEnergy(bare, powerKw, durationSec)
    }

    private fun pickEnergy(
        candidates: List<EnergyCandidate>,
        powerKw: Double?,
        durationSec: Int?,
    ): Double? {
        if (candidates.isEmpty()) return null
        val expected = if (powerKw != null && durationSec != null && durationSec > 0) {
            powerKw * durationSec / 3600.0
        } else null

        // Si la potencia y el tiempo dan un kWh esperado, elegimos el candidato
        // más cercano. Así "227" no le gana a "22.7".
        if (expected != null && expected in 0.4..220.0) {
            val closest = candidates.minByOrNull { abs(ln(it.value / expected)) } ?: return null
            var value = closest.value
            if (value / expected > 6.0 && value > 100.0) value /= 10.0
            if (value in 0.1..500.0) return value
        }

        // Sin datos para validar: preferimos el primer valor con decimal, que
        // suele venir de la variante de imagen más legible.
        return (candidates.firstOrNull { it.hasDecimal } ?: candidates.first()).value
    }

    private fun parseDuration(text: String): Int? {
        val full = timeRegex.find(text)
        if (full != null) {
            val h = full.groupValues[1].toIntOrNull() ?: return null
            val m = full.groupValues[2].toIntOrNull() ?: return null
            val s = full.groupValues[3].toIntOrNull() ?: return null
            if (h in 0..23 && m in 0..59 && s in 0..59) {
                return h * 3600 + m * 60 + s
            }
        }
        val shortMatch = shortTimeRegex.find(text) ?: return null
        val h = shortMatch.groupValues[1].toIntOrNull() ?: return null
        val m = shortMatch.groupValues[2].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h * 3600 + m * 60 else null
    }

    private fun parseAmps(text: String): AmpReading? {
        val candidates = currentRegex.findAll(text)
            .mapNotNull { match ->
                val token = match.groupValues[1]
                val value = token.toDoubleFlexible() ?: return@mapNotNull null
                if (value !in 0.5..100.0) null
                else AmpCandidate(value, token.contains('.') || token.contains(','))
            }
            .toList()
        if (candidates.isEmpty()) return null

        val decimalAmp = candidates.firstOrNull { it.isDecimal }?.value
        val integerAmps = candidates.filter { !it.isDecimal }.map { it.value }.sorted()
        val max = when {
            decimalAmp != null && integerAmps.isNotEmpty() -> integerAmps.last().toInt()
            candidates.size >= 2 -> integerAmps.lastOrNull()?.toInt()
            else -> null
        }
        val current = decimalAmp ?: integerAmps.lastOrNull() ?: candidates.first().value
        return AmpReading(current, max, decimalAmp != null)
    }

    private fun String.toDoubleFlexible(): Double? {
        if (isBlank()) return null
        val cleaned = trim()
            .replace('O', '0')
            .replace('o', '0')
            .replace('I', '1')
            .replace('l', '1')
            .replace('S', '5')
            .replace('s', '5')
            .replace('B', '8')
            .replace('Z', '2')
            .replace(',', '.')
        return cleaned.toDoubleOrNull()
    }

    private data class EnergyCandidate(val value: Double, val hasDecimal: Boolean, val index: Int)
    private data class AmpCandidate(val value: Double, val isDecimal: Boolean)
    private data class AmpReading(val current: Double, val max: Int?, val decimal: Boolean)
}
