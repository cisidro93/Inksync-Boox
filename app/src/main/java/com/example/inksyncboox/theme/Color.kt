package com.example.inksyncboox.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// KALEIDO 3 E-INK HARDWARE-OPTIMIZED PALETTE (Boox Note Air 4C)
// 300 PPI B&W Layer: Pure #000000 and #FFFFFF for maximum razor-sharp contrast
// 150 PPI CFA Color Filter Layer: High-luminance pastel inks so color overlays
// never muddy or darken the underlying 300 PPI black typography.
// ============================================================================

val EInkPaperWhite = Color(0xFFFFFFFF)
val EInkWarmIvory = Color(0xFFFAF9F5)
val EInkSurfaceGray = Color(0xFFF2F2F2)
val EInkBorderGray = Color(0xFFCCCCCC)
val EInkMutedText = Color(0xFF333333)
val EInkPureBlack = Color(0xFF000000)

// Kaleido 3 Optimized Highlighter & Accent Colors (High Luminance, Crisp CFA Mapping)
val KaleidoAmberHighlight = Color(0xFFFFF176)
val KaleidoMintHighlight = Color(0xFFA5D6A7)
val KaleidoCyanHighlight = Color(0xFF81D4FA)
val KaleidoCoralHighlight = Color(0xFFFFAB91)
val KaleidoLavenderHighlight = Color(0xFFCE93D8)

// Kaleido 3 Stylus Ink Colors (Saturated for 150 PPI RGB subpixels)
val KaleidoCobaltInk = Color(0xFF0D47A1)
val KaleidoCrimsonInk = Color(0xFFB71C1C)
val KaleidoEmeraldInk = Color(0xFF1B5E20)
val KaleidoIndigoInk = Color(0xFF311B92)

data class KaleidoInkSwatch(
    val id: String,
    val label: String,
    val color: Color,
    val isHighlighter: Boolean = false
)

val BooxPenSwatches = listOf(
    KaleidoInkSwatch("black", "Carbon 300PPI", EInkPureBlack, false),
    KaleidoInkSwatch("cobalt", "Cobalt Ink", KaleidoCobaltInk, false),
    KaleidoInkSwatch("crimson", "Crimson Ink", KaleidoCrimsonInk, false),
    KaleidoInkSwatch("emerald", "Emerald Ink", KaleidoEmeraldInk, false),
    KaleidoInkSwatch("amber_hl", "Kaleido Amber", KaleidoAmberHighlight, true),
    KaleidoInkSwatch("mint_hl", "Kaleido Mint", KaleidoMintHighlight, true),
    KaleidoInkSwatch("cyan_hl", "Kaleido Cyan", KaleidoCyanHighlight, true),
    KaleidoInkSwatch("coral_hl", "Kaleido Coral", KaleidoCoralHighlight, true),
)
