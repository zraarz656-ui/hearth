package com.tradequest.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Centralised colour tokens.
 *
 * Chart colours are deliberately kept separate from Material roles: the chart
 * renders through Canvas and must not depend on Material theming.
 */

// --- Chart tokens (used by the Canvas renderer from Phase 8 onwards) ---
val BullishGreen = Color(0xFF26A69A)
val BearishRed = Color(0xFFEF5350)
val BullishBlue = Color(0xFF3D7DCA)
val BearishOrange = Color(0xFFF57C00)

// --- Surfaces ---
val DarkSurface = Color(0xFF101418)
val DarkSurfaceVariant = Color(0xFF1C2228)
val DarkBackground = Color(0xFF0B0B0B)

val LightSurface = Color(0xFFFDFDFD)
val LightSurfaceVariant = Color(0xFFE7EAEE)
val LightBackground = Color(0xFFF5F6F8)

/** True-black OLED surface. Pure black is required so OLED pixels switch off. */
val OledSurface = Color(0xFF000000)
val OledSurfaceVariant = Color(0xFF0A0A0A)

// --- Accents ---
val GoldAccent = Color(0xFFD4A017)
val GoldAccentDark = Color(0xFF8A6A0F)
