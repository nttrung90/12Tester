package com.ntt.crosstest.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Content/container pairs for app states, independent of the system wallpaper. */
@Immutable
data class AppStatusColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
)

internal val LightAppStatusColors = AppStatusColors(
    success = GreenPrimary,
    successContainer = GreenPrimaryContainer,
    onSuccessContainer = GreenOnPrimaryContainer,
    warning = Color(0xFF795000),
    warningContainer = Color(0xFFFFF0D4),
    onWarningContainer = Color(0xFF795000),
    info = Color(0xFF315E86),
    infoContainer = Color(0xFFE6F0FA),
    onInfoContainer = Color(0xFF315E86),
)

internal val DarkAppStatusColors = AppStatusColors(
    success = GreenPrimaryDark,
    successContainer = GreenPrimaryContainerDark,
    onSuccessContainer = GreenOnPrimaryContainerDark,
    warning = Color(0xFFFFDB9F),
    warningContainer = Color(0xFF433319),
    onWarningContainer = Color(0xFFFFDB9F),
    info = Color(0xFFAECFEF),
    infoContainer = Color(0xFF22384D),
    onInfoContainer = Color(0xFFAECFEF),
)

internal val LocalAppStatusColors = staticCompositionLocalOf { LightAppStatusColors }

val MaterialTheme.statusColors: AppStatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppStatusColors.current
