package com.example.inksyncboox.ui.main

/** How taps on the page map to page turns. */
enum class TapLayout(val label: String) {
    /** Left quarter = previous, right quarter = next, middle = menu. */
    EDGES("Left / right edges"),

    /**
     * Mihon's "Kindle-ish" layout: top third = menu, left third below it = previous,
     * everything else = next. Comfortable one-handed on a large screen.
     */
    KINDLE("Kindle style");
}

enum class TapAction { PREVIOUS, NEXT, MENU }

object TapZones {

    /**
     * Resolves a tap at ([x], [y]) on a page of size [width]×[height].
     * [rightToLeft] mirrors the layout horizontally for manga.
     */
    fun resolve(layout: TapLayout, x: Float, y: Float, width: Float, height: Float, rightToLeft: Boolean): TapAction {
        val fx = (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f).let { if (rightToLeft) 1f - it else it }
        val fy = (y / height.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return when (layout) {
            TapLayout.EDGES -> when {
                fx < 0.25f -> TapAction.PREVIOUS
                fx > 0.75f -> TapAction.NEXT
                else -> TapAction.MENU
            }
            TapLayout.KINDLE -> when {
                fy < 1f / 3f -> TapAction.MENU
                fx < 1f / 3f -> TapAction.PREVIOUS
                else -> TapAction.NEXT
            }
        }
    }
}
