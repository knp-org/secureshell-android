package org.knp.secureshell.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

data class AppColors(
    val bgApp: Color,
    val bgPrimary: Color,
    val bgSecondary: Color,
    val bgCard: Color,
    val bgCardHover: Color,
    val bgInput: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val border: Color,
    val borderFocus: Color,
    val goldPrimary: Color,
    val goldDark: Color,
    val terminalBg: Color,
    val surfaceDim: Color,
    val isDark: Boolean,
)

val DarkAppColors = AppColors(
    bgApp = BgApp, bgPrimary = BgPrimary, bgSecondary = BgSecondary,
    bgCard = BgCard, bgCardHover = BgCardHover, bgInput = BgInput,
    textPrimary = TextPrimary, textSecondary = TextSecondary, textMuted = TextMuted,
    border = Border, borderFocus = BorderFocus,
    goldPrimary = GoldPrimary, goldDark = GoldDark,
    terminalBg = TerminalBg, surfaceDim = SurfaceDim, isDark = true,
)

val LightAppColors = AppColors(
    bgApp = LightBgApp, bgPrimary = LightBgPrimary, bgSecondary = LightBgSecondary,
    bgCard = LightBgCard, bgCardHover = LightBgCardHover, bgInput = LightBgInput,
    textPrimary = LightTextPrimary, textSecondary = LightTextSecondary, textMuted = LightTextMuted,
    border = LightBorder, borderFocus = LightBorderFocus,
    goldPrimary = LightGoldPrimary, goldDark = LightGoldDark,
    terminalBg = LightTerminalBg, surfaceDim = LightSurfaceDim, isDark = false,
)

val LocalAppColors = compositionLocalOf { DarkAppColors }

private val GoldDarkScheme = darkColorScheme(
    primary            = GoldPrimary,
    onPrimary          = BgApp,
    primaryContainer   = GoldDark,
    onPrimaryContainer = TextPrimary,
    secondary          = GoldMuted,
    onSecondary        = TextPrimary,
    tertiary           = GoldLight,
    background         = BgApp,
    onBackground       = TextPrimary,
    surface            = BgPrimary,
    onSurface          = TextPrimary,
    surfaceVariant     = BgSecondary,
    onSurfaceVariant   = TextSecondary,
    outline            = Border,
    outlineVariant     = Border,
    error              = Error,
    onError            = TextPrimary,
)

private val GoldLightScheme = lightColorScheme(
    primary            = LightGoldPrimary,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFFEF3C7),
    onPrimaryContainer = LightGoldDark,
    secondary          = Color(0xFF92400E),
    onSecondary        = Color.White,
    tertiary           = GoldDark,
    background         = LightBgApp,
    onBackground       = LightTextPrimary,
    surface            = LightBgPrimary,
    onSurface          = LightTextPrimary,
    surfaceVariant     = LightBgSecondary,
    onSurfaceVariant   = LightTextSecondary,
    outline            = LightBorder,
    outlineVariant     = LightBorder,
    error              = Error,
    onError            = Color.White,
)

@Composable
fun SecureShellTheme(
    isDark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (isDark) GoldDarkScheme else GoldLightScheme
    val appColors = if (isDark) DarkAppColors else LightAppColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, true)
            val controller = WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
