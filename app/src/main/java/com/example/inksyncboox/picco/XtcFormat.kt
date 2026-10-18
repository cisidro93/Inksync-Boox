package com.example.inksyncboox.picco

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * XTC / XTCH file format for CrossPoint Reader (BOOX Picco). See docs/picco-export-spec.md,
 * section 1. All values little-endian.
 */
object Xtc {
    /** Shared spec version (XTC layout itself is unchanged since version 1). */
    const val SPEC_VERSION = PICCO_SPEC_VERSION
    const val WIDTH = 480
    const val HEIGHT = 800

    const val XTCH_MAGIC = 0x48435458
    const val XTC_MAGIC = 0x00435458
    const val XTH_MAGIC = 0x00485458
    const val XTG_MAGIC = 0x00475458

    const val HEADER_SIZE = 56
    const val TITLE_OFFSET = 0x38
    const val CHAPTERS_OFFSET = 0xF8
    const val CHAPTER_SIZE = 96
    const val PAGE_ENTRY_SIZE = 16
    const val PAGE_HEADER_SIZE = 22

    fun bitmapSize(twoBit: Boolean): Int =
        if (twoBit) WIDTH * ((HEIGHT + 7) / 8) * 2 else ((WIDTH + 7) / 8) * HEIGHT

    fun blockSize(twoBit: Boolean): Int = PAGE_HEADER_SIZE + bitmapSize(twoBit)

    /** Total file size for a given page and chapter count. */
    fun fileSize(twoBit: Boolean, pages: Int, chapters: Int): Long =
        CHAPTERS_OFFSET.toLong() + CHAPTER_SIZE.toLong() * chapters +
            PAGE_ENTRY_SIZE.toLong() * pages + blockSize(twoBit).toLong() * pages

    /** Stored 2-bit value for brightness index 0 (black) .. 3 (white). */
    private val XTH_VALUE = intArrayOf(3, 1, 2, 0)

    /** Packs brightness indices (0 = black .. 3 = white) into the column-major XTH layout. */
    fun packXth(indices: ByteArray, w: Int, h: Int): ByteArray {
        val colBytes = (h + 7) / 8
        val plane = w * colBytes
        val out = ByteArray(plane * 2)
        for (y in 0 until h) {
            val bit = 1 shl (7 - (y and 7))
            val yByte = y shr 3
            for (x in 0 until w) {
                val v = XTH_VALUE[indices[y * w + x].toInt()]
                val off = (w - 1 - x) * colBytes + yByte
                if (v and 2 != 0) out[off] = (out[off].toInt() or bit).toByte()
                if (v and 1 != 0) out[plane + off] = (out[plane + off].toInt() or bit).toByte()
            }
        }
        return out
    }

    /** Packs brightness indices (0 = black, 1 = white) into the row-major XTG layout. */
    fun packXtg(indices: ByteArray, w: Int, h: Int): ByteArray {
        val rowBytes = (w + 7) / 8
        val out = ByteArray(rowBytes * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (indices[y * w + x].toInt() != 0) {
                    val off = y * rowBytes + (x shr 3)
                    out[off] = (out[off].toInt() or (1 shl (7 - (x and 7)))).toByte()
                }
            }
        }
        return out
    }

    /** UTF-8 text cut to at most size − 1 bytes on a character boundary, zero-padded to [size]. */
    fun fixedUtf8(text: String, size: Int): ByteArray {
        val raw = text.toByteArray(Charsets.UTF_8)
        var cut = minOf(raw.size, size - 1)
        if (cut < raw.size) {
            while (cut > 0 && (raw[cut].toInt() and 0xC0) == 0x80) cut--
        }
        return raw.copyOf(cut).copyOf(size)
    }
}

data class XtcChapter(val name: String, val firstPage1: Int, val lastPage1: Int)

/**
 * Streams an XTC/XTCH file front to back. Every page block has the same size, so the page
 * table can be written before any page data. Call [writePage] exactly [pageCount] times.
 */
class XtcWriter(
    private val out: OutputStream,
    private val twoBit: Boolean,
    private val pageCount: Int,
    title: String,
    author: String,
    chapters: List<XtcChapter>
) {
    private var written = 0
    private val bitmapSize = Xtc.bitmapSize(twoBit)

    init {
        require(pageCount in 1..65535) { "An XTC file holds 1 to 65,535 pages (got $pageCount)" }
        val chapterOffset = if (chapters.isEmpty()) 0L else Xtc.CHAPTERS_OFFSET.toLong()
        val tableOffset = Xtc.CHAPTERS_OFFSET.toLong() + Xtc.CHAPTER_SIZE.toLong() * chapters.size
        val dataOffset = tableOffset + Xtc.PAGE_ENTRY_SIZE.toLong() * pageCount

        val header = le(Xtc.HEADER_SIZE)
            .putInt(if (twoBit) Xtc.XTCH_MAGIC else Xtc.XTC_MAGIC)
            .put(1).put(0)
            .putShort(pageCount.toShort())
            .put(0) // reading direction: pages are already in reading order
            .put(1) // has metadata
            .put(0) // no thumbnails
            .put((if (chapters.isEmpty()) 0 else 1).toByte())
            .putInt(0) // current page
            .putLong(Xtc.TITLE_OFFSET.toLong())
            .putLong(tableOffset)
            .putLong(dataOffset)
            .putLong(0) // thumbnails
            .putLong(chapterOffset)
        out.write(header.array())
        out.write(Xtc.fixedUtf8(title, 128))
        out.write(Xtc.fixedUtf8(author, 64))
        for (c in chapters) {
            val rec = le(Xtc.CHAPTER_SIZE)
            rec.put(Xtc.fixedUtf8(c.name, 80))
            rec.putShort(c.firstPage1.toShort()).putShort(c.lastPage1.toShort())
            out.write(rec.array())
        }
        val block = Xtc.blockSize(twoBit)
        val table = le(Xtc.PAGE_ENTRY_SIZE * pageCount)
        for (i in 0 until pageCount) {
            table.putLong(dataOffset + block.toLong() * i)
                .putInt(block)
                .putShort(Xtc.WIDTH.toShort())
                .putShort(Xtc.HEIGHT.toShort())
        }
        out.write(table.array())
    }

    /** Writes one page from a 480 × 800 array of brightness indices. */
    fun writePage(canvas: ByteArray) {
        check(written < pageCount) { "More pages than declared" }
        require(canvas.size == Xtc.WIDTH * Xtc.HEIGHT)
        val header = le(Xtc.PAGE_HEADER_SIZE)
            .putInt(if (twoBit) Xtc.XTH_MAGIC else Xtc.XTG_MAGIC)
            .putShort(Xtc.WIDTH.toShort())
            .putShort(Xtc.HEIGHT.toShort())
            .put(0).put(0)
            .putInt(bitmapSize)
            .putLong(0)
        out.write(header.array())
        out.write(if (twoBit) Xtc.packXth(canvas, Xtc.WIDTH, Xtc.HEIGHT) else Xtc.packXtg(canvas, Xtc.WIDTH, Xtc.HEIGHT))
        written++
    }

    fun finish() {
        check(written == pageCount) { "Wrote $written of $pageCount pages" }
        out.flush()
    }

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
