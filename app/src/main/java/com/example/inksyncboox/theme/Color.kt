package com.example.inksyncboox.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// MODERN BOOX OS 4.0 & KALEIDO 3 E-INK PALETTE (Boox Note Air 4C)
// Warm alabaster & stone surfaces, subtle 1dp hairlines, crisp charcoal ink,
// and high-luminance Kaleido 3 pastel highlights.
// ============================================================================

val EInkPaperWhite = Color(0xFFFFFFFF)
val EInkWarmIvory = Color(0xFFFAF9F5)
val EInkSurfaceGray = Color(0xFFF3F1EC)
val EInkBorderGray = Color(0xFFE4E2DD)
val EInkMutedText = Color(0xFF52525B)
val EInkPureBlack = Color(0xFF18181B)

// Kaleido 3 Optimized Highlighter & Accent Colors (High Luminance, Crisp CFA Mapping)
val KaleidoAmberHighlight = Color(0xFFFEF08A)
val KaleidoMintHighlight = Color(0xFFBBF7D0)
val KaleidoCyanHighlight = Color(0xFFBAE6FD)
val KaleidoCoralHighlight = Color(0xFFFECDD3)
val KaleidoLavenderHighlight = Color(0xFFE9D5FF)

// Kaleido 3 Stylus Ink Colors (Refined for 150 PPI RGB subpixels)
val KaleidoCobaltInk = Color(0xFF1D4ED8)
val KaleidoCrimsonInk = Color(0xFFB91C1C)
val KaleidoEmeraldInk = Color(0xFF15803D)
val KaleidoIndigoInk = Color(0xFF4338CA)

data class KaleidoInkSwatch(
    val id: String,
    val label: String,
    val color: Color,
    val isHighlighter: Boolean = false
)

val BooxPenSwatches = listOf(
    KaleidoInkSwatch("black", "Carbon", EInkPureBlack, false),
    KaleidoInkSwatch("cobalt", "Cobalt", KaleidoCobaltInk, false),
    KaleidoInkSwatch("crimson", "Crimson", KaleidoCrimsonInk, false),
    KaleidoInkSwatch("emerald", "Sage", KaleidoEmeraldInk, false),
    KaleidoInkSwatch("amber_hl", "Amber HL", KaleidoAmberHighlight, true),
    KaleidoInkSwatch("mint_hl", "Mint HL", KaleidoMintHighlight, true),
    KaleidoInkSwatch("cyan_hl", "Sky HL", KaleidoCyanHighlight, true),
    KaleidoInkSwatch("coral_hl", "Rose HL", KaleidoCoralHighlight, true),
)
