package com.pofuduk.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.pofuduk.core.model.WarningLevel
import com.pofuduk.core.model.WeatherTheme

/** Pofuduk design tokens (same palette as iOS). Contrast checked for WCAG AA on the card surfaces. */
@Immutable
data class PofudukPalette(
    val ink: Color,
    val inkInverse: Color,
    val inkSecondary: Color,
    val surface: Color,
    val surfaceStrong: Color,
    val surfaceStroke: Color,
    val accent: Color,
    val highlight: Color,
    val isDark: Boolean,
)

private fun rgb(r: Double, g: Double, b: Double, a: Double = 1.0) = Color(r.toFloat(), g.toFloat(), b.toFloat(), a.toFloat())

val LightPalette = PofudukPalette(
    ink = rgb(0.17, 0.19, 0.33),
    inkInverse = Color.White,
    inkSecondary = rgb(0.36, 0.38, 0.52),
    surface = Color.White.copy(alpha = 0.72f),
    surfaceStrong = Color.White.copy(alpha = 0.94f),
    surfaceStroke = Color.White.copy(alpha = 0.9f),
    accent = rgb(0.36, 0.32, 0.82),
    highlight = rgb(1.0, 0.86, 0.45),
    isDark = false,
)

val DarkPalette = PofudukPalette(
    ink = rgb(0.95, 0.95, 1.0),
    inkInverse = rgb(0.10, 0.11, 0.20),
    inkSecondary = rgb(0.78, 0.80, 0.90),
    surface = rgb(0.16, 0.17, 0.27, 0.82),
    surfaceStrong = rgb(0.13, 0.14, 0.23, 0.96),
    surfaceStroke = Color.White.copy(alpha = 0.12f),
    accent = rgb(0.67, 0.64, 1.0),
    highlight = rgb(0.98, 0.80, 0.35),
    isDark = true,
)

/** Character palette (independent of light/dark). */
object CharacterColors {
    val cloudTop = Color.White
    val cloudBottom = rgb(0.86, 0.88, 1.0)
    val cloudShade = rgb(0.74, 0.76, 0.96)
    val eye = rgb(0.16, 0.17, 0.32)
    val cheek = rgb(1.0, 0.62, 0.70)
    val mouth = rgb(0.55, 0.22, 0.32)
}

fun warningColor(level: WarningLevel): Color = when (level) {
    WarningLevel.GREEN -> Color(0xFF34C759)
    WarningLevel.YELLOW -> Color(0xFFFFCC00)
    WarningLevel.ORANGE -> Color(0xFFFF9500)
    WarningLevel.RED -> Color(0xFFFF3B30)
}

fun backgroundGradient(theme: WeatherTheme, dark: Boolean): List<Color> = when (theme) {
    WeatherTheme.SUNNY -> if (dark) listOf(rgb(0.16, 0.18, 0.38), rgb(0.36, 0.26, 0.42)) else listOf(rgb(1.0, 0.89, 0.70), rgb(0.80, 0.88, 1.0))
    WeatherTheme.RAINY -> if (dark) listOf(rgb(0.10, 0.14, 0.26), rgb(0.18, 0.24, 0.38)) else listOf(rgb(0.74, 0.82, 0.95), rgb(0.86, 0.88, 0.97))
    WeatherTheme.STORMY -> if (dark) listOf(rgb(0.08, 0.08, 0.16), rgb(0.22, 0.18, 0.32)) else listOf(rgb(0.55, 0.58, 0.72), rgb(0.76, 0.74, 0.86))
    WeatherTheme.SNOWY -> if (dark) listOf(rgb(0.14, 0.18, 0.30), rgb(0.26, 0.30, 0.44)) else listOf(rgb(0.88, 0.94, 1.0), rgb(0.97, 0.97, 1.0))
    WeatherTheme.CLOUDY -> if (dark) listOf(rgb(0.14, 0.15, 0.24), rgb(0.24, 0.25, 0.36)) else listOf(rgb(0.84, 0.86, 0.94), rgb(0.94, 0.92, 0.97))
    WeatherTheme.WINDY -> if (dark) listOf(rgb(0.10, 0.18, 0.26), rgb(0.20, 0.30, 0.38)) else listOf(rgb(0.76, 0.92, 0.94), rgb(0.88, 0.90, 1.0))
    WeatherTheme.NIGHT -> listOf(rgb(0.06, 0.07, 0.18), rgb(0.18, 0.16, 0.36))
}

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Night theme always uses the dark palette (light text on the night sky), like iOS. */
@Composable
fun PofudukTheme(forceDark: Boolean = false, content: @Composable () -> Unit) {
    val dark = forceDark || isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(primary = palette.accent, onPrimary = palette.inkInverse, surface = palette.surfaceStrong, onSurface = palette.ink)
    } else {
        lightColorScheme(primary = palette.accent, onPrimary = palette.inkInverse, surface = palette.surfaceStrong, onSurface = palette.ink)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
