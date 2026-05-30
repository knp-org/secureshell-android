package org.knp.secureshell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.knp.secureshell.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isDark: Boolean,
    terminalFontSize: Int,
    onThemeChange: (Boolean) -> Unit,
    onFontSizeChange: (Int) -> Unit,
) {
    val c = LocalAppColors.current
    var showFontPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = c.bgPrimary,
                    titleContentColor = c.textPrimary,
                )
            )
        },
        containerColor = c.bgApp
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ─── Appearance section ───
            Text(
                "Appearance",
                color = c.goldPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )

            // Theme toggle
            Card(colors = CardDefaults.cardColors(containerColor = c.bgCard)) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isDark) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                        null,
                        tint = c.goldPrimary,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Theme", color = c.textPrimary, fontWeight = FontWeight.Bold)
                        Text(
                            if (isDark) "Dark" else "Light",
                            color = c.textMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.bgSecondary),
                    ) {
                        ThemeChip("Dark", isDark, c) { onThemeChange(true) }
                        ThemeChip("Light", !isDark, c) { onThemeChange(false) }
                    }
                }
            }

            // Font size
            Card(colors = CardDefaults.cardColors(containerColor = c.bgCard)) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .clickable { showFontPicker = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.TextFields, null, tint = c.goldPrimary)
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Terminal Font Size", color = c.textPrimary, fontWeight = FontWeight.Bold)
                        Text(
                            "${terminalFontSize}px",
                            color = c.textMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { if (terminalFontSize > 10) onFontSizeChange(terminalFontSize - 1) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Filled.Remove, "Decrease", tint = c.textSecondary)
                        }
                        Text(
                            "$terminalFontSize",
                            color = c.textPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        IconButton(
                            onClick = { if (terminalFontSize < 24) onFontSizeChange(terminalFontSize + 1) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Filled.Add, "Increase", tint = c.textSecondary)
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            val context = LocalContext.current
            val versionName = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } catch (_: Exception) { "" }
            }
            Text(
                "SecureShell Android Companion" + if (versionName.isNullOrBlank()) "" else "  v$versionName",
                color = c.textMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, c: AppColors, onClick: () -> Unit) {
    val bg = if (selected) c.goldPrimary else c.bgSecondary
    val fg = if (selected) c.bgApp else c.textMuted

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
