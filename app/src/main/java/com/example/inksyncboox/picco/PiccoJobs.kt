package com.example.inksyncboox.picco

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import com.example.inksyncboox.data.DocKind
import com.example.inksyncboox.neoflow.BooxGridPreset
import com.example.inksyncboox.neoflow.BooxTraversalFlow
import com.example.inksyncboox.neoflow.NeoFlowEngine
import com.example.inksyncboox.neoflow.NormalizedRect
import com.example.inksyncboox.pdf.BooxPdfEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The reader's tier grid, used as saved panels for the Picco export. */
data class PiccoGrid(
    val preset: BooxGridPreset,
    val flow: BooxTraversalFlow,
    val colSplits: List<Float>,
    val rowSplits: List<Float>,
    val overrides: Map<Int, NormalizedRect>
) {
    /** Panels in reading order, or null for the whole-page grid (the export then uses its auto grid). */
    fun panels(): List<NRect>? {
        if (preset == BooxGridPreset.GRID_1X1) return null
        return NeoFlowEngine.computeBlocks(preset, flow, colSplits, rowSplits, customOverrides = overrides)
            .map { b -> NRect(b.baseRect.left.toDouble(), b.baseRect.top.toDouble(), b.baseRect.right.toDouble(), b.baseRect.bottom.toDouble()) }
    }
}

sealed interface PiccoJobState {
    data object Idle : PiccoJobState
    data class Working(val label: String, val done: Int, val total: Int) : PiccoJobState
    data class Ready(val file: File, val pages: Int, val isEpub: Boolean, val note: String) : PiccoJobState
    data class Sending(val ready: Ready, val sent: Long, val total: Long) : PiccoJobState
    data class Sent(val ready: Ready, val host: String) : PiccoJobState
    data class Failed(val message: String, val ready: Ready?) : PiccoJobState
}

/** Runs Picco exports and uploads in the background so they survive leaving the screen. */
object PiccoJobs {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow<PiccoJobState>(PiccoJobState.Idle)
    val state: StateFlow<PiccoJobState> = _state.asStateFlow()

    private const val COMICS_DIR = "Comics"
    private const val BOOKS_DIR = "Books"

    fun fileName(title: String, extension: String): String {
        val base = title.substringBeforeLast('.', title)
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .ifEmpty { "Book" }
            .take(96)
        return "$base.$extension"
    }

    private fun outDir(context: Context) = File(context.cacheDir, "picco-out").apply {
        deleteRecursively()
        mkdirs()
    }

    fun exportComic(
        context: Context,
        uri: Uri,
        kind: DocKind,
        title: String,
        docKey: String,
        options: PiccoOptions,
        grid: PiccoGrid?
    ) {
        val app = context.applicationContext
        job?.cancel()
        _state.value = PiccoJobState.Working("Preparing…", 0, 0)
        job = scope.launch {
            val engine = BooxPdfEngine(app, cacheTag = "export")
            val dir = outDir(app)
            var part: File? = null
            try {
                val pages = if (kind == DocKind.PDF) engine.openPdf(uri) else engine.openComic(uri)
                val info = ComicInfo.parse(engine.comicInfoXml)
                val fallbackTitle = title.substringBeforeLast('.', title)
                val meta = info?.meta(fallbackTitle) ?: PiccoMeta(fallbackTitle)
                val opts = if (info?.rightToLeft == true) options.copy(rightToLeft = true) else options
                val failed = sortedSetOf<Int>()
                val source = object : PiccoPageSource {
                    override val pageCount = pages
                    override suspend fun size(index: Int) = try {
                        engine.exportPageSize(index)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failed += index
                        PLACEHOLDER_SIZE
                    }
                    override suspend fun decodeArgb(index: Int): IntArray {
                        if (index in failed) error("unreadable")
                        return engine.exportPageArgb(index)
                    }
                }
                val panels = grid?.panels()
                val panelsFor = panels?.let { p -> { _: Int, _: BaseKind -> p } }

                // Free space: plan from page sizes (no decoding) and compare with the estimate.
                val sizes = (0 until pages).map { source.size(it) }
                val planned = PiccoPlanner.plan(sizes, opts, panelsFor)
                val needed = if (opts.isEpub) PiccoEpub.estimateBytes(planned.pages.size, opts.epubImages)
                else Xtc.fileSize(opts.twoBit, planned.pages.size, planned.chapters.size)
                val free = dir.usableSpace
                if (free in 1 until needed + 50L * 1024 * 1024) {
                    error("Not enough free space: the file needs about ${needed / 1_048_576 + 1} MB. Free some space and try again.")
                }

                val target = File(dir, fileName(meta.title, opts.format.extension))
                part = File(target.path + ".part")
                val label = "Converting"
                val plan = part.outputStream().buffered(256 * 1024).use { out ->
                    PiccoExporter.export(
                        source = cachedSizes(source, sizes),
                        opts = opts,
                        meta = meta,
                        out = out,
                        panelsFor = panelsFor,
                        encoder = AndroidGrayEncoder,
                        onPageFailed = { index, _ -> failed += index }
                    ) { done, total -> _state.value = PiccoJobState.Working(label, done, total) }
                }
                if (!part.renameTo(target)) error("Couldn't save the converted file")
                savePositionMap(app, docKey, target.name, opts, plan)
                val unit = if (opts.layout == PiccoLayout.WHOLE) "pages" else "screens"
                val notes = mutableListOf("${plan.pages.size} $unit from $pages pages, ${"%.1f".format(target.length() / 1_048_576.0)} MB")
                if (info != null) notes += "title from ComicInfo.xml" + if (info.rightToLeft && !options.rightToLeft) ", manga order" else ""
                if (failed.isNotEmpty()) {
                    val list = failed.take(8).joinToString(", ") { (it + 1).toString() } + if (failed.size > 8) "…" else ""
                    notes += "${failed.size} unreadable ${if (failed.size == 1) "page was" else "pages were"} left blank ($list)"
                }
                _state.value = PiccoJobState.Ready(target, plan.pages.size, opts.isEpub, notes.joinToString("; "))
            } catch (e: CancellationException) {
                part?.delete()
                _state.value = PiccoJobState.Idle
                throw e
            } catch (t: Throwable) {
                part?.delete()
                val message = if (t is OutOfMemoryError) "The device ran out of memory. Try again, or close other apps." else t.message
                _state.value = PiccoJobState.Failed(message ?: "Conversion failed", null)
            } finally {
                engine.close()
            }
        }
    }

    /** A page source whose sizes were already read (and placeholders filled in for unreadable pages). */
    private fun cachedSizes(source: PiccoPageSource, sizes: List<Pair<Int, Int>>) = object : PiccoPageSource {
        override val pageCount = source.pageCount
        override suspend fun size(index: Int) = sizes[index]
        override suspend fun decodeArgb(index: Int) = source.decodeArgb(index)
    }

    /** Size used to plan a page that can't be read; it is exported as a white page. */
    private val PLACEHOLDER_SIZE = 1000 to 1500

    fun optimizeEpub(context: Context, uri: Uri, title: String) {
        val app = context.applicationContext
        job?.cancel()
        _state.value = PiccoJobState.Working("Optimizing images…", 0, 0)
        job = scope.launch {
            val target = File(outDir(app), fileName("${title.substringBeforeLast('.', title)} (Picco)", "epub"))
            val part = File(target.path + ".part")
            try {
                val needed = app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 } ?: 0L
                if (target.parentFile!!.usableSpace in 1 until needed + 50L * 1024 * 1024) {
                    error("Not enough free space to optimize this book.")
                }
                val result = app.contentResolver.openInputStream(uri)?.use { input ->
                    part.outputStream().buffered().use { out -> EpubOptimizer.optimize(input, out, AndroidGrayTranscoder) }
                } ?: error("Couldn't open the book")
                if (!part.renameTo(target)) error("Couldn't save the optimized book")
                val saved = result.bytesSaved / 1024
                _state.value = PiccoJobState.Ready(target, 0, true, "${result.imagesChanged} images optimized, $saved KB smaller")
            } catch (e: CancellationException) {
                part.delete()
                _state.value = PiccoJobState.Idle
                throw e
            } catch (t: Throwable) {
                part.delete()
                _state.value = PiccoJobState.Failed(t.message ?: "Optimizing failed", null)
            }
        }
    }

    /** Sends the ready file to the Picco. Calls [onConnected] with the address that worked. */
    fun send(host: String, onConnected: (String) -> Unit) {
        val ready = when (val s = _state.value) {
            is PiccoJobState.Ready -> s
            is PiccoJobState.Sent -> s.ready
            is PiccoJobState.Failed -> s.ready ?: return
            else -> return
        }
        job?.cancel()
        _state.value = PiccoJobState.Sending(ready, 0, ready.file.length())
        job = scope.launch {
            val uploader = PiccoUploader()
            try {
                val device = uploader.findDevice(listOf(host, "crosspoint.local"))
                    ?: throw PiccoUploader.UploadException(
                        "No Picco found at \"$host\". Turn on its file transfer (Wi-Fi) screen and check the address it shows."
                    )
                onConnected(device)
                val dir = if (ready.isEpub) BOOKS_DIR else COMICS_DIR
                uploader.mkdir(device, "/", dir)
                uploader.upload(device, ready.file, "/$dir", onProgress = { sent, total ->
                    _state.value = PiccoJobState.Sending(ready, sent, total)
                }, isCancelled = { job?.isCancelled == true })
                _state.value = PiccoJobState.Sent(ready, device)
            } catch (e: CancellationException) {
                _state.value = ready
                throw e
            } catch (t: Throwable) {
                _state.value = PiccoJobState.Failed(
                    (t.message ?: "Sending failed") + " You can still save the file and copy it to the SD card.",
                    ready
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismiss() {
        if (_state.value !is PiccoJobState.Working && _state.value !is PiccoJobState.Sending) {
            _state.value = PiccoJobState.Idle
        }
    }

    /** Output page → source page/panel, for future reading-progress sync (spec section 7). */
    private fun savePositionMap(context: Context, docKey: String, fileName: String, options: PiccoOptions, plan: PiccoPlan) {
        val dir = File(context.filesDir, "picco-maps").apply { mkdirs() }
        val pages = JSONArray()
        plan.pages.forEach { p ->
            pages.put(JSONObject().put("source", p.source).put("kind", p.base.key).put("panel", p.panelIndex))
        }
        val json = JSONObject()
            .put("specVersion", PICCO_SPEC_VERSION)
            .put("file", fileName)
            .put("format", options.format.extension)
            .put("layout", options.layout.name)
            .put("pages", pages)
        File(dir, "$docKey.json").writeText(json.toString())
    }
}

/** Encodes Picco EPUB screens: baseline JPEG (Android's encoder never writes progressive) or 8-bit gray PNG. */
object AndroidGrayEncoder : GrayImageEncoder {
    override fun encode(gray: ByteArray, w: Int, h: Int, format: EpubImages): ByteArray {
        if (format == EpubImages.PNG) return PngGrayEncoder.png(gray, w, h)
        val pixels = IntArray(w * h) { i ->
            val v = gray[i].toInt() and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream(120_000)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}

/**
 * Optimizer images: shrinks to fit the Picco's reading area (464 × 764) and converts to gray,
 * keeping the format. Also replaces progressive JPEGs, which CrossPoint shows at 1/8 resolution.
 */
object AndroidGrayTranscoder : EpubOptimizer.ImageTranscoder {
    override fun transcode(name: String, bytes: ByteArray): ByteArray? {
        val lower = name.lowercase()
        // CrossPoint can't show WebP and the name can't change without rewriting the book, so leave it.
        if (lower.endsWith(".webp")) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        val scale = min(1.0, min(PiccoEpub.WIDTH.toDouble() / w, PiccoEpub.HEIGHT.toDouble() / h))
        val tw = max(1, (w * scale).roundToInt())
        val th = max(1, (h * scale).roundToInt())
        var sample = 1
        while (w / (sample * 2) >= tw && h / (sample * 2) >= th) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val gray = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        gray.eraseColor(android.graphics.Color.WHITE) // transparent areas become white, as on the Picco
        Canvas(gray).drawBitmap(
            decoded,
            null,
            android.graphics.Rect(0, 0, tw, th),
            Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        )
        decoded.recycle()
        val result = if (lower.endsWith(".png")) {
            val px = IntArray(tw * th)
            gray.getPixels(px, 0, tw, 0, 0, tw, th)
            PngGrayEncoder.png(ByteArray(px.size) { (px[it] and 0xFF).toByte() }, tw, th)
        } else {
            ByteArrayOutputStream().also { gray.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        }
        gray.recycle()
        val mustReplace = w > PiccoEpub.WIDTH || h > PiccoEpub.HEIGHT || EpubOptimizer.isProgressiveJpeg(bytes)
        // Otherwise keep the original unless the gray version is smaller.
        return if (!mustReplace && result.size >= bytes.size) null else result
    }
}
