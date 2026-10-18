package com.example.inksyncboox.ui.main

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.inksyncboox.data.DocKind
import com.example.inksyncboox.eink.BooxEpdController
import com.example.inksyncboox.eink.OnyxPenController
import com.example.inksyncboox.epub.EInkTypographyPreset
import com.example.inksyncboox.epub.RealEpubParser
import com.example.inksyncboox.ink.BooxStylusTool
import com.example.inksyncboox.ink.NormalizedInkPoint
import com.example.inksyncboox.neoflow.BooxGridPreset
import com.example.inksyncboox.neoflow.BooxTraversalFlow
import com.example.inksyncboox.neoflow.NormalizedRect
import com.example.inksyncboox.pdf.BooxPdfEngine
import com.example.inksyncboox.study.BooxWorkspaceMode
import com.example.inksyncboox.study.HighlightsMarkdown
import com.example.inksyncboox.theme.BooxPenSwatches
import com.example.inksyncboox.theme.EInkBorderGray
import com.example.inksyncboox.theme.EInkMutedText
import com.example.inksyncboox.theme.EInkPaperWhite
import com.example.inksyncboox.theme.EInkPureBlack
import com.example.inksyncboox.theme.EInkSurfaceGray
import com.example.inksyncboox.theme.InkSyncBooxTheme
import com.example.inksyncboox.theme.KaleidoAmberHighlight
import com.example.inksyncboox.theme.KaleidoCobaltInk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val COMIC_EXTENSIONS = listOf(".cbz", ".zip", ".cbr", ".rar", ".cb7", ".7z")

private fun guessKind(name: String, mime: String): DocKind = when {
    name.endsWith(".epub") || mime.contains("epub") -> DocKind.EPUB
    name.endsWith(".pdf") || mime.contains("pdf") -> DocKind.PDF
    COMIC_EXTENSIONS.any { name.endsWith(it) } || mime.contains("cbz") || mime.contains("cbr") ||
        mime.contains("comicbook") || mime.contains("zip") || mime.contains("rar") || mime.contains("7z") -> DocKind.CBZ
    else -> DocKind.PDF
}

@Composable
fun MainScreen(viewModel: MainScreenViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.readerState.collectAsStateWithLifecycle()
    val pendingUri by viewModel.pendingOpenUri.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hostView = LocalView.current
    val scope = rememberCoroutineScope()

    val engine = remember(context) { BooxPdfEngine(context.applicationContext) }
    DisposableEffect(engine) { onDispose { engine.close() } }

    // Onyx's low-latency pen layer; inactive (and never created) on non-Boox devices.
    val penController = remember(hostView) {
        if (BooxEpdController.isBooxHardware) OnyxPenController(hostView) else null
    }
    DisposableEffect(penController) { onDispose { penController?.close() } }

    // Opening documents ------------------------------------------------------------------------
    LaunchedEffect(pendingUri) {
        val uri = pendingUri ?: return@LaunchedEffect
        viewModel.onOpenStarted()
        try {
            val name = engine.resolveDisplayName(uri)
            val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
            when (guessKind(name.lowercase(), mime)) {
                DocKind.EPUB -> {
                    val book = RealEpubParser.parseEpubFromUri(context, uri, name)
                        ?: error("This EPUB couldn't be read (it may be DRM-protected or damaged).")
                    viewModel.onEpubOpened(uri.toString(), book)
                }
                DocKind.CBZ -> {
                    val pages = engine.openComic(uri)
                    viewModel.onFixedDocumentOpened(uri.toString(), name, DocKind.CBZ, pages, engine.pageAspects)
                }
                DocKind.PDF -> {
                    val pages = engine.openPdf(uri)
                    viewModel.onFixedDocumentOpened(uri.toString(), name, DocKind.PDF, pages, engine.pageAspects)
                }
            }
        } catch (_: SecurityException) {
            viewModel.onOpenFailed("Access to this file has expired. Open it again with \"Open file\".")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            viewModel.onOpenFailed(e.localizedMessage ?: "Couldn't open this file.")
        } finally {
            // Cleared only after loading; clearing first would recompose and cancel this effect.
            viewModel.consumeOpenRequest(uri)
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep access across restarts so the library can reopen the file.
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            viewModel.requestOpen(uri)
        }
    }
    val openFile = {
        openDocumentLauncher.launch(
            arrayOf(
                "application/pdf",
                "application/epub+zip",
                "application/vnd.comicbook+zip",
                "application/vnd.comicbook-rar",
                "application/x-cbz",
                "application/x-cbr",
                "application/x-cb7",
                "application/zip",
                "application/vnd.rar",
                "application/x-rar-compressed",
                "application/x-7z-compressed",
                "application/octet-stream"
            )
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val snapshot = viewModel.readerState.value
        scope.launch {
            val ok = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(
                            HighlightsMarkdown.build(snapshot.document?.title.orEmpty(), snapshot.studyCards)
                                .toByteArray()
                        )
                    } != null
                }
            } catch (_: Throwable) {
                false
            }
            viewModel.setExportStatus(if (ok) "Exported ${snapshot.studyCards.size} highlights." else "Export failed.")
        }
    }

    // Full-screen e-ink flash whenever the view model asks for one.
    LaunchedEffect(state.regalFlashTick) {
        if (state.regalFlashTick > 0) BooxEpdController.requestFullRefresh(hostView)
    }

    var showOverlay by remember { mutableStateOf(false) }
    val closeOverlay = {
        if (showOverlay) {
            showOverlay = false
            viewModel.requestFullRefresh() // clear menu ghosting
        }
    }

    var showPicco by remember { mutableStateOf(false) }

    BackHandler(enabled = showPicco || showOverlay || state.workspaceMode != BooxWorkspaceMode.LIBRARY) {
        when {
            showPicco -> { showPicco = false; viewModel.requestFullRefresh() }
            showOverlay -> closeOverlay()
            state.workspaceMode == BooxWorkspaceMode.STUDY_BINDER -> viewModel.returnToReader()
            else -> viewModel.selectWorkspaceMode(BooxWorkspaceMode.LIBRARY)
        }
    }

    InkSyncBooxTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(EInkPaperWhite)
                .onSizeChanged { size ->
                    // Keep Android's edge back-gesture from stealing page-edge taps and pen strokes.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && size.width > 0 && state.isReaderMode) {
                        hostView.systemGestureExclusionRects = listOf(
                            Rect(0, (size.height * 0.15f).toInt(), 90, (size.height * 0.85f).toInt()),
                            Rect(size.width - 90, (size.height * 0.15f).toInt(), size.width, (size.height * 0.85f).toInt())
                        )
                    }
                }
        ) {
            when (state.workspaceMode) {
                BooxWorkspaceMode.LIBRARY -> LibraryScreen(
                    state = state,
                    onOpenFile = openFile,
                    onOpenRecent = { viewModel.requestOpen(Uri.parse(it.uri)) },
                    onRemoveRecent = { viewModel.removeRecent(it.uri) },
                    onBackToReader = viewModel::returnToReader,
                    onDismissError = viewModel::dismissError
                )

                BooxWorkspaceMode.STUDY_BINDER -> HighlightsScreen(
                    state = state,
                    onBack = viewModel::returnToReader,
                    onJumpToCard = viewModel::jumpToStudyCardOrigin,
                    onDeleteCard = viewModel::deleteStudyCard,
                    onExport = {
                        viewModel.setExportStatus(null)
                        exportLauncher.launch(HighlightsMarkdown.suggestedFileName(state.document?.title ?: "highlights"))
                    }
                )

                else -> Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(if (state.showCornellSplitScratchpad) 0.62f else 1f).fillMaxHeight()) {
                        if (state.workspaceMode == BooxWorkspaceMode.KINDLE_EPUB) {
                            EpubReaderView(
                                state = state,
                                onPagesComputed = viewModel::onEpubPagesComputed,
                                onStepPrev = { closeOverlay(); viewModel.stepBackwardTierOrPage() },
                                onStepNext = { closeOverlay(); viewModel.stepForwardTierOrPage() },
                                onToggleOverlay = { if (showOverlay) closeOverlay() else showOverlay = true },
                                onToggleParagraphHighlight = viewModel::toggleEpubParagraphHighlight
                            )
                        } else {
                            FixedPageViewport(
                                state = state,
                                showTierBadges = showOverlay || state.isStudioGridEditMode,
                                renderRegion = engine::renderRegion,
                                contentBounds = engine::contentBounds,
                                preload = engine::preload,
                                onStepPrev = { closeOverlay(); viewModel.stepBackwardTierOrPage() },
                                onStepNext = { closeOverlay(); viewModel.stepForwardTierOrPage() },
                                onToggleOverlay = { if (showOverlay) closeOverlay() else showOverlay = true },
                                onSelectBlock = viewModel::selectBlockStep,
                                onUpdateColSplit = viewModel::updateColSplitRatio,
                                onUpdateRowSplit = viewModel::updateRowSplitRatio,
                                onCommitStroke = viewModel::commitInkStroke,
                                onCommitQuadHighlight = viewModel::commitQuadHighlight,
                                onErasePoint = { x, y -> viewModel.eraseAtNormalizedPoint(x, y) },
                                penController = penController,
                                menuOpen = showOverlay
                            )
                        }
                    }
                    if (state.showCornellSplitScratchpad) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(EInkPureBlack))
                        NotesPane(
                            state = state,
                            onCycleTemplate = viewModel::cycleCornellTemplate,
                            onCommitStroke = viewModel::commitCornellScratchpadStroke,
                            onClear = viewModel::clearCornellScratchpad,
                            modifier = Modifier.weight(0.38f).fillMaxHeight()
                        )
                    }
                }
            }

            if (showOverlay && state.isReaderMode) {
                ReaderOverlay(
                    state = state,
                    viewModel = viewModel,
                    onDismiss = closeOverlay,
                    onOpenFile = { showOverlay = false; openFile() },
                    onOpenLibrary = { showOverlay = false; viewModel.selectWorkspaceMode(BooxWorkspaceMode.LIBRARY) },
                    onOpenHighlights = { showOverlay = false; viewModel.selectWorkspaceMode(BooxWorkspaceMode.STUDY_BINDER) },
                    onOpenPicco = { showOverlay = false; showPicco = true }
                )
                if (state.workspaceMode != BooxWorkspaceMode.KINDLE_EPUB) {
                    PenToolbar(
                        state = state,
                        onSelectTool = viewModel::selectStylusTool,
                        onSelectSwatch = viewModel::selectInkSwatch,
                        onTogglePalmShield = viewModel::togglePalmShieldStylusOnly,
                        onUndo = viewModel::undoLastAnnotation,
                        onClearPage = viewModel::clearPageAnnotations,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                    )
                }
            }

            if (showPicco && state.document != null) {
                PiccoExportScreen(
                    state = state,
                    viewModel = viewModel,
                    engine = engine,
                    onClose = { showPicco = false; viewModel.requestFullRefresh() }
                )
            }

            if (state.isLoading && state.workspaceMode != BooxWorkspaceMode.LIBRARY) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(8.dp),
                    color = EInkPaperWhite,
                    border = BorderStroke(1.5.dp, EInkPureBlack)
                ) {
                    Text("Opening…", color = EInkPureBlack, fontSize = 18.sp, modifier = Modifier.padding(24.dp))
                }
            }
        }
    }
}

// =============================================================================================
// Fixed-page viewport (PDF and CBZ)
// =============================================================================================

/** A rendered view, with the page region it shows and the page's crop (both page coordinates). */
private data class RenderedRegion(
    val key: String,
    val bitmap: Bitmap,
    val region: NormalizedRect,
    val crop: NormalizedRect
)

private val FULL_PAGE = NormalizedRect(0f, 0f, 1f, 1f)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FixedPageViewport(
    state: BooxReaderState,
    showTierBadges: Boolean,
    renderRegion: suspend (Int, NormalizedRect, Int, Int) -> Bitmap?,
    contentBounds: suspend (Int) -> NormalizedRect,
    preload: suspend (List<Pair<Int, NormalizedRect>>, Int, Int) -> Unit,
    onStepPrev: () -> Unit,
    onStepNext: () -> Unit,
    onToggleOverlay: () -> Unit,
    onSelectBlock: (Int, Boolean) -> Unit,
    onUpdateColSplit: (Int, Float) -> Unit,
    onUpdateRowSplit: (Int, Float) -> Unit,
    onCommitStroke: (List<NormalizedInkPoint>) -> Unit,
    onCommitQuadHighlight: (NormalizedRect) -> Unit,
    onErasePoint: (Float, Float) -> Unit,
    penController: OnyxPenController?,
    menuOpen: Boolean
) {
    val activeStrokePoints = remember { mutableStateListOf<NormalizedInkPoint>() }
    var quadDragStart by remember { mutableStateOf<Offset?>(null) }
    var quadDragCurrent by remember { mutableStateOf<Offset?>(null) }
    // Ignore finger touches briefly after the pen lifts (palm rejection).
    var lastStylusEventMillis by remember { mutableLongStateOf(0L) }
    var rendered by remember { mutableStateOf<RenderedRegion?>(null) }

    val settings = state.settings
    val blocks = state.computedBlocks
    val zoomed = state.isZoomedOn(state.pageIndex)
    val isComic = state.workspaceMode == BooxWorkspaceMode.PANELS_COMICS
    val background = if (isComic && settings.darkGutter) EInkPureBlack else EInkPaperWhite

    // Where this viewport sits in the window; Onyx's pen layer works in host-view pixels.
    var originInRoot by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .clipToBounds()
            .onGloballyPositioned { originInRoot = it.positionInRoot() }
    ) {
        val canvasW = constraints.maxWidth.coerceAtLeast(1)
        val canvasH = constraints.maxHeight.coerceAtLeast(1)
        val density = LocalDensity.current
        val docKey = state.document?.key.orEmpty()
        // Everything that changes which part of the page is shown.
        val renderKey = listOf(
            docKey, state.pageIndex, state.activeStepIndex, zoomed, state.workspaceMode,
            state.gridPreset, state.traversalFlow, state.colSplitRatios, state.rowSplitRatios,
            state.customOverrides, settings.autoCrop, settings.splitSpreads, settings.rightToLeft,
            canvasW, canvasH
        ).joinToString("|")

        LaunchedEffect(renderKey) {
            // Crop (if on) first, then the tier / spread half within the cropped content.
            suspend fun cropFor(page: Int) = if (settings.autoCrop) contentBounds(page) else FULL_PAGE
            suspend fun regionFor(page: Int, step: Int) = state.contentRegionFor(page, step).mapInto(cropFor(page))

            val crop = cropFor(state.pageIndex)
            val region = state.contentRegionFor(state.pageIndex, state.activeStepIndex).mapInto(crop)
            val bmp = renderRegion(state.pageIndex, region, canvasW, canvasH)
            rendered = bmp?.let { RenderedRegion(renderKey, it, region, crop) }

            // Prepare the next two views in the background so the next turn is instant.
            val upcoming = state.upcomingViews(2).map { (page, step) -> page to regionFor(page, step) }
            preload(upcoming, canvasW, canvasH)
        }

        // Only draw a bitmap rendered for exactly this page/region, so a stale image is never
        // stretched into the new viewport (which would cost an extra e-ink refresh).
        val current = rendered?.takeIf { it.key == renderKey }
        val bitmap = current?.bitmap
        val viewportRect = current?.region ?: FULL_PAGE
        val cropRect = current?.crop ?: FULL_PAGE

        // Aspect-fit the region into the screen; never stretch.
        val regionAspect = bitmap?.let { it.width.toFloat() / it.height } ?: (canvasW.toFloat() / canvasH)
        val scale = min(canvasW / regionAspect, canvasH.toFloat())
        val dstW = scale * regionAspect
        val dstH = scale
        val dstLeft = (canvasW - dstW) / 2f
        val dstTop = (canvasH - dstH) / 2f

        fun screenToPage(x: Float, y: Float): Pair<Float, Float> {
            val u = ((x - dstLeft) / dstW).coerceIn(0f, 1f)
            val v = ((y - dstTop) / dstH).coerceIn(0f, 1f)
            return (viewportRect.left + u * viewportRect.width) to (viewportRect.top + v * viewportRect.height)
        }

        fun pageToScreen(px: Float, py: Float): Offset = Offset(
            dstLeft + (px - viewportRect.left) / viewportRect.width * dstW,
            dstTop + (py - viewportRect.top) / viewportRect.height * dstH
        )

        // ---- Onyx low-latency pen layer (Boox only) -------------------------------------------
        val penTool = state.activeStylusTool == BooxStylusTool.FOUNTAIN_PEN ||
            state.activeStylusTool == BooxStylusTool.KALEIDO_HIGHLIGHTER
        val penLayerActive = penController?.isAvailable == true && penTool && !menuOpen &&
            !state.isStudioGridEditMode && bitmap != null
        val zoomFactor = 1f / viewportRect.width
        val highlighterInk = state.activeStylusTool == BooxStylusTool.KALEIDO_HIGHLIGHTER || state.activeSwatch.isHighlighter
        val inkWidthPx = (if (highlighterInk) 18f * 2.2f else state.strokeWidthPt * 2.4f) * zoomFactor
        val inkColor = state.activeSwatch.color.toArgb()
        val limitRect = Rect(
            (originInRoot.x + dstLeft).roundToInt(),
            (originInRoot.y + dstTop).roundToInt(),
            (originInRoot.x + dstLeft + dstW).roundToInt(),
            (originInRoot.y + dstTop + dstH).roundToInt()
        )
        val penActiveNow by rememberUpdatedState(penLayerActive)
        // Converts host-view pixels to page coordinates using the latest layout.
        val rootToPage by rememberUpdatedState { x: Float, y: Float -> screenToPage(x - originInRoot.x, y - originInRoot.y) }
        val commitStroke by rememberUpdatedState(onCommitStroke)
        val erasePoint by rememberUpdatedState(onErasePoint)

        if (penController != null) {
            SideEffect {
                penController.onStroke = { points ->
                    commitStroke(points.map { p ->
                        val (px, py) = rootToPage(p.x, p.y)
                        NormalizedInkPoint(px, py, p.pressure)
                    })
                }
                penController.onErase = { points ->
                    points.forEach { p ->
                        val (px, py) = rootToPage(p.x, p.y)
                        erasePoint(px, py)
                    }
                }
            }
            LaunchedEffect(penLayerActive, limitRect, highlighterInk, inkWidthPx, inkColor) {
                if (penLayerActive) {
                    // Let the new page reach the panel before raw drawing holds the screen.
                    delay(400)
                }
                penController.setActive(penLayerActive, limitRect, highlighterInk, inkWidthPx, inkColor)
            }
            DisposableEffect(penController) {
                onDispose { penController.setActive(false, null, false, 0f, 0) }
            }
        }

        fun dispatchTap(x: Float, y: Float) {
            when (TapZones.resolve(settings.tapLayout, x, y, canvasW.toFloat(), canvasH.toFloat(), settings.rightToLeft)) {
                TapAction.PREVIOUS -> onStepPrev()
                TapAction.NEXT -> onStepNext()
                TapAction.MENU -> onToggleOverlay()
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInteropFilter { event ->
                    val toolType = event.getToolType(0)
                    val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
                    val now = System.currentTimeMillis()
                    if (isStylus) lastStylusEventMillis = now
                    // The Onyx pen layer already handles the pen; don't draw the stroke twice.
                    if (isStylus && penActiveNow) return@pointerInteropFilter true
                    if (!isStylus && (state.palmShieldStylusOnly || now - lastStylusEventMillis < 850L)) {
                        return@pointerInteropFilter true
                    }

                    val tool = when {
                        state.isStudioGridEditMode -> BooxStylusTool.NAVIGATE
                        toolType == MotionEvent.TOOL_TYPE_ERASER -> BooxStylusTool.ERASER
                        state.activeStylusTool != BooxStylusTool.NAVIGATE -> state.activeStylusTool
                        isStylus -> BooxStylusTool.FOUNTAIN_PEN
                        else -> BooxStylusTool.NAVIGATE
                    }
                    val pressure = event.pressure.coerceIn(0.05f, 1f)

                    when (tool) {
                        BooxStylusTool.FOUNTAIN_PEN, BooxStylusTool.KALEIDO_HIGHLIGHTER -> when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                activeStrokePoints.clear()
                                val (px, py) = screenToPage(event.x, event.y)
                                activeStrokePoints.add(NormalizedInkPoint(px, py, pressure))
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                for (h in 0 until event.historySize) {
                                    val (hx, hy) = screenToPage(event.getHistoricalX(h), event.getHistoricalY(h))
                                    activeStrokePoints.add(NormalizedInkPoint(hx, hy, event.getHistoricalPressure(h).coerceIn(0.05f, 1f)))
                                }
                                val (px, py) = screenToPage(event.x, event.y)
                                activeStrokePoints.add(NormalizedInkPoint(px, py, pressure))
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                val committed = activeStrokePoints.toList()
                                activeStrokePoints.clear()
                                val xs = committed.map { it.x }
                                val ys = committed.map { it.y }
                                val isTap = committed.size < 3 ||
                                    (xs.max() - xs.min() < 0.01f && ys.max() - ys.min() < 0.01f)
                                if (isTap && state.activeStylusTool == BooxStylusTool.NAVIGATE) {
                                    // A pen tap in reading mode turns the page instead of leaving a dot.
                                    if (event.actionMasked == MotionEvent.ACTION_UP) dispatchTap(event.x, event.y)
                                } else if (!isTap) {
                                    onCommitStroke(committed)
                                }
                                true
                            }
                            else -> false
                        }

                        BooxStylusTool.QUAD_HIGHLIGHT -> when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                quadDragStart = Offset(event.x, event.y)
                                quadDragCurrent = quadDragStart
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                quadDragCurrent = Offset(event.x, event.y)
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                val start = quadDragStart
                                val end = quadDragCurrent
                                quadDragStart = null
                                quadDragCurrent = null
                                if (start != null && end != null && abs(end.x - start.x) > 8f && abs(end.y - start.y) > 8f) {
                                    val (x1, y1) = screenToPage(min(start.x, end.x), min(start.y, end.y))
                                    val (x2, y2) = screenToPage(max(start.x, end.x), max(start.y, end.y))
                                    onCommitQuadHighlight(NormalizedRect(x1, y1, x2, y2))
                                }
                                true
                            }
                            else -> false
                        }

                        BooxStylusTool.ERASER -> {
                            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                                val (px, py) = screenToPage(event.x, event.y)
                                onErasePoint(px, py)
                            }
                            true
                        }

                        BooxStylusTool.NAVIGATE -> {
                            if (event.actionMasked == MotionEvent.ACTION_UP && !state.isStudioGridEditMode) {
                                dispatchTap(event.x, event.y)
                            }
                            true
                        }
                    }
                }
        ) {
            if (bitmap != null) {
                val colorFilter = pageColorFilter(settings.contrastBoost, settings.grayscale)
                drawImage(
                    image = bitmap.asImageBitmap(),
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bitmap.width, bitmap.height),
                    dstOffset = IntOffset(dstLeft.roundToInt(), dstTop.roundToInt()),
                    dstSize = IntSize(dstW.roundToInt(), dstH.roundToInt()),
                    colorFilter = colorFilter
                )

                state.currentPageQuadHighlights.forEach { quad ->
                    val tl = pageToScreen(quad.bounds.left, quad.bounds.top)
                    val br = pageToScreen(quad.bounds.right, quad.bounds.bottom)
                    drawRect(
                        color = Color(quad.colorHex.toULong()).copy(alpha = 0.55f),
                        topLeft = tl,
                        size = Size(br.x - tl.x, br.y - tl.y),
                        blendMode = BlendMode.Multiply
                    )
                }

                fun drawInk(points: List<NormalizedInkPoint>, color: Color, widthPx: Float, highlighter: Boolean) {
                    if (points.size < 2) return
                    val path = Path()
                    val first = pageToScreen(points.first().x, points.first().y)
                    path.moveTo(first.x, first.y)
                    for (i in 1 until points.size) {
                        val p = pageToScreen(points[i].x, points[i].y)
                        path.lineTo(p.x, p.y)
                    }
                    drawPath(
                        path = path,
                        color = if (highlighter) color.copy(alpha = 0.52f) else color,
                        style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        blendMode = if (highlighter) BlendMode.Multiply else BlendMode.SrcOver
                    )
                }

                // Stroke widths are in page units so they scale with zoom (zoomFactor above).
                state.currentPageStrokes.forEach { s ->
                    val avgPressure = s.points.map { it.pressure }.average().toFloat().coerceIn(0.2f, 1f)
                    val w = if (s.isHighlighter) s.baseWidthPt * 2.2f else s.baseWidthPt * (0.65f + avgPressure * 1.15f) * 2f
                    drawInk(s.points, s.composeColor, w * zoomFactor, s.isHighlighter)
                }
                val liveHl = state.activeStylusTool == BooxStylusTool.KALEIDO_HIGHLIGHTER || state.activeSwatch.isHighlighter
                drawInk(
                    activeStrokePoints.toList(),
                    state.activeSwatch.color,
                    (if (liveHl) 18f * 2.2f else state.strokeWidthPt * 2.4f) * zoomFactor,
                    liveHl
                )

                val qs = quadDragStart
                val qe = quadDragCurrent
                if (qs != null && qe != null) {
                    drawRect(
                        color = KaleidoAmberHighlight.copy(alpha = 0.5f),
                        topLeft = Offset(min(qs.x, qe.x), min(qs.y, qe.y)),
                        size = Size(abs(qe.x - qs.x), abs(qe.y - qs.y)),
                        blendMode = BlendMode.Multiply
                    )
                    drawRect(
                        color = EInkPureBlack,
                        topLeft = Offset(min(qs.x, qe.x), min(qs.y, qe.y)),
                        size = Size(abs(qe.x - qs.x), abs(qe.y - qs.y)),
                        style = Stroke(width = 2f)
                    )
                }

                if (!zoomed && state.gridPreset != BooxGridPreset.GRID_1X1) {
                    blocks.forEach { block ->
                        val blockRect = block.baseRect.mapInto(cropRect)
                        val tl = pageToScreen(blockRect.left, blockRect.top)
                        val br = pageToScreen(blockRect.right, blockRect.bottom)
                        val isSelected = block.stepIndex == state.activeStepIndex
                        val size = Size(br.x - tl.x, br.y - tl.y)
                        if (isComic && settings.panelSpotlight && !isSelected) {
                            // A light wash renders cleanly on e-ink, unlike a dark translucent dither.
                            drawRect(color = Color.White.copy(alpha = 0.6f), topLeft = tl, size = size)
                        }
                        if (showTierBadges || (isComic && settings.panelSpotlight && isSelected)) {
                            drawRect(
                                color = if (isSelected) EInkPureBlack else EInkBorderGray,
                                topLeft = tl,
                                size = size,
                                style = Stroke(width = if (isSelected) 4f else 1.5f)
                            )
                        }
                    }
                }
            }
        }

        if (showTierBadges && !zoomed && state.gridPreset != BooxGridPreset.GRID_1X1) {
            blocks.forEach { block ->
                val isCurrent = block.stepIndex == state.activeStepIndex
                val blockRect = block.baseRect.mapInto(cropRect)
                val tl = pageToScreen(blockRect.left, blockRect.top)
                Surface(
                    modifier = Modifier
                        .offset(x = with(density) { (tl.x + 12f).toDp() }, y = with(density) { (tl.y + 12f).toDp() })
                        .clickable { onSelectBlock(block.stepIndex, true) },
                    shape = RoundedCornerShape(50),
                    color = if (isCurrent) EInkPureBlack else EInkPaperWhite,
                    border = BorderStroke(1.dp, EInkPureBlack)
                ) {
                    Text(
                        text = "${block.displayStepNumber}",
                        color = if (isCurrent) EInkPaperWhite else EInkPureBlack,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }

        if (state.isStudioGridEditMode && !zoomed) {
            state.colSplitRatios.forEachIndexed { idx, ratio ->
                SplitHandle(
                    vertical = true,
                    label = "${(ratio * 100).roundToInt()}%",
                    modifier = Modifier
                        .offset(x = with(density) { (dstLeft + ratio * dstW).toDp() } - 22.dp)
                        .width(44.dp)
                        .fillMaxHeight()
                        .pointerInput(idx, dstW) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                onUpdateColSplit(idx, state.colSplitRatios.getOrElse(idx) { 0.5f } + drag.x / dstW)
                            }
                        }
                )
            }
            state.rowSplitRatios.forEachIndexed { idx, ratio ->
                SplitHandle(
                    vertical = false,
                    label = "${(ratio * 100).roundToInt()}%",
                    modifier = Modifier
                        .offset(y = with(density) { (dstTop + ratio * dstH).toDp() } - 22.dp)
                        .height(44.dp)
                        .fillMaxWidth()
                        .pointerInput(idx, dstH) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                onUpdateRowSplit(idx, state.rowSplitRatios.getOrElse(idx) { 0.5f } + drag.y / dstH)
                            }
                        }
                )
            }
        }
    }
}

@Composable
private fun SplitHandle(vertical: Boolean, label: String, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = if (vertical) Modifier.width(4.dp).fillMaxHeight().background(KaleidoCobaltInk)
            else Modifier.height(4.dp).fillMaxWidth().background(KaleidoCobaltInk)
        )
        Box(
            modifier = Modifier
                .background(EInkPureBlack, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(label, color = EInkPaperWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// =============================================================================================
// Pen toolbar
// =============================================================================================

@Composable
private fun PenToolbar(
    state: BooxReaderState,
    onSelectTool: (BooxStylusTool) -> Unit,
    onSelectSwatch: (com.example.inksyncboox.theme.KaleidoInkSwatch) -> Unit,
    onTogglePalmShield: () -> Unit,
    onUndo: () -> Unit,
    onClearPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = EInkPaperWhite,
        border = BorderStroke(1.5.dp, EInkPureBlack)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                BooxStylusTool.NAVIGATE to "Read",
                BooxStylusTool.FOUNTAIN_PEN to "Pen",
                BooxStylusTool.KALEIDO_HIGHLIGHTER to "Marker",
                BooxStylusTool.QUAD_HIGHLIGHT to "Area",
                BooxStylusTool.ERASER to "Erase"
            ).forEach { (tool, label) ->
                val selected = state.activeStylusTool == tool
                Surface(
                    modifier = Modifier.width(64.dp).clickable { onSelectTool(tool) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) EInkPureBlack else EInkPaperWhite
                ) {
                    Text(
                        label,
                        color = if (selected) EInkPaperWhite else EInkPureBlack,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.width(40.dp), color = EInkBorderGray)
            BooxPenSwatches.forEach { swatch ->
                val selected = state.activeSwatch.id == swatch.id
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(swatch.color, CircleShape)
                        .border(if (selected) 3.dp else 1.dp, EInkPureBlack, CircleShape)
                        .clickable { onSelectSwatch(swatch) }
                )
            }
            HorizontalDivider(modifier = Modifier.width(40.dp), color = EInkBorderGray)
            SmallToggle(if (state.palmShieldStylusOnly) "Pen only" else "Touch on", state.palmShieldStylusOnly, onTogglePalmShield)
            SmallToggle("Undo", false, onUndo)
            SmallToggle("Clear page", false, onClearPage)
        }
    }
}

@Composable
private fun SmallToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) EInkPaperWhite else EInkPureBlack,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(if (selected) EInkPureBlack else EInkPaperWhite, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    )
}

// =============================================================================================
// Reader menu overlay (tap the centre of the page)
// =============================================================================================

private enum class OverlayTab(val title: String) {
    GO_TO("Go to"),
    DISPLAY("Display"),
    TIERS("Panels & tiers"),
    SCREEN("Screen")
}

@Composable
private fun ReaderOverlay(
    state: BooxReaderState,
    viewModel: MainScreenViewModel,
    onDismiss: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenPicco: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenHighlights: () -> Unit
) {
    val isEpub = state.workspaceMode == BooxWorkspaceMode.KINDLE_EPUB
    var tab by remember { mutableStateOf(OverlayTab.GO_TO) }
    val tabs = if (isEpub) OverlayTab.entries - OverlayTab.TIERS else OverlayTab.entries.toList()

    Box(modifier = Modifier.fillMaxSize()) {
        // Tapping outside the bars closes the menu.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        )

        // Top bar
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(EInkPaperWhite)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                EInkActionPill("← Library", isSelected = false, onClick = onOpenLibrary)
                Text(
                    text = state.document?.title.orEmpty(),
                    color = EInkPureBlack,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                EInkActionPill("Highlights (${state.studyCards.size})", isSelected = false, onClick = onOpenHighlights)
                EInkActionPill(
                    if (state.showCornellSplitScratchpad) "Hide notes" else "Notes",
                    isSelected = state.showCornellSplitScratchpad,
                    onClick = viewModel::toggleCornellSplitScratchpad
                )
                EInkActionPill("Picco", isSelected = false, onClick = onOpenPicco)
                EInkActionPill("Open", isSelected = false, onClick = onOpenFile)
            }
            HorizontalDivider(thickness = 1.5.dp, color = EInkPureBlack)
        }

        // Bottom sheet
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(EInkPaperWhite)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
        ) {
            HorizontalDivider(thickness = 1.5.dp, color = EInkPureBlack)
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                when (tab) {
                    OverlayTab.GO_TO -> if (isEpub) EpubGoToPanel(state, viewModel) else PageGoToPanel(state, viewModel)
                    OverlayTab.DISPLAY -> DisplayPanel(state, viewModel, isEpub)
                    OverlayTab.TIERS -> TiersPanel(state, viewModel)
                    OverlayTab.SCREEN -> ScreenPanel(state, viewModel)
                }
            }
            HorizontalDivider(thickness = 1.dp, color = EInkBorderGray)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEach { t -> EInkActionPill(t.title, isSelected = tab == t, onClick = { tab = t }) }
            }
        }
    }
}

@Composable
private fun PillRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun PanelLabel(text: String) {
    Text(text, color = EInkMutedText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun PageGoToPanel(state: BooxReaderState, viewModel: MainScreenViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelLabel("Page ${state.pageIndex + 1} of ${state.pageCount}")
        PillRow {
            EInkActionPill("First", false, { viewModel.goToPage(0) })
            EInkActionPill("−10", false, { viewModel.goToPage(state.pageIndex - 10) })
            EInkActionPill("−1", false, { viewModel.goToPage(state.pageIndex - 1) })
            EInkActionPill("+1", false, { viewModel.goToPage(state.pageIndex + 1) })
            EInkActionPill("+10", false, { viewModel.goToPage(state.pageIndex + 10) })
            EInkActionPill("Last", false, { viewModel.goToPage(state.pageCount - 1) })
        }
    }
}

@Composable
private fun EpubGoToPanel(state: BooxReaderState, viewModel: MainScreenViewModel) {
    val book = state.activeEpubBook ?: return
    Column {
        PanelLabel("Contents")
        Spacer(Modifier.height(6.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
            itemsIndexed(book.chapters) { idx, ch ->
                val current = idx == state.epubChapterIndex
                Text(
                    text = ch.title,
                    color = EInkPureBlack,
                    fontSize = 16.sp,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (current) EInkSurfaceGray else EInkPaperWhite)
                        .clickable { viewModel.goToEpubChapter(idx) }
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun DisplayPanel(state: BooxReaderState, viewModel: MainScreenViewModel, isEpub: Boolean) {
    val s = state.settings
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (isEpub) {
            PanelLabel("Text size")
            PillRow {
                EInkTypographyPreset.entries.forEach { p ->
                    EInkActionPill(p.label, s.typography == p, { viewModel.selectEpubTypographyPreset(p) })
                }
            }
            PillRow {
                EInkActionPill("One column", !s.epubTwoColumn, { if (s.epubTwoColumn) viewModel.toggleEpubTwoColumnMode() })
                EInkActionPill("Two columns", s.epubTwoColumn, { if (!s.epubTwoColumn) viewModel.toggleEpubTwoColumnMode() })
            }
        } else {
            PillRow {
                EInkActionPill("Contrast boost", s.contrastBoost, viewModel::toggleContrastBoost)
                EInkActionPill("Auto-crop margins", s.autoCrop, viewModel::toggleAutoCrop)
                EInkActionPill("Grayscale", s.grayscale, viewModel::toggleGrayscale)
                EInkActionPill(
                    if (s.rightToLeft) "Right to left (manga)" else "Left to right",
                    s.rightToLeft,
                    viewModel::toggleRightToLeft
                )
                if (state.workspaceMode == BooxWorkspaceMode.PANELS_COMICS) {
                    EInkActionPill("Split two-page spreads", s.splitSpreads, viewModel::toggleSplitSpreads)
                    EInkActionPill("Black background", s.darkGutter, viewModel::toggleDarkGutter)
                }
            }
            if (state.document?.kind == DocKind.PDF) {
                PillRow {
                    PanelLabel("Show as")
                    EInkActionPill("Document", state.workspaceMode == BooxWorkspaceMode.PDF_NEOFLOW, {
                        viewModel.selectWorkspaceMode(BooxWorkspaceMode.PDF_NEOFLOW)
                    })
                    EInkActionPill("Comic", state.workspaceMode == BooxWorkspaceMode.PANELS_COMICS, {
                        viewModel.selectWorkspaceMode(BooxWorkspaceMode.PANELS_COMICS)
                    })
                }
            }
        }
        TapLayoutRow(state, viewModel)
    }
}

@Composable
private fun TapLayoutRow(state: BooxReaderState, viewModel: MainScreenViewModel) {
    PillRow {
        PanelLabel("Tap to turn")
        TapLayout.entries.forEach { layout ->
            EInkActionPill(layout.label, state.settings.tapLayout == layout, { viewModel.setTapLayout(layout) })
        }
    }
}

@Composable
private fun TiersPanel(state: BooxReaderState, viewModel: MainScreenViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelLabel("Split each page into tiers and step through them with page turns")
        PillRow {
            listOf(
                BooxGridPreset.GRID_1X1, BooxGridPreset.GRID_1X2, BooxGridPreset.GRID_2X1,
                BooxGridPreset.GRID_2X2, BooxGridPreset.GRID_2X3, BooxGridPreset.GRID_3X3
            ).forEach { p ->
                EInkActionPill(if (p == BooxGridPreset.GRID_1X1) "Whole page" else p.badge, state.gridPreset == p, {
                    viewModel.selectGridPreset(p)
                })
            }
        }
        if (state.gridPreset != BooxGridPreset.GRID_1X1) {
            PillRow {
                BooxTraversalFlow.entries.forEach { f ->
                    EInkActionPill(f.label, state.traversalFlow == f, { viewModel.selectTraversalFlow(f) })
                }
            }
            PillRow {
                EInkActionPill(if (state.isZoomedToTier) "Zoomed to tier" else "Full page", state.isZoomedToTier, viewModel::toggleZoomToActiveTier)
                EInkActionPill(if (state.isStudioGridEditMode) "Done adjusting" else "Adjust splits", state.isStudioGridEditMode, viewModel::toggleStudioGridEditMode)
                if (state.isStudioGridEditMode) EInkActionPill("Reset", false, viewModel::resetGridSplitsAndOverrides)
                if (state.workspaceMode == BooxWorkspaceMode.PANELS_COMICS) {
                    EInkActionPill("Highlight current panel", state.settings.panelSpotlight, viewModel::togglePanelSpotlight)
                }
            }
        }
    }
}

@Composable
private fun ScreenPanel(state: BooxReaderState, viewModel: MainScreenViewModel) {
    val every = state.settings.fullRefreshEveryNTurns
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelLabel("Full refresh to clear ghosting")
        PillRow {
            listOf(1, 3, 5, 10, 0).forEach { n ->
                EInkActionPill(
                    when (n) { 0 -> "Never"; 1 -> "Every page"; else -> "Every $n pages" },
                    every == n,
                    { viewModel.setFullRefreshInterval(n) }
                )
            }
            EInkActionPill("Refresh now", true, viewModel::requestFullRefresh)
        }
        Text(
            BooxEpdController.statusSummary + ". Refresh speed (HD, Balanced, Fast) is set per app in Boox E-Ink Center.",
            color = EInkMutedText,
            fontSize = 13.sp
        )
    }
}

/**
 * Optional contrast boost and grayscale for page images. Grayscale lets the Note Air 4C show
 * black-and-white pages at the panel's full 300 PPI instead of through the 150 PPI color layer.
 */
private fun pageColorFilter(contrastBoost: Boolean, grayscale: Boolean): ColorFilter? {
    if (!contrastBoost && !grayscale) return null
    val gain = if (contrastBoost) 1.12f else 1f
    val offset = if (contrastBoost) -6f else 0f
    val m = if (grayscale) {
        val r = 0.299f * gain
        val g = 0.587f * gain
        val b = 0.114f * gain
        floatArrayOf(
            r, g, b, 0f, offset,
            r, g, b, 0f, offset,
            r, g, b, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    } else {
        floatArrayOf(
            gain, 0f, 0f, 0f, offset,
            0f, gain, 0f, 0f, offset,
            0f, 0f, gain, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    }
    return ColorFilter.colorMatrix(ColorMatrix(m))
}
