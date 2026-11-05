/*
 * E-saver — actividad principal.
 *
 * Es el único punto de entrada de la app. Aquí se configura el tema (claro,
 * oscuro o el del sistema) y se delega toda la navegación a ESaverApp.
 */
package cr.esaver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cr.esaver.ui.ESaverApp
import cr.esaver.ui.theme.ESaverTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = viewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()
            ESaverTheme(mode = settings.theme) {
                ESaverApp(vm)
            }
        }
    }
}
