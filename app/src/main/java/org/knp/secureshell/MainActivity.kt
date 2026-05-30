package org.knp.secureshell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import kotlinx.coroutines.launch
import org.knp.secureshell.data.db.entity.ConnectionEntity
import org.knp.secureshell.ui.navigation.Screen
import org.knp.secureshell.ui.screens.*
import org.knp.secureshell.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SecureShellApp

        setContent {
            val scope = rememberCoroutineScope()
            var isDark by remember { mutableStateOf(true) }
            var terminalFontSize by remember { mutableIntStateOf(14) }

            LaunchedEffect(Unit) {
                app.repository.getSetting("theme")?.let { isDark = it != "light" }
                app.repository.getSetting("font_size")?.let {
                    it.toIntOrNull()?.let { size -> terminalFontSize = size }
                }
            }

            SecureShellTheme(isDark = isDark) {
                val navController = rememberNavController()
                var pendingConnection by remember { mutableStateOf<ConnectionEntity?>(null) }

                Scaffold(
                    bottomBar = { AppBottomNavigation(navController) }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Hosts.route,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable(Screen.Hosts.route) {
                            HostsScreen(app.repository) { conn ->
                                pendingConnection = conn
                                navController.navigate(Screen.Terminal.route)
                            }
                        }
                        composable(Screen.Terminal.route) {
                            val conn = pendingConnection
                            LaunchedEffect(Unit) { pendingConnection = null }
                            TerminalScreen(app.repository, app.sshManager, conn, terminalFontSize)
                        }
                        composable(Screen.Snippets.route) { SnippetsScreen(app.repository) }
                        composable(Screen.Sync.route) { SyncScreen(app.repository, app.syncManager) }
                        composable(Screen.Settings.route) {
                            SettingsScreen(
                                isDark = isDark,
                                terminalFontSize = terminalFontSize,
                                onThemeChange = { dark ->
                                    isDark = dark
                                    scope.launch {
                                        app.repository.setSetting("theme", if (dark) "dark" else "light")
                                    }
                                },
                                onFontSizeChange = { size ->
                                    terminalFontSize = size
                                    scope.launch {
                                        app.repository.setSetting("font_size", size.toString())
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppBottomNavigation(navController: NavHostController) {
    val c = LocalAppColors.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val items = listOf(Screen.Hosts, Screen.Terminal, Screen.Snippets, Screen.Sync, Screen.Settings)

    NavigationBar(containerColor = c.bgPrimary) {
        items.forEach { screen ->
            val selected = currentDestination?.route == screen.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(if (selected) screen.selectedIcon else screen.unselectedIcon, null) },
                label = { Text(screen.title) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = c.bgApp,
                    indicatorColor = c.goldPrimary,
                    unselectedIconColor = c.textMuted,
                    selectedTextColor = c.goldPrimary,
                    unselectedTextColor = c.textMuted,
                )
            )
        }
    }
}
