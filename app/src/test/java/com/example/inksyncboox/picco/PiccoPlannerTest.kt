package com.example.inksyncboox.picco

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PiccoPlannerTest {

    private val portrait = 600 to 900
    private val spread = 1400 to 900

    @Test
    fun `spreads split into halves in reading order`() {
        val ltr = PiccoPlanner.plan(listOf(spread), PiccoOptions())
        assertEquals(listOf(BaseKind.FIRST, BaseKind.SECOND), ltr.pages.map { it.base })
        val crop = intArrayOf(0, 0, 1400, 900)
        assertArrayEquals(intArrayOf(0, 0, 700, 900), PiccoPlanner.baseRect(crop, BaseKind.FIRST, rtl = false))
        assertArrayEquals(intArrayOf(700, 0, 1400, 900), PiccoPlanner.baseRect(crop, BaseKind.FIRST, rtl = true))
    }

    @Test
    fun `panel chapters cover each source page`() {
        val plan = PiccoPlanner.plan(listOf(portrait, spread, portrait), PiccoOptions(layout = PiccoLayout.PANELS_OVERVIEW))
        // 1 + 2 | 1 + 2 + 2 | 1 + 2
        assertEquals(11, plan.pages.size)
        assertEquals(
            listOf(XtcChapter("Page 1", 1, 3), XtcChapter("Page 2", 4, 8), XtcChapter("Page 3", 9, 11)),
            plan.chapters
        )
        // Position map: chapter ranges agree with the source of each page.
        plan.chapters.forEachIndexed { src, c ->
            assertEquals(true, plan.pages.subList(c.firstPage1 - 1, c.lastPage1).all { it.source == src })
        }
    }

    @Test
    fun `auto grid cells follow manga order on wide bases`() {
        val wide = intArrayOf(0, 0, 1000, 400)
        val firstLtr = PiccoPlanner.cellRect(wide, Cell.Auto(0), rtl = false)
        val firstRtl = PiccoPlanner.cellRect(wide, Cell.Auto(0), rtl = true)
        assertEquals(0, firstLtr[0])
        assertEquals(500 - 20, firstRtl[0]) // right cell, minus 2% padding
    }

    @Test
    fun `small panels are merged into a neighbour`() {
        val big1 = NRect(0.0, 0.0, 1.0, 0.45)
        val tiny = NRect(0.0, 0.45, 0.2, 0.5)
        val big2 = NRect(0.0, 0.5, 1.0, 1.0)
        assertEquals(listOf(NRect(0.0, 0.0, 1.0, 0.5), big2), PiccoPlanner.mergeSmall(listOf(big1, tiny, big2)))
        // A tiny first panel joins the next one.
        assertEquals(listOf(NRect(0.0, 0.45, 1.0, 1.0)), PiccoPlanner.mergeSmall(listOf(tiny, big2)))
    }

    @Test
    fun `saved panels replace the auto grid`() {
        val plan = PiccoPlanner.plan(listOf(portrait), PiccoOptions(layout = PiccoLayout.PANELS)) { _, _ ->
            listOf(NRect(0.0, 0.0, 1.0, 0.3), NRect(0.0, 0.3, 1.0, 0.6), NRect(0.0, 0.6, 1.0, 1.0))
        }
        assertEquals(3, plan.pages.size)
        assertEquals(listOf(1, 2, 3), plan.pages.map { it.panelIndex })
    }
}
