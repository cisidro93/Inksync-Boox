package com.example.inksyncboox.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class TapZonesTest {

    private fun tap(layout: TapLayout, fx: Float, fy: Float, rtl: Boolean = false) =
        TapZones.resolve(layout, fx * 1000f, fy * 1400f, 1000f, 1400f, rtl)

    @Test
    fun `edges layout uses left and right quarters`() {
        assertEquals(TapAction.PREVIOUS, tap(TapLayout.EDGES, 0.1f, 0.5f))
        assertEquals(TapAction.NEXT, tap(TapLayout.EDGES, 0.9f, 0.5f))
        assertEquals(TapAction.MENU, tap(TapLayout.EDGES, 0.5f, 0.5f))
    }

    @Test
    fun `kindle layout puts the menu on top and next everywhere else on the right`() {
        assertEquals(TapAction.MENU, tap(TapLayout.KINDLE, 0.9f, 0.1f))
        assertEquals(TapAction.PREVIOUS, tap(TapLayout.KINDLE, 0.2f, 0.6f))
        assertEquals(TapAction.NEXT, tap(TapLayout.KINDLE, 0.5f, 0.6f))
        assertEquals(TapAction.NEXT, tap(TapLayout.KINDLE, 0.9f, 0.9f))
    }

    @Test
    fun `manga mode mirrors the layout`() {
        assertEquals(TapAction.NEXT, tap(TapLayout.EDGES, 0.1f, 0.5f, rtl = true))
        assertEquals(TapAction.PREVIOUS, tap(TapLayout.KINDLE, 0.9f, 0.6f, rtl = true))
    }
}
