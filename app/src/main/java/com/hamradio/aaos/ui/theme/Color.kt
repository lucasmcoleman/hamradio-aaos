package com.hamradio.aaos.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Base palette — tuned for AAOS dark cockpit environments
// ---------------------------------------------------------------------------

val Background       = Color(0xFF0A0A0F)
val SurfaceCard      = Color(0xFF16161E)
val SurfaceElevated  = Color(0xFF1F1F2A)
val Outline          = Color(0xFF2E2E3E)
val OnBackground     = Color(0xFFEAEAF0)
val OnSurfaceMuted   = Color(0xFF7A7A90)

// Accent — amber, readable against dark backgrounds
val Accent           = Color(0xFFFFB300)
val AccentDim        = Color(0xFF7A5500)

// ---------------------------------------------------------------------------
// TX / RX — single source of truth
// TX = RED  |  RX = GREEN
// These must be used everywhere TX/RX state is indicated.
// ---------------------------------------------------------------------------

/** Transmitting. Always red. */
val TxRed            = Color(0xFFE53935)
val TxRedDim         = Color(0xFF6B0F0F)

/** Receiving / squelch open. Always green. */
val RxGreen          = Color(0xFF00C853)
val RxGreenDim       = Color(0xFF003D1A)

/** Squelch closed (idle indicator). Amber. */
val SqAmber          = Color(0xFFFFB300)
val SqAmberDim       = Color(0xFF5C3F00)

// ---------------------------------------------------------------------------
// Signal / status
// ---------------------------------------------------------------------------

val SignalFull       = RxGreen
val SignalMid        = Color(0xFFFFD600)
val SignalLow        = TxRed
val SignalNone       = Outline

// Battery levels
val BatteryHigh      = RxGreen
val BatteryMid       = Color(0xFFFFD600)
val BatteryLow       = TxRed
val BatteryCritical  = Color(0xFFFF1744)

// GPS
val GpsLocked        = RxGreen
val GpsSearching     = SqAmber
val GpsNone          = OnSurfaceMuted

// Scan active
val ScanActive       = Accent

// DMR indicator
val DmrColor         = Color(0xFF29B6F6)

// APRS
val AprsColor        = Color(0xFFAB47BC)
