package com.example.inksyncboox.pdf

import com.example.inksyncboox.neoflow.NormalizedRect

/**
 * Finds the drawn area of a page so blank margins can be cropped away.
 *
 * Ported from Aidoku's `CropBordersProcessor`: on a downscaled copy of the page, pixels that are
 * near-white (every channel above 0xAA) or near-black (every channel below 0x05) count as border;
 * the crop is the bounding box of everything else. Works on plain ARGB pixels so it can be unit
 * tested without Android.
 */
object ContentBounds {

    private const val WHITE_THRESHOLD = 0xAA
    private const val BLACK_THRESHOLD = 0x05

    /** Extra space kept around the content, as a fraction of the page. */
    private const val PADDING = 0.012f

    /** If the content box is smaller than this fraction of the page, keep the whole page. */
    private const val MIN_AREA = 0.15f

    fun find(pixels: IntArray, width: Int, height: Int): NormalizedRect {
        val full = NormalizedRect(0f, 0f, 1f, 1f)
        if (width <= 0 || height <= 0 || pixels.size < width * height) return full

        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val c = pixels[row + x]
                if ((c ushr 24) == 0) continue // transparent
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > WHITE_THRESHOLD && g > WHITE_THRESHOLD && b > WHITE_THRESHOLD) continue
                if (r < BLACK_THRESHOLD && g < BLACK_THRESHOLD && b < BLACK_THRESHOLD) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX || maxY < minY) return full

        val rect = NormalizedRect(
            left = (minX.toFloat() / width - PADDING).coerceAtLeast(0f),
            top = (minY.toFloat() / height - PADDING).coerceAtLeast(0f),
            right = ((maxX + 1).toFloat() / width + PADDING).coerceAtMost(1f),
            bottom = ((maxY + 1).toFloat() / height + PADDING).coerceAtMost(1f)
        )
        val area = (rect.right - rect.left) * (rect.bottom - rect.top)
        return if (area < MIN_AREA) full else rect
    }
}
