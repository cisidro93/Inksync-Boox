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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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
    var hasCustomImportedDocument by remember { mutableStateOf(false) }

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

    // Automatically swap between built-in Comic .CBZ and built-in Academic .PDF when switching modes (if no custom file is loaded)
    LaunchedEffect(readerState.workspaceMode) {
        if (!hasCustomImportedDocument && viewModel.incomingFileUri.value == null) {
            when (readerState.workspaceMode) {
                BooxWorkspaceMode.PANELS_COMICS -> {
                    if (!pdfEngine.isCbzArchive) {
                        val count = pdfEngine.openBuiltInSampleCbz()
                        val safeIdx = readerState.pageIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
                        renderedBitmap = pdfEngine.renderPage(safeIdx)
                    }
                }
                BooxWorkspaceMode.PDF_NEOFLOW -> {
                    if (pdfEngine.isCbzArchive) {
                        val count = pdfEngine.ensureSampleOrOpenPdf(null)
                        val safeIdx = readerState.pageIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
                        renderedBitmap = pdfEngine.renderPage(safeIdx)
                    }
                }
                else -> Unit
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
                        hasCustomImportedDocument = true
                        viewModel.updateDocumentMetadata(pdfEngine.activeDocumentTitle, count, isCbz = true)
                        renderedBitmap = pdfEngine.renderPage(0)
                    }
                }
                else -> {
                    val count = pdfEngine.ensureSampleOrOpenPdf(uri)
                    if (count > 0) {
                        hasCustomImportedDocument = true
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
            onToggleKaleidoBoost = viewModel::toggleKaleidoOpticalBoost,
            onToggleMarginTrim = viewModel::toggleAutoTrimMargins,
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
    onToggleKaleidoBoost: () -> Unit = {},
    onToggleMarginTrim: () -> Unit = {},
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
    var showReaderOverlayMenu by remember { mutableStateOf(false) }
    var lastReaderMode by remember { mutableStateOf(BooxWorkspaceMode.PDF_NEOFLOW) }
    if (state.workspaceMode == BooxWorkspaceMode.PDF_NEOFLOW ||
        state.workspaceMode == BooxWorkspaceMode.PANELS_COMICS ||
        state.workspaceMode == BooxWorkspaceMode.KINDLE_EPUB
    ) {
        lastReaderMode = state.workspaceMode
    }

    val isModalSheetOpen = state.workspaceMode == BooxWorkspaceMode.SANCTUARY_SHELF ||
        state.workspaceMode == BooxWorkspaceMode.STUDY_BINDER

    // =========================================================================
    // 100% FULL-SCREEN BOOK / PDF / COMIC CANVAS ON EVERY UI
    // - When showReaderOverlayMenu == false: 100.0% of the screen is ONLY the book.
    //   Zero top bars, zero bottom bars, zero sidebars, zero floating buttons.
    // - Tapping the center of the screen activates/hides the floating Overlay HUD.
    // =========================================================================
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(EInkPaperWhite)
    ) {
        // 1. ALWAYS-ON 100% FULL-SCREEN BOOK LAYER (Edge-to-Edge)
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(if (state.showCornellSplitScratchpad) 0.62f else 1f)
                    .fillMaxHeight()
            ) {
                when (lastReaderMode) {
                    BooxWorkspaceMode.PANELS_COMICS,
                    BooxWorkspaceMode.PDF_NEOFLOW -> {
                        BooxPdfNeoFlowViewport(
                            state = state.copy(workspaceMode = lastReaderMode),
                            pageBitmap = pageBitmap,
                            showOverlayBadges = showReaderOverlayMenu || state.isStudioGridEditMode,
                            onStepPrev = {
                                showReaderOverlayMenu = false
                                onStepPrev()
                            },
                            onStepNext = {
                                showReaderOverlayMenu = false
                                onStepNext()
                            },
                            onSelectBlock = onSelectBlock,
                            onUpdateColSplit = onUpdateColSplit,
                            onUpdateRowSplit = onUpdateRowSplit,
                            onCommitStroke = onCommitStroke,
                            onCommitQuadHighlight = onCommitQuadHighlight,
                            onErasePoint = onErasePoint,
                            onToggleReaderOverlay = {
                                showReaderOverlayMenu = !showReaderOverlayMenu
                            }
                        )
                    }
                    BooxWorkspaceMode.KINDLE_EPUB -> {
                        BooxKindleEpubReaderView(
                            state = state,
                            onCycleTypography = onCycleEpubTypography,
                            onToggleTwoColumn = onToggleEpubTwoColumn,
                            onToggleParagraphHighlight = onToggleEpubParagraphHighlight,
                            onStepPrevChapter = {
                                showReaderOverlayMenu = false
                                onStepPrev()
                            },
                            onStepNextChapter = {
                                showReaderOverlayMenu = false
                                onStepNext()
                            },
                            onToggleReaderOverlay = {
                                showReaderOverlayMenu = !showReaderOverlayMenu
                            }
                        )
                    }
                    else -> Unit
                }
            }

            if (state.showCornellSplitScratchpad) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(EInkPureBlack)
                )
                BooxCornellScratchpadPane(
                    state = state,
                    onCycleTemplate = onCycleCornellTemplate,
                    onCommitScratchpadStroke = onCommitCornellStroke,
                    onClearScratchpad = onClearCornellScratchpad,
                    modifier = Modifier
                        .weight(0.38f)
                        .fillMaxHeight()
                )
            }
        }

        // 2. ACTIVATABLE & HIDABLE FLOATING OVERLAY HUD (Appears ONLY when activated)
        if (showReaderOverlayMenu || isModalSheetOpen) {
            // Floating Top & Bottom NeoReader V3 Tabbed Overlay Bars + Tap-Anywhere-to-Dismiss Scrim
            BooxFloatingNeoReaderOverlay(
                state = state,
                onDismissOverlay = {
                    showReaderOverlayMenu = false
                    if (isModalSheetOpen) {
                        onSelectWorkspaceMode(lastReaderMode)
                    }
                },
                onReturnToLibrary = {
                    if (state.workspaceMode == BooxWorkspaceMode.SANCTUARY_SHELF) {
                        onSelectWorkspaceMode(lastReaderMode)
                    } else {
                        onSelectWorkspaceMode(BooxWorkspaceMode.SANCTUARY_SHELF)
                    }
                },
                onOpenStudyBinder = {
                    if (state.workspaceMode == BooxWorkspaceMode.STUDY_BINDER) {
                        onSelectWorkspaceMode(lastReaderMode)
                    } else {
                        onSelectWorkspaceMode(BooxWorkspaceMode.STUDY_BINDER)
                    }
                },
                onSelectWorkspaceMode = { mode ->
                    onSelectWorkspaceMode(mode)
                },
                onToggleCornellSplit = onToggleCornellSplit,
                onManualRegalFlash = onManualRegalFlash,
                onOpenPdfClick = onOpenPdfClick,
                onSelectPreset = onSelectPreset,
                onSelectFlow = onSelectFlow,
                onToggleStudioEdit = onToggleStudioEdit,
                onToggleZoomTier = onToggleZoomTier,
                onTogglePanelsSpotlight = onTogglePanelsSpotlight,
                onTogglePanelsSpread = onTogglePanelsSpread,
                onTogglePanelsBleed = onTogglePanelsBleed,
                onToggleKaleidoBoost = onToggleKaleidoBoost,
                onToggleMarginTrim = onToggleMarginTrim,
                onResetGridSplits = onResetGridSplits,
                onCycleEpubTypography = onCycleEpubTypography,
                onToggleEpubTwoColumn = onToggleEpubTwoColumn,
                onCycleRefreshMode = onCycleRefreshMode,
                onToggleHighContrast = onToggleHighContrast,
                onStepPrev = onStepPrev,
                onStepNext = onStepNext
            )

            // Floating Right-Edge Wacom Stylus Pill (Sits above the overlay scrim so all stylus tools remain interactive)
            BooxFloatingStylusEdgePill(
                state = state,
                isExpanded = true,
                onToggleExpanded = { showReaderOverlayMenu = false },
                onSelectTool = onSelectTool,
                onSelectSwatch = onSelectSwatch,
                onTogglePalmShield = onTogglePalmShield,
                onUndo = onUndo,
                onClearAnnotations = onClearAnnotations,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
            )

            // Optional Floating Library / Study Binder Modal Sheet over the Full-Screen Book
            if (isModalSheetOpen) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.88f)
                        .fillMaxHeight(0.76f),
                    shape = RoundedCornerShape(16.dp),
                    color = EInkPaperWhite,
                    border = BorderStroke(2.dp, EInkPureBlack)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(EInkSurfaceGray)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (state.workspaceMode == BooxWorkspaceMode.SANCTUARY_SHELF) {
                                    "LIBRARY DRAWER OVERLAY (BOOK REMAINS FULL SCREEN BEHIND)"
                                } else {
                                    "STUDY BINDER OVERLAY (BOOK REMAINS FULL SCREEN BEHIND)"
                                },
                                color = EInkPureBlack,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            EInkActionPill(
                                label = "Close Overlay ✕",
                                isSelected = true,
                                onClick = {
                                    showReaderOverlayMenu = false
                                    onSelectWorkspaceMode(lastReaderMode)
                                }
                            )
                        }
                        HorizontalDivider(thickness = 1.5.dp, color = EInkPureBlack)
                        Box(modifier = Modifier.weight(1f)) {
                            if (state.workspaceMode == BooxWorkspaceMode.SANCTUARY_SHELF) {
                                BooxSanctuaryShelfView(
                                    state = state,
                                    onSelectMode = { mode ->
                                        showReaderOverlayMenu = false
                                        onSelectWorkspaceMode(mode)
                                    },
                                    onOpenLocalPdf = onOpenPdfClick,
                                    onDismissWelcomeBanner = onDismissWelcomeBanner,
                                    onJumpToCard = { card ->
                                        showReaderOverlayMenu = false
                                        onJumpToStudyCard(card)
                                    }
                                )
                            } else {
                                BooxStudyHubBinderView(
                                    state = state,
                                    onJumpToCard = { card ->
                                        showReaderOverlayMenu = false
                                        onJumpToStudyCard(card)
                                    },
                                    onExportSidecars = onUpdateSidecarExportStatus
                                )
                            }
                        }
                    }
                }
            }
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
private fun NeoFlowControlStrip(
    state: BooxReaderState,
    onSelectPreset: (BooxGridPreset) -> Unit,
    onSelectFlow: (BooxTraversalFlow) -> Unit,
    onToggleStudioEdit: () -> Unit,
    onToggleZoomTier: () -> Unit,
    onTogglePanelsSpotlight: () -> Unit = {},
    onTogglePanelsSpread: () -> Unit = {},
    onTogglePanelsBleed: () -> Unit = {},
    onToggleKaleidoBoost: () -> Unit = {},
    onToggleMarginTrim: () -> Unit = {},
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
            label = if (state.kaleidoOpticalBoost) "Kaleido LUT: ON" else "Kaleido LUT: OFF",
            isSelected = state.kaleidoOpticalBoost,
            onClick = onToggleKaleidoBoost
        )

        EInkActionPill(
            label = if (state.autoTrimMargins) "Trim Margins: ON" else "Trim Margins",
            isSelected = state.autoTrimMargins,
            onClick = onToggleMarginTrim
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
    showOverlayBadges: Boolean = false,
    onStepPrev: () -> Unit,
    onStepNext: () -> Unit,
    onSelectBlock: (Int, Boolean) -> Unit,
    onUpdateColSplit: (Int, Float) -> Unit,
    onUpdateRowSplit: (Int, Float) -> Unit,
    onCommitStroke: (List<NormalizedInkPoint>, String) -> Unit,
    onCommitQuadHighlight: (NormalizedRect, String) -> Unit,
    onErasePoint: (Float, Float) -> Unit,
    onToggleReaderOverlay: () -> Unit = {}
) {
    val activeStrokePoints = remember { mutableStateListOf<NormalizedInkPoint>() }
    var quadDragStart by remember { mutableStateOf<Offset?>(null) }
    var quadDragCurrent by remember { mutableStateOf<Offset?>(null) }
    // Track last Wacom stylus activity timestamp to enforce automatic 850ms Palm Rejection cooldown
    var lastStylusEventMillis by remember { mutableLongStateOf(0L) }

    val blocks = state.computedBlocks
    val currentBlock = state.currentBlock
    val baseViewportRect = if (state.isZoomedToTier && state.gridPreset != BooxGridPreset.GRID_1X1) {
        currentBlock.redundantRect
    } else {
        NormalizedRect(0f, 0f, 1f, 1f)
    }
    val viewportRect = if (state.autoTrimMargins && !state.isZoomedToTier) {
        NormalizedRect(0.055f, 0.055f, 0.945f, 0.945f)
    } else {
        baseViewportRect
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

        val srcLeftPx = if (pageBitmap != null) {
            (viewportRect.left * pageBitmap.width).roundToInt().coerceIn(0, pageBitmap.width - 1)
        } else 0
        val srcTopPx = if (pageBitmap != null) {
            (viewportRect.top * pageBitmap.height).roundToInt().coerceIn(0, pageBitmap.height - 1)
        } else 0
        val srcWidthPx = if (pageBitmap != null) {
            (viewportRect.width * pageBitmap.width).roundToInt().coerceIn(1, pageBitmap.width - srcLeftPx)
        } else canvasWidthPx.roundToInt().coerceAtLeast(1)
        val srcHeightPx = if (pageBitmap != null) {
            (viewportRect.height * pageBitmap.height).roundToInt().coerceIn(1, pageBitmap.height - srcTopPx)
        } else canvasHeightPx.roundToInt().coerceAtLeast(1)

        val srcAspect = srcWidthPx.toFloat() / srcHeightPx.toFloat().coerceAtLeast(1f)
        val canvasAspect = canvasWidthPx / canvasHeightPx.coerceAtLeast(1f)
        val aspectDeltaRatio = kotlin.math.abs(srcAspect - canvasAspect) / canvasAspect.coerceAtLeast(0.01f)

        // When the page/quadrant aspect ratio matches the Note Air 4C's 3:4 screen (e.g. Full Page or 2x2 / 3x3),
        // fill 100.0% edge-to-edge. When viewing a 1x2 strip, 2x1 column, or rotated canvas, fit proportionally
        // so text and comic art are never stretched or distorted.
        val (dstLeftPx, dstTopPx, dstWidthPx, dstHeightPx) = if (aspectDeltaRatio <= 0.22f) {
            listOf(0f, 0f, canvasWidthPx, canvasHeightPx)
        } else {
            val scale = min(canvasWidthPx / srcWidthPx.toFloat(), canvasHeightPx / srcHeightPx.toFloat())
            val fitW = (srcWidthPx * scale).coerceAtLeast(1f)
            val fitH = (srcHeightPx * scale).coerceAtLeast(1f)
            val offX = (canvasWidthPx - fitW) * 0.5f
            val offY = (canvasHeightPx - fitH) * 0.5f
            listOf(offX, offY, fitW, fitH)
        }

        fun screenToPageNormalized(screenX: Float, screenY: Float): Pair<Float, Float> {
            val u = ((screenX - dstLeftPx) / dstWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
            val v = ((screenY - dstTopPx) / dstHeightPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
            val pageX = viewportRect.left + u * viewportRect.width
            val pageY = viewportRect.top + v * viewportRect.height
            return pageX.coerceIn(0f, 1f) to pageY.coerceIn(0f, 1f)
        }

        fun pageNormalizedToScreen(pageX: Float, pageY: Float): Offset {
            val u = (pageX - viewportRect.left) / viewportRect.width
            val v = (pageY - viewportRect.top) / viewportRect.height
            return Offset(dstLeftPx + u * dstWidthPx, dstTopPx + v * dstHeightPx)
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
                                    // Authentic Boox NeoReader V3 Tap Zones:
                                    // Left 24% -> Previous Panel/Page
                                    // Right 24% -> Next Panel/Page
                                    // Center 52% -> Toggle Floating NeoReader Overlay Menu
                                    tapXRatio < 0.24f -> onStepPrev()
                                    tapXRatio > 0.76f -> onStepNext()
                                    else -> onToggleReaderOverlay()
                                }
                            }
                            true
                        }
                    }
                }
        ) {
            if (pageBitmap != null) {
                val kaleidoLutFilter = if (state.kaleidoOpticalBoost) {
                    ColorFilter.colorMatrix(
                        ColorMatrix(
                            floatArrayOf(
                                1.12f, 0f, 0f, 0f, -6f,
                                0f, 1.12f, 0f, 0f, -6f,
                                0f, 0f, 1.12f, 0f, -6f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                } else {
                    null
                }

                drawImage(
                    image = pageBitmap.asImageBitmap(),
                    srcOffset = IntOffset(srcLeftPx, srcTopPx),
                    srcSize = IntSize(srcWidthPx, srcHeightPx),
                    dstOffset = IntOffset(dstLeftPx.roundToInt(), dstTopPx.roundToInt()),
                    dstSize = IntSize(dstWidthPx.roundToInt(), dstHeightPx.roundToInt()),
                    colorFilter = kaleidoLutFilter
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

                    // iOS Panels Guided Spotlight Dimming Mask over non-active comic panels
                    if (isPanelsMode && state.panelsSpotlightDimming && !isSelected) {
                        drawRect(
                            color = Color(0x99141417),
                            topLeft = tl,
                            size = panelSize
                        )
                    }

                    // In PDF mode, only draw NeoFlow quadrant boundaries when the Overlay or Studio Edit is active,
                    // keeping the 100% Full-Screen PDF page completely free of boxes when reading.
                    val shouldDrawFrame = showOverlayBadges || (isPanelsMode && state.panelsSpotlightDimming && isSelected)
                    if (shouldDrawFrame) {
                        drawRect(
                            color = when {
                                isSelected && isPanelsMode -> Color(0xFFFACC15)
                                isSelected -> EInkPureBlack
                                else -> EInkBorderGray
                            },
                            topLeft = tl,
                            size = panelSize,
                            style = Stroke(width = if (isSelected) 3f else 1.2f)
                        )
                    }
                }
            }
        }

        // Only show quadrant pill badges when the overlay is active (so full-screen book stays 100% clean)
        if (showOverlayBadges && !state.isZoomedToTier && state.gridPreset != BooxGridPreset.GRID_1X1) {
            blocks.forEach { block ->
                val isCurrent = block.stepIndex == state.activeStepIndex
                val tlScreen = pageNormalizedToScreen(block.baseRect.left, block.baseRect.top)
                val badgeX = with(density) { (tlScreen.x + 12f).toDp() }
                val badgeY = with(density) { (tlScreen.y + 12f).toDp() }

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
    }
}

/**
 * Whisper-Quiet 20.dp NeoReader V3 Micro Status Bar at the bottom edge of 100% Full-Screen Readers.
 */
@Composable
private fun BooxFullScreenMicroStatusBar(
    state: BooxReaderState,
    onClickStatusBar: () -> Unit
) {
    val isDarkBleed = state.workspaceMode == BooxWorkspaceMode.PANELS_COMICS && state.panelsDarkGutterBleed
    val barBg = if (isDarkBleed) Color(0xFF141417) else EInkPaperWhite
    val barText = if (isDarkBleed) Color(0xFF9CA3AF) else EInkMutedText
    val dividerColor = if (isDarkBleed) Color(0xFF27272A) else EInkBorderGray

    val totalSteps = max(1, state.computedBlocks.size)
    val statusMiddle = when (state.workspaceMode) {
        BooxWorkspaceMode.KINDLE_EPUB -> {
            "Ch ${state.epubChapterIndex + 1}/${state.activeEpubBook.chapters.size} · ~${state.currentEpubChapter.estimatedMinutes}m left · Tap center for menu"
        }
        BooxWorkspaceMode.PANELS_COMICS -> {
            "Page ${state.pageIndex + 1}/${state.pageCount} · Panel ${state.activeStepIndex + 1}/$totalSteps · Tap center for menu"
        }
        else -> {
            "Page ${state.pageIndex + 1}/${state.pageCount} · Tier ${state.currentBlock.label} (${state.activeStepIndex + 1}/$totalSteps) · Tap center for menu"
        }
    }
    val progressPct = when (state.workspaceMode) {
        BooxWorkspaceMode.KINDLE_EPUB -> {
            (((state.epubChapterIndex + 1).toFloat() / max(1, state.activeEpubBook.chapters.size).toFloat()) * 100).roundToInt()
        }
        else -> {
            (((state.activeStepIndex + 1).toFloat() / totalSteps.toFloat()) * 100).roundToInt()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(barBg)
            .clickable(onClick = onClickStatusBar)
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = dividerColor)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (state.workspaceMode == BooxWorkspaceMode.KINDLE_EPUB) state.activeEpubBook.title else state.documentTitle,
                color = barText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = statusMiddle,
                color = barText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif
            )
            Text(
                text = "${progressPct.coerceIn(1, 100)}%  ·  ${state.refreshMode.title}",
                color = barText,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

/**
 * Collapsible Floating Wacom Stylus Pill (Boox Notes 4.0 / NeoReader V3 Style).
 * Docks unobtrusively on the right edge as a tiny `✒` pill when collapsed, or expands into
 * a floating vertical stylus bar without shrinking the underlying full-screen document.
 */
@Composable
private fun BooxFloatingStylusEdgePill(
    state: BooxReaderState,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectTool: (BooxStylusTool) -> Unit,
    onSelectSwatch: (com.example.inksyncboox.theme.KaleidoInkSwatch) -> Unit,
    onTogglePalmShield: () -> Unit,
    onUndo: () -> Unit,
    onClearAnnotations: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isExpanded) {
        Surface(
            modifier = modifier
                .clickable(onClick = onToggleExpanded),
            shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 6.dp, bottomEnd = 6.dp),
            color = EInkPaperWhite,
            border = BorderStroke(1.5.dp, EInkPureBlack)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "✒",
                    color = EInkPureBlack,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(state.activeSwatch.color, CircleShape)
                        .border(1.dp, EInkPureBlack, CircleShape)
                )
            }
        }
    } else {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(24.dp),
            color = EInkPaperWhite,
            border = BorderStroke(1.5.dp, EInkPureBlack)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val toolGlyphs = listOf(
                    Triple(BooxStylusTool.NAVIGATE, "↖", "Nav"),
                    Triple(BooxStylusTool.FOUNTAIN_PEN, "✒", "Pen"),
                    Triple(BooxStylusTool.KALEIDO_HIGHLIGHTER, "🖍", "Mark"),
                    Triple(BooxStylusTool.QUAD_HIGHLIGHT, "▭", "Quad"),
                    Triple(BooxStylusTool.ERASER, "⌫", "Erase")
                )

                toolGlyphs.forEach { (tool, glyph, shortLabel) ->
                    val isSelected = state.activeStylusTool == tool
                    Surface(
                        modifier = Modifier
                            .size(width = 42.dp, height = 36.dp)
                            .clickable { onSelectTool(tool) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) EInkPureBlack else Color.Transparent
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = glyph,
                                color = if (isSelected) EInkPaperWhite else EInkPureBlack,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = shortLabel,
                                color = if (isSelected) EInkPaperWhite else EInkMutedText,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.width(28.dp),
                    thickness = 1.dp,
                    color = EInkBorderGray
                )

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

                HorizontalDivider(
                    modifier = Modifier.width(28.dp),
                    thickness = 1.dp,
                    color = EInkBorderGray
                )

                Surface(
                    modifier = Modifier
                        .size(width = 42.dp, height = 28.dp)
                        .clickable(onClick = onTogglePalmShield),
                    shape = RoundedCornerShape(8.dp),
                    color = if (state.palmShieldStylusOnly) EInkPureBlack else EInkSurfaceGray
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (state.palmShieldStylusOnly) "✋On" else "✋",
                            color = if (state.palmShieldStylusOnly) EInkPaperWhite else EInkPureBlack,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Text(
                    text = "↶",
                    color = EInkPureBlack,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(onClick = onUndo)
                        .padding(4.dp)
                )

                Surface(
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(onClick = onToggleExpanded),
                    shape = CircleShape,
                    color = EInkSurfaceGray
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "×",
                            color = EInkPureBlack,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private enum class NeoReaderOverlayTab(val title: String, val iconGlyph: String) {
    NAVIGATION("Navigation", "⊞"),
    PROGRESS("Progress", "⇹"),
    FORMAT("Format", "Aa"),
    CONTRAST("Contrast", "◐"),
    REFRESH("Refresh", "↻")
}

/**
 * Authentic Boox OS 4.0 / NeoReader V3 Center-Tap Floating Top & Bottom Tabbed Overlay Menu.
 * Appears only when the user taps the center of the full-screen reader; tapping anywhere on the uncovered page dismisses it.
 */
@Composable
private fun BooxFloatingNeoReaderOverlay(
    state: BooxReaderState,
    onDismissOverlay: () -> Unit,
    onReturnToLibrary: () -> Unit,
    onOpenStudyBinder: () -> Unit,
    onSelectWorkspaceMode: (BooxWorkspaceMode) -> Unit,
    onToggleCornellSplit: () -> Unit,
    onManualRegalFlash: () -> Unit,
    onOpenPdfClick: () -> Unit,
    onSelectPreset: (BooxGridPreset) -> Unit,
    onSelectFlow: (BooxTraversalFlow) -> Unit,
    onToggleStudioEdit: () -> Unit,
    onToggleZoomTier: () -> Unit,
    onTogglePanelsSpotlight: () -> Unit,
    onTogglePanelsSpread: () -> Unit,
    onTogglePanelsBleed: () -> Unit,
    onToggleKaleidoBoost: () -> Unit,
    onToggleMarginTrim: () -> Unit,
    onResetGridSplits: () -> Unit,
    onCycleEpubTypography: () -> Unit,
    onToggleEpubTwoColumn: () -> Unit,
    onCycleRefreshMode: () -> Unit,
    onToggleHighContrast: () -> Unit,
    onStepPrev: () -> Unit,
    onStepNext: () -> Unit
) {
    var activeTab by remember { mutableStateOf(NeoReaderOverlayTab.NAVIGATION) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 0. FULL-SCREEN TAP-TO-DISMISS SCRIM (Tapping anywhere on the uncovered book hides the overlay)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissOverlay
                )
        )

        // 1. TOP FLOATING NEOREADER V3 BAR
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color(0xF7FAF9F5))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    EInkActionPill(
                        label = "← Library",
                        isSelected = true,
                        onClick = onReturnToLibrary
                    )

                    Text(
                        text = if (state.workspaceMode == BooxWorkspaceMode.KINDLE_EPUB) state.activeEpubBook.title else state.documentTitle,
                        color = EInkPureBlack,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.SansSerif,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // Instant Reader Mode Switcher Pills
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        EInkActionPill(
                            label = "NeoFlow PDF",
                            isSelected = state.workspaceMode == BooxWorkspaceMode.PDF_NEOFLOW,
                            onClick = { onSelectWorkspaceMode(BooxWorkspaceMode.PDF_NEOFLOW) }
                        )
                        EInkActionPill(
                            label = "Panels Comic",
                            isSelected = state.workspaceMode == BooxWorkspaceMode.PANELS_COMICS,
                            onClick = { onSelectWorkspaceMode(BooxWorkspaceMode.PANELS_COMICS) }
                        )
                        EInkActionPill(
                            label = "Kindle EPUB",
                            isSelected = state.workspaceMode == BooxWorkspaceMode.KINDLE_EPUB,
                            onClick = { onSelectWorkspaceMode(BooxWorkspaceMode.KINDLE_EPUB) }
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    EInkActionPill(
                        label = if (state.showCornellSplitScratchpad) "◫ Split Notes: ON" else "◫ Split Notes",
                        isSelected = state.showCornellSplitScratchpad,
                        onClick = onToggleCornellSplit
                    )
                    EInkActionPill(
                        label = "✦ Binder (${state.studyCards.size})",
                        isSelected = state.workspaceMode == BooxWorkspaceMode.STUDY_BINDER,
                        onClick = onOpenStudyBinder
                    )
                    EInkActionPill(
                        label = "+ Open File",
                        isSelected = false,
                        onClick = onOpenPdfClick
                    )
                    EInkActionPill(
                        label = "Hide ✕",
                        isSelected = true,
                        onClick = onDismissOverlay
                    )
                }
            }
            HorizontalDivider(thickness = 1.5.dp, color = EInkPureBlack)
        }

        // 2. BOTTOM FLOATING NEOREADER V3 TABBED CONTROL SHEET (Boox Firmware V4.0 Architecture)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xF7FAF9F5))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            HorizontalDivider(thickness = 1.5.dp, color = EInkPureBlack)

            // Active Tab Content Tier
            when (activeTab) {
                NeoReaderOverlayTab.NAVIGATION -> {
                    if (state.workspaceMode == BooxWorkspaceMode.KINDLE_EPUB) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Chapter Navigation",
                                color = EInkMutedText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            EInkActionPill(label = "← Prev Chapter", isSelected = false, onClick = onStepPrev)
                            EInkActionPill(label = "Next Chapter →", isSelected = true, onClick = onStepNext)
                            EInkActionPill(
                                label = "Aa: ${state.epubTypographyPreset.label}",
                                isSelected = false,
                                onClick = onCycleEpubTypography
                            )
                            EInkActionPill(
                                label = if (state.epubTwoColumnMode) "2-Column Book Spread" else "1-Column Kindle",
                                isSelected = state.epubTwoColumnMode,
                                onClick = onToggleEpubTwoColumn
                            )
                        }
                    } else {
                        NeoFlowControlStrip(
                            state = state,
                            onSelectPreset = onSelectPreset,
                            onSelectFlow = onSelectFlow,
                            onToggleStudioEdit = onToggleStudioEdit,
                            onToggleZoomTier = onToggleZoomTier,
                            onTogglePanelsSpotlight = onTogglePanelsSpotlight,
                            onTogglePanelsSpread = onTogglePanelsSpread,
                            onTogglePanelsBleed = onTogglePanelsBleed,
                            onToggleKaleidoBoost = onToggleKaleidoBoost,
                            onToggleMarginTrim = onToggleMarginTrim,
                            onResetGridSplits = onResetGridSplits
                        )
                    }
                }

                NeoReaderOverlayTab.PROGRESS -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            EInkActionPill(label = "← Prev", isSelected = false, onClick = onStepPrev)
                            EInkActionPill(label = "Next →", isSelected = true, onClick = onStepNext)
                        }

                        val totalSteps = max(1, state.computedBlocks.size)
                        val progressFraction = ((state.activeStepIndex + 1).toFloat() / totalSteps.toFloat()).coerceIn(0.1f, 1f)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = if (state.workspaceMode == BooxWorkspaceMode.KINDLE_EPUB) {
                                    "Ch ${state.epubChapterIndex + 1}/${state.activeEpubBook.chapters.size}"
                                } else {
                                    "Page ${state.pageIndex + 1}/${state.pageCount} · Step ${state.activeStepIndex + 1}/$totalSteps"
                                },
                                color = EInkPureBlack,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Box(
                                modifier = Modifier
                                    .width(180.dp)
                                    .height(8.dp)
                                    .background(EInkBorderGray, RoundedCornerShape(50))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(progressFraction)
                                        .fillMaxHeight()
                                        .background(EInkPureBlack, RoundedCornerShape(50))
                                )
                            }
                        }
                    }
                }

                NeoReaderOverlayTab.FORMAT -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Format & Layout",
                            color = EInkMutedText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        EInkActionPill(
                            label = if (state.autoTrimMargins) "Trim Margins: ON (6.5%)" else "Trim Margins",
                            isSelected = state.autoTrimMargins,
                            onClick = onToggleMarginTrim
                        )
                        EInkActionPill(
                            label = if (state.panelsDoublePageSpread) "2-Page Spread (+1 Cover)" else "1-Page Fit",
                            isSelected = state.panelsDoublePageSpread,
                            onClick = onTogglePanelsSpread
                        )
                        EInkActionPill(
                            label = if (state.panelsDarkGutterBleed) "Bleed: Dark Ink" else "Bleed: Paper White",
                            isSelected = state.panelsDarkGutterBleed,
                            onClick = onTogglePanelsBleed
                        )
                        EInkActionPill(
                            label = "Aa: ${state.epubTypographyPreset.label}",
                            isSelected = false,
                            onClick = onCycleEpubTypography
                        )
                        EInkActionPill(
                            label = if (state.epubTwoColumnMode) "2-Column Book Spread" else "1-Column Kindle",
                            isSelected = state.epubTwoColumnMode,
                            onClick = onToggleEpubTwoColumn
                        )
                    }
                }

                NeoReaderOverlayTab.CONTRAST -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Kaleido 3 Optical Contrast",
                            color = EInkMutedText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        EInkActionPill(
                            label = if (state.kaleidoOpticalBoost) "Kaleido LUT: ON (1.12x Gain)" else "Kaleido LUT: OFF",
                            isSelected = state.kaleidoOpticalBoost,
                            onClick = onToggleKaleidoBoost
                        )
                        EInkActionPill(
                            label = if (state.highContrastBAndW) "300 PPI Pure B&W" else "150 PPI Kaleido 3 Color",
                            isSelected = state.highContrastBAndW,
                            onClick = onToggleHighContrast
                        )
                        EInkActionPill(
                            label = if (state.panelsSpotlightDimming) "Spotlight Dimming: ON" else "Spotlight Dimming: OFF",
                            isSelected = state.panelsSpotlightDimming,
                            onClick = onTogglePanelsSpotlight
                        )
                    }
                }

                NeoReaderOverlayTab.REFRESH -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Onyx EpdController",
                            color = EInkMutedText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        EInkActionPill(
                            label = "Waveform: ${state.refreshMode.title}",
                            isSelected = true,
                            onClick = onCycleRefreshMode
                        )
                        EInkActionPill(
                            label = "↻ Full GC16 Regal Flash",
                            isSelected = false,
                            onClick = onManualRegalFlash
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = EInkBorderGray)

            // Bottom NeoReader V3 5-Tab Bar + Quick Step Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NeoReaderOverlayTab.entries.forEach { tab ->
                        val selected = activeTab == tab
                        Surface(
                            modifier = Modifier.clickable { activeTab = tab },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) EInkPureBlack else Color.Transparent,
                            border = BorderStroke(1.dp, if (selected) EInkPureBlack else EInkBorderGray)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = tab.iconGlyph,
                                    color = if (selected) EInkPaperWhite else EInkPureBlack,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = tab.title,
                                    color = if (selected) EInkPaperWhite else EInkPureBlack,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EInkActionPill(
                        label = "← Prev",
                        isSelected = false,
                        onClick = onStepPrev
                    )
                    EInkActionPill(
                        label = "Next →",
                        isSelected = true,
                        onClick = onStepNext
                    )
                }
            }
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
