package com.example.inksyncboox.picco

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** docs/picco-export-spec.md version implemented by this app. */
const val PICCO_SPEC_VERSION = 2

/** Encodes an 8-bit gray image (0 = black, 255 = white, row-major) to JPEG or PNG bytes. */
fun interface GrayImageEncoder {
    fun encode(gray: ByteArray, w: Int, h: Int, format: EpubImages): ByteArray
}

/**
 * 8-bit grayscale PNG (color type 0, no interlace, only IHDR/IDAT/IEND), pure JVM.
 * Rows use the Up filter, which suits screentone and gradients. Asked for JPEG, it still
 * writes PNG, so tests and non-Android callers can use it.
 */
object PngGrayEncoder : GrayImageEncoder {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    override fun encode(gray: ByteArray, w: Int, h: Int, format: EpubImages): ByteArray = png(gray, w, h)

    fun png(gray: ByteArray, w: Int, h: Int): ByteArray {
        require(gray.size == w * h)
        val out = ByteArrayOutputStream(w * h / 2 + 1024)
        out.write(SIGNATURE)
        val ihdr = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(w); writeInt(h)
                writeByte(8); writeByte(0); writeByte(0); writeByte(0); writeByte(0)
            }
        }.toByteArray()
        chunk(out, "IHDR", ihdr)
        val raw = ByteArrayOutputStream(w * h / 2 + 64)
        val deflater = Deflater(9)
        DeflaterOutputStream(raw, deflater, 64 * 1024).use { z ->
            val row = ByteArray(w + 1)
            for (y in 0 until h) {
                val base = y * w
                if (y == 0) {
                    row[0] = 0
                    System.arraycopy(gray, 0, row, 1, w)
                } else {
                    row[0] = 2 // Up
                    for (x in 0 until w) row[x + 1] = (gray[base + x] - gray[base - w + x]).toByte()
                }
                z.write(row)
            }
        }
        deflater.end()
        chunk(out, "IDAT", raw.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        d.write(t)
        d.write(data)
        val crc = CRC32().apply { update(t); update(data) }
        d.writeInt(crc.value.toInt())
    }
}

/** Picco EPUB constants and helpers (docs/picco-export-spec.md, section 9). */
object PiccoEpub {
    /** CrossPoint 1.2's reading area on the Picco with default margins. */
    const val WIDTH = 464
    const val HEIGHT = 764

    /** Rough per-screen size for the estimate shown before converting. */
    fun estimateBytes(screens: Int, images: EpubImages): Long =
        screens.toLong() * (if (images == EpubImages.JPEG) 90_000L else 200_000L) + 4_000L + screens * 600L

    fun identifier(title: String, author: String, screenCount: Int): String {
        val b = MessageDigest.getInstance("SHA-256")
            .digest("inksync-picco\n$title\n$author\n$screenCount".toByteArray(Charsets.UTF_8))
            .copyOf(16)
        b[6] = ((b[6].toInt() and 0x0F) or 0x50).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        val h = b.joinToString("") { "%02x".format(it) }
        return "urn:uuid:${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    fun number(n: Int): String = n.toString().padStart(4, '0')

    fun xml(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (c < ' ' && c != '\t' && c != '\n' && c != '\r') append(' ') else append(c)
        }
    }

    /** Table of contents: (source page index, first screen index, screen count), in reading order. */
    fun pagesOf(plan: PiccoPlan): List<Triple<Int, Int, Int>> {
        val result = mutableListOf<Triple<Int, Int, Int>>()
        plan.pages.forEachIndexed { n, p ->
            val last = result.lastOrNull()
            if (last != null && last.first == p.source) result[result.size - 1] = last.copy(third = last.third + 1)
            else result += Triple(p.source, n, 1)
        }
        return result
    }
}

/**
 * Streams a Picco EPUB. Everything except the images depends only on the plan, so it is
 * written first; then call [writeScreen] once per output page with its 464 × 764 gray canvas.
 */
class PiccoEpubWriter(
    out: OutputStream,
    private val plan: PiccoPlan,
    meta: PiccoMeta,
    private val images: EpubImages,
    private val encoder: GrayImageEncoder,
    modified: Date = Date()
) {
    private val zip = ZipOutputStream(out)
    private val total = plan.pages.size
    private var written = 0

    init {
        require(total in 1..99_999) { "A Picco EPUB holds 1 to 99,999 screens (got $total)" }
        val title = meta.title.ifBlank { "Untitled" }
        val id = PiccoEpub.identifier(title, meta.author, total)
        val pages = PiccoEpub.pagesOf(plan)

        // mimetype: first, stored, no extra field.
        val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        zip.putNextEntry(ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = mime.size.toLong()
            compressedSize = mime.size.toLong()
            crc = CRC32().apply { update(mime) }.value
        })
        zip.write(mime)
        zip.closeEntry()
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)

        text("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
""")
        text("OEBPS/content.opf", opf(id, title, meta, pages, modified))
        text("OEBPS/nav.xhtml", nav(title, pages))
        text("OEBPS/toc.ncx", ncx(id, title, pages))
        for ((src, first, count) in pages) {
            val sb = StringBuilder()
            sb.append(XHTML_HEAD).append("<title>Page ").append(src + 1).append("</title></head>\n<body>\n")
            for (s in first until first + count) {
                sb.append("<div><img src=\"images/s").append(PiccoEpub.number(s + 1)).append('.')
                    .append(images.extension).append("\" alt=\"\"/></div>\n")
            }
            sb.append("</body>\n</html>\n")
            text("OEBPS/${pageFile(src)}", sb.toString())
        }
    }

    fun writeScreen(gray: ByteArray) {
        check(written < total) { "More screens than planned" }
        require(gray.size == PiccoEpub.WIDTH * PiccoEpub.HEIGHT)
        val bytes = encoder.encode(gray, PiccoEpub.WIDTH, PiccoEpub.HEIGHT, images)
        written++
        // Already-compressed images are stored, which also saves the Picco inflating them.
        zip.putNextEntry(ZipEntry("OEBPS/images/s${PiccoEpub.number(written)}.${images.extension}").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        })
        zip.write(bytes)
        zip.closeEntry()
    }

    fun finish() {
        check(written == total) { "Wrote $written of $total screens" }
        zip.finish()
        zip.flush()
    }

    private fun text(name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun pageFile(src: Int) = "p${PiccoEpub.number(src + 1)}.xhtml"

    private fun opf(id: String, title: String, meta: PiccoMeta, pages: List<Triple<Int, Int, Int>>, modified: Date): String {
        val x = PiccoEpub::xml
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(modified)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<package version="3.0" unique-identifier="bookid" xmlns="http://www.idpf.org/2007/opf">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
<dc:identifier id="bookid">$id</dc:identifier>
<dc:title>${x(title)}</dc:title>
""")
        if (meta.author.isNotBlank()) sb.append("<dc:creator>${x(meta.author)}</dc:creator>\n")
        sb.append("<dc:language>en</dc:language>\n<meta property=\"dcterms:modified\">$time</meta>\n")
        val series = meta.series?.takeIf { it.isNotBlank() }
        if (series != null) {
            sb.append("<meta name=\"calibre:series\" content=\"${x(series)}\"/>\n")
            val index = meta.seriesIndex?.takeIf { it.isNotBlank() }
            if (index != null) sb.append("<meta name=\"calibre:series_index\" content=\"${x(index)}\"/>\n")
            sb.append("<meta property=\"belongs-to-collection\" id=\"series\">${x(series)}</meta>\n")
            sb.append("<meta refines=\"#series\" property=\"collection-type\">series</meta>\n")
            if (index != null) sb.append("<meta refines=\"#series\" property=\"group-position\">${x(index)}</meta>\n")
        }
        sb.append("<meta name=\"cover\" content=\"cover\"/>\n</metadata>\n<manifest>\n")
        sb.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        sb.append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
        for ((src, _, _) in pages) {
            sb.append("<item id=\"p${PiccoEpub.number(src + 1)}\" href=\"${pageFile(src)}\" media-type=\"application/xhtml+xml\"/>\n")
        }
        for (s in 1..total) {
            val itemId = if (s == 1) "cover" else "s${PiccoEpub.number(s)}"
            val props = if (s == 1) " properties=\"cover-image\"" else ""
            sb.append("<item id=\"$itemId\" href=\"images/s${PiccoEpub.number(s)}.${images.extension}\" media-type=\"${images.mediaType}\"$props/>\n")
        }
        sb.append("</manifest>\n<spine toc=\"ncx\">\n")
        for ((src, _, _) in pages) sb.append("<itemref idref=\"p${PiccoEpub.number(src + 1)}\"/>\n")
        sb.append("</spine>\n</package>\n")
        return sb.toString()
    }

    private fun nav(title: String, pages: List<Triple<Int, Int, Int>>): String {
        val sb = StringBuilder(XHTML_HEAD)
        sb.append("<title>${PiccoEpub.xml(title)}</title></head>\n<body>\n<nav epub:type=\"toc\" id=\"toc\">\n<h1>Contents</h1>\n<ol>\n")
        for ((src, _, _) in pages) sb.append("<li><a href=\"${pageFile(src)}\">Page ${src + 1}</a></li>\n")
        sb.append("</ol>\n</nav>\n</body>\n</html>\n")
        return sb.toString()
    }

    private fun ncx(id: String, title: String, pages: List<Triple<Int, Int, Int>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head>
<meta name="dtb:uid" content="$id"/>
<meta name="dtb:depth" content="1"/>
<meta name="dtb:totalPageCount" content="0"/>
<meta name="dtb:maxPageNumber" content="0"/>
</head>
<docTitle><text>${PiccoEpub.xml(title)}</text></docTitle>
<navMap>
""")
        pages.forEachIndexed { i, (src, _, _) ->
            sb.append("<navPoint id=\"np${i + 1}\" playOrder=\"${i + 1}\"><navLabel><text>Page ${src + 1}</text></navLabel>")
            sb.append("<content src=\"${pageFile(src)}\"/></navPoint>\n")
        }
        sb.append("</navMap>\n</ncx>\n")
        return sb.toString()
    }

    private companion object {
        const val XHTML_HEAD = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en" xml:lang="en">
<head><meta charset="UTF-8"/>"""
    }
}
