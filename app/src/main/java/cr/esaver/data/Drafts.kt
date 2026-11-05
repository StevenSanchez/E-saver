/*
 * Objetos de borrador que viajan del formulario al ViewModel. De esta forma la
 * UI no necesita conocer detalles de SQLite ni de fotos.
 */
package cr.esaver.data

/** Datos que llegan desde el formulario de carga. */
data class SessionDraft(
    val id: Long? = null,
    val timestamp: Long,
    val energyKwh: Double,
    val durationSec: Int? = null,
    val voltage: Int? = null,
    val currentA: Double? = null,
    val powerKw: Double? = null,
    val chargerMaxA: Int? = null,
    val photoPath: String? = null,
    val notes: String = "",
    val periodOverride: String? = null,
)

/** Datos que llegan desde el formulario de recibo. */
data class BillDraft(
    val id: Long? = null,
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
)
