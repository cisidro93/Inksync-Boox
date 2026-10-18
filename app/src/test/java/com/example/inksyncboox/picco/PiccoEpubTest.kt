package com.example.inksyncboox.picco

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

/** Picco EPUB structure (docs/picco-export-spec.md, section 9). */
class PiccoEpubTest {

    companion object {
        fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
            val result = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    result[e.name] = z.readBytes()
                }
            }
            return result
        }

        fun decodeGrayPng(png: ByteArray, w: Int, h: Int): ByteArray {
            val img = ImageIO.read(ByteArrayInputStream(png))
            assertEquals(BufferedImage.TYPE_BYTE_GRAY, img.type)
            assertEquals(w, img.width)
            assertEquals(h, img.height)
            return img.raster.getDataElements(0, 0, w, h, null) as ByteArray
        }

        private fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun plan(vararg screensPerPage: Int): PiccoPlan {
        val pages = mutableListOf<OutputPage>()
        screensPerPage.forEachIndexed { src, n -> repeat(n) { k -> pages += OutputPage(src, BaseKind.WHOLE, Cell.Auto(k), k) } }
        return PiccoPlan(pages, emptyList())
    }

    private fun build(plan: PiccoPlan, meta: PiccoMeta, images: EpubImages = EpubImages.PNG): ByteArray {
        val out = ByteArrayOutputStream()
        val w = PiccoEpubWriter(out, plan, meta, images, PngGrayEncoder, Date(0))
        repeat(plan.pages.size) { i -> w.writeScreen(ByteArray(PiccoEpub.WIDTH * PiccoEpub.HEIGHT) { (i * 40).toByte() }) }
        w.finish()
        return out.toByteArray()
    }

    @Test
    fun `mimetype is the first entry, stored, with no extra field`() {
        val bytes = build(plan(1), PiccoMeta("Book"))
        // Local file header: signature, version, flags, method (0 = stored) at offset 8.
        assertEquals(0x04034b50, (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or ((bytes[3].toInt() and 0xFF) shl 24))
        assertEquals(0, bytes[8].toInt())
        assertEquals(8, bytes[26].toInt()) // name length
        assertEquals(0, bytes[28].toInt()) // extra length
        assertEquals("mimetypeapplication/epub+zip", String(bytes, 30, 28, Charsets.US_ASCII))
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            val first = z.nextEntry!!
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
        }
    }

    @Test
    fun `files, spine, manifest and table of contents follow the spec`() {
        val entries = unzip(build(plan(1, 3, 2), PiccoMeta("A & B <test>", "Ann", "Saga", "2")))
        assertEquals(
            listOf(
                "mimetype", "META-INF/container.xml", "OEBPS/content.opf", "OEBPS/nav.xhtml", "OEBPS/toc.ncx",
                "OEBPS/p0001.xhtml", "OEBPS/p0002.xhtml", "OEBPS/p0003.xhtml",
                "OEBPS/images/s0001.png", "OEBPS/images/s0002.png", "OEBPS/images/s0003.png",
                "OEBPS/images/s0004.png", "OEBPS/images/s0005.png", "OEBPS/images/s0006.png"
            ),
            entries.keys.toList()
        )
        val opf = xml(entries.getValue("OEBPS/content.opf"))
        val dc = "http://purl.org/dc/elements/1.1/"
        assertEquals("A & B <test>", opf.getElementsByTagNameNS(dc, "title").item(0).textContent)
        assertEquals("Ann", opf.getElementsByTagNameNS(dc, "creator").item(0).textContent)
        assertEquals(PiccoEpub.identifier("A & B <test>", "Ann", 6), opf.getElementsByTagNameNS(dc, "identifier").item(0).textContent)
        val items = opf.getElementsByTagName("item")
        val byId = (0 until items.length).associate { i -> (items.item(i) as Element).let { it.getAttribute("id") to it } }
        assertEquals("cover-image", byId.getValue("cover").getAttribute("properties"))
        assertEquals("images/s0001.png", byId.getValue("cover").getAttribute("href"))
        assertEquals("image/png", byId.getValue("s0006").getAttribute("media-type"))
        assertEquals("nav", byId.getValue("nav").getAttribute("properties"))
        val spine = opf.getElementsByTagName("itemref")
        assertEquals(listOf("p0001", "p0002", "p0003"), (0 until spine.length).map { (spine.item(it) as Element).getAttribute("idref") })
        val metas = opf.getElementsByTagName("meta")
        val named = (0 until metas.length).map { metas.item(it) as Element }.associate { it.getAttribute("name") to it.getAttribute("content") }
        assertEquals("cover", named["cover"])
        assertEquals("Saga", named["calibre:series"])
        assertEquals("2", named["calibre:series_index"])
        assertTrue(entries.getValue("OEBPS/content.opf").decodeToString().contains("1970-01-01T00:00:00Z"))

        // Page 2 holds screens 2-4, one image each, nothing else in the body.
        val page2 = xml(entries.getValue("OEBPS/p0002.xhtml"))
        val imgs = page2.getElementsByTagName("img")
        assertEquals(listOf("images/s0002.png", "images/s0003.png", "images/s0004.png"),
            (0 until imgs.length).map { (imgs.item(it) as Element).getAttribute("src") })
        assertFalse((0 until imgs.length).any { (imgs.item(it) as Element).hasAttribute("width") })

        val ncx = xml(entries.getValue("OEBPS/toc.ncx"))
        val contents = ncx.getElementsByTagName("content")
        assertEquals(listOf("p0001.xhtml", "p0002.xhtml", "p0003.xhtml"), (0 until contents.length).map { (contents.item(it) as Element).getAttribute("src") })
        val nav = xml(entries.getValue("OEBPS/nav.xhtml"))
        assertEquals(3, nav.getElementsByTagName("li").length)
        assertNotNull(xml(entries.getValue("META-INF/container.xml")))
    }

    @Test
    fun `screens round-trip through the PNG encoder`() {
        val gray = ByteArray(PiccoEpub.WIDTH * PiccoEpub.HEIGHT) { i -> ((i * 7) xor (i / 464)).toByte() }
        val png = PngGrayEncoder.png(gray, PiccoEpub.WIDTH, PiccoEpub.HEIGHT)
        assertArrayEquals(gray, decodeGrayPng(png, PiccoEpub.WIDTH, PiccoEpub.HEIGHT))
    }

    @Test
    fun `identifier matches the spec test vectors`() {
        assertEquals("urn:uuid:dc8f66da-e325-56ac-a428-e9af60f98228", PiccoEpub.identifier("Fixture", "", 11))
        assertEquals("urn:uuid:1a60bfee-49bc-5f2a-9bdb-1fa2a501a352", PiccoEpub.identifier("ワンピース 第1巻", "尾田", 3))
    }

    @Test
    fun `no author means no creator element`() {
        val opf = unzip(build(plan(1), PiccoMeta("Solo")))["OEBPS/content.opf"]!!.decodeToString()
        assertFalse(opf.contains("dc:creator"))
        assertFalse(opf.contains("calibre:series"))
    }

    @Test
    fun `progressive JPEGs are detected`() {
        val baseline = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0,
            0xFF.toByte(), 0xC0.toByte(), 0, 2)
        val progressive = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0,
            0xFF.toByte(), 0xC4.toByte(), 0, 2, 0xFF.toByte(), 0xC2.toByte(), 0, 2)
        assertFalse(EpubOptimizer.isProgressiveJpeg(baseline))
        assertTrue(EpubOptimizer.isProgressiveJpeg(progressive))
        assertFalse(EpubOptimizer.isProgressiveJpeg(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }
}
