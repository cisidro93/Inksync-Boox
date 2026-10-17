package com.example.inksyncboox.ui.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.inksyncboox.data.DocKind
import com.example.inksyncboox.picco.PiccoDither
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
 * "Send to Picco": converts the open PDF or comic to CrossPoint's XTC/XTCH format (whole pages
 * or one panel per screen), or shrinks an EPUB's images, then saves, shares or sends it over Wi-Fi.
 */
@Composable
fun PiccoExportScreen(
    state: BooxReaderState,
    viewModel: MainScreenViewModel,
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
            saveNote = if (ok) "Saved. Copy it to the Picco's SD card if you're not sending over Wi-Fi." else "Saving failed."
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
                        "Makes the book's images smaller (480 × 800) and gray so the Picco opens it quickly. Text and layout don't change.",
                        color = EInkPureBlack, fontSize = 16.sp
                    )
                    EInkActionPill("Optimize for Picco", isSelected = true, onClick = {
                        if (!busy) PiccoJobs.optimizeEpub(context, Uri.parse(doc.uri), doc.title)
                    })
                } else {
                    OptionRow("Format") {
                        EInkActionPill("4 grays (.xtch)", opts.twoBit, { viewModel.updatePiccoOptions { it.copy(twoBit = true) } })
                        EInkActionPill("Black & white (.xtc)", !opts.twoBit, { viewModel.updatePiccoOptions { it.copy(twoBit = false) } })
                    }
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
                    OptionRow("Dithering") {
                        PiccoDither.entries.forEach { d ->
                            EInkActionPill(d.label, opts.dither == d, { viewModel.updatePiccoOptions { it.copy(dither = d) } })
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        EInkActionPill("Save to device…", false, { saveLauncher.launch(readyFile.name) })
                        EInkActionPill("Share…", false, { share(readyFile) })
                    }
                    saveNote?.let { Text(it, color = EInkMutedText, fontSize = 14.sp) }
                    Text("Send over Wi-Fi", color = EInkPureBlack, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "On the Picco, open its file transfer (Wi-Fi) screen and enter the address it shows. Files go to the ${if (isEpub) "Books" else "Comics"} folder.",
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
                    "Files work with the Picco's built-in reader and with CrossPoint; no firmware changes are needed. " +
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
    val plan = PiccoPlanner.plan(sizes, opts, panels?.let { p -> { _: Int, _: com.example.inksyncboox.picco.BaseKind -> p } })
    val chapters = if (opts.layout == PiccoLayout.WHOLE) 0 else plan.chapters.size
    return plan.pages.size to Xtc.fileSize(opts.twoBit, plan.pages.size, chapters)
}
