/*
 * Navegación principal.
 *
 * Las cuatro pestañas (Inicio, Cargas, Recibos, Ajustes) viven en un NavHost.
 * Los editores y el detalle del recibo se abren como rutas sin barra inferior
 * para aprovechar toda la pantalla.
 */
package cr.esaver.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cr.esaver.AppViewModel
import cr.esaver.ui.screens.BillDetailScreen
import cr.esaver.ui.screens.BillEditorScreen
import cr.esaver.ui.screens.BillsScreen
import cr.esaver.ui.screens.HomeScreen
import cr.esaver.ui.screens.SessionEditorScreen
import cr.esaver.ui.screens.SessionsScreen
import cr.esaver.ui.screens.SettingsScreen

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val TABS = listOf(
    Tab("home", "Inicio", Icons.Rounded.Home),
    Tab("sessions", "Cargas", Icons.Rounded.Bolt),
    Tab("bills", "Recibos", Icons.Rounded.ReceiptLong),
    Tab("settings", "Ajustes", Icons.Rounded.Settings),
)

@Composable
fun ESaverApp(vm: AppViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val error by vm.error.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTab = TABS.any { it.route == currentRoute }
    val showFab = currentRoute == "sessions"

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearError()
        }
    }

    fun navigateTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (isTab) {
                BottomBar(currentRoute = currentRoute, onNavigate = ::navigateTab)
            }
        },
        floatingActionButton = {
            if (showFab) {
                ExtendedFloatingActionButton(
                    onClick = { navController.navigate("session/new") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Nueva carga") },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("home") {
                HomeScreen(
                    vm = vm,
                    onAddSession = { navController.navigate("session/new") },
                    onAddBill = { key -> navController.navigate("bill/new?period=$key") },
                    onOpenSessions = { navigateTab("sessions") },
                    onOpenBillDetail = { id -> navController.navigate("bill/detail/$id") },
                    onEditBill = { id -> navController.navigate("bill/edit/$id") },
                    onEditSession = { id -> navController.navigate("session/$id") },
                )
            }
            composable("sessions") {
                SessionsScreen(
                    vm = vm,
                    onAddSession = { navController.navigate("session/new") },
                    onEditSession = { id -> navController.navigate("session/$id") },
                )
            }
            composable("bills") {
                BillsScreen(
                    vm = vm,
                    onAddBill = { key -> navController.navigate("bill/new?period=$key") },
                    onOpenBill = { id -> navController.navigate("bill/detail/$id") },
                )
            }
            composable("settings") {
                SettingsScreen(vm = vm)
            }
            composable("session/new") {
                SessionEditorScreen(
                    vm = vm,
                    sessionId = null,
                    onClose = { navController.popBackStack() },
                )
            }
            composable(
                route = "session/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                SessionEditorScreen(
                    vm = vm,
                    sessionId = entry.arguments?.getLong("id"),
                    onClose = { navController.popBackStack() },
                )
            }
            composable(
                route = "bill/new?period={period}",
                arguments = listOf(
                    navArgument("period") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                BillEditorScreen(
                    vm = vm,
                    billId = null,
                    initialPeriod = entry.arguments?.getString("period")?.takeIf { it.isNotBlank() },
                    onClose = { navController.popBackStack() },
                )
            }
            composable(
                route = "bill/edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                BillEditorScreen(
                    vm = vm,
                    billId = entry.arguments?.getLong("id"),
                    initialPeriod = null,
                    onClose = { navController.popBackStack() },
                )
            }
            composable(
                route = "bill/detail/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                BillDetailScreen(
                    vm = vm,
                    billId = entry.arguments?.getLong("id") ?: 0L,
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate("bill/edit/$id") },
                )
            }
        }
    }
}

@Composable
private fun BottomBar(currentRoute: String?, onNavigate: (String) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        TABS.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onNavigate(tab.route) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
