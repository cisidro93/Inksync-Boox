package com.example.inksyncboox.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.example.inksyncboox.neoflow.NormalizedRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/**
 * Fixed-page document engine for `.pdf` (Android [PdfRenderer]) and comic archives
 * (`.cbz` zip, plus `.cbr` RAR and `.cb7` 7-Zip via [ComicArchiveExtractor]).
 *
 * Pages are rendered region-by-region at the size they will be shown, so zooming into a
 * NeoFlow tier or comic panel stays sharp on the Note Air 4C's 300 PPI panel instead of
 * upscaling a full-page bitmap.
 */
class BooxPdfEngine(private val context: Context) {

    private enum class SourceKind { NONE, PDF, CBZ, EXTRACTED }

    private val renderMutex = Mutex()
    private var sourceKind = SourceKind.NONE
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var cbzZip: ZipFile? = null
    private var cbzEntries: List<String> = emptyList()
    private var extractedPages: List<File> = emptyList()

    // The most recently decoded CBZ page, so panning between panels doesn't re-read the zip.
    private var cachedCbzPage: Int = -1
    private var cachedCbzDecoder: BitmapRegionDecoder? = null

    var pageCount: Int = 0
        private set

    fun resolveDisplayName(uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Throwable) {
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Document"
    }

    /** Opens a PDF. Returns the page count, or throws if the file can't be read. */
    suspend fun openPdf(uri: Uri): Int = withContext(Dispatchers.IO) {
        renderMutex.withLock {
            closeInternal()
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("Could not open file")
            fileDescriptor = pfd
            val renderer = PdfRenderer(pfd)
            pdfRenderer = renderer
            sourceKind = SourceKind.PDF
            pageCount = renderer.pageCount
            pageCount
        }
    }

    private fun isComicImage(name: String): Boolean {
        val lower = name.lowercase()
        return !lower.contains("__macosx") &&
            !lower.substringAfterLast('/').startsWith(".") &&
            IMAGE_EXTENSIONS.any { lower.endsWith(it) }
    }

    /**
     * Opens a comic archive: `.cbz`/`.zip` directly, or `.cbr` (RAR, including RAR5) and
     * `.cb7` (7-Zip) by unpacking it once. The archive is copied into the app cache first.
     * Pages are ordered by natural sort (`page2.jpg` before `page10.jpg`).
     */
    suspend fun openComic(uri: Uri): Int = withContext(Dispatchers.IO) {
        renderMutex.withLock {
            closeInternal()
            val cacheFile = File(context.cacheDir, "active_comic.bin")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output -> input.copyTo(output, 64 * 1024) }
            } ?: error("Could not open file")

            if (ComicArchiveExtractor.detect(cacheFile) == ComicArchiveExtractor.Format.ZIP) {
                val zip = ZipFile(cacheFile)
                val images = zip.entries().toList()
                    .filter { !it.isDirectory && isComicImage(it.name) }
                    .map { it.name }
                    .sortedWith { a, b -> compareNaturalOrder(a, b) }
                if (images.isEmpty()) {
                    zip.close()
                    error("No images found in this archive")
                }
                cbzZip = zip
                cbzEntries = images
                sourceKind = SourceKind.CBZ
                pageCount = images.size
            } else {
                val extracted = try {
                    ComicArchiveExtractor.extractImages(
                        archive = cacheFile,
                        outDir = File(context.cacheDir, "comic-pages"),
                        isImage = { name -> isComicImage(name) }
                    )
                } catch (t: Throwable) {
                    error("This archive couldn't be read (${t.message ?: "unsupported format"}).")
                } finally {
                    cacheFile.delete() // pages are unpacked; the archive copy isn't needed
                }
                if (extracted.isEmpty()) error("No images found in this archive")
                extractedPages = extracted.keys
                    .sortedWith { a, b -> compareNaturalOrder(a, b) }
                    .map { extracted.getValue(it) }
                sourceKind = SourceKind.EXTRACTED
                pageCount = extractedPages.size
            }
            pageCount
        }
    }

    /**
     * Renders [region] (normalized page coordinates) of page [pageIndex] into a bitmap about
     * [targetWidthPx] wide, keeping the region's aspect ratio. Returns null if nothing is open.
     */
    suspend fun renderRegion(
        pageIndex: Int,
        region: NormalizedRect,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        renderMutex.withLock {
            when (sourceKind) {
                SourceKind.NONE -> null
                SourceKind.PDF -> renderPdfRegion(pageIndex, region, targetWidthPx, targetHeightPx)
                SourceKind.CBZ, SourceKind.EXTRACTED -> renderCbzRegion(pageIndex, region, targetWidthPx, targetHeightPx)
            }
        }
    }

    private fun fitSize(regionW: Float, regionH: Float, maxW: Int, maxH: Int): Pair<Int, Int> {
        val scale = minOf(maxW / regionW, maxH / regionH)
        var w = (regionW * scale).roundToInt().coerceAtLeast(1)
        var h = (regionH * scale).roundToInt().coerceAtLeast(1)
        // Never allocate more than ~1.5 screens of pixels.
        val maxPixels = MAX_BITMAP_PIXELS
        if (w.toLong() * h > maxPixels) {
            val shrink = kotlin.math.sqrt(maxPixels.toDouble() / (w.toLong() * h)).toFloat()
            w = (w * shrink).roundToInt().coerceAtLeast(1)
            h = (h * shrink).roundToInt().coerceAtLeast(1)
        }
        return w to h
    }

    private fun renderPdfRegion(pageIndex: Int, region: NormalizedRect, maxW: Int, maxH: Int): Bitmap? {
        val renderer = pdfRenderer ?: return null
        if (pageIndex !in 0 until renderer.pageCount) return null
        return renderer.openPage(pageIndex).use { page ->
            val regionWPt = region.width * page.width
            val regionHPt = region.height * page.height
            val (w, h) = fitSize(regionWPt, regionHPt, maxW, maxH)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val sx = w / regionWPt
            val sy = h / regionHPt
            val matrix = Matrix().apply {
                setScale(sx, sy)
                postTranslate(-region.left * page.width * sx, -region.top * page.height * sy)
            }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    @Suppress("DEPRECATION")
    private fun cbzDecoderFor(pageIndex: Int): BitmapRegionDecoder? {
        if (pageIndex == cachedCbzPage && cachedCbzDecoder != null) return cachedCbzDecoder
        val bytes = if (sourceKind == SourceKind.EXTRACTED) {
            extractedPages.getOrNull(pageIndex)?.readBytes() ?: return null
        } else {
            val zip = cbzZip ?: return null
            val entry = zip.getEntry(cbzEntries.getOrNull(pageIndex) ?: return null) ?: return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
        cachedCbzDecoder?.recycle()
        cachedCbzDecoder = try {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } catch (_: Throwable) {
            null
        }
        cachedCbzPage = pageIndex
        if (cachedCbzDecoder == null) {
            // Formats the region decoder can't handle (e.g. some BMP/GIF): decode whole image.
            val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val out = java.io.ByteArrayOutputStream()
            full.compress(Bitmap.CompressFormat.PNG, 100, out)
            full.recycle()
            val png = out.toByteArray()
            cachedCbzDecoder = BitmapRegionDecoder.newInstance(png, 0, png.size, false)
        }
        return cachedCbzDecoder
    }

    private fun renderCbzRegion(pageIndex: Int, region: NormalizedRect, maxW: Int, maxH: Int): Bitmap? {
        val decoder = cbzDecoderFor(pageIndex) ?: return null
        val imgW = decoder.width
        val imgH = decoder.height
        val rect = Rect(
            (region.left * imgW).roundToInt().coerceIn(0, imgW - 1),
            (region.top * imgH).roundToInt().coerceIn(0, imgH - 1),
            (region.right * imgW).roundToInt().coerceIn(1, imgW),
            (region.bottom * imgH).roundToInt().coerceIn(1, imgH)
        )
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val (targetW, _) = fitSize(rect.width().toFloat(), rect.height().toFloat(), maxW, maxH)
        var sample = 1
        while (rect.width() / (sample * 2) >= targetW) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return decoder.decodeRegion(rect, opts)
    }

    fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        try { pdfRenderer?.close() } catch (_: Throwable) {}
        try { fileDescriptor?.close() } catch (_: Throwable) {}
        try { cbzZip?.close() } catch (_: Throwable) {}
        cachedCbzDecoder?.recycle()
        cachedCbzDecoder = null
        cachedCbzPage = -1
        pdfRenderer = null
        fileDescriptor = null
        cbzZip = null
        cbzEntries = emptyList()
        extractedPages = emptyList()
        sourceKind = SourceKind.NONE
        pageCount = 0
    }

    companion object {
        private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif")
        private const val MAX_BITMAP_PIXELS = 1860L * 2480L * 3 / 2

        /** Natural ordering so "page2" sorts before "page10". */
        fun compareNaturalOrder(a: String, b: String): Int {
            val regex = Regex("(\\d+)|(\\D+)")
            val partsA = regex.findAll(a.lowercase()).map { it.value }.toList()
            val partsB = regex.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(partsA.size, partsB.size)) {
                val numA = partsA[i].toBigIntegerOrNull()
                val numB = partsB[i].toBigIntegerOrNull()
                val cmp = if (numA != null && numB != null) numA.compareTo(numB) else partsA[i].compareTo(partsB[i])
                if (cmp != 0) return cmp
            }
            return partsA.size.compareTo(partsB.size)
        }
    }
}
