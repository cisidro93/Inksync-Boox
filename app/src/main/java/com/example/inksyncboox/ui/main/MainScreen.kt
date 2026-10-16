package com.example.inksyncboox.ui.main

import android.graphics.Bitmap
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.inksyncboox.data.DefaultDataRepository
import com.example.inksyncboox.eink.BooxEpdController
import com.example.inksyncboox.ink.BooxStylusTool
import com.example.inksyncboox.ink.NormalizedInkPoint
import com.example.inksyncboox.neoflow.BooxGridPreset
import com.example.inksyncboox.neoflow.BooxTraversalFlow
import com.example.inksyncboox.neoflow.NormalizedRect
import com.example.inksyncboox.pdf.BooxPdfEngine
import com.example.inksyncboox.theme.BooxPenSwatches
import com.example.inksyncboox.theme.EInkBorderGray
import com.example.inksyncboox.theme.EInkMutedText
import com.example.inksyncboox.theme.EInkPaperWhite
import com.example.inksyncboox.theme.EInkPureBlack
import com.example.inksyncboox.theme.EInkSurfaceGray
import com.example.inksyncboox.theme.InkSyncBooxTheme
import com.example.inksyncboox.theme.KaleidoAmberHighlight
import com.example.inksyncboox.theme.KaleidoCobaltInk
import com.example.inksyncboox.theme.KaleidoCyanHighlight
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) },
) {
    val readerState by viewModel.readerState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hostView = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    val pdfEngine = remember(context) { BooxPdfEngine(context) }
    var renderedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(pdfEngine) {
        onDispose { pdfEngine.close() }
    }

    // Initialize built-in Note Air 4C showcase PDF on first launch
    LaunchedEffect(pdfEngine) {
        val count = pdfEngine.ensureSampleOrOpenPdf(null)
        viewModel.updateDocumentMetadata(pdfEngine.activeDocumentTitle, count)
        renderedBitmap = pdfEngine.renderPage(0)
    }

    // Re-render page when pageIndex changes
    LaunchedEffect(readerState.pageIndex, readerState.pageCount) {
        renderedBitmap = pdfEngine.renderPage(readerState.pageIndex)
    }

    // Apply Boox E-Ink hardware refresh mode & trigger Regal anti-ghosting flash on page/tier step
    LaunchedEffect(readerState.refreshMode) {
        BooxEpdController.applyRefreshMode(hostView, readerState.refreshMode)
    }
    LaunchedEffect(readerState.regalFlashTick) {
        if (readerState.regalFlashTick > 0) {
            BooxEpdController.triggerRegalFullRefresh(hostView)
        }
    }

    // System SAF Document Picker to open any personal PDF on the Boox Note Air 4C
    val openPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val count = pdfEngine.ensureSampleOrOpenPdf(uri)
                viewModel.updateDocumentMetadata(pdfEngine.activeDocumentTitle, count)
                renderedBitmap = pdfEngine.renderPage(0)
            }
        }
    }

    InkSyncBooxTheme(highContrastBAndW = readerState.highContrastBAndW) {
        BooxWorkspaceContent(
            state = readerState,
            pageBitmap = renderedBitmap,
            onOpenPdfClick = { openPdfLauncher.launch(arrayOf("application/pdf")) },
            onSelectPreset = viewModel::selectGridPreset,
            onSelectFlow = viewModel::selectTraversalFlow,
            onToggleStudioEdit = viewModel::toggleStudioGridEditMode,
            onToggleZoomTier = viewModel::toggleZoomToActiveTier,
            onStepPrev = viewModel::stepBackwardTierOrPage,
            onStepNext = viewModel::stepForwardTierOrPage,
            onSelectBlock = viewModel::selectBlockStep,
            onUpdateColSplit = viewModel::updateColSplitRatio,
            onUpdateRowSplit = viewModel::updateRowSplitRatio,
            onResetGridSplits = viewModel::resetGridSplitsAndOverrides,
            onSelectTool = viewModel::selectStylusTool,
            onSelectSwatch = viewModel::selectInkSwatch,
            onCommitStroke = viewModel::commitInkStroke,
            onCommitQuadHighlight = viewModel::commitQuadHighlight,
            onErasePoint = viewModel::eraseAtNormalizedPoint,
            onUndo = viewModel::undoLastAnnotation,
            onClearAnnotations = viewModel::clearPageAnnotations,
            onCycleRefreshMode = viewModel::cycleRefreshMode,
            onManualRegalFlash = viewModel::triggerManualRegalRefresh,
            onToggleHighContrast = viewModel::toggleHighContrastBAndW,
            modifier = modifier
        )
    }
}

@Composable
internal fun BooxWorkspaceContent(
    state: BooxReaderState,
    pageBitmap: Bitmap?,
    onOpenPdfClick: () -> Unit,
    onSelectPreset: (BooxGridPreset) -> Unit,
    onSelectFlow: (BooxTraversalFlow) -> Unit,
    onToggleStudioEdit: () -> Unit,
    onToggleZoomTier: () -> Unit,
    onStepPrev: () -> Unit,
    onStepNext: () -> Unit,
    onSelectBlock: (Int, Boolean) -> Unit,
    onUpdateColSplit: (Int, Float) -> Unit,
    onUpdateRowSplit: (Int, Float) -> Unit,
    onResetGridSplits: () -> Unit,
    onSelectTool: (BooxStylusTool) -> Unit,
    onSelectSwatch: (com.example.inksyncboox.theme.KaleidoInkSwatch) -> Unit,
    onCommitStroke: (List<NormalizedInkPoint>, String) -> Unit,
    onCommitQuadHighlight: (NormalizedRect, String) -> Unit,
    onErasePoint: (Float, Float) -> Unit,
    onUndo: () -> Unit,
    onClearAnnotations: () -> Unit,
    onCycleRefreshMode: () -> Unit,
    onManualRegalFlash: () -> Unit,
    onToggleHighContrast: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(EInkPaperWhite)
    ) {
        // 1. TOP E-INK COMMAND HEADER (Zero shadow, 2dp solid black dividers)
        BooxCommandHeaderBar(
            state = state,
            onOpenPdfClick = onOpenPdfClick,
            onCycleRefreshMode = onCycleRefreshMode,
            onManualRegalFlash = onManualRegalFlash,
            onToggleHighContrast = onToggleHighContrast
        )

        HorizontalDivider(thickness = 2.dp, color = EInkPureBlack)

        // 2. NEOFLOW SMART TIERS & TRAVERSAL MATRIX BAR
        NeoFlowControlStrip(
            state = state,
            onSelectPreset = onSelectPreset,
            onSelectFlow = onSelectFlow,
            onToggleStudioEdit = onToggleStudioEdit,
            onToggleZoomTier = onToggleZoomTier,
            onResetGridSplits = onResetGridSplits
        )

        HorizontalDivider(thickness = 2.dp, color = EInkPureBlack)

        // 3. CENTRAL PDF + NEOFLOW + WACOM EMR CANVAS VIEWPORT
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(EInkSurfaceGray)
                .padding(8.dp)
        ) {
            BooxPdfNeoFlowViewport(
                state = state,
                pageBitmap = pageBitmap,
                onStepPrev = onStepPrev,
                onStepNext = onStepNext,
                onSelectBlock = onSelectBlock,
                onUpdateColSplit = onUpdateColSplit,
                onUpdateRowSplit = onUpdateRowSplit,
                onCommitStroke = onCommitStroke,
                onCommitQuadHighlight = onCommitQuadHighlight,
                onErasePoint = onErasePoint
            )
        }

        HorizontalDivider(thickness = 2.dp, color = EInkPureBlack)

        // 4. BOTTOM WACOM EMR STYLUS DOCK & TIER STEPPER
        BooxWacomStylusDock(
            state = state,
            onSelectTool = onSelectTool,
            onSelectSwatch = onSelectSwatch,
            onUndo = onUndo,
            onClearAnnotations = onClearAnnotations,
            onStepPrev = onStepPrev,
            onStepNext = onStepNext
        )
    }
}

@Composable
private fun BooxCommandHeaderBar(
    state: BooxReaderState,
    onOpenPdfClick: () -> Unit,
    onCycleRefreshMode: () -> Unit,
    onManualRegalFlash: () -> Unit,
    onToggleHighContrast: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(EInkPaperWhite)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(EInkPureBlack, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "INKSYNC BOOX · 4C",
                        color = EInkPaperWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = state.documentTitle,
                    color = EInkPureBlack,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${BooxEpdController.hardwareModelSummary} · ${state.lastStylusTelemetry}",
                color = EInkMutedText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EInkActionPill(
                label = "Open PDF",
                isSelected = false,
                onClick = onOpenPdfClick
            )
            EInkActionPill(
                label = state.refreshMode.title,
                isSelected = state.refreshMode != com.example.inksyncboox.eink.BooxRefreshMode.REGAL_HD,
                onClick = onCycleRefreshMode
            )
            EInkActionPill(
                label = "Regal Flash",
                isSelected = false,
                onClick = onManualRegalFlash
            )
            EInkActionPill(
                label = if (state.highContrastBAndW) "300PPI B&W" else "Kaleido 3",
                isSelected = state.highContrastBAndW,
                onClick = onToggleHighContrast
            )
        }
    }
}

@Composable
private fun NeoFlowControlStrip(
    state: BooxReaderState,
    onSelectPreset: (BooxGridPreset) -> Unit,
    onSelectFlow: (BooxTraversalFlow) -> Unit,
    onToggleStudioEdit: () -> Unit,
    onToggleZoomTier: () -> Unit,
    onResetGridSplits: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(EInkSurfaceGray)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "NEOFLOW:",
            color = EInkPureBlack,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )

        val primaryPresets = listOf(
            BooxGridPreset.GRID_1X1,
            BooxGridPreset.GRID_1X2,
            BooxGridPreset.GRID_2X1,
            BooxGridPreset.GRID_2X2,
            BooxGridPreset.GRID_2X3,
            BooxGridPreset.GRID_3X3
        )
        primaryPresets.forEach { preset ->
            EInkActionPill(
                label = preset.badge,
                isSelected = state.gridPreset == preset,
                onClick = { onSelectPreset(preset) }
            )
        }

        Box(
            modifier = Modifier
                .width(2.dp)
                .height(24.dp)
                .background(EInkPureBlack)
        )

        BooxTraversalFlow.entries.forEach { flow ->
            EInkActionPill(
                label = flow.symbol,
                isSelected = state.traversalFlow == flow,
                onClick = { onSelectFlow(flow) }
            )
        }

        Box(
            modifier = Modifier
                .width(2.dp)
                .height(24.dp)
                .background(EInkPureBlack)
        )

        EInkActionPill(
            label = if (state.isZoomedToTier) "Zoom: Tier Fit" else "Zoom: Full Page",
            isSelected = state.isZoomedToTier,
            onClick = onToggleZoomTier
        )

        EInkActionPill(
            label = if (state.isStudioGridEditMode) "Done Split Edit" else "Edit Splits",
            isSelected = state.isStudioGridEditMode,
            onClick = onToggleStudioEdit
        )

        if (state.isStudioGridEditMode) {
            EInkActionPill(
                label = "Reset Grid",
                isSelected = false,
                onClick = onResetGridSplits
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BooxPdfNeoFlowViewport(
    state: BooxReaderState,
    pageBitmap: Bitmap?,
    onStepPrev: () -> Unit,
    onStepNext: () -> Unit,
    onSelectBlock: (Int, Boolean) -> Unit,
    onUpdateColSplit: (Int, Float) -> Unit,
    onUpdateRowSplit: (Int, Float) -> Unit,
    onCommitStroke: (List<NormalizedInkPoint>, String) -> Unit,
    onCommitQuadHighlight: (NormalizedRect, String) -> Unit,
    onErasePoint: (Float, Float) -> Unit
) {
    val activeStrokePoints = remember { mutableStateListOf<NormalizedInkPoint>() }
    var quadDragStart by remember { mutableStateOf<Offset?>(null) }
    var quadDragCurrent by remember { mutableStateOf<Offset?>(null) }

    val blocks = state.computedBlocks
    val currentBlock = state.currentBlock
    val viewportRect = if (state.isZoomedToTier && state.gridPreset != BooxGridPreset.GRID_1X1) {
        currentBlock.redundantRect
    } else {
        NormalizedRect(0f, 0f, 1f, 1f)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .border(2.dp, EInkPureBlack)
            .background(EInkPaperWhite)
            .clipToBounds()
    ) {
        val canvasWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val canvasHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current

        // Helper functions to map between screen pixel coordinates and normalized page [0, 1] coordinates
        fun screenToPageNormalized(screenX: Float, screenY: Float): Pair<Float, Float> {
            val u = (screenX / canvasWidthPx).coerceIn(0f, 1f)
            val v = (screenY / canvasHeightPx).coerceIn(0f, 1f)
            val pageX = viewportRect.left + u * viewportRect.width
            val pageY = viewportRect.top + v * viewportRect.height
            return pageX.coerceIn(0f, 1f) to pageY.coerceIn(0f, 1f)
        }

        fun pageNormalizedToScreen(pageX: Float, pageY: Float): Offset {
            val u = (pageX - viewportRect.left) / viewportRect.width
            val v = (pageY - viewportRect.top) / viewportRect.height
            return Offset(u * canvasWidthPx, v * canvasHeightPx)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInteropFilter { motionEvent ->
                    val toolType = motionEvent.getToolType(0)
                    val isStylusHardware = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                        toolType == MotionEvent.TOOL_TYPE_ERASER
                    val pressure = motionEvent.pressure.coerceIn(0.05f, 1f)
                    val tilt = motionEvent.getAxisValue(MotionEvent.AXIS_TILT)

                    // Automatic Wacom Pen Barrel / Tail Eraser or Direct Stylus Inking
                    val effectiveTool = when {
                        state.isStudioGridEditMode -> BooxStylusTool.NAVIGATE
                        toolType == MotionEvent.TOOL_TYPE_ERASER -> BooxStylusTool.ERASER
                        state.activeStylusTool != BooxStylusTool.NAVIGATE -> state.activeStylusTool
                        isStylusHardware -> BooxStylusTool.FOUNTAIN_PEN
                        else -> BooxStylusTool.NAVIGATE
                    }

                    when (effectiveTool) {
                        BooxStylusTool.FOUNTAIN_PEN, BooxStylusTool.KALEIDO_HIGHLIGHTER -> {
                            when (motionEvent.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    activeStrokePoints.clear()
                                    val (px, py) = screenToPageNormalized(motionEvent.x, motionEvent.y)
                                    activeStrokePoints.add(NormalizedInkPoint(px, py, pressure, tilt, 0f))
                                    true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    // Process historical batched Wacom EMR samples (up to 240Hz on Note Air 4C)
                                    val historySize = motionEvent.historySize
                                    for (h in 0 until historySize) {
                                        val hx = motionEvent.getHistoricalX(h)
                                        val hy = motionEvent.getHistoricalY(h)
                                        val hp = motionEvent.getHistoricalPressure(h).coerceIn(0.05f, 1f)
                                        val (hpx, hpy) = screenToPageNormalized(hx, hy)
                                        activeStrokePoints.add(NormalizedInkPoint(hpx, hpy, hp, tilt, 0f))
                                    }
                                    val (px, py) = screenToPageNormalized(motionEvent.x, motionEvent.y)
                                    activeStrokePoints.add(NormalizedInkPoint(px, py, pressure, tilt, 0f))
                                    true
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    val committed = activeStrokePoints.toList()
                                    activeStrokePoints.clear()
                                    val sourceLabel = if (isStylusHardware) "Wacom EMR Stylus" else "Touch Ink"
                                    val pressureLvl = (pressure * 4096).roundToInt()
                                    onCommitStroke(
                                        committed,
                                        "$sourceLabel · Pressure $pressureLvl/4096 · ${committed.size} pts"
                                    )
                                    true
                                }
                                else -> false
                            }
                        }

                        BooxStylusTool.QUAD_HIGHLIGHT -> {
                            when (motionEvent.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    quadDragStart = Offset(motionEvent.x, motionEvent.y)
                                    quadDragCurrent = Offset(motionEvent.x, motionEvent.y)
                                    true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    quadDragCurrent = Offset(motionEvent.x, motionEvent.y)
                                    true
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    val start = quadDragStart
                                    val end = quadDragCurrent
                                    quadDragStart = null
                                    quadDragCurrent = null
                                    if (start != null && end != null) {
                                        val (x1, y1) = screenToPageNormalized(min(start.x, end.x), min(start.y, end.y))
                                        val (x2, y2) = screenToPageNormalized(max(start.x, end.x), max(start.y, end.y))
                                        // Snap to clean horizontal reading line bands
                                        val snappedTop = (y1 * 40f).roundToInt() / 40f
                                        val snappedBottom = max(snappedTop + 0.025f, (y2 * 40f).roundToInt() / 40f)
                                        val rect = NormalizedRect(
                                            left = x1,
                                            top = snappedTop.coerceIn(0f, 0.97f),
                                            right = max(x1 + 0.05f, x2),
                                            bottom = snappedBottom.coerceIn(0.03f, 1f)
                                        )
                                        onCommitQuadHighlight(
                                            rect,
                                            "Quad-Point Text Highlight Snapped · [${(rect.left * 100).roundToInt()}%, ${(rect.top * 100).roundToInt()}%]"
                                        )
                                    }
                                    true
                                }
                                else -> false
                            }
                        }

                        BooxStylusTool.ERASER -> {
                            when (motionEvent.actionMasked) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                    val (px, py) = screenToPageNormalized(motionEvent.x, motionEvent.y)
                                    onErasePoint(px, py)
                                    true
                                }
                                else -> true
                            }
                        }

                        BooxStylusTool.NAVIGATE -> {
                            if (motionEvent.actionMasked == MotionEvent.ACTION_UP && !state.isStudioGridEditMode) {
                                val tapXRatio = motionEvent.x / canvasWidthPx
                                when {
                                    tapXRatio < 0.24f -> onStepPrev()
                                    tapXRatio > 0.76f -> onStepNext()
                                    else -> {
                                        // Center tap selects whichever quadrant was tapped in full-page view
                                        val (px, py) = screenToPageNormalized(motionEvent.x, motionEvent.y)
                                        val hit = blocks.firstOrNull { b ->
                                            px in b.baseRect.left..b.baseRect.right &&
                                                py in b.baseRect.top..b.baseRect.bottom
                                        }
                                        if (hit != null) {
                                            onSelectBlock(hit.stepIndex, !state.isZoomedToTier)
                                        }
                                    }
                                }
                            }
                            true
                        }
                    }
                }
        ) {
            // 1. Draw Cropped / Scaled PDF Page Bitmap
            if (pageBitmap != null) {
                val srcLeft = (viewportRect.left * pageBitmap.width).roundToInt().coerceIn(0, pageBitmap.width - 1)
                val srcTop = (viewportRect.top * pageBitmap.height).roundToInt().coerceIn(0, pageBitmap.height - 1)
                val srcWidth = (viewportRect.width * pageBitmap.width).roundToInt().coerceIn(1, pageBitmap.width - srcLeft)
                val srcHeight = (viewportRect.height * pageBitmap.height).roundToInt().coerceIn(1, pageBitmap.height - srcTop)

                drawImage(
                    image = pageBitmap.asImageBitmap(),
                    srcOffset = IntOffset(srcLeft, srcTop),
                    srcSize = IntSize(srcWidth, srcHeight),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                )
            }

            // 2. Draw Snapped Quad-Point Text Highlights (BlendMode.Multiply keeps 300 PPI black ink razor-sharp)
            state.currentPageQuadHighlights.forEach { quad ->
                val topLeft = pageNormalizedToScreen(quad.bounds.left, quad.bounds.top)
                val bottomRight = pageNormalizedToScreen(quad.bounds.right, quad.bounds.bottom)
                drawRect(
                    color = Color(quad.colorHex.toULong()).copy(alpha = 0.55f),
                    topLeft = topLeft,
                    size = Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
                    blendMode = BlendMode.Multiply
                )
                drawRect(
                    color = EInkPureBlack,
                    topLeft = topLeft,
                    size = Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
                    style = Stroke(width = 1.5f)
                )
            }

            // 3. Draw Committed Wacom EMR Ink Strokes
            state.currentPageStrokes.forEach { stroke ->
                if (stroke.points.size >= 2) {
                    val path = Path()
                    val first = pageNormalizedToScreen(stroke.points.first().x, stroke.points.first().y)
                    path.moveTo(first.x, first.y)
                    for (i in 1 until stroke.points.size) {
                        val pt = pageNormalizedToScreen(stroke.points[i].x, stroke.points[i].y)
                        path.lineTo(pt.x, pt.y)
                    }
                    val avgPressure = stroke.points.map { it.pressure }.average().toFloat().coerceIn(0.2f, 1f)
                    val widthPx = if (stroke.isHighlighter) {
                        stroke.baseWidthPt * 2.2f
                    } else {
                        stroke.baseWidthPt * (0.65f + avgPressure * 1.15f) * 2.0f
                    }
                    drawPath(
                        path = path,
                        color = if (stroke.isHighlighter) stroke.composeColor.copy(alpha = 0.52f) else stroke.composeColor,
                        style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        blendMode = if (stroke.isHighlighter) BlendMode.Multiply else BlendMode.SrcOver
                    )
                }
            }

            // 4. Draw Live In-Flight Wacom Stroke (Zero-Lag Front Preview)
            if (activeStrokePoints.size >= 2) {
                val livePath = Path()
                val first = pageNormalizedToScreen(activeStrokePoints.first().x, activeStrokePoints.first().y)
                livePath.moveTo(first.x, first.y)
                for (i in 1 until activeStrokePoints.size) {
                    val pt = pageNormalizedToScreen(activeStrokePoints[i].x, activeStrokePoints[i].y)
                    livePath.lineTo(pt.x, pt.y)
                }
                val isHl = state.activeStylusTool == BooxStylusTool.KALEIDO_HIGHLIGHTER || state.activeSwatch.isHighlighter
                drawPath(
                    path = livePath,
                    color = if (isHl) state.activeSwatch.color.copy(alpha = 0.55f) else state.activeSwatch.color,
                    style = Stroke(
                        width = if (isHl) 36f else state.strokeWidthPt * 2.4f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    ),
                    blendMode = if (isHl) BlendMode.Multiply else BlendMode.SrcOver
                )
            }

            // 5. Draw Live Quad-Point Selection Marquee
            val qStart = quadDragStart
            val qEnd = quadDragCurrent
            if (qStart != null && qEnd != null) {
                val left = min(qStart.x, qEnd.x)
                val top = min(qStart.y, qEnd.y)
                val w = max(8f, kotlin.math.abs(qEnd.x - qStart.x))
                val h = max(8f, kotlin.math.abs(qEnd.y - qStart.y))
                drawRect(
                    color = KaleidoAmberHighlight.copy(alpha = 0.5f),
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    blendMode = BlendMode.Multiply
                )
                drawRect(
                    color = EInkPureBlack,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    style = Stroke(width = 2.5f)
                )
            }

            // 6. When in Full-Page View, Draw NeoFlow Smart Tier Matrix Boundaries & Active Selection Frame
            if (!state.isZoomedToTier && state.gridPreset != BooxGridPreset.GRID_1X1) {
                blocks.forEach { block ->
                    val tl = pageNormalizedToScreen(block.baseRect.left, block.baseRect.top)
                    val br = pageNormalizedToScreen(block.baseRect.right, block.baseRect.bottom)
                    val isSelected = block.stepIndex == state.activeStepIndex
                    drawRect(
                        color = if (isSelected) EInkPureBlack else EInkBorderGray,
                        topLeft = tl,
                        size = Size(br.x - tl.x, br.y - tl.y),
                        style = Stroke(width = if (isSelected) 4.5f else 2f)
                    )
                }
            }
        }

        // 7. Sequence Step Badges Overlay (in Full Page or Studio Grid Edit Mode)
        if (!state.isZoomedToTier && state.gridPreset != BooxGridPreset.GRID_1X1) {
            blocks.forEach { block ->
                val isCurrent = block.stepIndex == state.activeStepIndex
                val badgeX = with(density) { (block.baseRect.left * canvasWidthPx + 12f).toDp() }
                val badgeY = with(density) { (block.baseRect.top * canvasHeightPx + 12f).toDp() }

                Box(
                    modifier = Modifier
                        .offset(x = badgeX, y = badgeY)
                        .background(
                            if (isCurrent) EInkPureBlack else EInkPaperWhite,
                            RoundedCornerShape(4.dp)
                        )
                        .border(2.dp, EInkPureBlack, RoundedCornerShape(4.dp))
                        .clickable { onSelectBlock(block.stepIndex, true) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${block.displayStepNumber} · R${block.row + 1}C${block.col + 1}",
                        color = if (isCurrent) EInkPaperWhite else EInkPureBlack,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // 8. Interactive 48dp Touch-Target Split Divider Handles in Studio Grid Edit Mode
        if (state.isStudioGridEditMode && !state.isZoomedToTier) {
            state.colSplitRatios.forEachIndexed { idx, ratio ->
                val handleX = with(density) { (ratio * canvasWidthPx).toDp() - 22.dp }
                Box(
                    modifier = Modifier
                        .offset(x = handleX, y = 0.dp)
                        .width(44.dp)
                        .fillMaxHeight()
                        .pointerInput(idx, canvasWidthPx) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val deltaRatio = dragAmount.x / canvasWidthPx
                                onUpdateColSplit(idx, (state.colSplitRatios.getOrElse(idx) { 0.5f } + deltaRatio))
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .background(KaleidoCobaltInk)
                    )
                    Box(
                        modifier = Modifier
                            .background(EInkPureBlack, RoundedCornerShape(6.dp))
                            .border(2.dp, EInkPaperWhite, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "⇔ ${(ratio * 100).roundToInt()}%",
                            color = EInkPaperWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            state.rowSplitRatios.forEachIndexed { idx, ratio ->
                val handleY = with(density) { (ratio * canvasHeightPx).toDp() - 22.dp }
                Box(
                    modifier = Modifier
                        .offset(x = 0.dp, y = handleY)
                        .fillMaxWidth()
                        .height(44.dp)
                        .pointerInput(idx, canvasHeightPx) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val deltaRatio = dragAmount.y / canvasHeightPx
                                onUpdateRowSplit(idx, (state.rowSplitRatios.getOrElse(idx) { 0.5f } + deltaRatio))
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(KaleidoCobaltInk)
                    )
                    Box(
                        modifier = Modifier
                            .background(EInkPureBlack, RoundedCornerShape(6.dp))
                            .border(2.dp, EInkPaperWhite, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "⇕ ${(ratio * 100).roundToInt()}%",
                            color = EInkPaperWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // 9. Floating HUD Pill (Top-Right): Active Page & Smart Tier Status
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .background(EInkPaperWhite, RoundedCornerShape(4.dp))
                .border(2.dp, EInkPureBlack, RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = "Page ${state.pageIndex + 1}/${state.pageCount} · ${currentBlock.label}/${blocks.size} (${state.traversalFlow.symbol})",
                color = EInkPureBlack,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun BooxWacomStylusDock(
    state: BooxReaderState,
    onSelectTool: (BooxStylusTool) -> Unit,
    onSelectSwatch: (com.example.inksyncboox.theme.KaleidoInkSwatch) -> Unit,
    onUndo: () -> Unit,
    onClearAnnotations: () -> Unit,
    onStepPrev: () -> Unit,
    onStepNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(EInkPaperWhite)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Row 1: Stylus Tools + Kaleido 3 Color Swatches + Undo/Clear
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BooxStylusTool.entries.forEach { tool ->
                EInkActionPill(
                    label = "${tool.badge}: ${tool.label}",
                    isSelected = state.activeStylusTool == tool,
                    onClick = { onSelectTool(tool) }
                )
            }

            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(24.dp)
                    .background(EInkPureBlack)
            )

            BooxPenSwatches.forEach { swatch ->
                val isSelected = state.activeSwatch.id == swatch.id
                Row(
                    modifier = Modifier
                        .background(
                            if (isSelected) EInkSurfaceGray else EInkPaperWhite,
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            width = if (isSelected) 2.5.dp else 1.5.dp,
                            color = EInkPureBlack,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .clickable { onSelectSwatch(swatch) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(swatch.color, CircleShape)
                            .border(1.5.dp, EInkPureBlack, CircleShape)
                    )
                    Text(
                        text = swatch.label,
                        color = EInkPureBlack,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold
                    )
                }
            }

            EInkActionPill(label = "Undo", isSelected = false, onClick = onUndo)
            EInkActionPill(label = "Clear Page", isSelected = false, onClick = onClearAnnotations)
        }

        // Row 2: Instant Zero-Animation Tier & Page Stepper Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            EInkActionPill(
                label = "◀ Prev Tier / Page (Vol+)",
                isSelected = false,
                onClick = onStepPrev
            )

            Text(
                text = "Strokes: ${state.currentPageStrokes.size} · Quads: ${state.currentPageQuadHighlights.size} · Overlap: ${(state.overlapRatio * 100).roundToInt()}%",
                color = EInkPureBlack,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            EInkActionPill(
                label = "Next Tier / Page (Vol-) ▶",
                isSelected = true,
                onClick = onStepNext
            )
        }
    }
}

@Composable
private fun EInkActionPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(4.dp),
        color = if (isSelected) EInkPureBlack else EInkPaperWhite,
        border = BorderStroke(2.dp, EInkPureBlack)
    ) {
        Text(
            text = label,
            color = if (isSelected) EInkPaperWhite else EInkPureBlack,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

// Keep internal overload for existing scaffold test compatibility
@Composable
internal fun MainScreen(data: List<String>, modifier: Modifier = Modifier) {
    Column(modifier) { data.forEach { Greeting(it) } }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true, widthDp = 900, heightDp = 1200)
@Composable
fun MainScreenPreview() {
    InkSyncBooxTheme {
        BooxWorkspaceContent(
            state = BooxReaderState(),
            pageBitmap = null,
            onOpenPdfClick = {},
            onSelectPreset = {},
            onSelectFlow = {},
            onToggleStudioEdit = {},
            onToggleZoomTier = {},
            onStepPrev = {},
            onStepNext = {},
            onSelectBlock = { _, _ -> },
            onUpdateColSplit = { _, _ -> },
            onUpdateRowSplit = { _, _ -> },
            onResetGridSplits = {},
            onSelectTool = {},
            onSelectSwatch = {},
            onCommitStroke = { _, _ -> },
            onCommitQuadHighlight = { _, _ -> },
            onErasePoint = { _, _ -> },
            onUndo = {},
            onClearAnnotations = {},
            onCycleRefreshMode = {},
            onManualRegalFlash = {},
            onToggleHighContrast = {}
        )
    }
}
