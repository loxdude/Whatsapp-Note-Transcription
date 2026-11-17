package com.local.voicenotes.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** Fallback palette (seed #49664B, the original brand green) when wallpaper colours are off. */
private val LightFallback = lightColorScheme(
    primary = Color(0xFF3A6A47), onPrimary = Color.White,
    primaryContainer = Color(0xFFBCEFC6), onPrimaryContainer = Color(0xFF00210E),
    secondary = Color(0xFF506352), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E8D3), onSecondaryContainer = Color(0xFF0E1F12),
    tertiary = Color(0xFF3A6470), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBEEAF8), onTertiaryContainer = Color(0xFF001F27),
    background = Color(0xFFF6FBF3), onBackground = Color(0xFF181D18),
    surface = Color(0xFFF6FBF3), onSurface = Color(0xFF181D18),
    surfaceVariant = Color(0xFFDDE5DA), onSurfaceVariant = Color(0xFF414941),
    outline = Color(0xFF717970), outlineVariant = Color(0xFFC1C9BF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF0F5ED),
    surfaceContainer = Color(0xFFEAEFE7), surfaceContainerHigh = Color(0xFFE4EAE2),
    surfaceContainerHighest = Color(0xFFDFE4DC)
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFA1D3AB), onPrimary = Color(0xFF06391C),
    primaryContainer = Color(0xFF215131), onPrimaryContainer = Color(0xFFBCEFC6),
    secondary = Color(0xFFB7CCB8), onSecondary = Color(0xFF233426),
    secondaryContainer = Color(0xFF394B3C), onSecondaryContainer = Color(0xFFD3E8D3),
    tertiary = Color(0xFFA2CEDB), onTertiary = Color(0xFF023640),
    tertiaryContainer = Color(0xFF214C57), onTertiaryContainer = Color(0xFFBEEAF8),
    background = Color(0xFF101510), onBackground = Color(0xFFDFE4DC),
    surface = Color(0xFF101510), onSurface = Color(0xFFDFE4DC),
    surfaceVariant = Color(0xFF414941), onSurfaceVariant = Color(0xFFC1C9BF),
    outline = Color(0xFF8B9389), outlineVariant = Color(0xFF414941),
    surfaceContainerLowest = Color(0xFF0B0F0B), surfaceContainerLow = Color(0xFF181D18),
    surfaceContainer = Color(0xFF1C211C), surfaceContainerHigh = Color(0xFF262B26),
    surfaceContainerHighest = Color(0xFF313631)
)

/** Generous, expressive corner radii. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

private val AppTypography: Typography by lazy { appTypography() }

private fun appTypography(): Typography {
    val base = Typography()
    return base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp)
    )
}

@Composable
fun VoiceNotesTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    // minSdk 31: Material You wallpaper colours are always available.
    // Remembered: the screen recomposes several times per second while the clock runs.
    val scheme: ColorScheme = remember(dark, dynamicColor, context) {
        when {
            dynamicColor && dark -> dynamicDarkColorScheme(context)
            dynamicColor -> dynamicLightColorScheme(context)
            dark -> DarkFallback
            else -> LightFallback
        }
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, shapes = AppShapes, typography = AppTypography, content = content)
}
