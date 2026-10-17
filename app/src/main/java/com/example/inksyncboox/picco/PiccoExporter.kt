package com.example.inksyncboox.picco

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.floor

enum class PiccoLayout(val label: String) {
    WHOLE("Whole pages"),
    PANELS("Panels"),
    PANELS_OVERVIEW("Panels + page overview")
}

enum class PiccoDither(val label: String) {
    FLOYD_STEINBERG("Floyd–Steinberg"),
    ATKINSON("Atkinson"),
    NONE("None (crisp lines)")
}

/** Export options (docs/picco-export-spec.md, section 2). */
data class PiccoOptions(
    val twoBit: Boolean = true,
    val layout: PiccoLayout = PiccoLayout.WHOLE,
    val autoCrop: Boolean = true,
    val splitSpreads: Boolean = true,
    val dither: PiccoDither = PiccoDither.FLOYD_STEINBERG,
    val rotateWide: Boolean = false,
    val rightToLeft: Boolean = false
)

/** A panel as a normalized rectangle (0..1) inside its page or half. */
data class NRect(val l: Double, val t: Double, val r: Double, val b: Double) {
    val area: Double get() = (r - l) * (b - t)
    fun union(o: NRect) = NRect(minOf(l, o.l), minOf(t, o.t), maxOf(r, o.r), maxOf(b, o.b))
}

enum class BaseKind(val key: String) { WHOLE("whole"), FIRST("first"), SECOND("second"), OVERVIEW("overview") }

sealed interface Cell {
    data class Auto(val k: Int) : Cell
    data class Panel(val rect: NRect) : Cell
}

/** One page of the output file, and where it comes from (also the position map entry). */
data class OutputPage(val source: Int, val base: BaseKind, val cell: Cell?, val panelIndex: Int)

data class PiccoPlan(val pages: List<OutputPage>, val chapters: List<XtcChapter>)

/** Where the exporter gets source pages. Sizes must equal the decoded pixel sizes. */
interface PiccoPageSource {
    val pageCount: Int
    suspend fun size(index: Int): Pair<Int, Int>
    suspend fun decodeArgb(index: Int): IntArray
}

object PiccoPlanner {

    /** Joins panels under 8% of the area to a neighbour (spec section 3). */
    fun mergeSmall(rects: List<NRect>): List<NRect> {
        val out = mutableListOf<NRect>()
        var pending: NRect? = null
        for (orig in rects) {
            var r = orig
            pending?.let { r = it.union(r); pending = null }
            if (r.area * 100 < 8) {
                if (out.isNotEmpty()) out[out.lastIndex] = out.last().union(r) else pending = r
                continue
            }
            out += r
        }
        pending?.let { p -> if (out.isNotEmpty()) out[out.lastIndex] = out.last().union(p) else out += p }
        return out
    }

    /**
     * Plans the output pages from page sizes alone. [panelsFor] returns saved panels for a page
     * half (or the whole page), or null to use the 2-cell auto grid.
     */
    fun plan(
        sizes: List<Pair<Int, Int>>,
        opts: PiccoOptions,
        panelsFor: ((page: Int, base: BaseKind) -> List<NRect>?)? = null
    ): PiccoPlan {
        val pages = mutableListOf<OutputPage>()
        val chapters = mutableListOf<XtcChapter>()
        sizes.forEachIndexed { i, (w, h) ->
            val start = pages.size
            val spread = opts.splitSpreads && 100L * w > 105L * h
            val bases = if (spread) listOf(BaseKind.FIRST, BaseKind.SECOND) else listOf(BaseKind.WHOLE)
            if (opts.layout == PiccoLayout.WHOLE) {
                bases.forEach { pages += OutputPage(i, it, null, 0) }
            } else {
                if (opts.layout == PiccoLayout.PANELS_OVERVIEW) pages += OutputPage(i, BaseKind.OVERVIEW, null, 0)
                for (base in bases) {
                    val rects = panelsFor?.invoke(i, base)
                    if (!rects.isNullOrEmpty()) {
                        mergeSmall(rects).forEachIndexed { k, r -> pages += OutputPage(i, base, Cell.Panel(r), k + 1) }
                    } else {
                        for (k in 0..1) pages += OutputPage(i, base, Cell.Auto(k), k + 1)
                    }
                }
                chapters += XtcChapter("Page ${i + 1}", start + 1, pages.size)
            }
        }
        return PiccoPlan(pages, chapters)
    }

    /** The part of the crop a base uses (spec section 4, step 3). */
    fun baseRect(crop: IntArray, base: BaseKind, rtl: Boolean): IntArray {
        val (x0, y0, x1, y1) = crop.toList()
        if (base == BaseKind.WHOLE || base == BaseKind.OVERVIEW) return crop
        val mid = x0 + (x1 - x0) / 2
        val left = intArrayOf(x0, y0, mid, y1)
        val right = intArrayOf(mid, y0, x1, y1)
        val first = if (rtl) right else left
        val second = if (rtl) left else right
        return if (base == BaseKind.FIRST) first else second
    }

    private fun normToPx(lo: Double, hi: Double, base0: Int, size: Int): Pair<Int, Int> {
        var a = base0 + floor(lo * size).toInt()
        var b = base0 + ceil(hi * size).toInt()
        a = a.coerceIn(base0, base0 + size - 1)
        b = b.coerceIn(a + 1, base0 + size)
        return a to b
    }

    fun cellRect(base: IntArray, cell: Cell?, rtl: Boolean): IntArray {
        if (cell == null) return base
        val (bx0, by0, bx1, by1) = base.toList()
        val bw = bx1 - bx0
        val bh = by1 - by0
        val r: IntArray = when (cell) {
            is Cell.Auto -> if (bh >= bw) {
                val mid = by0 + bh / 2
                if (cell.k == 0) intArrayOf(bx0, by0, bx1, mid) else intArrayOf(bx0, mid, bx1, by1)
            } else {
                val mid = bx0 + bw / 2
                val cells = mutableListOf(intArrayOf(bx0, by0, mid, by1), intArrayOf(mid, by0, bx1, by1))
                if (rtl) cells.reverse()
                cells[cell.k]
            }
            is Cell.Panel -> {
                val (x0, x1) = normToPx(cell.rect.l, cell.rect.r, bx0, bw)
                val (y0, y1) = normToPx(cell.rect.t, cell.rect.b, by0, bh)
                intArrayOf(x0, y0, x1, y1)
            }
        }
        val padX = (bw * 2 + 50) / 100
        val padY = (bh * 2 + 50) / 100
        return intArrayOf(
            maxOf(bx0, r[0] - padX), maxOf(by0, r[1] - padY),
            minOf(bx1, r[2] + padX), minOf(by1, r[3] + padY)
        )
    }
}

object PiccoExporter {

    /** Renders one output page to a 480 × 800 canvas of brightness indices. */
    fun renderPage(gray: ByteArray, w: Int, h: Int, page: OutputPage, opts: PiccoOptions): ByteArray {
        val crop = if (opts.autoCrop) PiccoPipeline.autoCrop(gray, w, h) else intArrayOf(0, 0, w, h)
        val rect = PiccoPlanner.cellRect(PiccoPlanner.baseRect(crop, page.base, opts.rightToLeft), page.cell, opts.rightToLeft)
        var rw = rect[2] - rect[0]
        var rh = rect[3] - rect[1]
        var img = PiccoPipeline.subImage(gray, w, rect)
        if (opts.rotateWide && 5L * rw > 6L * rh) {
            val (rot, nw, nh) = PiccoPipeline.rotateCw(img, rw, rh)
            img = rot; rw = nw; rh = nh
        }
        val (fitted, dw, dh) = PiccoPipeline.fit(img, rw, rh)
        val leveled = PiccoPipeline.levelsGamma(fitted)
        val indices = PiccoPipeline.dither(leveled, dw, dh, opts.twoBit, opts.dither)
        return PiccoPipeline.compose(indices, dw, dh, opts.twoBit)
    }

    /**
     * Writes a complete .xtc/.xtch to [out]. Cancellable between pages; reports
     * (pages done, total) through [onProgress]. Returns the plan (its pages are the position map).
     */
    suspend fun export(
        source: PiccoPageSource,
        opts: PiccoOptions,
        title: String,
        author: String,
        out: OutputStream,
        panelsFor: ((Int, BaseKind) -> List<NRect>?)? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): PiccoPlan {
        val sizes = (0 until source.pageCount).map { source.size(it) }
        val plan = PiccoPlanner.plan(sizes, opts, panelsFor)
        val chapters = if (opts.layout == PiccoLayout.WHOLE) emptyList() else plan.chapters
        val writer = XtcWriter(out, opts.twoBit, plan.pages.size, title, author, chapters)
        var cachedIndex = -1
        var cachedGray = ByteArray(0)
        var cachedW = 0
        var cachedH = 0
        plan.pages.forEachIndexed { n, page ->
            currentCoroutineContext().ensureActive()
            if (page.source != cachedIndex) {
                val (w, h) = sizes[page.source]
                val argb = source.decodeArgb(page.source)
                require(argb.size == w * h) { "Page ${page.source + 1} decoded to an unexpected size" }
                cachedGray = PiccoPipeline.toGray(argb, w, h)
                cachedIndex = page.source
                cachedW = w
                cachedH = h
            }
            writer.writePage(renderPage(cachedGray, cachedW, cachedH, page, opts))
            onProgress(n + 1, plan.pages.size)
        }
        writer.finish()
        return plan.copy(chapters = chapters)
    }
}
