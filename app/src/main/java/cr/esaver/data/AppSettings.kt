/*
 * Preferencias de la app: ciclo de facturación, tarifa estimada, tema y datos
 * del carro. Se guardan con DataStore (no en la base de datos de cargas).
 */
package cr.esaver.data

/** Preferencia de la tarifa usada para estimar. */
enum class EstimateSource {
    MANUAL,
    LAST_BILL,
    BILL_AVERAGE,
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class AppSettings(
    val cycleDay: Int = 21,
    val approxCostPerKwh: Double = 94.16,
    val estimateSource: EstimateSource = EstimateSource.MANUAL,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val carName: String = "Mi carro",
    /** Capacidad útil de la batería en kWh. 0 = sin configurar. */
    val batteryKwh: Double = 0.0,
)
