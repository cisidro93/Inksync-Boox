package com.example.inksyncboox.picco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class EpubOptimizerTest {

    private fun epub(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val opf = "OEBPS/content.opf" to "<package/>".toByteArray()

    @Test
    fun `mimetype goes first, uncompressed, and images are replaced`() {
        val src = epub(opf, "mimetype" to "application/epub+zip".toByteArray(), "OEBPS/a.jpg" to ByteArray(5000) { 1 })
        val out = ByteArrayOutputStream()
        val result = EpubOptimizer.optimize(ByteArrayInputStream(src), out) { _, _ -> ByteArray(10) }
        assertEquals(1, result.imagesChanged)
        assertEquals(4990L, result.bytesSaved)

        val bytes = out.toByteArray()
        // Local header (30 bytes) + "mimetype", then the stored content.
        assertEquals("mimetype", String(bytes, 30, 8))
        assertEquals("application/epub+zip", String(bytes, 38, 20))
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val first = zip.nextEntry!!
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            val names = generateSequence { zip.nextEntry?.also { zip.readBytes() } }.map { it.name }.toList()
            assertEquals(listOf("OEBPS/content.opf", "OEBPS/a.jpg"), names)
        }
    }

    @Test
    fun `font obfuscation is allowed but content encryption is refused`() {
        val fonts = """<encryption><EncryptedData><EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/></EncryptedData></encryption>"""
        val ok = epub("mimetype" to "application/epub+zip".toByteArray(), opf, "META-INF/encryption.xml" to fonts.toByteArray())
        EpubOptimizer.optimize(ByteArrayInputStream(ok), ByteArrayOutputStream()) { _, _ -> null }

        val drm = """<encryption><EncryptedData><enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/></EncryptedData></encryption>"""
        val bad = epub("mimetype" to "application/epub+zip".toByteArray(), opf, "META-INF/encryption.xml" to drm.toByteArray())
        val failure = runCatching { EpubOptimizer.optimize(ByteArrayInputStream(bad), ByteArrayOutputStream()) { _, _ -> null } }
        assertTrue(failure.exceptionOrNull() is EpubOptimizer.DrmException)

        val rights = epub("mimetype" to "application/epub+zip".toByteArray(), opf, "META-INF/rights.xml" to ByteArray(1))
        assertTrue(runCatching { EpubOptimizer.optimize(ByteArrayInputStream(rights), ByteArrayOutputStream()) { _, _ -> null } }
            .exceptionOrNull() is EpubOptimizer.DrmException)
    }
}
