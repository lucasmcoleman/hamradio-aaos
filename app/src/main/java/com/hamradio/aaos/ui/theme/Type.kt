package com.hamradio.aaos.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// AAOS: use larger sizes than phone apps — drivers glance at 60+ cm distance.
val AppTypography = Typography(
    // Big frequency/channel display
    displayLarge = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.Bold,
        fontSize     = 52.sp,
        lineHeight   = 56.sp,
        letterSpacing = (-1).sp,
    ),
    displayMedium = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.Bold,
        fontSize     = 36.sp,
        lineHeight   = 40.sp,
    ),
    displaySmall = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.Medium,
        fontSize     = 28.sp,
        lineHeight   = 32.sp,
    ),
    // Section headers
    headlineLarge = TextStyle(
        fontFamily   = FontFamily.SansSerif,
        fontWeight   = FontWeight.SemiBold,
        fontSize     = 24.sp,
        lineHeight   = 28.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily   = FontFamily.SansSerif,
        fontWeight   = FontWeight.SemiBold,
        fontSize     = 20.sp,
        lineHeight   = 24.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily   = FontFamily.SansSerif,
        fontWeight   = FontWeight.Medium,
        fontSize     = 18.sp,
        lineHeight   = 22.sp,
    ),
    // Labels, channel names
    titleLarge = TextStyle(
        fontFamily   = FontFamily.SansSerif,
        fontWeight   = FontWeight.Medium,
        fontSize     = 18.sp,
        lineHeight   = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily   = FontFamily.SansSerif,
        fontWeight   = FontWeight.Medium,
        fontSize     = 16.sp,
        lineHeight   = 20.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily   = FontFamily.SansSerif,
        fontWeight   = FontWeight.Medium,
        fontSize     = 14.sp,
        lineHeight   = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    // Body text
    bodyLarge = TextStyle(
        fontFamily   = FontFamily.SansSerif,
        fontWeight   = FontWeight.Normal,
        fontSize     = 16.sp,
        lineHeight   = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily   = FontFamily.SansSerif,
        fontWeight   = FontWeight.Normal,
        fontSize     = 14.sp,
        lineHeight   = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily   = FontFamily.SansSerif,
        fontWeight   = FontWeight.Normal,
        fontSize     = 12.sp,
        lineHeight   = 16.sp,
    ),
    // Caps labels, tone codes
    labelLarge = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.Medium,
        fontSize     = 14.sp,
        lineHeight   = 18.sp,
        letterSpacing = 0.5.sp,
    ),
    labelMedium = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.Normal,
        fontSize     = 12.sp,
        lineHeight   = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.Normal,
        fontSize     = 10.sp,
        lineHeight   = 14.sp,
        letterSpacing = 0.5.sp,
    ),
)
