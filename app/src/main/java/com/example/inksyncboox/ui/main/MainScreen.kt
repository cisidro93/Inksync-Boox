package com.example.inksyncboox.ui.main

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
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
import com.example.inksyncboox.study.BooxWorkspaceMode
import com.example.inksyncboox.study.UnifiedStudyCard
import com.example.inksyncboox.theme.BooxPenSwatches
import com.example.inksyncboox.theme.EInkBorderGray
import com.example.inksyncboox.theme.EInkMutedText
import com.example.inksyncboox.theme.EInkPaperWhite
import com.example.inksyncboox.theme.EInkPureBlack
import com.example.inksyncboox.theme.EInkSurfaceGray
import com.example.inksyncboox.theme.InkSyncBooxTheme
import com.example.inksyncboox.theme.KaleidoAmberHighlight
import com.example.inksyncboox.theme.KaleidoCobaltInk
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

    // Initialize built-in Note Air 4C showcase PDF on first launch ONLY if no external file intent was passed
    LaunchedEffect(pdfEngine) {
        if (viewModel.incomingFileUri.value == null) {
            val count = pdfEngine.ensureSampleOrOpenPdf(null)
            if (viewModel.incomingFileUri.value == null) {
                viewModel.updateDocumentMetadata(pdfEngine.activeDocumentTitle, count)
                renderedBitmap = pdfEngine.renderPage(0)
            }
        }
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

    val incomingUri by viewModel.incomingFileUri.collectAsStateWithLifecycle()

    suspend fun loadAnyRealDocumentUri(uri: android.net.Uri) {
        try {
            val fileName = pdfEngine.resolveDisplayName(uri)
            val lowerName = fileName.lowercase()
            val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()

            when {
                lowerName.endsWith(".epub") || mime.contains("epub") -> {
                    val parsedBook = com.example.inksyncboox.epub.RealEpubParser.parseEpubFromUri(
                        context = context,
                        uri = uri,
                        fallbackFileName = fileName
                    )
                    if (parsedBook != null) {
                        viewModel.loadImportedEpubBook(parsedBook)
                    }
                }
                lowerName.endsWith(".cbz") || lowerName.endsWith(".zip") || mime.contains("cbz") || mime.contains("comicbook") -> {
                    val count = pdfEngine.openCbzArchive(uri)
                    if (count > 0) {
                        viewModel.updateDocumentMetadata(pdfEngine.activeDocumentTitle, count, isCbz = true)
                        renderedBitmap = pdfEngine.renderPage(0)
                    }
                }
                else -> {
                    val count = pdfEngine.ensureSampleOrOpenPdf(uri)
                    if (count > 0) {
                        viewModel.updateDocumentMetadata(pdfEngine.activeDocumentTitle, count, isCbz = false)
                        renderedBitmap = pdfEngine.renderPage(0)
                    }
                }
            }
        } catch (e: Throwable) {
            viewModel.updateSidecarExportStatus("File Access Notice: ${e.localizedMessage ?: "Use SAF Picker"}")
        }
    }

    LaunchedEffect(incomingUri) {
        val target = incomingUri
        if (target != null) {
            loadAnyRealDocumentUri(target)
            viewModel.setIncomingFileUri(null)
        }
    }

    // System SAF Document Picker to open ANY real personal .PDF, .EPUB, or .CBZ on the Boox Note Air 4C
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                loadAnyRealDocumentUri(uri)
            }
        }
    }

    InkSyncBooxTheme(highContrastBAndW = readerState.highContrastBAndW) {
        BooxWorkspaceContent(
            state = readerState,
            pageBitmap = renderedBitmap,
            onSelectWorkspaceMode = viewModel::selectWorkspaceMode,
            onDismissWelcomeBanner = viewModel::dismissWelcomeBackBanner,
            onTogglePalmShield = viewModel::togglePalmShieldStylusOnly,
            onToggleCornellSplit = viewModel::toggleCornellSplitScratchpad,
            onCycleCornellTemplate = viewModel::cycleCornellTemplate,
            onCommitCornellStroke = viewModel::commitCornellScratchpadStroke,
            onClearCornellScratchpad = viewModel::clearCornellScratchpad,
            onOpenPdfClick = {
                openDocumentLauncher.launch(
                    arrayOf(
                        "application/pdf",
                        "application/epub+zip",
                        "application/vnd.comicbook+zip",
                        "application/x-cbz",
                        "application/zip",
                        "application/octet-stream",
                        "*/*"
                    )
                )
            },
            onSelectPreset = viewModel::selectGridPreset,
            onSelectFlow = viewModel::selectTraversalFlow,
            onToggleStudioEdit = viewModel::toggleStudioGridEditMode,
            onToggleZoomTier = viewModel::toggleZoomToActiveTier,
            onTogglePanelsSpotlight = viewModel::togglePanelsSpotlightDimming,
            onTogglePanelsSpread = viewModel::togglePanelsDoublePageSpread,
            onTogglePanelsBleed = viewModel::togglePanelsDarkGutterBleed,
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
            onCycleEpubTypography = viewModel::cycleEpubTypographyPreset,
            onToggleEpubTwoColumn = viewModel::toggleEpubTwoColumnMode,
            onToggleEpubParagraphHighlight = viewModel::toggleEpubParagraphHighlight,
            onJumpToStudyCard = viewModel::jumpToStudyCardOrigin,
            onUpdateSidecarExportStatus = viewModel::updateSidecarExportStatus,
            onCycleRefreshMode = viewModel::cycleRefreshMode,
            onManualRegalFlash = viewModel::triggerManualRegalRefresh,
            onToggleHighContrast = viewModel::toggleHighContrastBAndW,
            modifier = modifier.onSizeChanged { size ->
                // Exclude reading/inking edges from Android's system Back swipe gesture on Note Air 4C
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && size.width > 0 && size.height > 0) {
                    hostView.systemGestureExclusionRects = listOf(
                        Rect(0, (size.height * 0.15f).toInt(), 90, (size.height * 0.85f).toInt()),
                        Rect(size.width - 90, (size.height * 0.15f).toInt(), size.width, (size.height * 0.85f).toInt())
                    )
                }
            }
        )
    }
}

@Composable
internal fun BooxWorkspaceContent(
    state: BooxReaderState,
    pageBitmap: Bitmap?,
    onSelectWorkspaceMode: (BooxWorkspaceMode) -> Unit,
    onDismissWelcomeBanner: () -> Unit,
    onTogglePalmShield: () -> Unit,
    onToggleCornellSplit: () -> Unit,
    onCycleCornellTemplate: () -> Unit,
    onCommitCornellStroke: (List<NormalizedInkPoint>, String) -> Unit,
    onClearCornellScratchpad: () -> Unit,
    onOpenPdfClick: () -> Unit,
    onSelectPreset: (BooxGridPreset) -> Unit,
    onSelectFlow: (BooxTraversalFlow) -> Unit,
    onToggleStudioEdit: () -> Unit,
    onToggleZoomTier: () -> Unit,
    onTogglePanelsSpotlight: () -> Unit = {},
    onTogglePanelsSpread: () -> Unit = {},
    onTogglePanelsBleed: () -> Unit = {},
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
    onCycleEpubTypography: () -> Unit,
    onToggleEpubTwoColumn: () -> Unit,
    onToggleEpubParagraphHighlight: (String, String, Int, Int) -> Unit,
    onJumpToStudyCard: (UnifiedStudyCard) -> Unit,
    onUpdateSidecarExportStatus: (String) -> Unit,
    onCycleRefreshMode: () -> Unit,
    onManualRegalFlash: () -> Unit,
    onToggleHighContrast: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(EInkSurfaceGray)
    ) {
        // 1. SLEEK LEFT BOOX OS 4.0 VERTICAL NAVIGATION & TOOL RAIL
        BooxLeftNavigationRail(
            state = state,
            onSelectWorkspaceMode = onSelectWorkspaceMode,
            onToggleCornellSplit = onToggleCornellSplit,
            onManualRegalFlash = onManualRegalFlash,
            onOpenPdfClick = onOpenPdfClick
        )

        // Vertical 1dp warm stone divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(EInkBorderGray)
        )

        // 2. RIGHT MAIN CONTENT AREA
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xFFFAF9F5))
        ) {
            // Sleek Floating Boox OS 4.0 Top Header + Pill Stylus Bar
            BooxCommandHeaderBar(
                state = state,
                onSelectTool = onSelectTool,
                onSelectSwatch = onSelectSwatch,
                onUndo = onUndo,
                onClearAnnotations = onClearAnnotations,
                onTogglePalmShield = onTogglePalmShield,
                onCycleRefreshMode = onCycleRefreshMode,
                onToggleHighContrast = onToggleHighContrast
            )

            HorizontalDivider(thickness = 1.dp, color = EInkBorderGray)

            // Contextual NeoFlow / Panels Studio Pill Strip when in PDF or PANELS_COMICS Mode
            if (state.workspaceMode == BooxWorkspaceMode.PDF_NEOFLOW || state.workspaceMode == BooxWorkspaceMode.PANELS_COMICS) {
                NeoFlowControlStrip(
                    state = state,
                    onSelectPreset = onSelectPreset,
                    onSelectFlow = onSelectFlow,
                    onToggleStudioEdit = onToggleStudioEdit,
                    onToggleZoomTier = onToggleZoomTier,
                    onTogglePanelsSpotlight = onTogglePanelsSpotlight,
                    onTogglePanelsSpread = onTogglePanelsSpread,
                    onTogglePanelsBleed = onTogglePanelsBleed,
                    onResetGridSplits = onResetGridSplits
                )
                HorizontalDivider(thickness = 1.dp, color = EInkBorderGray)
            }

            // Central Unified Workspace + Optional Split-Screen Cornell Notebook
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFFFAF9F5))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(if (state.showCornellSplitScratchpad) 0.60f else 1f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp),
                    color = EInkPaperWhite,
                    border = BorderStroke(1.dp, EInkBorderGray)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (state.workspaceMode) {
                            BooxWorkspaceMode.SANCTUARY_SHELF -> {
                                BooxSanctuaryShelfView(
                                    state = state,
                                    onSelectMode = onSelectWorkspaceMode,
                                    onOpenLocalPdf = onOpenPdfClick,
                                    onDismissWelcomeBanner = onDismissWelcomeBanner,
                                    onJumpToCard = onJumpToStudyCard
                                )
                            }
                            BooxWorkspaceMode.PANELS_COMICS,
                            BooxWorkspaceMode.PDF_NEOFLOW -> {
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
                            BooxWorkspaceMode.KINDLE_EPUB -> {
                                BooxKindleEpubReaderView(
                                    state = state,
                                    onCycleTypography = onCycleEpubTypography,
                                    onToggleTwoColumn = onToggleEpubTwoColumn,
                                    onToggleParagraphHighlight = onToggleEpubParagraphHighlight,
                                    onStepPrevChapter = onStepPrev,
                                    onStepNextChapter = onStepNext
                                )
                            }
                            BooxWorkspaceMode.STUDY_BINDER -> {
                                BooxStudyHubBinderView(
                                    state = state,
                                    onJumpToCard = onJumpToStudyCard,
                                    onExportSidecars = onUpdateSidecarExportStatus
                                )
                            }
                        }
                    }
                }

                if (state.showCornellSplitScratchpad) {
                    Surface(
                        modifier = Modifier
                            .weight(0.40f)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(16.dp),
                        color = EInkPaperWhite,
                        border = BorderStroke(1.dp, EInkBorderGray)
                    ) {
                        BooxCornellScratchpadPane(
                            state = state,
                            onCycleTemplate = onCycleCornellTemplate,
                            onCommitScratchpadStroke = onCommitCornellStroke,
                            onClearScratchpad = onClearCornellScratchpad,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = EInkBorderGray)

            // Minimalist Boox OS 4.0 / Kindle Scribe Progress & Quadrant Step Bar
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
}

@Composable
private fun BooxLeftNavigationRail(
    state: BooxReaderState,
    onSelectWorkspaceMode: (BooxWorkspaceMode) -> Unit,
    onToggleCornellSplit: () -> Unit,
    onManualRegalFlash: () -> Unit,
    onOpenPdfClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .fillMaxHeight()
            .background(EInkSurfaceGray)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Modern Rounded Brand Badge
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = EInkPureBlack,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "IS",
                        color = EInkPaperWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val navItems = listOf(
                Triple(BooxWorkspaceMode.SANCTUARY_SHELF, "Shelf", "□"),
                Triple(BooxWorkspaceMode.PANELS_COMICS, "Panels", "▣"),
                Triple(BooxWorkspaceMode.PDF_NEOFLOW, "NeoFlow", "⊞"),
                Triple(BooxWorkspaceMode.KINDLE_EPUB, "Reflow", "≡"),
                Triple(BooxWorkspaceMode.STUDY_BINDER, "Binder", "✦")
            )

            navItems.forEach { (mode, label, glyph) ->
                val isSelected = state.workspaceMode == mode
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectWorkspaceMode(mode) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) EInkPureBlack else Color.Transparent
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = glyph,
                            color = if (isSelected) EInkPaperWhite else EInkPureBlack,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = label,
                            color = if (isSelected) EInkPaperWhite else EInkMutedText,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            fontFamily = FontFamily.SansSerif,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Bottom Hardware & Split-Screen Controls
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleCornellSplit),
                shape = RoundedCornerShape(12.dp),
                color = if (state.showCornellSplitScratchpad) EInkPureBlack else EInkPaperWhite,
                border = BorderStroke(1.dp, EInkBorderGray)
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 7.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "◫",
                        color = if (state.showCornellSplitScratchpad) EInkPaperWhite else EInkPureBlack,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Cornell",
                        color = if (state.showCornellSplitScratchpad) EInkPaperWhite else EInkMutedText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onManualRegalFlash),
                shape = RoundedCornerShape(12.dp),
                color = EInkPaperWhite,
                border = BorderStroke(1.dp, EInkBorderGray)
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 7.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "↻",
                        color = EInkPureBlack,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Regal",
                        color = EInkMutedText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPdfClick),
                shape = RoundedCornerShape(12.dp),
                color = EInkPureBlack
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 7.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "+",
                        color = EInkPaperWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Import",
                        color = EInkPaperWhite,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun BooxCommandHeaderBar(
    state: BooxReaderState,
    onSelectTool: (BooxStylusTool) -> Unit,
    onSelectSwatch: (com.example.inksyncboox.theme.KaleidoInkSwatch) -> Unit,
    onUndo: () -> Unit,
    onClearAnnotations: () -> Unit,
    onTogglePalmShield: () -> Unit,
    onCycleRefreshMode: () -> Unit,
    onToggleHighContrast: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFAF9F5))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Active Book Title + Subtle Mode Pill
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = state.documentTitle,
                color = EInkPureBlack,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = EInkSurfaceGray,
                border = BorderStroke(1.dp, EInkBorderGray)
            ) {
                Text(
                    text = "${state.refreshMode.title} · ${if (state.highContrastBAndW) "300 PPI B&W" else "Kaleido 3"}",
                    color = EInkMutedText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable(onClick = onCycleRefreshMode)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // Right: Floating Pill Wacom Stylus Bar (Boox Notes 4.0 Style)
        Surface(
            shape = RoundedCornerShape(50),
            color = EInkPaperWhite,
            border = BorderStroke(1.dp, EInkBorderGray)
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BooxStylusTool.entries.forEach { tool ->
                    val isSelected = state.activeStylusTool == tool
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isSelected) EInkPureBlack else Color.Transparent,
                        modifier = Modifier.clickable { onSelectTool(tool) }
                    ) {
                        Text(
                            text = tool.label,
                            color = if (isSelected) EInkPaperWhite else EInkMutedText,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(EInkBorderGray)
                )

                // Circular Kaleido 3 Color Swatches
                BooxPenSwatches.take(5).forEach { swatch ->
                    val isSelected = state.activeSwatch.id == swatch.id
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(swatch.color, CircleShape)
                            .border(
                                width = if (isSelected) 2.5.dp else 1.dp,
                                color = if (isSelected) EInkPureBlack else EInkBorderGray,
                                shape = CircleShape
                            )
                            .clickable { onSelectSwatch(swatch) }
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(EInkBorderGray)
                )

                Text(
                    text = "Undo",
                    color = EInkMutedText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable(onClick = onUndo)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
                Text(
                    text = "Clear",
                    color = EInkMutedText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable(onClick = onClearAnnotations)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
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
    onTogglePanelsSpotlight: () -> Unit = {},
    onTogglePanelsSpread: () -> Unit = {},
    onTogglePanelsBleed: () -> Unit = {},
    onResetGridSplits: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFAF9F5))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val isPanelsMode = state.workspaceMode == BooxWorkspaceMode.PANELS_COMICS
        Text(
            text = if (isPanelsMode) "Panels Studio" else "Matrix",
            color = EInkMutedText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )

        if (isPanelsMode) {
            EInkActionPill(
                label = if (state.panelsSpotlightDimming) "Spotlight: ON" else "Spotlight: OFF",
                isSelected = state.panelsSpotlightDimming,
                onClick = onTogglePanelsSpotlight
            )
            EInkActionPill(
                label = if (state.panelsDoublePageSpread) "2-Page Spread (+1 Cover)" else "1-Page Fit",
                isSelected = state.panelsDoublePageSpread,
                onClick = onTogglePanelsSpread
            )
            EInkActionPill(
                label = if (state.panelsDarkGutterBleed) "Bleed: Dark Ink" else "Bleed: Newsprint",
                isSelected = state.panelsDarkGutterBleed,
                onClick = onTogglePanelsBleed
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(EInkBorderGray)
            )
        }

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
                .width(1.dp)
                .height(18.dp)
                .background(EInkBorderGray)
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
                .width(1.dp)
                .height(18.dp)
                .background(EInkBorderGray)
        )

        EInkActionPill(
            label = if (state.isZoomedToTier) "Panel Zoom" else "Full Page",
            isSelected = state.isZoomedToTier,
            onClick = onToggleZoomTier
        )

        EInkActionPill(
            label = if (state.isStudioGridEditMode) "Done Adjusting" else "Adjust Panels",
            isSelected = state.isStudioGridEditMode,
            onClick = onToggleStudioEdit
        )

        if (state.isStudioGridEditMode) {
            EInkActionPill(
                label = "Reset",
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
    // Track last Wacom stylus activity timestamp to enforce automatic 850ms Palm Rejection cooldown
    var lastStylusEventMillis by remember { mutableLongStateOf(0L) }

    val blocks = state.computedBlocks
    val currentBlock = state.currentBlock
    val viewportRect = if (state.isZoomedToTier && state.gridPreset != BooxGridPreset.GRID_1X1) {
        currentBlock.redundantRect
    } else {
        NormalizedRect(0f, 0f, 1f, 1f)
    }

    val isPanelsMode = state.workspaceMode == BooxWorkspaceMode.PANELS_COMICS
    val gutterBleedColor = when {
        isPanelsMode && state.panelsDarkGutterBleed -> Color(0xFF141417)
        isPanelsMode -> Color(0xFFFAF8F2)
        else -> EInkPaperWhite
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(gutterBleedColor)
            .clipToBounds()
    ) {
        val canvasWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val canvasHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current

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
                    val now = System.currentTimeMillis()
                    if (isStylusHardware) {
                        lastStylusEventMillis = now
                    }
                    val withinPalmCooldown = (now - lastStylusEventMillis) < 850L
                    val pressure = motionEvent.pressure.coerceIn(0.05f, 1f)
                    val tilt = motionEvent.getAxisValue(MotionEvent.AXIS_TILT)

                    // If Palm Shield is locked and a finger/palm touches the screen, ignore accidental page turns
                    if (!isStylusHardware && (state.palmShieldStylusOnly || withinPalmCooldown)) {
                        return@pointerInteropFilter true
                    }

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
                                            "Quad-Point Highlight Pinned to Study Hub · [${(rect.left * 100).roundToInt()}%, ${(rect.top * 100).roundToInt()}%]"
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

            if (!state.isZoomedToTier && state.gridPreset != BooxGridPreset.GRID_1X1) {
                blocks.forEach { block ->
                    val tl = pageNormalizedToScreen(block.baseRect.left, block.baseRect.top)
                    val br = pageNormalizedToScreen(block.baseRect.right, block.baseRect.bottom)
                    val isSelected = block.stepIndex == state.activeStepIndex
                    val panelSize = Size(br.x - tl.x, br.y - tl.y)

                    // iOS Panels Guided Spotlight Dimming Mask over non-active panels
                    if (isPanelsMode && state.panelsSpotlightDimming && !isSelected) {
                        drawRect(
                            color = Color(0x99141417),
                            topLeft = tl,
                            size = panelSize
                        )
                    }

                    drawRect(
                        color = when {
                            isSelected && isPanelsMode -> Color(0xFFFACC15)
                            isSelected -> EInkPureBlack
                            else -> EInkBorderGray
                        },
                        topLeft = tl,
                        size = panelSize,
                        style = Stroke(width = if (isSelected) 4f else 1.5f)
                    )
                }
            }
        }

        if (!state.isZoomedToTier && state.gridPreset != BooxGridPreset.GRID_1X1) {
            blocks.forEach { block ->
                val isCurrent = block.stepIndex == state.activeStepIndex
                val badgeX = with(density) { (block.baseRect.left * canvasWidthPx + 12f).toDp() }
                val badgeY = with(density) { (block.baseRect.top * canvasHeightPx + 12f).toDp() }

                Surface(
                    modifier = Modifier
                        .offset(x = badgeX, y = badgeY)
                        .clickable { onSelectBlock(block.stepIndex, true) },
                    shape = RoundedCornerShape(50),
                    color = if (isCurrent) EInkPureBlack else EInkPaperWhite,
                    border = BorderStroke(1.dp, if (isCurrent) EInkPureBlack else EInkBorderGray)
                ) {
                    Text(
                        text = if (isPanelsMode) {
                            "Panel ${block.displayStepNumber}"
                        } else {
                            "${block.displayStepNumber} · R${block.row + 1}C${block.col + 1}"
                        },
                        color = if (isCurrent) EInkPaperWhite else EInkPureBlack,
                        fontSize = 11.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                        fontFamily = FontFamily.SansSerif,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

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

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            shape = RoundedCornerShape(50),
            color = EInkPaperWhite,
            border = BorderStroke(1.dp, EInkBorderGray)
        ) {
            Text(
                text = "Page ${state.pageIndex + 1} of ${state.pageCount} · Quadrant ${currentBlock.label}",
                color = EInkPureBlack,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFAF9F5))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EInkActionPill(
                label = "← Previous",
                isSelected = false,
                onClick = onStepPrev
            )
            EInkActionPill(
                label = "Next Quadrant →",
                isSelected = true,
                onClick = onStepNext
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Subtle Kindle Scribe / NeoReader Progress Bar
            val totalSteps = max(1, state.computedBlocks.size)
            val progressFraction = ((state.activeStepIndex + 1).toFloat() / totalSteps.toFloat()).coerceIn(0.1f, 1f)
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(6.dp)
                    .background(EInkBorderGray, RoundedCornerShape(50))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction)
                        .fillMaxHeight()
                        .background(EInkPureBlack, RoundedCornerShape(50))
                )
            }

            Text(
                text = "Page ${state.pageIndex + 1}/${state.pageCount} · ${state.studyCards.size} Notes · ${state.currentPageStrokes.size + state.cornellStrokes.size} Strokes",
                color = EInkMutedText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif
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
        shape = RoundedCornerShape(50),
        color = if (isSelected) EInkPureBlack else EInkPaperWhite,
        border = BorderStroke(1.dp, if (isSelected) EInkPureBlack else EInkBorderGray)
    ) {
        Text(
            text = label,
            color = if (isSelected) EInkPaperWhite else EInkPureBlack,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = FontFamily.SansSerif,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
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

@Preview(showBackground = true, widthDp = 1000, heightDp = 1300)
@Composable
fun MainScreenPreview() {
    InkSyncBooxTheme {
        BooxWorkspaceContent(
            state = BooxReaderState(),
            pageBitmap = null,
            onSelectWorkspaceMode = {},
            onDismissWelcomeBanner = {},
            onTogglePalmShield = {},
            onToggleCornellSplit = {},
            onCycleCornellTemplate = {},
            onCommitCornellStroke = { _, _ -> },
            onClearCornellScratchpad = {},
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
            onCycleEpubTypography = {},
            onToggleEpubTwoColumn = {},
            onToggleEpubParagraphHighlight = { _, _, _, _ -> },
            onJumpToStudyCard = {},
            onUpdateSidecarExportStatus = {},
            onCycleRefreshMode = {},
            onManualRegalFlash = {},
            onToggleHighContrast = {}
        )
    }
}
