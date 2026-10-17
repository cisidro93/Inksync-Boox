package com.example.inksyncboox.picco

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XtcFormatTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `2-bit packing matches the spec vector`() {
        // Every row: white, dark gray, light gray, black (brightness indices 3, 1, 2, 0).
        val idx = ByteArray(4 * 8) { i -> intArrayOf(3, 1, 2, 0)[i % 4].toByte() }
        assertEquals("ffff0000ff00ff00", hex(Xtc.packXth(idx, 4, 8)))
    }

    @Test
    fun `1-bit packing matches the spec vector`() {
        val idx = byteArrayOf(0, 1, 0, 1, 0, 1, 0, 1)
        assertEquals("55", hex(Xtc.packXtg(idx, 8, 1)))
    }

    @Test
    fun `utf8 text is cut on a character boundary`() {
        val s = "a".repeat(78) + "é" // 78 + 2 bytes = 80 > 79
        val fixed = Xtc.fixedUtf8(s, 80)
        assertEquals(80, fixed.size)
        assertEquals("a".repeat(78), String(fixed.copyOf(78), Charsets.UTF_8))
        assertEquals(0, fixed[78].toInt())
    }

    /** Independent reader written from the spec (not from the writer). */
    private class Parsed(data: ByteArray) {
        val b: ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val magic = b.getInt(0)
        val version = (b.get(4).toInt() to b.get(5).toInt())
        val pageCount = b.getShort(6).toInt() and 0xFFFF
        val hasChapters = b.get(0x0B).toInt()
        val tableOffset = b.getLong(0x18)
        val dataOffset = b.getLong(0x20)
        val chapterOffset = b.getLong(0x30)
        val title = String(data, 0x38, 128, Charsets.UTF_8).trimEnd('\u0000')
        val author = String(data, 0xB8, 64, Charsets.UTF_8).trimEnd('\u0000')
        val chapters = if (chapterOffset == 0L) emptyList() else
            (0 until ((tableOffset - chapterOffset) / 96).toInt()).map { i ->
                val o = (chapterOffset + i * 96).toInt()
                Triple(
                    String(data, o, 80, Charsets.UTF_8).trimEnd('\u0000'),
                    b.getShort(o + 0x50).toInt(),
                    b.getShort(o + 0x52).toInt()
                )
            }

        fun entry(i: Int): LongArray {
            val o = (tableOffset + i * 16).toInt()
            return longArrayOf(b.getLong(o), b.getInt(o + 8).toLong(), b.getShort(o + 12).toLong(), b.getShort(o + 14).toLong())
        }

        /** Brightness index of pixel (x, y) on page i, decoded the way CrossPoint does. */
        fun pixel(i: Int, x: Int, y: Int): Int {
            val off = entry(i)[0].toInt()
            val twoBit = magic == Xtc.XTCH_MAGIC
            val bitmap = off + 22
            return if (twoBit) {
                val colBytes = 100
                val o = bitmap + (479 - x) * colBytes + y / 8
                val bit = 7 - y % 8
                val v = (((b.get(o).toInt() shr bit) and 1) shl 1) or ((b.get(o + 48000).toInt() shr bit) and 1)
                intArrayOf(3, 1, 2, 0)[v] // stored value -> brightness (the mapping is its own inverse)
            } else {
                (b.get(bitmap + y * 60 + x / 8).toInt() shr (7 - x % 8)) and 1
            }
        }
    }

    @Test
    fun `round trip through an independent reader`() {
        val pages = (0 until 3).map { p -> ByteArray(480 * 800) { i -> ((i + p * 7) % 4).toByte() } }
        val chapters = listOf(XtcChapter("One", 1, 2), XtcChapter("Two", 3, 3))
        val out = ByteArrayOutputStream()
        XtcWriter(out, true, 3, "Title ✓", "Author", chapters).apply {
            pages.forEach { writePage(it) }
            finish()
        }
        val data = out.toByteArray()
        assertEquals(Xtc.fileSize(true, 3, 2), data.size.toLong())
        val f = Parsed(data)
        assertEquals(Xtc.XTCH_MAGIC, f.magic)
        assertEquals(1 to 0, f.version)
        assertEquals(3, f.pageCount)
        assertEquals(1, f.hasChapters)
        assertEquals(0xF8L, f.chapterOffset)
        assertEquals(0xF8L + 2 * 96, f.tableOffset)
        assertEquals(f.tableOffset + 3 * 16, f.dataOffset)
        assertEquals("Title ✓", f.title)
        assertEquals("Author", f.author)
        assertEquals(listOf(Triple("One", 1, 2), Triple("Two", 3, 3)), f.chapters)
        for (i in 0 until 3) {
            val e = f.entry(i)
            assertEquals(f.dataOffset + i * 96022L, e[0])
            assertEquals(96022L, e[1])
            assertEquals(480L, e[2]); assertEquals(800L, e[3])
            assertEquals(Xtc.XTH_MAGIC, f.b.getInt(e[0].toInt()))
            assertEquals(96000, f.b.getInt(e[0].toInt() + 10))
        }
        for (p in 0 until 3) for (y in listOf(0, 1, 7, 8, 399, 799)) for (x in listOf(0, 1, 240, 478, 479)) {
            assertEquals("page $p ($x,$y)", pages[p][y * 480 + x].toInt(), f.pixel(p, x, y))
        }
    }

    @Test
    fun `1-bit files without chapters put the page table right after the author`() {
        val out = ByteArrayOutputStream()
        val canvas = ByteArray(480 * 800) { i -> (i % 2).toByte() }
        XtcWriter(out, false, 1, "", "", emptyList()).apply { writePage(canvas); finish() }
        val f = Parsed(out.toByteArray())
        assertEquals(Xtc.XTC_MAGIC, f.magic)
        assertEquals(0, f.hasChapters)
        assertEquals(0L, f.chapterOffset)
        assertEquals(0xF8L, f.tableOffset)
        assertArrayEquals(longArrayOf(0xF8L + 16, 48022, 480, 800), f.entry(0))
        assertEquals(1, f.pixel(0, 1, 0))
        assertEquals(0, f.pixel(0, 0, 0))
    }
}
