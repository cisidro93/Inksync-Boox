package com.example.inksyncboox.ink

import androidx.compose.ui.graphics.Color
import com.example.inksyncboox.neoflow.NormalizedRect

/**
 * Normalized point in page coordinates [0.0, 1.0] with Wacom EMR pressure [0.0, 1.0]
 * and tilt metadata from the Boox Pen2 Pro stylus.
 */
data class NormalizedInkPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 0.5f,
    val tiltX: Float = 0f,
    val tiltY: Float = 0f
)

enum class BooxStylusTool(
    val label: String,
    val badge: String
) {
    NAVIGATE("Read / Step", "TAP"),
    FOUNTAIN_PEN("Wacom Ink", "PEN"),
    KALEIDO_HIGHLIGHTER("Kaleido HL", "HL"),
    QUAD_HIGHLIGHT("Snap Text HL", "TEXT"),
    ERASER("Stroke Eraser", "ERASE")
}

/**
 * Freehand vector stroke stored in normalized page coordinates [0.0, 1.0],
 * ensuring strokes remain 100% locked to the underlying text whether viewing
 * the full page or zoomed into a NeoFlow Smart Tier quadrant.
 */
data class BooxInkStroke(
    val id: String,
    val pageIndex: Int,
    val points: List<NormalizedInkPoint>,
    val colorHex: Long,
    val baseWidthPt: Float,
    val isHighlighter: Boolean
) {
    val composeColor: Color get() = Color(colorHex.toULong())
}

/**
 * Quad-point snapped text highlight region on a PDF page.
 */
data class QuadTextHighlight(
    val id: String,
    val pageIndex: Int,
    val bounds: NormalizedRect,
    val snippet: String,
    val colorHex: Long,
    val noteTag: String = ""
)
