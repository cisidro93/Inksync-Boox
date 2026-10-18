package com.example.inksyncboox.picco

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Fixture parity with tools/picco_reference.py (docs/picco-export-spec.md, section 5). */
class PiccoExportTest {

    private class FixtureSource(private val specs: List<IntArray>) : PiccoPageSource {
        override val pageCount get() = specs.size
        override suspend fun size(index: Int) = specs[index][0] to specs[index][1]
        override suspend fun decodeArgb(index: Int): IntArray {
            val (w, h, seed, mx, my) = specs[index].toList()
            return IntArray(w * h) { i ->
                val x = i % w
                val y = i / w
                if (x < mx || x >= w - mx || y < my || y >= h - my) {
                    0xFFFFFFFF.toInt()
                } else {
                    val r = (x * 7 + y * 3) and 255
                    val g = (x * y + seed * 31) and 255
                    val b = (x * 2 + y * 5 + seed) and 255
                    val a = if ((x / 16 + y / 16) % 5 == 0) 128 else 255
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    private val pages = FixtureSource(
        listOf(intArrayOf(300, 420, 1, 30, 40), intArrayOf(840, 600, 2, 50, 36), intArrayOf(200, 1000, 3, 12, 90))
    )

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun export(opts: PiccoOptions): ByteArray = runBlocking {
        val out = ByteArrayOutputStream()
        PiccoExporter.export(pages, opts, PiccoMeta("Fixture"), out)
        out.toByteArray()
    }

    @Test
    fun `auto-crop of the first fixture page matches the reference`() = runBlocking {
        val gray = PiccoPipeline.toGray(pages.decodeArgb(0), 300, 420)
        assertArrayEquals(intArrayOf(26, 35, 274, 385), PiccoPipeline.autoCrop(gray, 300, 420))
    }

    @Test
    fun `fixture A - 2-bit panels with overview`() {
        val data = export(
            PiccoOptions(PiccoFormat.XTCH, layout = PiccoLayout.PANELS_OVERVIEW, autoCrop = true, splitSpreads = true,
                dither = PiccoDither.FLOYD_STEINBERG, rotateWide = false, rightToLeft = false)
        )
        assertEquals(1_056_954, data.size)
        assertEquals("023659e813f1a92b631e9f7d0aa20557d895a6d5efa8575516a33642642148e8", sha256(data))
    }

    @Test
    fun `fixture B - 1-bit whole pages, Atkinson, right to left, rotated`() {
        val data = export(
            PiccoOptions(PiccoFormat.XTC, layout = PiccoLayout.WHOLE, autoCrop = false, splitSpreads = true,
                dither = PiccoDither.ATKINSON, rotateWide = true, rightToLeft = true)
        )
        assertEquals(192_400, data.size)
        assertEquals("30a6c74a490b401865d9cdd102cdf5c3e2f1129fd0dd25ea778513e384c4c67c", sha256(data))
    }

    @Test
    fun `fixture C - 2-bit whole pages, no dithering, no spread split`() {
        val data = export(
            PiccoOptions(PiccoFormat.XTCH, layout = PiccoLayout.WHOLE, autoCrop = true, splitSpreads = false,
                dither = PiccoDither.NONE, rotateWide = false, rightToLeft = false)
        )
        assertEquals(288_362, data.size)
        assertEquals("220f867b03f59cdf3afc7dc0d6681bd3722573b5514ad1803e5b2c11b10a6d9e", sha256(data))
    }

    @Test
    fun `export is deterministic`() {
        val opts = PiccoOptions(format = PiccoFormat.XTCH, layout = PiccoLayout.PANELS)
        assertArrayEquals(export(opts), export(opts))
    }

    @Test
    fun `flat gray quantizes to a single level`() {
        val flat = ByteArray(40 * 40) { 120.toByte() }
        val leveled = PiccoPipeline.levelsGamma(flat) // no stretch for flat images; gamma only
        val idx = PiccoPipeline.dither(leveled, 40, 40, twoBit = true, method = PiccoDither.NONE)
        val expected = (PiccoPipeline.GAMMA_LUT[120] * 3 + 127) / 255
        assertTrue(idx.all { it.toInt() == expected })
    }

    @Test
    fun `fit keeps aspect, centers, and caps enlargement at 2,5x`() {
        val (_, dw, dh) = PiccoPipeline.fit(ByteArray(100 * 50), 100, 50)
        assertEquals(250, dw) // capped: 480 would be 4.8x
        assertEquals(125, dh)
        val (_, w2, h2) = PiccoPipeline.fit(ByteArray(960 * 3200), 960, 3200)
        assertEquals(240, w2)
        assertEquals(800, h2)
    }

    // ---- Picco EPUB (spec section 9)

    private val fixtureD = PiccoOptions(PiccoFormat.EPUB, layout = PiccoLayout.PANELS_OVERVIEW, autoCrop = true,
        splitSpreads = true, rotateWide = false, rightToLeft = false)
    private val fixtureE = PiccoOptions(PiccoFormat.EPUB, layout = PiccoLayout.WHOLE, autoCrop = false,
        splitSpreads = true, rotateWide = true, rightToLeft = true)

    /** All EPUB screens rendered directly, like the reference's epub_screens. */
    private fun screens(opts: PiccoOptions): List<ByteArray> = runBlocking {
        val sizes = (0 until pages.pageCount).map { pages.size(it) }
        val plan = PiccoPlanner.plan(sizes, opts)
        plan.pages.map { p ->
            val (w, h) = sizes[p.source]
            PiccoExporter.renderEpubScreen(PiccoPipeline.toGray(pages.decodeArgb(p.source), w, h), w, h, p, opts)
        }
    }

    private fun concatHash(parts: List<ByteArray>): String {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `fixture D - EPUB screens, panels with overview`() {
        val s = screens(fixtureD)
        assertEquals(11, s.size)
        assertEquals("fd5485665ce2936cfc76cf9050aac18da4eaeb3f3193fcd61ad2f33669ae776a", concatHash(s))
    }

    @Test
    fun `fixture E - EPUB screens, whole pages rotated right to left`() {
        val s = screens(fixtureE)
        assertEquals(4, s.size)
        assertEquals("9f1f51e8524a4d926a61a0d19ae827422db5976c1bcde61b02afd1a4ca71428b", concatHash(s))
    }

    @Test
    fun `EPUB table of contents matches the reference`() {
        val sizes = runBlocking { (0 until pages.pageCount).map { pages.size(it) } }
        val d = PiccoEpub.pagesOf(PiccoPlanner.plan(sizes, fixtureD)).map { it.first to it.second }
        assertEquals(listOf(0 to 0, 1 to 3, 2 to 8), d)
        val e = PiccoEpub.pagesOf(PiccoPlanner.plan(sizes, fixtureE)).map { it.first to it.second }
        assertEquals(listOf(0 to 0, 1 to 1, 2 to 3), e)
    }

    @Test
    fun `exported EPUB with PNG images decodes back to the fixture screens`() {
        val out = ByteArrayOutputStream()
        runBlocking {
            PiccoExporter.export(pages, fixtureD.copy(epubImages = EpubImages.PNG), PiccoMeta("Fixture"), out, encoder = PngGrayEncoder)
        }
        val entries = PiccoEpubTest.unzip(out.toByteArray())
        val images = entries.filterKeys { it.startsWith("OEBPS/images/") }.toSortedMap()
        assertEquals(11, images.size)
        val decoded = images.values.map { PiccoEpubTest.decodeGrayPng(it, PiccoEpub.WIDTH, PiccoEpub.HEIGHT) }
        assertEquals("fd5485665ce2936cfc76cf9050aac18da4eaeb3f3193fcd61ad2f33669ae776a", concatHash(decoded))
    }

    @Test
    fun `an unreadable page becomes a white screen instead of failing the export`() {
        val broken = object : PiccoPageSource {
            override val pageCount = 3
            override suspend fun size(index: Int) = pages.size(index)
            override suspend fun decodeArgb(index: Int): IntArray =
                if (index == 1) throw IllegalStateException("corrupt") else pages.decodeArgb(index)
        }
        val failed = mutableListOf<Int>()
        val out = ByteArrayOutputStream()
        val plan = runBlocking {
            PiccoExporter.export(broken, PiccoOptions(PiccoFormat.XTCH), PiccoMeta("Fixture"), out,
                onPageFailed = { i, _ -> failed += i })
        }
        assertEquals(listOf(1), failed)
        assertEquals(Xtc.fileSize(true, plan.pages.size, 0), out.size().toLong())
    }

    @Test
    fun `EPUB fit targets the reading area`() {
        val (_, dw, dh) = PiccoPipeline.fit(ByteArray(1000 * 1500), 1000, 1500, PiccoEpub.WIDTH, PiccoEpub.HEIGHT)
        assertEquals(464, dw)
        assertEquals(696, dh)
    }
}
