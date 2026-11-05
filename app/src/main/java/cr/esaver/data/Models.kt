/*
 * Modelos de datos de E-saver.
 *
 * ChargeSession = una carga del carro.
 * Bill = un recibo de luz de un periodo.
 * ChargerReading = resultado del OCR de la pantalla del cargador.
 */
package cr.esaver.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Una carga registrada en el cargador. */
data class ChargeSession(
    val id: Long = 0L,
    val timestamp: Long,
    val energyKwh: Double,
    val durationSec: Int? = null,
    val voltage: Int? = null,
    val currentA: Double? = null,
    val powerKw: Double? = null,
    val chargerMaxA: Int? = null,
    val photoPath: String? = null,
    val notes: String = "",
    /** Periodo de facturación al que pertenece, con formato yyyy-MM. */
    val periodKey: String,
    /** Si no es nulo, el periodo fue elegido a mano y no se recalcula. */
    val periodOverride: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val dateTime: LocalDateTime
        get() = Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
}

/** Recibo de luz (ICE / Coopelesca / etc.) de un periodo. */
data class Bill(
    val id: Long = 0L,
    val periodKey: String,
    val totalAmount: Double,
    val totalKwh: Double,
    val energyAmount: Double? = null,
    val taxAmount: Double? = null,
    val fireAmount: Double? = null,
    val invoiceNumber: String = "",
    val clientNumber: String = "",
    val meterNumber: String = "",
    val readingFrom: Int? = null,
    val readingTo: Int? = null,
    val photoPath: String? = null,
    val notes: String = "",
    val paidAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val costPerKwh: Double
        get() = if (totalKwh > 0.0) totalAmount / totalKwh else 0.0
}

/** Datos capturados con la cámara / OCR del cargador. */
data class ChargerReading(
    val energyKwh: Double? = null,
    val durationSec: Int? = null,
    val voltage: Int? = null,
    val currentA: Double? = null,
    val powerKw: Double? = null,
    val chargerMaxA: Int? = null,
    val rawText: String = "",
) {
    val hasData: Boolean
        get() = energyKwh != null || durationSec != null || voltage != null ||
            currentA != null || powerKw != null
}
