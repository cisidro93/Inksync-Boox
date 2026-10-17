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
            val target = File(outDir(app), fileName(title, if (options.twoBit) "xtch" else "xtc"))
            val part = File(target.path + ".part")
            try {
                val pages = if (kind == DocKind.PDF) engine.openPdf(uri) else engine.openComic(uri)
                val source = object : PiccoPageSource {
                    override val pageCount = pages
                    override suspend fun size(index: Int) = engine.exportPageSize(index)
                    override suspend fun decodeArgb(index: Int) = engine.exportPageArgb(index)
                }
                val panels = grid?.panels()
                val plan = part.outputStream().buffered(256 * 1024).use { out ->
                    PiccoExporter.export(
                        source, options, title.substringBeforeLast('.', title), "", out,
                        panelsFor = panels?.let { p -> { _: Int, _: BaseKind -> p } }
                    ) { done, total -> _state.value = PiccoJobState.Working("Converting", done, total) }
                }
                if (!part.renameTo(target)) error("Couldn't save the converted file")
                savePositionMap(app, docKey, target.name, options, plan)
                val layout = if (options.layout == PiccoLayout.WHOLE) "pages" else "screens"
                _state.value = PiccoJobState.Ready(target, plan.pages.size, false, "${plan.pages.size} $layout from $pages pages")
            } catch (e: CancellationException) {
                part.delete()
                _state.value = PiccoJobState.Idle
                throw e
            } catch (t: Throwable) {
                part.delete()
                _state.value = PiccoJobState.Failed(t.message ?: "Conversion failed", null)
            } finally {
                engine.close()
            }
        }
    }

    fun optimizeEpub(context: Context, uri: Uri, title: String) {
        val app = context.applicationContext
        job?.cancel()
        _state.value = PiccoJobState.Working("Optimizing images…", 0, 0)
        job = scope.launch {
            val target = File(outDir(app), fileName("${title.substringBeforeLast('.', title)} (Picco)", "epub"))
            val part = File(target.path + ".part")
            try {
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
            .put("specVersion", Xtc.SPEC_VERSION)
            .put("file", fileName)
            .put("format", if (options.twoBit) "xtch" else "xtc")
            .put("layout", options.layout.name)
            .put("pages", pages)
        File(dir, "$docKey.json").writeText(json.toString())
    }
}

/** Shrinks EPUB images to fit 480 × 800 and converts them to gray, keeping their format. */
object AndroidGrayTranscoder : EpubOptimizer.ImageTranscoder {
    override fun transcode(name: String, bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        val scale = min(1.0, min(480.0 / w, 800.0 / h))
        val tw = max(1, (w * scale).roundToInt())
        val th = max(1, (h * scale).roundToInt())
        var sample = 1
        while (w / (sample * 2) >= tw && h / (sample * 2) >= th) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val gray = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        Canvas(gray).drawBitmap(
            decoded,
            null,
            android.graphics.Rect(0, 0, tw, th),
            Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        )
        decoded.recycle()
        val lower = name.lowercase()
        val out = ByteArrayOutputStream()
        val format = when {
            lower.endsWith(".png") -> Bitmap.CompressFormat.PNG
            lower.endsWith(".webp") -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.JPEG
        }
        gray.compress(format, if (format == Bitmap.CompressFormat.PNG) 100 else 80, out)
        gray.recycle()
        val result = out.toByteArray()
        val shrunk = w > 480 || h > 800
        // Keep the original if it already fits and the gray version isn't smaller.
        return if (!shrunk && result.size >= bytes.size) null else result
    }
}
