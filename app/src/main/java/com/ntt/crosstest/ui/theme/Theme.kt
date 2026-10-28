package com.ntt.crosstest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = GreenOnPrimary,
    primaryContainer = GreenPrimaryContainer,
    onPrimaryContainer = GreenOnPrimaryContainer,
    secondary = GreenSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE8D9),
    onSecondaryContainer = Color(0xFF263E2A),
    tertiary = Color(0xFF6F537F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEEE7F6),
    onTertiaryContainer = Color(0xFF59416F),
    background = GreenBackground,
    surface = GreenSurface,
    onSurface = GreenOnSurface,
    surfaceVariant = Color(0xFFE7EEE3),
    onSurfaceVariant = Color(0xFF515E52),
    surfaceTint = GreenPrimary,
    surfaceDim = Color(0xFFD1DAD0),
    surfaceBright = Color(0xFFF8FBF5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F8F2),
    surfaceContainer = Color(0xFFEBF1E7),
    surfaceContainerHigh = Color(0xFFE7EEE3),
    surfaceContainerHighest = Color(0xFFDEE6DA),
    outline = Color(0xFF73806F),
    outlineVariant = Color(0xFFD9E2D5),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF2C352D),
    inverseOnSurface = Color(0xFFEFF5EB),
    inversePrimary = GreenPrimaryDark,
    scrim = Color.Black,
)

private val DarkColorScheme = darkColorScheme(
    primary = GreenPrimaryDark,
    onPrimary = GreenOnPrimaryDark,
    primaryContainer = GreenPrimaryContainerDark,
    onPrimaryContainer = GreenOnPrimaryContainerDark,
    secondary = GreenSecondaryDark,
    onSecondary = Color(0xFF1D3522),
    secondaryContainer = Color(0xFF354B39),
    onSecondaryContainer = Color(0xFFD3E8D4),
    tertiary = Color(0xFFDBC5EE),
    onTertiary = Color(0xFF3B294A),
    tertiaryContainer = Color(0xFF35293E),
    onTertiaryContainer = Color(0xFFDBC5EE),
    background = GreenBackgroundDark,
    surface = GreenSurfaceDark,
    onSurface = GreenOnSurfaceDark,
    surfaceVariant = Color(0xFF283229),
    onSurfaceVariant = Color(0xFFBBC7BB),
    surfaceTint = GreenPrimaryDark,
    surfaceDim = GreenBackgroundDark,
    surfaceBright = Color(0xFF343F35),
    surfaceContainerLowest = Color(0xFF0B100C),
    surfaceContainerLow = Color(0xFF171F18),
    surfaceContainer = GreenSurfaceDark,
    surfaceContainerHigh = Color(0xFF283229),
    surfaceContainerHighest = Color(0xFF333E34),
    outline = Color(0xFF8B998B),
    outlineVariant = Color(0xFF3E4C40),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = GreenOnSurfaceDark,
    inverseOnSurface = Color(0xFF273129),
    inversePrimary = GreenPrimary,
    scrim = Color.Black,
)

@Composable
fun TestCheoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    CompositionLocalProvider(
        LocalAppStatusColors provides if (darkTheme) DarkAppStatusColors else LightAppStatusColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
