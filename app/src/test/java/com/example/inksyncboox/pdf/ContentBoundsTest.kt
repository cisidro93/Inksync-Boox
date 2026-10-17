package com.example.inksyncboox.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBoundsTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val ink = 0xFF404040.toInt()

    private fun page(w: Int, h: Int, fill: Int, box: IntArray? = null, boxColor: Int = ink): IntArray {
        val px = IntArray(w * h) { fill }
        if (box != null) {
            val (l, t, r, b) = box.toList()
            for (y in t until b) for (x in l until r) px[y * w + x] = boxColor
        }
        return px
    }

    @Test
    fun `crops white margins around the content`() {
        val r = ContentBounds.find(page(100, 100, white, intArrayOf(20, 10, 80, 90)), 100, 100)
        assertEquals(0.188f, r.left, 0.01f)
        assertEquals(0.088f, r.top, 0.01f)
        assertEquals(0.812f, r.right, 0.01f)
        assertEquals(0.912f, r.bottom, 0.01f)
    }

    @Test
    fun `black borders count as margin too`() {
        val r = ContentBounds.find(page(100, 100, black, intArrayOf(10, 10, 90, 90)), 100, 100)
        assertTrue(r.left > 0.05f && r.right < 0.95f)
    }

    @Test
    fun `blank or tiny content keeps the whole page`() {
        val blank = ContentBounds.find(page(50, 50, white), 50, 50)
        assertEquals(0f, blank.left, 0f)
        assertEquals(1f, blank.right, 0f)
        val speck = ContentBounds.find(page(100, 100, white, intArrayOf(50, 50, 52, 52)), 100, 100)
        assertEquals(0f, speck.left, 0f)
        assertEquals(1f, speck.bottom, 0f)
    }
}
