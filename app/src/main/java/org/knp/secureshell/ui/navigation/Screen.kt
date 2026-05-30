package org.knp.secureshell.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Hosts("hosts", "Hosts", Icons.Filled.Dns, Icons.Outlined.Dns),
    Terminal("terminal", "Terminal", Icons.Filled.Terminal, Icons.Outlined.Terminal),
    Snippets("snippets", "Snippets", Icons.Filled.Code, Icons.Outlined.Code),
    Sync("sync", "Sync", Icons.Filled.SyncAlt, Icons.Outlined.SyncAlt),
    Settings("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}
