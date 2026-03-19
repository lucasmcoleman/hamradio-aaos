package com.hamradio.aaos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AppColorScheme = darkColorScheme(
    primary          = Accent,
    onPrimary        = Color.Black,
    primaryContainer = AccentDim,
    onPrimaryContainer = Accent,

    secondary        = RxGreen,
    onSecondary      = Color.Black,
    secondaryContainer = RxGreenDim,
    onSecondaryContainer = RxGreen,

    tertiary         = TxRed,
    onTertiary       = Color.White,
    tertiaryContainer = TxRedDim,
    onTertiaryContainer = TxRed,

    background       = Background,
    onBackground     = OnBackground,

    surface          = SurfaceCard,
    onSurface        = OnBackground,
    surfaceVariant   = SurfaceElevated,
    onSurfaceVariant = OnSurfaceMuted,

    outline          = Outline,
    outlineVariant   = Outline.copy(alpha = 0.5f),

    error            = TxRed,
    onError          = Color.White,
)

@Composable
fun HamRadioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography  = AppTypography,
        content     = content,
    )
}
