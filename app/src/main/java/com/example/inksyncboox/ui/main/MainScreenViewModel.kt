package com.example.inksyncboox.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inksyncboox.data.DataRepository
import com.example.inksyncboox.eink.BooxRefreshMode
import com.example.inksyncboox.epub.BooxEpubSampleCatalog
import com.example.inksyncboox.epub.EInkTypographyPreset
import com.example.inksyncboox.epub.EpubBookDocument
import com.example.inksyncboox.epub.EpubChapter
import com.example.inksyncboox.ink.BooxInkStroke
import com.example.inksyncboox.ink.BooxStylusTool
import com.example.inksyncboox.ink.NormalizedInkPoint
import com.example.inksyncboox.ink.QuadTextHighlight
import com.example.inksyncboox.neoflow.BooxGridPreset
import com.example.inksyncboox.neoflow.BooxSectionBlock
import com.example.inksyncboox.neoflow.BooxTraversalFlow
import com.example.inksyncboox.neoflow.NeoFlowEngine
import com.example.inksyncboox.neoflow.NormalizedRect
import com.example.inksyncboox.study.BooxWorkspaceMode
import com.example.inksyncboox.study.CornellScratchpadTemplate
import com.example.inksyncboox.study.UnifiedStudyCard
import com.example.inksyncboox.theme.BooxPenSwatches
import com.example.inksyncboox.theme.KaleidoAmberHighlight
import com.example.inksyncboox.theme.KaleidoCyanHighlight
import com.example.inksyncboox.theme.KaleidoInkSwatch
import com.example.inksyncboox.theme.KaleidoMintHighlight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.UUID
import kotlin.math.hypot

data class BooxReaderState(
    val workspaceMode: BooxWorkspaceMode = BooxWorkspaceMode.PDF_NEOFLOW,
    val documentTitle: String = "InkSync Boox · Note Air 4C Showcase.pdf",
    val pageIndex: Int = 0,
    val pageCount: Int = 2,
    val gridPreset: BooxGridPreset = BooxGridPreset.GRID_2X2,
    val traversalFlow: BooxTraversalFlow = BooxTraversalFlow.N_FLOW,
    val activeStepIndex: Int = 0,
    val isStudioGridEditMode: Boolean = false,
    val isZoomedToTier: Boolean = false,
    val colSplitRatios: List<Float> = listOf(0.5f),
    val rowSplitRatios: List<Float> = listOf(0.5f),
    val overlapRatio: Float = 0.08f,
    val customOverrides: Map<Int, NormalizedRect> = emptyMap(),
    val refreshMode: BooxRefreshMode = BooxRefreshMode.REGAL_HD,
    val autoRegalEveryNTurns: Int = 3,
    val turnsSinceLastRegal: Int = 0,
    val highContrastBAndW: Boolean = false,
    val palmShieldStylusOnly: Boolean = false,
    val showCornellSplitScratchpad: Boolean = false,
    val cornellTemplate: CornellScratchpadTemplate = CornellScratchpadTemplate.CORNELL_CUE_GRID,
    val activeStylusTool: BooxStylusTool = BooxStylusTool.NAVIGATE,
    val activeSwatch: KaleidoInkSwatch = BooxPenSwatches.first(),
    val strokeWidthPt: Float = 3.2f,
    val strokes: List<BooxInkStroke> = emptyList(),
    val cornellStrokes: List<BooxInkStroke> = emptyList(),
    val quadHighlights: List<QuadTextHighlight> = emptyList(),
    // Kindle EPUB Reflow State
    val activeEpubBook: EpubBookDocument = BooxEpubSampleCatalog.deepWorkOnEInkBook,
    val epubChapterIndex: Int = 0,
    val epubTypographyPreset: EInkTypographyPreset = EInkTypographyPreset.KINDLE_CLASSIC,
    val epubTwoColumnMode: Boolean = false,
    val epubHighlightedCfis: Map<String, KaleidoInkSwatch> = mapOf(
        "ch1_p3" to BooxPenSwatches.first { it.id == "amber_hl" },
        "ch2_p3" to BooxPenSwatches.first { it.id == "cyan_hl" }
    ),
    val epubMarginStrokesByCfi: Map<String, List<BooxInkStroke>> = emptyMap(),
    val showWelcomeBackContextBanner: Boolean = true,
    // Unified Study Hub Cards
    val studyCards: List<UnifiedStudyCard> = defaultSampleStudyCards(),
    val lastSidecarExportStatus: String = "Auto-Sidecar Ready (.inksync.json + .md)",
    val regalFlashTick: Int = 0,
    val lastStylusTelemetry: String = "Wacom EMR Ready · 4096 Levels",
    // iOS Panels-inspired Comic & Manga Studio State + Kaleido 3 Optical Enhancements
    val panelsSpotlightDimming: Boolean = true,
    val panelsDoublePageSpread: Boolean = false,
    val panelsDarkGutterBleed: Boolean = true,
    val kaleidoOpticalBoost: Boolean = true,
    val autoTrimMargins: Boolean = false
) {
    val computedBlocks: List<BooxSectionBlock>
        get() = NeoFlowEngine.computeBlocks(
            preset = gridPreset,
            flow = traversalFlow,
            colSplitRatios = colSplitRatios,
            rowSplitRatios = rowSplitRatios,
            overlapRatio = overlapRatio,
            customOverrides = customOverrides
        )

    val currentBlock: BooxSectionBlock
        get() {
            val blocks = computedBlocks
            return blocks.getOrElse(activeStepIndex.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))) {
                blocks.first()
            }
        }

    val currentPageStrokes: List<BooxInkStroke>
        get() = strokes.filter { it.pageIndex == pageIndex }

    val currentPageQuadHighlights: List<QuadTextHighlight>
        get() = quadHighlights.filter { it.pageIndex == pageIndex }

    val currentEpubChapter: EpubChapter
        get() = activeEpubBook.chapters.getOrElse(
            epubChapterIndex.coerceIn(0, activeEpubBook.chapters.lastIndex)
        ) { activeEpubBook.chapters.first() }
}

private fun defaultSampleStudyCards(): List<UnifiedStudyCard> = listOf(
    UnifiedStudyCard(
        id = "seed_card_1",
        sourceType = "EPUB",
        documentTitle = "The Sanctuary of Slow Reading",
        locatorBadge = "Ch 1 · ¶3 (ch1_p3)",
        pageOrChapterIndex = 0,
        tierOrParagraphIndex = 2,
        quoteText = "Once a page is set via a Regal waveform pass, zero power is consumed and only ambient room light reflects back to your eye.",
        reflectionCue = "Reflective Carta 1200 layer eliminates 120Hz OLED photonic fatigue.",
        swatchId = "amber_hl",
        colorHex = KaleidoAmberHighlight.value.toLong(),
        timestampLabel = "Saved in Sanctuary"
    ),
    UnifiedStudyCard(
        id = "seed_card_2",
        sourceType = "PDF",
        documentTitle = "InkSync Boox · Note Air 4C Showcase.pdf",
        locatorBadge = "Page 1 · Tier 1/4 · R1C1",
        pageOrChapterIndex = 0,
        tierOrParagraphIndex = 0,
        quoteText = "NeoFlow Smart Tiers partition any 2-column academic page into an M×N matrix with 8% Connection Redundancy so lines at boundaries are never clipped.",
        reflectionCue = "Zero horizontal pan in portrait = zero A2 ghosting.",
        swatchId = "mint_hl",
        colorHex = KaleidoMintHighlight.value.toLong(),
        timestampLabel = "Saved in Sanctuary"
    ),
    UnifiedStudyCard(
        id = "seed_card_3",
        sourceType = "EPUB",
        documentTitle = "The Sanctuary of Slow Reading",
        locatorBadge = "Ch 2 · ¶3 (ch2_p3)",
        pageOrChapterIndex = 1,
        tierOrParagraphIndex = 2,
        quoteText = "By anchoring Wacom EMR vector strokes and Cornell scratchpad cards directly to canonical paragraph identifiers (CFI), InkSync Boox restores marginalia.",
        reflectionCue = "CFI anchors keep handwritten notes aligned across font size changes.",
        swatchId = "cyan_hl",
        colorHex = KaleidoCyanHighlight.value.toLong(),
        timestampLabel = "Saved in Sanctuary"
    )
)

class MainScreenViewModel(dataRepository: DataRepository) : ViewModel() {

    val uiState: StateFlow<MainScreenUiState> =
        dataRepository.data
            .map<List<String>, MainScreenUiState>(MainScreenUiState::Success)
            .catch { emit(MainScreenUiState.Error(it)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainScreenUiState.Loading)

    private val _readerState = MutableStateFlow(BooxReaderState())
    val readerState: StateFlow<BooxReaderState> = _readerState.asStateFlow()

    private val _incomingFileUri = MutableStateFlow<android.net.Uri?>(null)
    val incomingFileUri: StateFlow<android.net.Uri?> = _incomingFileUri.asStateFlow()

    fun setIncomingFileUri(uri: android.net.Uri?) {
        _incomingFileUri.value = uri
    }

    fun selectWorkspaceMode(mode: BooxWorkspaceMode) {
        _readerState.update { state ->
            state.copy(
                workspaceMode = mode,
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun dismissWelcomeBackBanner() {
        _readerState.update { it.copy(showWelcomeBackContextBanner = false) }
    }

    fun togglePalmShieldStylusOnly() {
        _readerState.update { state ->
            val enabled = !state.palmShieldStylusOnly
            state.copy(
                palmShieldStylusOnly = enabled,
                lastStylusTelemetry = if (enabled) {
                    "Palm Shield ACTIVE · Finger Page Turns Locked"
                } else {
                    "Palm Shield OFF · Finger + Stylus Active"
                }
            )
        }
    }

    fun toggleCornellSplitScratchpad() {
        _readerState.update { state ->
            state.copy(
                showCornellSplitScratchpad = !state.showCornellSplitScratchpad,
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun cycleCornellTemplate() {
        _readerState.update { state ->
            val templates = CornellScratchpadTemplate.entries
            val next = templates[(templates.indexOf(state.cornellTemplate) + 1) % templates.size]
            state.copy(cornellTemplate = next)
        }
    }

    fun commitCornellScratchpadStroke(points: List<NormalizedInkPoint>, telemetry: String) {
        if (points.size < 2) return
        _readerState.update { state ->
            val isHl = state.activeStylusTool == BooxStylusTool.KALEIDO_HIGHLIGHTER || state.activeSwatch.isHighlighter
            val stroke = BooxInkStroke(
                id = UUID.randomUUID().toString(),
                pageIndex = state.pageIndex,
                points = points,
                colorHex = state.activeSwatch.color.value.toLong(),
                baseWidthPt = if (isHl) 16f else state.strokeWidthPt,
                isHighlighter = isHl
            )
            state.copy(
                cornellStrokes = state.cornellStrokes + stroke,
                lastStylusTelemetry = telemetry
            )
        }
    }

    fun clearCornellScratchpad() {
        _readerState.update { state ->
            state.copy(
                cornellStrokes = emptyList(),
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun updateDocumentMetadata(title: String, pageCount: Int, isCbz: Boolean = false) {
        _readerState.update { state ->
            state.copy(
                workspaceMode = if (isCbz) BooxWorkspaceMode.PANELS_COMICS else BooxWorkspaceMode.PDF_NEOFLOW,
                documentTitle = title,
                pageCount = pageCount.coerceAtLeast(1),
                pageIndex = 0,
                activeStepIndex = 0,
                traversalFlow = if (isCbz) BooxTraversalFlow.REVERSE_Z else state.traversalFlow,
                lastStylusTelemetry = if (isCbz) {
                    "Panels Studio · $title ($pageCount pages) · Guided Spotlight RTL"
                } else {
                    "Loaded Real .PDF ($pageCount pages) · NeoFlow Matrix Ready"
                }
            )
        }
    }

    fun togglePanelsSpotlightDimming() {
        _readerState.update { state ->
            state.copy(panelsSpotlightDimming = !state.panelsSpotlightDimming)
        }
    }

    fun togglePanelsDoublePageSpread() {
        _readerState.update { state ->
            state.copy(
                panelsDoublePageSpread = !state.panelsDoublePageSpread,
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun togglePanelsDarkGutterBleed() {
        _readerState.update { state ->
            state.copy(panelsDarkGutterBleed = !state.panelsDarkGutterBleed)
        }
    }

    fun toggleKaleidoOpticalBoost() {
        _readerState.update { state ->
            val enabled = !state.kaleidoOpticalBoost
            state.copy(
                kaleidoOpticalBoost = enabled,
                regalFlashTick = state.regalFlashTick + 1,
                lastStylusTelemetry = if (enabled) {
                    "Kaleido 3 Optical LUT ON · White-Point Clip + Shadow Lift"
                } else {
                    "Kaleido 3 Optical LUT OFF · Native Gamma"
                }
            )
        }
    }

    fun toggleAutoTrimMargins() {
        _readerState.update { state ->
            val enabled = !state.autoTrimMargins
            state.copy(
                autoTrimMargins = enabled,
                regalFlashTick = state.regalFlashTick + 1,
                lastStylusTelemetry = if (enabled) {
                    "Auto-Margin Trim ON (6.5% Border Crop)"
                } else {
                    "Auto-Margin Trim OFF (Full Bleed)"
                }
            )
        }
    }

    fun loadImportedEpubBook(book: EpubBookDocument) {
        _readerState.update { state ->
            state.copy(
                workspaceMode = BooxWorkspaceMode.KINDLE_EPUB,
                activeEpubBook = book,
                epubChapterIndex = 0,
                regalFlashTick = state.regalFlashTick + 1,
                lastStylusTelemetry = "Loaded Real .EPUB (${book.chapters.size} chapters) · 300 PPI Reflow"
            )
        }
    }

    fun selectGridPreset(preset: BooxGridPreset) {
        val defaultColSplits = if (preset.columns <= 1) emptyList() else List(preset.columns - 1) { i ->
            (i + 1).toFloat() / preset.columns.toFloat()
        }
        val defaultRowSplits = if (preset.rows <= 1) emptyList() else List(preset.rows - 1) { i ->
            (i + 1).toFloat() / preset.rows.toFloat()
        }
        _readerState.update { state ->
            state.copy(
                gridPreset = preset,
                colSplitRatios = defaultColSplits,
                rowSplitRatios = defaultRowSplits,
                activeStepIndex = 0,
                customOverrides = emptyMap(),
                isZoomedToTier = preset != BooxGridPreset.GRID_1X1,
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun selectTraversalFlow(flow: BooxTraversalFlow) {
        _readerState.update { state ->
            state.copy(
                traversalFlow = flow,
                activeStepIndex = 0,
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun toggleStudioGridEditMode() {
        _readerState.update { state ->
            val enteringStudio = !state.isStudioGridEditMode
            state.copy(
                isStudioGridEditMode = enteringStudio,
                isZoomedToTier = if (enteringStudio) false else state.isZoomedToTier,
                refreshMode = if (enteringStudio) BooxRefreshMode.FAST_SPEED else BooxRefreshMode.REGAL_HD
            )
        }
    }

    fun toggleZoomToActiveTier() {
        _readerState.update { state ->
            state.copy(
                isZoomedToTier = !state.isZoomedToTier,
                isStudioGridEditMode = false,
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun stepForwardTierOrPage() {
        _readerState.update { state ->
            if (state.workspaceMode == BooxWorkspaceMode.KINDLE_EPUB) {
                val maxCh = state.activeEpubBook.chapters.lastIndex
                val nextCh = (state.epubChapterIndex + 1).coerceAtMost(maxCh)
                return@update state.copy(
                    epubChapterIndex = nextCh,
                    regalFlashTick = state.regalFlashTick + 1
                )
            }
            val totalBlocks = state.computedBlocks.size
            val nextTurns = state.turnsSinceLastRegal + 1
            val shouldFlash = nextTurns >= state.autoRegalEveryNTurns
            if (state.activeStepIndex + 1 < totalBlocks) {
                state.copy(
                    activeStepIndex = state.activeStepIndex + 1,
                    turnsSinceLastRegal = if (shouldFlash) 0 else nextTurns,
                    regalFlashTick = if (shouldFlash) state.regalFlashTick + 1 else state.regalFlashTick
                )
            } else if (state.pageIndex + 1 < state.pageCount) {
                state.copy(
                    pageIndex = state.pageIndex + 1,
                    activeStepIndex = 0,
                    turnsSinceLastRegal = 0,
                    regalFlashTick = state.regalFlashTick + 1
                )
            } else {
                state
            }
        }
    }

    fun stepBackwardTierOrPage() {
        _readerState.update { state ->
            if (state.workspaceMode == BooxWorkspaceMode.KINDLE_EPUB) {
                val prevCh = (state.epubChapterIndex - 1).coerceAtLeast(0)
                return@update state.copy(
                    epubChapterIndex = prevCh,
                    regalFlashTick = state.regalFlashTick + 1
                )
            }
            if (state.activeStepIndex > 0) {
                state.copy(
                    activeStepIndex = state.activeStepIndex - 1,
                    regalFlashTick = state.regalFlashTick + 1
                )
            } else if (state.pageIndex > 0) {
                val prevPage = state.pageIndex - 1
                val lastBlockIndex = (state.computedBlocks.size - 1).coerceAtLeast(0)
                state.copy(
                    pageIndex = prevPage,
                    activeStepIndex = lastBlockIndex,
                    regalFlashTick = state.regalFlashTick + 1
                )
            } else {
                state
            }
        }
    }

    fun selectBlockStep(stepIndex: Int, zoomImmediately: Boolean = false) {
        _readerState.update { state ->
            val clamped = stepIndex.coerceIn(0, (state.computedBlocks.size - 1).coerceAtLeast(0))
            state.copy(
                activeStepIndex = clamped,
                isZoomedToTier = if (zoomImmediately) true else state.isZoomedToTier,
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun updateColSplitRatio(index: Int, newRatio: Float) {
        _readerState.update { state ->
            val updated = state.colSplitRatios.toMutableList()
            if (index in updated.indices) {
                updated[index] = newRatio.coerceIn(0.15f, 0.85f)
            }
            state.copy(colSplitRatios = updated)
        }
    }

    fun updateRowSplitRatio(index: Int, newRatio: Float) {
        _readerState.update { state ->
            val updated = state.rowSplitRatios.toMutableList()
            if (index in updated.indices) {
                updated[index] = newRatio.coerceIn(0.15f, 0.85f)
            }
            state.copy(rowSplitRatios = updated)
        }
    }

    fun resetGridSplitsAndOverrides() {
        _readerState.update { state ->
            val preset = state.gridPreset
            val defaultCols = if (preset.columns <= 1) emptyList() else List(preset.columns - 1) { i ->
                (i + 1).toFloat() / preset.columns.toFloat()
            }
            val defaultRows = if (preset.rows <= 1) emptyList() else List(preset.rows - 1) { i ->
                (i + 1).toFloat() / preset.rows.toFloat()
            }
            state.copy(
                colSplitRatios = defaultCols,
                rowSplitRatios = defaultRows,
                customOverrides = emptyMap(),
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun selectStylusTool(tool: BooxStylusTool) {
        _readerState.update { state ->
            val targetSwatch = when (tool) {
                BooxStylusTool.KALEIDO_HIGHLIGHTER, BooxStylusTool.QUAD_HIGHLIGHT -> {
                    if (state.activeSwatch.isHighlighter) state.activeSwatch
                    else BooxPenSwatches.first { it.isHighlighter }
                }
                BooxStylusTool.FOUNTAIN_PEN -> {
                    if (!state.activeSwatch.isHighlighter) state.activeSwatch
                    else BooxPenSwatches.first { !it.isHighlighter }
                }
                else -> state.activeSwatch
            }
            state.copy(
                activeStylusTool = tool,
                activeSwatch = targetSwatch,
                refreshMode = if (tool == BooxStylusTool.FOUNTAIN_PEN || tool == BooxStylusTool.KALEIDO_HIGHLIGHTER) {
                    BooxRefreshMode.A2_ULTRA
                } else {
                    BooxRefreshMode.REGAL_HD
                }
            )
        }
    }

    fun selectInkSwatch(swatch: KaleidoInkSwatch) {
        _readerState.update { state ->
            val nextTool = if (swatch.isHighlighter && state.activeStylusTool == BooxStylusTool.FOUNTAIN_PEN) {
                BooxStylusTool.KALEIDO_HIGHLIGHTER
            } else if (!swatch.isHighlighter && state.activeStylusTool == BooxStylusTool.KALEIDO_HIGHLIGHTER) {
                BooxStylusTool.FOUNTAIN_PEN
            } else {
                state.activeStylusTool
            }
            state.copy(activeSwatch = swatch, activeStylusTool = nextTool)
        }
    }

    fun commitInkStroke(points: List<NormalizedInkPoint>, telemetry: String) {
        if (points.size < 2) return
        _readerState.update { state ->
            val isHl = state.activeStylusTool == BooxStylusTool.KALEIDO_HIGHLIGHTER || state.activeSwatch.isHighlighter
            val stroke = BooxInkStroke(
                id = UUID.randomUUID().toString(),
                pageIndex = state.pageIndex,
                points = points,
                colorHex = state.activeSwatch.color.value.toLong(),
                baseWidthPt = if (isHl) 18f else state.strokeWidthPt,
                isHighlighter = isHl
            )
            state.copy(
                strokes = state.strokes + stroke,
                lastStylusTelemetry = telemetry
            )
        }
    }

    fun commitQuadHighlight(bounds: NormalizedRect, telemetry: String) {
        _readerState.update { state ->
            val hlSwatch = if (state.activeSwatch.isHighlighter) {
                state.activeSwatch
            } else {
                BooxPenSwatches.first { it.isHighlighter }
            }
            val locator = "Page ${state.pageIndex + 1} · ${state.currentBlock.label}"
            val item = QuadTextHighlight(
                id = UUID.randomUUID().toString(),
                pageIndex = state.pageIndex,
                bounds = bounds,
                snippet = locator,
                colorHex = hlSwatch.color.value.toLong()
            )
            val newStudyCard = UnifiedStudyCard(
                id = item.id,
                sourceType = "PDF",
                documentTitle = state.documentTitle,
                locatorBadge = locator,
                pageOrChapterIndex = state.pageIndex,
                tierOrParagraphIndex = state.activeStepIndex,
                quoteText = "Quad-Point Passage Captured in ${state.currentBlock.label} ([${(bounds.left * 100).toInt()}%, ${(bounds.top * 100).toInt()}%])",
                reflectionCue = "Pinned automatically from PDF NeoFlow Matrix",
                swatchId = hlSwatch.id,
                colorHex = hlSwatch.color.value.toLong(),
                timestampLabel = "Just now"
            )
            state.copy(
                quadHighlights = state.quadHighlights + item,
                studyCards = listOf(newStudyCard) + state.studyCards,
                lastStylusTelemetry = telemetry
            )
        }
    }

    // Kindle EPUB Reflow Actions
    fun cycleEpubTypographyPreset() {
        _readerState.update { state ->
            val presets = EInkTypographyPreset.entries
            val next = presets[(presets.indexOf(state.epubTypographyPreset) + 1) % presets.size]
            state.copy(
                epubTypographyPreset = next,
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun toggleEpubTwoColumnMode() {
        _readerState.update { state ->
            state.copy(
                epubTwoColumnMode = !state.epubTwoColumnMode,
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun toggleEpubParagraphHighlight(cfiId: String, paragraphText: String, chapterIndex: Int, paragraphIndex: Int) {
        _readerState.update { state ->
            val hlSwatch = if (state.activeSwatch.isHighlighter) {
                state.activeSwatch
            } else {
                BooxPenSwatches.first { it.isHighlighter }
            }
            val updatedMap = state.epubHighlightedCfis.toMutableMap()
            val updatedCards = state.studyCards.toMutableList()
            if (updatedMap.containsKey(cfiId)) {
                updatedMap.remove(cfiId)
                updatedCards.removeAll { it.id == "epub_$cfiId" }
            } else {
                updatedMap[cfiId] = hlSwatch
                updatedCards.add(
                    0,
                    UnifiedStudyCard(
                        id = "epub_$cfiId",
                        sourceType = "EPUB",
                        documentTitle = state.activeEpubBook.title,
                        locatorBadge = "Ch ${chapterIndex + 1} · ¶${paragraphIndex + 1} ($cfiId)",
                        pageOrChapterIndex = chapterIndex,
                        tierOrParagraphIndex = paragraphIndex,
                        quoteText = paragraphText,
                        reflectionCue = "CFI-Anchored Kindle Highlight · Ready for Cornell Notes",
                        swatchId = hlSwatch.id,
                        colorHex = hlSwatch.color.value.toLong(),
                        timestampLabel = "Just now"
                    )
                )
            }
            state.copy(
                epubHighlightedCfis = updatedMap,
                studyCards = updatedCards,
                lastStylusTelemetry = "EPUB CFI Anchor ($cfiId) · ${hlSwatch.label}"
            )
        }
    }

    fun jumpToStudyCardOrigin(card: UnifiedStudyCard) {
        _readerState.update { state ->
            if (card.sourceType == "EPUB") {
                state.copy(
                    workspaceMode = BooxWorkspaceMode.KINDLE_EPUB,
                    epubChapterIndex = card.pageOrChapterIndex.coerceIn(0, state.activeEpubBook.chapters.lastIndex),
                    regalFlashTick = state.regalFlashTick + 1
                )
            } else {
                state.copy(
                    workspaceMode = BooxWorkspaceMode.PDF_NEOFLOW,
                    pageIndex = card.pageOrChapterIndex.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)),
                    activeStepIndex = card.tierOrParagraphIndex.coerceIn(0, (state.computedBlocks.size - 1).coerceAtLeast(0)),
                    regalFlashTick = state.regalFlashTick + 1
                )
            }
        }
    }

    fun updateSidecarExportStatus(message: String) {
        _readerState.update { it.copy(lastSidecarExportStatus = message) }
    }

    fun eraseAtNormalizedPoint(x: Float, y: Float, radius: Float = 0.035f) {
        _readerState.update { state ->
            val page = state.pageIndex
            val remainingStrokes = state.strokes.filterNot { stroke ->
                stroke.pageIndex == page && stroke.points.any { pt ->
                    hypot(pt.x - x, pt.y - y) <= radius
                }
            }
            val remainingQuads = state.quadHighlights.filterNot { quad ->
                quad.pageIndex == page &&
                    x in quad.bounds.left..quad.bounds.right &&
                    y in quad.bounds.top..quad.bounds.bottom
            }
            state.copy(
                strokes = remainingStrokes,
                quadHighlights = remainingQuads
            )
        }
    }

    fun undoLastAnnotation() {
        _readerState.update { state ->
            if (state.strokes.isNotEmpty()) {
                state.copy(strokes = state.strokes.dropLast(1))
            } else if (state.cornellStrokes.isNotEmpty()) {
                state.copy(cornellStrokes = state.cornellStrokes.dropLast(1))
            } else if (state.quadHighlights.isNotEmpty()) {
                state.copy(quadHighlights = state.quadHighlights.dropLast(1))
            } else {
                state
            }
        }
    }

    fun clearPageAnnotations() {
        _readerState.update { state ->
            val page = state.pageIndex
            state.copy(
                strokes = state.strokes.filterNot { it.pageIndex == page },
                quadHighlights = state.quadHighlights.filterNot { it.pageIndex == page },
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun cycleRefreshMode() {
        _readerState.update { state ->
            val modes = BooxRefreshMode.entries
            val next = modes[(modes.indexOf(state.refreshMode) + 1) % modes.size]
            state.copy(
                refreshMode = next,
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun triggerManualRegalRefresh() {
        _readerState.update { state ->
            state.copy(
                refreshMode = BooxRefreshMode.REGAL_HD,
                turnsSinceLastRegal = 0,
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun toggleHighContrastBAndW() {
        _readerState.update { state ->
            state.copy(
                highContrastBAndW = !state.highContrastBAndW,
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }
}

sealed interface MainScreenUiState {
    object Loading : MainScreenUiState
    data class Error(val throwable: Throwable) : MainScreenUiState
    data class Success(val data: List<String>) : MainScreenUiState
}
