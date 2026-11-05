/*
 * Ajustes persistentes con DataStore.
 *
 * Se expone un Flow<AppSettings> para que la UI reaccione a los cambios sin
 * necesidad de recargar manualmente la pantalla.
 */
package cr.esaver.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "esaver_settings",
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val CYCLE_DAY = intPreferencesKey("cycle_day")
        val APPROX_RATE = stringPreferencesKey("approx_rate")
        val ESTIMATE_SOURCE = stringPreferencesKey("estimate_source")
        val THEME = stringPreferencesKey("theme")
        val CAR_NAME = stringPreferencesKey("car_name")
        val BATTERY_KWH = stringPreferencesKey("battery_kwh")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { prefs ->
            AppSettings(
                cycleDay = (prefs[Keys.CYCLE_DAY] ?: 21).coerceIn(1, 31),
                approxCostPerKwh = prefs[Keys.APPROX_RATE]?.toDoubleOrNull() ?: 94.16,
                estimateSource = prefs[Keys.ESTIMATE_SOURCE]
                    ?.let { runCatching { EstimateSource.valueOf(it) }.getOrNull() }
                    ?: EstimateSource.MANUAL,
                theme = prefs[Keys.THEME]
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                carName = prefs[Keys.CAR_NAME]?.takeIf { it.isNotBlank() } ?: "Mi carro",
                batteryKwh = prefs[Keys.BATTERY_KWH]?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
            )
        }

    suspend fun setCycleDay(day: Int) {
        context.settingsDataStore.edit { it[Keys.CYCLE_DAY] = day.coerceIn(1, 31) }
    }

    suspend fun setApproxCostPerKwh(rate: Double) {
        context.settingsDataStore.edit { it[Keys.APPROX_RATE] = rate.toString() }
    }

    suspend fun setEstimateSource(source: EstimateSource) {
        context.settingsDataStore.edit { it[Keys.ESTIMATE_SOURCE] = source.name }
    }

    suspend fun setTheme(theme: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME] = theme.name }
    }

    suspend fun setCarProfile(name: String, batteryKwh: Double) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.CAR_NAME] = name.trim().ifBlank { "Mi carro" }
            prefs[Keys.BATTERY_KWH] = batteryKwh.coerceAtLeast(0.0).toString()
        }
    }
}
