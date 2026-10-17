package com.example.inksyncboox.pdf

import android.os.ParcelFileDescriptor
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.File

/**
 * Unpacks the images from a RAR (`.cbr`, including RAR5) or 7-Zip (`.cb7`) comic archive using
 * libarchive (me.zhanghai.android.libarchive, the same library Mihon uses).
 *
 * RAR and 7z archives are often "solid", which means pages can't be read individually without
 * decompressing everything before them, so the whole archive is unpacked once when it opens.
 */
object ComicArchiveExtractor {

    enum class Format { ZIP, OTHER }

    /** Detects ZIP by its magic bytes; many `.cbr` files are really zips. */
    fun detect(file: File): Format {
        val header = ByteArray(4)
        val read = file.inputStream().use { it.read(header) }
        return if (read == 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
            header[2] == 3.toByte() && header[3] == 4.toByte()
        ) Format.ZIP else Format.OTHER
    }

    /**
     * Extracts every image entry of [archive] into [outDir] (emptied first).
     * Returns entry name -> extracted file, or throws if the archive can't be read.
     */
    fun extractImages(archive: File, outDir: File, isImage: (String) -> Boolean): Map<String, File> {
        outDir.deleteRecursively()
        outDir.mkdirs()
        val result = linkedMapOf<String, File>()
        val handle = Archive.readNew()
        try {
            Archive.setCharset(handle, Charsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(handle)
            Archive.readSupportFormatAll(handle)
            Archive.readOpenFileName(handle, archive.path.toByteArray(), 64 * 1024)
            while (true) {
                val entry = Archive.readNextHeader(handle)
                if (entry == 0L) break
                val name = ArchiveEntry.pathnameUtf8(entry)
                    ?: ArchiveEntry.pathname(entry)?.decodeToString()
                val isFile = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG
                if (name == null || !isFile || !isImage(name)) {
                    Archive.readDataSkip(handle)
                    continue
                }
                val ext = name.substring(name.lastIndexOf('.')).lowercase()
                val target = File(outDir, "${result.size}$ext")
                ParcelFileDescriptor.open(
                    target,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                ).use { pfd -> Archive.readDataIntoFd(handle, pfd.fd) }
                result[name] = target
            }
        } finally {
            try {
                Archive.readFree(handle)
            } catch (_: Throwable) {
            }
        }
        return result
    }
}
