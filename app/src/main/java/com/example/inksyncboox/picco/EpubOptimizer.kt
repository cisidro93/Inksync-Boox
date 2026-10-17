package com.example.inksyncboox.picco

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Makes an EPUB lighter for the Picco (export spec, section 8): images larger than 480 × 800
 * are shrunk and converted to gray, everything else is copied unchanged, `mimetype` is written
 * first and uncompressed. DRM-protected books are refused.
 */
object EpubOptimizer {

    /** Re-encodes one image, or returns null to keep the original bytes. */
    fun interface ImageTranscoder {
        fun transcode(name: String, bytes: ByteArray): ByteArray?
    }

    class DrmException : Exception("This book is DRM-protected and can't be converted.")

    data class Result(val imagesChanged: Int, val bytesSaved: Long)

    private val IMAGE_EXT = listOf(".jpg", ".jpeg", ".png", ".webp")

    /** Font obfuscation algorithms that use encryption.xml without being DRM. */
    private val FONT_OBFUSCATION = listOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

    fun isDrm(name: String, bytes: ByteArray): Boolean = when (name) {
        "META-INF/rights.xml" -> true
        "META-INF/encryption.xml" -> {
            val xml = bytes.decodeToString()
            // Any encryption method other than font obfuscation means the content is encrypted.
            Regex("EncryptionMethod[^>]*Algorithm\\s*=\\s*[\"']([^\"']+)[\"']").findAll(xml)
                .map { it.groupValues[1] }
                .any { alg -> FONT_OBFUSCATION.none { alg.startsWith(it) } }
        }
        else -> false
    }

    fun optimize(input: InputStream, output: OutputStream, transcoder: ImageTranscoder): Result {
        // Read all entries first: DRM must be detected before writing anything, and mimetype
        // has to be written first even if it isn't first in the source.
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) entries += e.name to zip.readBytes()
                zip.closeEntry()
            }
        }
        if (entries.none { it.first == "mimetype" } && entries.none { it.first.endsWith(".opf") }) {
            throw IllegalArgumentException("This file isn't an EPUB.")
        }
        if (entries.any { (name, bytes) -> isDrm(name, bytes) }) throw DrmException()

        var changed = 0
        var saved = 0L
        ZipOutputStream(output).use { zip ->
            val mimetype = entries.firstOrNull { it.first == "mimetype" }?.second ?: "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = mimetype.size.toLong()
                crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype)
            zip.closeEntry()

            for ((name, bytes) in entries) {
                if (name == "mimetype") continue
                var data = bytes
                if (IMAGE_EXT.any { name.lowercase().endsWith(it) }) {
                    val smaller = transcoder.transcode(name, bytes)
                    if (smaller != null) {
                        saved += bytes.size - smaller.size
                        data = smaller
                        changed++
                    }
                }
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return Result(changed, saved)
    }
}
