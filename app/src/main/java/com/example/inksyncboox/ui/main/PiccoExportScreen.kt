package com.example.inksyncboox.ui.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.inksyncboox.data.DocKind
import com.example.inksyncboox.pdf.BooxPdfEngine
import com.example.inksyncboox.picco.BaseKind
import com.example.inksyncboox.picco.EpubImages
import com.example.inksyncboox.picco.PiccoDither
import com.example.inksyncboox.picco.PiccoEpub
import com.example.inksyncboox.picco.PiccoExporter
import com.example.inksyncboox.picco.PiccoFormat
import com.example.inksyncboox.picco.PiccoOptions
import com.example.inksyncboox.picco.PiccoPipeline
import com.example.inksyncboox.picco.PiccoGrid
import com.example.inksyncboox.picco.PiccoJobState
import com.example.inksyncboox.picco.PiccoJobs
import com.example.inksyncboox.picco.PiccoLayout
import com.example.inksyncboox.picco.PiccoPlanner
import com.example.inksyncboox.picco.Xtc
import com.example.inksyncboox.theme.EInkBorderGray
import com.example.inksyncboox.theme.EInkMutedText
import com.example.inksyncboox.theme.EInkPaperWhite
import com.example.inksyncboox.theme.EInkPureBlack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * "Send to Picco": converts the open PDF or comic to a Picco EPUB (default; works with BOOX Tiles)
 * or CrossPoint's XTC/XTCH format, as whole pages or one panel per screen, or shrinks an EPUB's
 * images. Then saves it, shares it (to BOOX Tiles or anywhere), or sends it over Wi-Fi to CrossPoint.
 */
@Composable
fun PiccoExportScreen(
    state: BooxReaderState,
    viewModel: MainScreenViewModel,
    engine: BooxPdfEngine,
    onClose: () -> Unit
) {
    val doc = state.document ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by PiccoJobs.state.collectAsStateWithLifecycle()
    val opts = state.settings.picco.copy(rightToLeft = state.settings.rightToLeft)
    val isEpub = doc.kind == DocKind.EPUB
    val grid = PiccoGrid(state.gridPreset, state.traversalFlow, state.colSplitRatios, state.rowSplitRatios, state.customOverrides)
    var host by remember { mutableStateOf(state.settings.piccoHost) }
    var showWifi by remember { mutableStateOf(!opts.isEpub) }

    // Keep the screen on while converting or sending (they can take minutes on a long comic).
    val view = LocalView.current
    val keepAwake = job is PiccoJobState.Working || job is PiccoJobState.Sending
    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }

    // Preview of the current page's output screens, rendered with the reader's open document.
    var preview by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var previewNote by remember { mutableStateOf<String?>(null) }
    if (!isEpub) {
        LaunchedEffect(opts, state.pageIndex, grid) {
            previewNote = "Rendering preview…"
            val result = withContext(Dispatchers.Default) {
                runCatching { renderPreview(engine, state.pageIndex, opts, grid) }
            }
            preview = result.getOrDefault(emptyList())
            previewNote = if (result.isFailure) "Preview unavailable for this page." else null
        }
    }

    val readyFile: File? = when (val j = job) {
        is PiccoJobState.Ready -> j.file
        is PiccoJobState.Sent -> j.ready.file
        is PiccoJobState.Failed -> j.ready?.file
        else -> null
    }

    var saveNote by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val file = readyFile
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
                }.getOrDefault(false)
            }
            saveNote = if (ok) "Saved. Upload it at booxtiles.com, or copy it to the Picco over USB or its SD card." else "Saving failed."
        }
    }

    fun share(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "epub") "application/epub+zip" else "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(send, "Share ${file.name}"))
        } catch (_: ActivityNotFoundException) {
            saveNote = "No app can receive this file."
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EInkPaperWhite)
            // Swallow taps so they don't reach the reader underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Send to Picco", color = EInkPureBlack, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                    Text(doc.title, color = EInkMutedText, fontSize = 15.sp, maxLines = 1)
                }
                EInkActionPill("Close", isSelected = false, onClick = onClose)
            }
            HorizontalDivider(thickness = 1.dp, color = EInkPureBlack)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                val busy = job is PiccoJobState.Working || job is PiccoJobState.Sending

                if (isEpub) {
                    Text(
                        "Makes the book's images fit the Picco's screen (464 × 764) and gray, and replaces progressive JPEGs " +
                            "(which the Picco shows blurry), so the book opens quickly. Text and layout don't change. " +
                            "Books without large images don't need this; send them as they are.",
                        color = EInkPureBlack, fontSize = 16.sp
                    )
                    EInkActionPill("Optimize for Picco", isSelected = true, onClick = {
                        if (!busy) PiccoJobs.optimizeEpub(context, Uri.parse(doc.uri), doc.title)
                    })
                } else {
                    OptionRow("Format") {
                        PiccoFormat.entries.forEach { f ->
                            EInkActionPill(f.label, opts.format == f, { viewModel.updatePiccoOptions { it.copy(format = f) } })
                        }
                    }
                    Text(
                        if (opts.isEpub) {
                            "Works with the Picco's own reader, the BOOX Tiles app and booxtiles.com. Each screen is a picture sized to the Picco's reading area."
                        } else {
                            "XTC files open in CrossPoint's reader. BOOX Tiles doesn't list them, so copy them to the SD card or send them over Wi-Fi."
                        },
                        color = EInkMutedText, fontSize = 14.sp
                    )
                    OptionRow("Layout") {
                        PiccoLayout.entries.forEach { layout ->
                            EInkActionPill(layout.label, opts.layout == layout, { viewModel.updatePiccoOptions { it.copy(layout = layout) } })
                        }
                    }
                    if (opts.layout != PiccoLayout.WHOLE) {
                        Text(
                            if (grid.panels() != null) {
                                "Panels follow your tier grid (${state.gridPreset.badge}, ${state.traversalFlow.label}) on every page."
                            } else {
                                "No tier grid is set, so each page (or spread half) is split into 2 panels. Set a grid in Panels & tiers to change this."
                            },
                            color = EInkMutedText, fontSize = 14.sp
                        )
                    }
                    if (opts.isEpub) {
                        OptionRow("Images") {
                            EpubImages.entries.forEach { im ->
                                EInkActionPill(im.label, opts.epubImages == im, { viewModel.updatePiccoOptions { it.copy(epubImages = im) } })
                            }
                        }
                    } else {
                        OptionRow("Dithering") {
                            PiccoDither.entries.forEach { d ->
                                EInkActionPill(d.label, opts.dither == d, { viewModel.updatePiccoOptions { it.copy(dither = d) } })
                            }
                        }
                    }
                    OptionRow("Page") {
                        EInkActionPill("Auto-crop margins", opts.autoCrop, { viewModel.updatePiccoOptions { it.copy(autoCrop = !it.autoCrop) } })
                        EInkActionPill("Split spreads", opts.splitSpreads, { viewModel.updatePiccoOptions { it.copy(splitSpreads = !it.splitSpreads) } })
                        EInkActionPill("Rotate wide panels", opts.rotateWide, { viewModel.updatePiccoOptions { it.copy(rotateWide = !it.rotateWide) } })
                    }
                    Text(
                        "Reading order: ${if (opts.rightToLeft) "right to left (manga)" else "left to right"}, from the reader's Display menu.",
                        color = EInkMutedText, fontSize = 14.sp
                    )
                    Text(
                        "Comics with a ComicInfo.xml get their title, series and author from it, and manga marked right-to-left is ordered that way.",
                        color = EInkMutedText, fontSize = 14.sp
                    )
                    PreviewStrip(preview, previewNote, state.pageIndex, opts.isEpub)
                    estimate(state, opts, grid)?.let { (pages, bytes) ->
                        Text("About $pages screens, ${"%.1f".format(bytes / 1_048_576.0)} MB", color = EInkPureBlack, fontSize = 16.sp)
                    }
                    EInkActionPill("Convert", isSelected = true, onClick = {
                        if (!busy) {
                            saveNote = null
                            PiccoJobs.exportComic(context, Uri.parse(doc.uri), doc.kind, doc.title, doc.key, opts, grid)
                        }
                    })
                }

                HorizontalDivider(thickness = 1.dp, color = EInkBorderGray)
                JobStatus(job)

                if (readyFile != null && !busy) {
                    val readyIsEpub = readyFile.extension.equals("epub", ignoreCase = true)
                    Text("Get it onto the Picco", color = EInkPureBlack, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (readyIsEpub) {
                            "• Share… → BOOX Tiles (if the app is on this device), or any app that syncs to your phone.\n" +
                                "• Or Save to device…, then upload it at booxtiles.com or copy it over USB / to the SD card.\n" +
                                "Keep the same file name when you re-send a book so the Picco keeps your place."
                        } else {
                            "• Save to device… and copy it to the Picco's SD card, or\n" +
                                "• Send over Wi-Fi below if the Picco runs CrossPoint's file transfer screen."
                        },
                        color = EInkMutedText, fontSize = 14.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        EInkActionPill("Share…", false, { share(readyFile) })
                        EInkActionPill("Save to device…", false, { saveLauncher.launch(readyFile.name) })
                    }
                    saveNote?.let { Text(it, color = EInkMutedText, fontSize = 14.sp) }
                    if (readyIsEpub) {
                        Text(
                            "On the Picco: keep portrait orientation and the default margins, and set the reader's image option to \"Display\" " +
                                "so pictures aren't replaced by placeholders.",
                            color = EInkMutedText, fontSize = 14.sp
                        )
                    }
                    EInkActionPill(
                        if (showWifi) "Hide Wi-Fi sending" else "Send over Wi-Fi (CrossPoint firmware)…",
                        false,
                        { showWifi = !showWifi }
                    )
                }
                if (readyFile != null && !busy && showWifi) {
                    Text(
                        "For Picco firmware with CrossPoint's file transfer screen: open it on the Picco, and enter the address it shows. " +
                            "Files go to the ${if (readyFile.extension.equals("epub", true)) "Books" else "Comics"} folder.",
                        color = EInkMutedText, fontSize = 14.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            singleLine = true,
                            label = { Text("Picco address, e.g. 192.168.1.42") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EInkPureBlack,
                                unfocusedBorderColor = EInkMutedText,
                                focusedLabelColor = EInkPureBlack,
                                cursorColor = EInkPureBlack
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(12.dp))
                        EInkActionPill("Send to Picco", true, {
                            PiccoJobs.send(host.trim()) { worked -> viewModel.setPiccoHost(worked.removePrefix("http://")) }
                        })
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = EInkBorderGray)
                Text(
                    "No firmware changes are needed. " +
                        "Only DRM-free files can be converted. If you ever flash other firmware, back up the Picco first " +
                        "(esptool read_flash); Onyx says flashing may void the warranty.",
                    color = EInkMutedText, fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = EInkMutedText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun JobStatus(job: PiccoJobState) {
    when (job) {
        PiccoJobState.Idle -> Unit
        is PiccoJobState.Working -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (job.total > 0) "${job.label}: screen ${job.done} of ${job.total}" else job.label,
                color = EInkPureBlack, fontSize = 16.sp
            )
            if (job.total > 0) ProgressBar(job.done.toFloat() / job.total)
            EInkActionPill("Cancel", false, { PiccoJobs.cancel() })
        }
        is PiccoJobState.Ready -> Text("Ready: ${job.file.name} (${job.note})", color = EInkPureBlack, fontSize = 16.sp)
        is PiccoJobState.Sending -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sending ${job.ready.file.name}…", color = EInkPureBlack, fontSize = 16.sp)
            if (job.total > 0) ProgressBar(job.sent.toFloat() / job.total)
            EInkActionPill("Cancel", false, { PiccoJobs.cancel() })
        }
        is PiccoJobState.Sent -> Text(
            "Sent ${job.ready.file.name} to ${job.host.removePrefix("http://")}. Find it in the ${if (job.ready.isEpub) "Books" else "Comics"} folder on the Picco.",
            color = EInkPureBlack, fontSize = 16.sp
        )
        is PiccoJobState.Failed -> Text(job.message, color = EInkPureBlack, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .border(1.dp, EInkPureBlack)
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(EInkPureBlack))
    }
}

/** Rough screen count and file size from the page shapes the reader already knows. */
private fun estimate(
    state: BooxReaderState,
    opts: com.example.inksyncboox.picco.PiccoOptions,
    grid: PiccoGrid
): Pair<Int, Long>? {
    if (state.pageAspects.isEmpty()) return null
    val sizes = state.pageAspects.map { a -> (1000 * a.coerceAtLeast(0.05f)).roundToInt() to 1000 }
    val panels = grid.panels()
    val plan = PiccoPlanner.plan(sizes, opts, panels?.let { p -> { _: Int, _: BaseKind -> p } })
    if (opts.isEpub) return plan.pages.size to PiccoEpub.estimateBytes(plan.pages.size, opts.epubImages)
    val chapters = if (opts.layout == PiccoLayout.WHOLE) 0 else plan.chapters.size
    return plan.pages.size to Xtc.fileSize(opts.twoBit, plan.pages.size, chapters)
}

/** Renders the output screens of one source page, as the Picco will get them. */
private suspend fun renderPreview(engine: BooxPdfEngine, pageIndex: Int, opts: PiccoOptions, grid: PiccoGrid): List<ImageBitmap> {
    val (w, h) = engine.exportPageSize(pageIndex)
    val gray = PiccoPipeline.toGray(engine.exportPageArgb(pageIndex), w, h)
    val panels = grid.panels()
    val plan = PiccoPlanner.plan(listOf(w to h), opts, panels?.let { p -> { _: Int, _: BaseKind -> p } })
    return plan.pages.take(12).map { page ->
        val (pixels, pw, ph) = if (opts.isEpub) {
            val canvas = PiccoExporter.renderEpubScreen(gray, w, h, page, opts)
            Triple(IntArray(canvas.size) { i -> grayArgb(canvas[i].toInt() and 0xFF) }, PiccoEpub.WIDTH, PiccoEpub.HEIGHT)
        } else {
            val idx = PiccoExporter.renderPage(gray, w, h, page, opts)
            val step = if (opts.twoBit) 85 else 255
            Triple(IntArray(idx.size) { i -> grayArgb(idx[i] * step) }, Xtc.WIDTH, Xtc.HEIGHT)
        }
        Bitmap.createBitmap(pixels, pw, ph, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

private fun grayArgb(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

@Composable
private fun PreviewStrip(screens: List<ImageBitmap>, note: String?, pageIndex: Int, isEpub: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Preview of page ${pageIndex + 1}" + if (screens.isNotEmpty()) " (${screens.size} ${if (screens.size == 1) "screen" else "screens"})" else "",
            color = EInkMutedText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
        )
        if (note != null) Text(note, color = EInkMutedText, fontSize = 14.sp)
        if (screens.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                screens.forEach { img ->
                    Image(
                        bitmap = img,
                        contentDescription = null,
                        modifier = Modifier
                            .width(150.dp)
                            .aspectRatio(img.width.toFloat() / img.height)
                            .border(1.dp, EInkPureBlack),
                        filterQuality = FilterQuality.Medium
                    )
                }
            }
            if (isEpub) {
                Text("Smooth gray here; the Picco adds its own dithering when it shows each picture.", color = EInkMutedText, fontSize = 13.sp)
            }
        }
    }
}
