package com.example.inksyncboox.neoflow

import kotlin.math.max
import kotlin.math.min

/**
 * Normalized rectangle in [0.0, 1.0] page coordinates.
 * Origin (0,0) is Top-Left in Android/Image space (X rightward, Y downward).
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0.01f)
    val height: Float get() = (bottom - top).coerceAtLeast(0.01f)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    /**
     * Expands the normalized rect by overlap ratios (Connection Redundancy) so lines of text
     * or equations at quadrant boundaries are never clipped mid-character.
     */
    fun withConnectionRedundancy(overlapRatioX: Float, overlapRatioY: Float): NormalizedRect {
        val dx = width * overlapRatioX
        val dy = height * overlapRatioY
        return NormalizedRect(
            left = (left - dx).coerceIn(0f, 1f),
            top = (top - dy).coerceIn(0f, 1f),
            right = (right + dx).coerceIn(0f, 1f),
            bottom = (bottom + dy).coerceIn(0f, 1f)
        )
    }

    /**
     * Converts between Android Top-Left Image Space and PDF Bottom-Left CropBox Space.
     */
    fun flippedForPdfSpace(): NormalizedRect = NormalizedRect(
        left = left,
        top = 1f - bottom,
        right = right,
        bottom = 1f - top
    )
}

enum class BooxGridPreset(
    val label: String,
    val badge: String,
    val columns: Int,
    val rows: Int
) {
    GRID_1X1("Full Page", "1×1", 1, 1),
    GRID_1X2("2-Tier Split", "1×2", 1, 2),
    GRID_1X3("3-Tier Paper", "1×3", 1, 3),
    GRID_2X1("2-Col Reflow", "2×1", 2, 1),
    GRID_2X2("4-Quad Matrix", "2×2", 2, 2),
    GRID_2X3("6-Block Study", "2×3", 2, 3),
    GRID_3X2("Wide 6-Grid", "3×2", 3, 2),
    GRID_3X3("9-Block Fine", "3×3", 3, 3)
}

enum class BooxTraversalFlow(
    val label: String,
    val symbol: String,
    val description: String
) {
    Z_FLOW("Z-Flow", "Z →", "Left-to-Right, Top-to-Bottom"),
    REVERSE_Z("Manga R→L", "← Z", "Right-to-Left, Top-to-Bottom"),
    N_FLOW("Academic Col", "N ↓", "Column 1 Top-to-Bottom, then Col 2"),
    REVERSE_N("Vertical R→L", "↓ N", "Right Column Top-to-Bottom, then Left")
}

data class BooxSectionBlock(
    val stepIndex: Int, // 0-based traversal order
    val row: Int,
    val col: Int,
    val baseRect: NormalizedRect,
    val redundantRect: NormalizedRect,
    val isCustomOverridden: Boolean
) {
    val displayStepNumber: Int get() = stepIndex + 1
    val label: String get() = "Tier ${stepIndex + 1} · R${row + 1}C${col + 1}"
}

object NeoFlowEngine {

    /**
     * Generates the ordered sequence of reading blocks for a page according to the M×N grid,
     * custom split ratios, traversal flow, connection redundancy, and per-quadrant overrides.
     */
    fun computeBlocks(
        preset: BooxGridPreset,
        flow: BooxTraversalFlow,
        colSplitRatios: List<Float>,
        rowSplitRatios: List<Float>,
        overlapRatio: Float = 0.08f,
        marginInset: Float = 0.02f,
        customOverrides: Map<Int, NormalizedRect> = emptyMap()
    ): List<BooxSectionBlock> {
        val cols = preset.columns
        val rows = preset.rows

        val xStops = buildStops(cols, colSplitRatios, marginInset, 1f - marginInset)
        val yStops = buildStops(rows, rowSplitRatios, marginInset, 1f - marginInset)

        // Generate ordered (row, col) pairs according to traversal flow
        val orderedCoords: List<Pair<Int, Int>> = when (flow) {
            BooxTraversalFlow.Z_FLOW -> buildList {
                for (r in 0 until rows) {
                    for (c in 0 until cols) add(r to c)
                }
            }
            BooxTraversalFlow.REVERSE_Z -> buildList {
                for (r in 0 until rows) {
                    for (c in (cols - 1) downTo 0) add(r to c)
                }
            }
            BooxTraversalFlow.N_FLOW -> buildList {
                for (c in 0 until cols) {
                    for (r in 0 until rows) add(r to c)
                }
            }
            BooxTraversalFlow.REVERSE_N -> buildList {
                for (c in (cols - 1) downTo 0) {
                    for (r in 0 until rows) add(r to c)
                }
            }
        }

        return orderedCoords.mapIndexed { stepIdx, (r, c) ->
            val defaultRect = NormalizedRect(
                left = xStops[c],
                top = yStops[r],
                right = xStops[c + 1],
                bottom = yStops[r + 1]
            )
            val customRect = customOverrides[stepIdx]
            val activeRect = customRect ?: defaultRect
            val expanded = if (preset == BooxGridPreset.GRID_1X1) {
                activeRect
            } else {
                activeRect.withConnectionRedundancy(overlapRatio, overlapRatio)
            }
            BooxSectionBlock(
                stepIndex = stepIdx,
                row = r,
                col = c,
                baseRect = activeRect,
                redundantRect = expanded,
                isCustomOverridden = customRect != null
            )
        }
    }

    private fun buildStops(
        count: Int,
        ratios: List<Float>,
        minBound: Float,
        maxBound: Float
    ): List<Float> {
        if (count <= 1) return listOf(minBound, maxBound)
        val span = (maxBound - minBound).coerceAtLeast(0.1f)
        val stops = mutableListOf(minBound)
        for (i in 0 until (count - 1)) {
            val defaultRatio = (i + 1).toFloat() / count.toFloat()
            val ratio = ratios.getOrElse(i) { defaultRatio }.coerceIn(0.12f, 0.88f)
            val mapped = minBound + span * ratio
            stops.add(max(stops.last() + 0.05f, min(mapped, maxBound - 0.05f)))
        }
        stops.add(maxBound)
        return stops
    }
}
