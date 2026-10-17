package com.example.inksyncboox.theme

import androidx.compose.ui.graphics.Color

// E-ink palette for the Note Air 4C (Kaleido 3 color filter over a monochrome panel).
// Near-black text on white for contrast; light pastel highlighter colors so highlights
// read as pale tints on the color layer and never darken the text underneath.

val EInkPaperWhite = Color(0xFFFFFFFF)
val EInkWarmIvory = Color(0xFFFAF9F5)
val EInkSurfaceGray = Color(0xFFF3F1EC)
val EInkBorderGray = Color(0xFFE4E2DD)
val EInkMutedText = Color(0xFF52525B)
val EInkPureBlack = Color(0xFF18181B)

// Highlighter colors
val KaleidoAmberHighlight = Color(0xFFFEF08A)
val KaleidoMintHighlight = Color(0xFFBBF7D0)
val KaleidoCyanHighlight = Color(0xFFBAE6FD)
val KaleidoCoralHighlight = Color(0xFFFECDD3)
val KaleidoLavenderHighlight = Color(0xFFE9D5FF)

// Pen ink colors
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
