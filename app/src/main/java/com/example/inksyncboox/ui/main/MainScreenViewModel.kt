package com.example.inksyncboox.ui.main

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inksyncboox.data.DocKind
import com.example.inksyncboox.data.DocumentAnnotations
import com.example.inksyncboox.data.ReaderSettings
import com.example.inksyncboox.data.ReaderStore
import com.example.inksyncboox.data.RecentDocument
import com.example.inksyncboox.data.documentKey
import com.example.inksyncboox.epub.EInkTypographyPreset
import com.example.inksyncboox.epub.EpubAnchor
import com.example.inksyncboox.epub.EpubBookDocument
import com.example.inksyncboox.epub.EpubChapter
import com.example.inksyncboox.epub.EpubPaging
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
import com.example.inksyncboox.theme.KaleidoInkSwatch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.hypot

/** The document currently open in a reader. */
data class OpenDocument(
    val uri: String,
    val title: String,
    val kind: DocKind
) {
    val key: String get() = documentKey(uri)
}

/** Pages wider than this (width / height) are treated as two-page spreads. */
private const val SPREAD_ASPECT = 1.05f

data class BooxReaderState(
    val workspaceMode: BooxWorkspaceMode = BooxWorkspaceMode.LIBRARY,
    val document: OpenDocument? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val settings: ReaderSettings = ReaderSettings(),
    val recentDocuments: List<RecentDocument> = emptyList(),

    // Fixed-page documents (PDF / CBZ)
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val gridPreset: BooxGridPreset = BooxGridPreset.GRID_1X1,
    val traversalFlow: BooxTraversalFlow = BooxTraversalFlow.Z_FLOW,
    val activeStepIndex: Int = 0,
    val isStudioGridEditMode: Boolean = false,
    val isZoomedToTier: Boolean = false,
    val colSplitRatios: List<Float> = emptyList(),
    val rowSplitRatios: List<Float> = emptyList(),
    val overlapRatio: Float = 0.08f,
    val customOverrides: Map<Int, NormalizedRect> = emptyMap(),
    /** Width / height of every page, known when the document opens (used to detect spreads). */
    val pageAspects: List<Float> = emptyList(),

    // EPUB
    val activeEpubBook: EpubBookDocument? = null,
    val epubChapterIndex: Int = 0,
    val epubAnchor: EpubAnchor = EpubAnchor.START,
    /** Start of each page of the current chapter, reported by the reader after layout. */
    val epubPageStarts: List<EpubAnchor> = emptyList(),

    // Pen, highlights and notes for the open document
    val palmShieldStylusOnly: Boolean = false,
    val showCornellSplitScratchpad: Boolean = false,
    val cornellTemplate: CornellScratchpadTemplate = CornellScratchpadTemplate.CORNELL_CUE_GRID,
    val activeStylusTool: BooxStylusTool = BooxStylusTool.NAVIGATE,
    val activeSwatch: KaleidoInkSwatch = BooxPenSwatches.first(),
    val strokeWidthPt: Float = 3.2f,
    val strokes: List<BooxInkStroke> = emptyList(),
    val cornellStrokes: List<BooxInkStroke> = emptyList(),
    val quadHighlights: List<QuadTextHighlight> = emptyList(),
    val epubHighlightedCfis: Map<String, KaleidoInkSwatch> = emptyMap(),
    val studyCards: List<UnifiedStudyCard> = emptyList(),

    val lastExportStatus: String? = null,
    /** Incremented whenever the screen should get a full e-ink flash. */
    val regalFlashTick: Int = 0,
    val turnsSinceLastRegal: Int = 0
) {
    /** A wide two-page spread that is shown as two screens (comics only, whole-page mode). */
    fun isSplitSpread(page: Int): Boolean =
        settings.splitSpreads &&
            workspaceMode == BooxWorkspaceMode.PANELS_COMICS &&
            gridPreset == BooxGridPreset.GRID_1X1 &&
            (pageAspects.getOrNull(page) ?: 0f) > SPREAD_ASPECT

    /** Reading steps for [page], in content (cropped) coordinates. */
    fun blocksFor(page: Int): List<BooxSectionBlock> =
        if (isSplitSpread(page)) {
            // Left and right halves; manga reads the right half first.
            NeoFlowEngine.computeBlocks(
                preset = BooxGridPreset.GRID_2X1,
                flow = if (settings.rightToLeft) BooxTraversalFlow.REVERSE_Z else BooxTraversalFlow.Z_FLOW,
                colSplitRatios = listOf(0.5f),
                rowSplitRatios = emptyList(),
                overlapRatio = 0f,
                marginInset = 0f
            )
        } else {
            NeoFlowEngine.computeBlocks(
                preset = gridPreset,
                flow = traversalFlow,
                colSplitRatios = colSplitRatios,
                rowSplitRatios = rowSplitRatios,
                overlapRatio = overlapRatio,
                customOverrides = customOverrides
            )
        }

    /** Whether [page] is shown one block at a time (a zoomed tier or half of a spread). */
    fun isZoomedOn(page: Int): Boolean =
        isSplitSpread(page) || (isZoomedToTier && gridPreset != BooxGridPreset.GRID_1X1)

    /** Part of the content area shown for ([page], [step]), before cropping is applied. */
    fun contentRegionFor(page: Int, step: Int): NormalizedRect {
        if (!isZoomedOn(page)) return NormalizedRect(0f, 0f, 1f, 1f)
        val blocks = blocksFor(page)
        return blocks[step.coerceIn(0, blocks.lastIndex)].redundantRect
    }

    /** The next [count] (page, step) views after the current one, for preloading. */
    fun upcomingViews(count: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var page = pageIndex
        var step = activeStepIndex
        while (out.size < count) {
            if (step + 1 < blocksFor(page).size) {
                step++
            } else if (page + 1 < pageCount) {
                page++
                step = 0
            } else {
                break
            }
            out += page to step
        }
        return out
    }

    val computedBlocks: List<BooxSectionBlock>
        get() = blocksFor(pageIndex)

    val currentBlock: BooxSectionBlock
        get() {
            val blocks = computedBlocks
            return blocks[activeStepIndex.coerceIn(0, blocks.lastIndex)]
        }

    val currentPageStrokes: List<BooxInkStroke>
        get() = strokes.filter { it.pageIndex == pageIndex }

    val currentPageQuadHighlights: List<QuadTextHighlight>
        get() = quadHighlights.filter { it.pageIndex == pageIndex }

    val currentEpubChapter: EpubChapter?
        get() = activeEpubBook?.chapters?.getOrNull(epubChapterIndex)

    val epubPageIndex: Int
        get() = EpubPaging.pageIndexFor(epubPageStarts, epubAnchor)

    val isReaderMode: Boolean
        get() = workspaceMode == BooxWorkspaceMode.PDF_NEOFLOW ||
            workspaceMode == BooxWorkspaceMode.PANELS_COMICS ||
            workspaceMode == BooxWorkspaceMode.KINDLE_EPUB

    /** The reader that matches the open document, or null when nothing is open. */
    val readerModeForDocument: BooxWorkspaceMode?
        get() = when (document?.kind) {
            DocKind.EPUB -> BooxWorkspaceMode.KINDLE_EPUB
            DocKind.CBZ -> BooxWorkspaceMode.PANELS_COMICS
            DocKind.PDF -> if (workspaceMode == BooxWorkspaceMode.PANELS_COMICS) {
                BooxWorkspaceMode.PANELS_COMICS
            } else {
                BooxWorkspaceMode.PDF_NEOFLOW
            }
            null -> null
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainScreenViewModel(
    private val store: ReaderStore,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {

    // Single-threaded so writes to the same file never race.
    private val writeDispatcher = ioDispatcher.limitedParallelism(1)

    private val _readerState = MutableStateFlow(
        store.loadSettings().let { settings ->
            BooxReaderState(
                settings = settings,
                traversalFlow = if (settings.rightToLeft) BooxTraversalFlow.REVERSE_Z else BooxTraversalFlow.Z_FLOW,
                recentDocuments = store.loadRecents().sortedByDescending { it.lastOpenedMillis }
            )
        }
    )
    val readerState: StateFlow<BooxReaderState> = _readerState.asStateFlow()

    private val _pendingOpenUri = MutableStateFlow<Uri?>(null)
    /** A document the UI should open (from the picker, an intent, or the library). */
    val pendingOpenUri: StateFlow<Uri?> = _pendingOpenUri.asStateFlow()

    // ---------------------------------------------------------------------------------------
    // Opening documents
    // ---------------------------------------------------------------------------------------

    fun requestOpen(uri: Uri) {
        _pendingOpenUri.value = uri
    }

    /** Clears [uri] once it has been handled, unless a newer request replaced it meanwhile. */
    fun consumeOpenRequest(uri: Uri) {
        _pendingOpenUri.compareAndSet(uri, null)
    }

    /** Re-opens the most recent document on a cold start with no incoming file. */
    fun restoreLastDocumentIfIdle(parse: (String) -> Uri) {
        val state = _readerState.value
        if (state.document != null || _pendingOpenUri.value != null) return
        val last = state.recentDocuments.firstOrNull() ?: return
        _pendingOpenUri.value = parse(last.uri)
    }

    fun onOpenStarted() {
        _readerState.update { it.copy(isLoading = true, errorMessage = null) }
    }

    fun onOpenFailed(message: String) {
        _readerState.update {
            it.copy(
                isLoading = false,
                errorMessage = message,
                workspaceMode = if (it.document == null) BooxWorkspaceMode.LIBRARY else it.workspaceMode
            )
        }
    }

    fun dismissError() {
        _readerState.update { it.copy(errorMessage = null) }
    }

    fun onFixedDocumentOpened(
        uri: String,
        title: String,
        kind: DocKind,
        pageCount: Int,
        pageAspects: List<Float> = emptyList()
    ) {
        val doc = OpenDocument(uri, title, kind)
        val saved = recentFor(uri)
        val annotations = store.loadAnnotations(doc.key)
        _readerState.update { state ->
            state.withAnnotations(annotations).copy(
                document = doc,
                isLoading = false,
                errorMessage = null,
                workspaceMode = if (kind == DocKind.CBZ) BooxWorkspaceMode.PANELS_COMICS else BooxWorkspaceMode.PDF_NEOFLOW,
                pageCount = pageCount.coerceAtLeast(1),
                pageAspects = pageAspects,
                pageIndex = (saved?.pageIndex ?: 0).coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                activeStepIndex = 0,
                activeEpubBook = null,
                epubPageStarts = emptyList(),
                isStudioGridEditMode = false,
                regalFlashTick = state.regalFlashTick + 1,
                turnsSinceLastRegal = 0
            )
        }
        touchRecent()
    }

    fun onEpubOpened(uri: String, book: EpubBookDocument) {
        val doc = OpenDocument(uri, book.title, DocKind.EPUB)
        val saved = recentFor(uri)
        val annotations = store.loadAnnotations(doc.key)
        val chapter = (saved?.epubChapter ?: 0).coerceIn(0, book.chapters.lastIndex)
        _readerState.update { state ->
            state.withAnnotations(annotations).copy(
                document = doc,
                isLoading = false,
                errorMessage = null,
                workspaceMode = BooxWorkspaceMode.KINDLE_EPUB,
                activeEpubBook = book,
                pageAspects = emptyList(),
                epubChapterIndex = chapter,
                epubAnchor = if (saved != null) EpubAnchor(saved.epubParagraph, saved.epubOffset) else EpubAnchor.START,
                epubPageStarts = emptyList(),
                pageIndex = 0,
                pageCount = 0,
                regalFlashTick = state.regalFlashTick + 1,
                turnsSinceLastRegal = 0
            )
        }
        touchRecent()
    }

    private fun BooxReaderState.withAnnotations(a: DocumentAnnotations): BooxReaderState = copy(
        strokes = a.strokes,
        cornellStrokes = a.notesStrokes,
        quadHighlights = a.quadHighlights,
        epubHighlightedCfis = a.epubHighlights.mapNotNull { (cfi, swatchId) ->
            BooxPenSwatches.firstOrNull { it.id == swatchId }?.let { cfi to it }
        }.toMap(),
        studyCards = a.studyCards
    )

    private fun recentFor(uri: String): RecentDocument? =
        _readerState.value.recentDocuments.firstOrNull { it.uri == uri }

    /** Records the current document and position at the top of the recent list. */
    private fun touchRecent() {
        val state = _readerState.value
        val doc = state.document ?: return
        val anchor = state.epubAnchor.takeIf { it != EpubAnchor.END }
            ?: state.epubPageStarts.lastOrNull() ?: EpubAnchor.START
        val entry = RecentDocument(
            uri = doc.uri,
            title = doc.title,
            kind = doc.kind,
            lastOpenedMillis = clock(),
            pageIndex = state.pageIndex,
            pageCount = state.pageCount,
            epubChapter = state.epubChapterIndex,
            epubParagraph = anchor.paragraph,
            epubOffset = anchor.offset,
            epubChapterCount = state.activeEpubBook?.chapters?.size ?: 0
        )
        val updated = (listOf(entry) + state.recentDocuments.filterNot { it.uri == doc.uri }).take(MAX_RECENTS)
        _readerState.update { it.copy(recentDocuments = updated) }
        viewModelScope.launch(writeDispatcher) { store.saveRecents(updated) }
    }

    fun removeRecent(uri: String) {
        val updated = _readerState.value.recentDocuments.filterNot { it.uri == uri }
        _readerState.update { it.copy(recentDocuments = updated) }
        viewModelScope.launch(writeDispatcher) { store.saveRecents(updated) }
    }

    private fun persistAnnotations() {
        val state = _readerState.value
        val key = state.document?.key ?: return
        val snapshot = DocumentAnnotations(
            strokes = state.strokes,
            notesStrokes = state.cornellStrokes,
            quadHighlights = state.quadHighlights,
            epubHighlights = state.epubHighlightedCfis.mapValues { it.value.id },
            studyCards = state.studyCards
        )
        viewModelScope.launch(writeDispatcher) { store.saveAnnotations(key, snapshot) }
    }

    private fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        _readerState.update { it.copy(settings = transform(it.settings)) }
        val settings = _readerState.value.settings
        viewModelScope.launch(writeDispatcher) { store.saveSettings(settings) }
    }

    // ---------------------------------------------------------------------------------------
    // Screens
    // ---------------------------------------------------------------------------------------

    fun selectWorkspaceMode(mode: BooxWorkspaceMode) {
        _readerState.update { state ->
            val target = when (mode) {
                BooxWorkspaceMode.KINDLE_EPUB ->
                    if (state.document?.kind == DocKind.EPUB) mode else state.readerModeForDocument
                BooxWorkspaceMode.PDF_NEOFLOW, BooxWorkspaceMode.PANELS_COMICS ->
                    when (state.document?.kind) {
                        DocKind.PDF -> mode
                        DocKind.CBZ -> BooxWorkspaceMode.PANELS_COMICS
                        else -> state.readerModeForDocument
                    }
                else -> mode
            } ?: BooxWorkspaceMode.LIBRARY
            state.copy(workspaceMode = target, regalFlashTick = state.regalFlashTick + 1)
        }
    }

    /** Leaves the library or highlights list and returns to the open document, if any. */
    fun returnToReader() {
        _readerState.update { state ->
            state.copy(
                workspaceMode = state.readerModeForDocument ?: BooxWorkspaceMode.LIBRARY,
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun requestFullRefresh() {
        _readerState.update { it.copy(regalFlashTick = it.regalFlashTick + 1, turnsSinceLastRegal = 0) }
    }

    /** Counts a page turn and flashes the screen every N turns, per the user's setting. */
    private fun BooxReaderState.afterPageTurn(): BooxReaderState {
        val every = settings.fullRefreshEveryNTurns
        if (every <= 0) return copy(turnsSinceLastRegal = 0)
        val turns = turnsSinceLastRegal + 1
        return if (turns >= every) {
            copy(turnsSinceLastRegal = 0, regalFlashTick = regalFlashTick + 1)
        } else {
            copy(turnsSinceLastRegal = turns)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Page turning
    // ---------------------------------------------------------------------------------------

    fun stepForwardTierOrPage() {
        val before = _readerState.value
        _readerState.update { state ->
            if (state.workspaceMode == BooxWorkspaceMode.KINDLE_EPUB) {
                return@update state.stepEpub(forward = true)
            }
            if (state.pageCount <= 0) return@update state
            val totalBlocks = state.computedBlocks.size
            when {
                state.activeStepIndex + 1 < totalBlocks ->
                    state.copy(activeStepIndex = state.activeStepIndex + 1).afterPageTurn()
                state.pageIndex + 1 < state.pageCount ->
                    state.copy(pageIndex = state.pageIndex + 1, activeStepIndex = 0).afterPageTurn()
                else -> state
            }
        }
        onPositionChanged(before)
    }

    fun stepBackwardTierOrPage() {
        val before = _readerState.value
        _readerState.update { state ->
            if (state.workspaceMode == BooxWorkspaceMode.KINDLE_EPUB) {
                return@update state.stepEpub(forward = false)
            }
            if (state.pageCount <= 0) return@update state
            when {
                state.activeStepIndex > 0 ->
                    state.copy(activeStepIndex = state.activeStepIndex - 1).afterPageTurn()
                state.pageIndex > 0 -> state.copy(
                    pageIndex = state.pageIndex - 1,
                    activeStepIndex = (state.blocksFor(state.pageIndex - 1).size - 1).coerceAtLeast(0)
                ).afterPageTurn()
                else -> state
            }
        }
        onPositionChanged(before)
    }

    private fun BooxReaderState.stepEpub(forward: Boolean): BooxReaderState {
        val book = activeEpubBook ?: return this
        val step = if (forward) {
            EpubPaging.next(epubPageStarts, epubAnchor, epubChapterIndex, book.chapters.size)
        } else {
            EpubPaging.previous(epubPageStarts, epubAnchor, epubChapterIndex)
        }
        return when (step) {
            is EpubPaging.Step.SamePage -> copy(epubAnchor = step.anchor).afterPageTurn()
            is EpubPaging.Step.OtherChapter -> copy(
                epubChapterIndex = step.chapter,
                epubAnchor = step.anchor,
                epubPageStarts = emptyList()
            ).afterPageTurn()
            EpubPaging.Step.None -> this
        }
    }

    private fun onPositionChanged(before: BooxReaderState) {
        val after = _readerState.value
        if (after.pageIndex != before.pageIndex ||
            after.epubChapterIndex != before.epubChapterIndex ||
            after.epubAnchor != before.epubAnchor
        ) {
            touchRecent()
        }
    }

    fun goToPage(index: Int) {
        val before = _readerState.value
        _readerState.update { state ->
            if (state.pageCount <= 0) return@update state
            state.copy(
                pageIndex = index.coerceIn(0, state.pageCount - 1),
                activeStepIndex = 0,
                regalFlashTick = state.regalFlashTick + 1,
                turnsSinceLastRegal = 0
            )
        }
        onPositionChanged(before)
    }

    fun goToEpubChapter(index: Int) {
        val before = _readerState.value
        _readerState.update { state ->
            val book = state.activeEpubBook ?: return@update state
            state.copy(
                epubChapterIndex = index.coerceIn(0, book.chapters.lastIndex),
                epubAnchor = EpubAnchor.START,
                epubPageStarts = emptyList(),
                regalFlashTick = state.regalFlashTick + 1,
                turnsSinceLastRegal = 0
            )
        }
        onPositionChanged(before)
    }

    /**
     * Called by the EPUB reader after it lays out [chapter]. Snaps the reading position to the
     * start of the page that contains it, so changing font size keeps you in roughly the same place.
     */
    fun onEpubPagesComputed(chapter: Int, pageStarts: List<EpubAnchor>) {
        if (pageStarts.isEmpty()) return
        _readerState.update { state ->
            if (state.epubChapterIndex != chapter) return@update state
            val idx = EpubPaging.pageIndexFor(pageStarts, state.epubAnchor)
            state.copy(epubPageStarts = pageStarts, epubAnchor = pageStarts[idx])
        }
        touchRecent()
    }

    // ---------------------------------------------------------------------------------------
    // NeoFlow tiers and panels
    // ---------------------------------------------------------------------------------------

    private fun defaultSplits(count: Int): List<Float> =
        if (count <= 1) emptyList() else List(count - 1) { i -> (i + 1).toFloat() / count }

    fun selectGridPreset(preset: BooxGridPreset) {
        _readerState.update { state ->
            state.copy(
                gridPreset = preset,
                colSplitRatios = defaultSplits(preset.columns),
                rowSplitRatios = defaultSplits(preset.rows),
                activeStepIndex = 0,
                customOverrides = emptyMap(),
                isZoomedToTier = preset != BooxGridPreset.GRID_1X1,
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun selectTraversalFlow(flow: BooxTraversalFlow) {
        _readerState.update { state ->
            state.copy(traversalFlow = flow, activeStepIndex = 0, regalFlashTick = state.regalFlashTick + 1)
        }
    }

    fun toggleStudioGridEditMode() {
        _readerState.update { state ->
            val entering = !state.isStudioGridEditMode
            state.copy(
                isStudioGridEditMode = entering,
                isZoomedToTier = if (entering) false else state.isZoomedToTier,
                regalFlashTick = if (entering) state.regalFlashTick else state.regalFlashTick + 1
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

    fun selectBlockStep(stepIndex: Int, zoomImmediately: Boolean = false) {
        _readerState.update { state ->
            state.copy(
                activeStepIndex = stepIndex.coerceIn(0, (state.computedBlocks.size - 1).coerceAtLeast(0)),
                isZoomedToTier = zoomImmediately || state.isZoomedToTier,
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    fun updateColSplitRatio(index: Int, newRatio: Float) {
        _readerState.update { state ->
            val updated = state.colSplitRatios.toMutableList()
            if (index in updated.indices) updated[index] = newRatio.coerceIn(0.15f, 0.85f)
            state.copy(colSplitRatios = updated)
        }
    }

    fun updateRowSplitRatio(index: Int, newRatio: Float) {
        _readerState.update { state ->
            val updated = state.rowSplitRatios.toMutableList()
            if (index in updated.indices) updated[index] = newRatio.coerceIn(0.15f, 0.85f)
            state.copy(rowSplitRatios = updated)
        }
    }

    fun resetGridSplitsAndOverrides() {
        _readerState.update { state ->
            state.copy(
                colSplitRatios = defaultSplits(state.gridPreset.columns),
                rowSplitRatios = defaultSplits(state.gridPreset.rows),
                customOverrides = emptyMap(),
                regalFlashTick = state.regalFlashTick + 1
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------------

    fun cycleEpubTypographyPreset() = updateSettings { s ->
        val presets = EInkTypographyPreset.entries
        s.copy(typography = presets[(presets.indexOf(s.typography) + 1) % presets.size])
    }

    fun selectEpubTypographyPreset(preset: EInkTypographyPreset) = updateSettings { it.copy(typography = preset) }

    fun toggleEpubTwoColumnMode() = updateSettings { it.copy(epubTwoColumn = !it.epubTwoColumn) }

    fun setFullRefreshInterval(turns: Int) = updateSettings { it.copy(fullRefreshEveryNTurns = turns.coerceAtLeast(0)) }

    fun toggleContrastBoost() = updateSettings { it.copy(contrastBoost = !it.contrastBoost) }

    fun toggleAutoCrop() = updateSettings { it.copy(autoCrop = !it.autoCrop) }

    fun toggleSplitSpreads() {
        updateSettings { it.copy(splitSpreads = !it.splitSpreads) }
        _readerState.update { it.copy(activeStepIndex = 0) }
    }

    fun toggleGrayscale() = updateSettings { it.copy(grayscale = !it.grayscale) }

    fun setTapLayout(layout: TapLayout) = updateSettings { it.copy(tapLayout = layout) }

    fun togglePanelSpotlight() = updateSettings { it.copy(panelSpotlight = !it.panelSpotlight) }

    fun toggleDarkGutter() = updateSettings { it.copy(darkGutter = !it.darkGutter) }

    /** Switches page turning direction (manga) and mirrors the panel order to match. */
    fun toggleRightToLeft() {
        updateSettings { it.copy(rightToLeft = !it.rightToLeft) }
        _readerState.update { state ->
            val rtl = state.settings.rightToLeft
            val flow = when (state.traversalFlow) {
                BooxTraversalFlow.Z_FLOW -> if (rtl) BooxTraversalFlow.REVERSE_Z else BooxTraversalFlow.Z_FLOW
                BooxTraversalFlow.REVERSE_Z -> if (rtl) BooxTraversalFlow.REVERSE_Z else BooxTraversalFlow.Z_FLOW
                BooxTraversalFlow.N_FLOW -> if (rtl) BooxTraversalFlow.REVERSE_N else BooxTraversalFlow.N_FLOW
                BooxTraversalFlow.REVERSE_N -> if (rtl) BooxTraversalFlow.REVERSE_N else BooxTraversalFlow.N_FLOW
            }
            state.copy(traversalFlow = flow, activeStepIndex = 0)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Pen, highlights and notes
    // ---------------------------------------------------------------------------------------

    fun togglePalmShieldStylusOnly() {
        _readerState.update { it.copy(palmShieldStylusOnly = !it.palmShieldStylusOnly) }
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
            state.copy(cornellTemplate = templates[(templates.indexOf(state.cornellTemplate) + 1) % templates.size])
        }
    }

    fun commitCornellScratchpadStroke(points: List<NormalizedInkPoint>) {
        if (points.size < 2) return
        _readerState.update { state ->
            val isHl = state.activeSwatch.isHighlighter
            state.copy(
                cornellStrokes = state.cornellStrokes + BooxInkStroke(
                    id = UUID.randomUUID().toString(),
                    pageIndex = 0,
                    points = points,
                    colorHex = state.activeSwatch.color.value.toLong(),
                    baseWidthPt = if (isHl) 16f else state.strokeWidthPt,
                    isHighlighter = isHl
                )
            )
        }
        persistAnnotations()
    }

    fun clearCornellScratchpad() {
        _readerState.update { it.copy(cornellStrokes = emptyList(), regalFlashTick = it.regalFlashTick + 1) }
        persistAnnotations()
    }

    fun selectStylusTool(tool: BooxStylusTool) {
        _readerState.update { state ->
            val swatch = when (tool) {
                BooxStylusTool.KALEIDO_HIGHLIGHTER, BooxStylusTool.QUAD_HIGHLIGHT ->
                    if (state.activeSwatch.isHighlighter) state.activeSwatch else BooxPenSwatches.first { it.isHighlighter }
                BooxStylusTool.FOUNTAIN_PEN ->
                    if (!state.activeSwatch.isHighlighter) state.activeSwatch else BooxPenSwatches.first { !it.isHighlighter }
                else -> state.activeSwatch
            }
            state.copy(activeStylusTool = tool, activeSwatch = swatch)
        }
    }

    fun selectInkSwatch(swatch: KaleidoInkSwatch) {
        _readerState.update { state ->
            val tool = when {
                swatch.isHighlighter && state.activeStylusTool == BooxStylusTool.FOUNTAIN_PEN -> BooxStylusTool.KALEIDO_HIGHLIGHTER
                !swatch.isHighlighter && state.activeStylusTool == BooxStylusTool.KALEIDO_HIGHLIGHTER -> BooxStylusTool.FOUNTAIN_PEN
                else -> state.activeStylusTool
            }
            state.copy(activeSwatch = swatch, activeStylusTool = tool)
        }
    }

    fun commitInkStroke(points: List<NormalizedInkPoint>) {
        if (points.size < 2) return
        _readerState.update { state ->
            val isHl = state.activeStylusTool == BooxStylusTool.KALEIDO_HIGHLIGHTER || state.activeSwatch.isHighlighter
            state.copy(
                strokes = state.strokes + BooxInkStroke(
                    id = UUID.randomUUID().toString(),
                    pageIndex = state.pageIndex,
                    points = points,
                    colorHex = state.activeSwatch.color.value.toLong(),
                    baseWidthPt = if (isHl) 18f else state.strokeWidthPt,
                    isHighlighter = isHl
                )
            )
        }
        persistAnnotations()
    }

    fun commitQuadHighlight(bounds: NormalizedRect) {
        _readerState.update { state ->
            val swatch = if (state.activeSwatch.isHighlighter) state.activeSwatch else BooxPenSwatches.first { it.isHighlighter }
            val id = UUID.randomUUID().toString()
            val locator = "Page ${state.pageIndex + 1}"
            state.copy(
                quadHighlights = state.quadHighlights + QuadTextHighlight(
                    id = id,
                    pageIndex = state.pageIndex,
                    bounds = bounds,
                    snippet = locator,
                    colorHex = swatch.color.value.toLong()
                ),
                studyCards = listOf(
                    UnifiedStudyCard(
                        id = id,
                        sourceType = "PDF",
                        documentTitle = state.document?.title.orEmpty(),
                        locatorBadge = locator,
                        pageOrChapterIndex = state.pageIndex,
                        tierOrParagraphIndex = state.activeStepIndex,
                        quoteText = "Area highlight on page ${state.pageIndex + 1}",
                        swatchId = swatch.id,
                        colorHex = swatch.color.value.toLong(),
                        createdMillis = clock()
                    )
                ) + state.studyCards
            )
        }
        persistAnnotations()
    }

    fun toggleEpubParagraphHighlight(cfiId: String, paragraphText: String, chapterIndex: Int, paragraphIndex: Int) {
        _readerState.update { state ->
            val swatch = if (state.activeSwatch.isHighlighter) state.activeSwatch else BooxPenSwatches.first { it.isHighlighter }
            val highlights = state.epubHighlightedCfis.toMutableMap()
            val cards = state.studyCards.toMutableList()
            val cardId = "epub_$cfiId"
            if (highlights.remove(cfiId) != null) {
                cards.removeAll { it.id == cardId }
            } else {
                highlights[cfiId] = swatch
                cards.add(
                    0,
                    UnifiedStudyCard(
                        id = cardId,
                        sourceType = "EPUB",
                        documentTitle = state.activeEpubBook?.title.orEmpty(),
                        locatorBadge = state.activeEpubBook?.chapters?.getOrNull(chapterIndex)?.title
                            ?: "Chapter ${chapterIndex + 1}",
                        pageOrChapterIndex = chapterIndex,
                        tierOrParagraphIndex = paragraphIndex,
                        quoteText = paragraphText,
                        swatchId = swatch.id,
                        colorHex = swatch.color.value.toLong(),
                        createdMillis = clock()
                    )
                )
            }
            state.copy(epubHighlightedCfis = highlights, studyCards = cards)
        }
        persistAnnotations()
    }

    fun deleteStudyCard(card: UnifiedStudyCard) {
        _readerState.update { state ->
            state.copy(
                studyCards = state.studyCards.filterNot { it.id == card.id },
                quadHighlights = state.quadHighlights.filterNot { it.id == card.id },
                epubHighlightedCfis = if (card.id.startsWith("epub_")) {
                    state.epubHighlightedCfis - card.id.removePrefix("epub_")
                } else {
                    state.epubHighlightedCfis
                }
            )
        }
        persistAnnotations()
    }

    fun jumpToStudyCardOrigin(card: UnifiedStudyCard) {
        val before = _readerState.value
        _readerState.update { state ->
            val book = state.activeEpubBook
            if (card.sourceType == "EPUB" && book != null) {
                state.copy(
                    workspaceMode = BooxWorkspaceMode.KINDLE_EPUB,
                    epubChapterIndex = card.pageOrChapterIndex.coerceIn(0, book.chapters.lastIndex),
                    epubAnchor = EpubAnchor(card.tierOrParagraphIndex, 0),
                    epubPageStarts = emptyList(),
                    regalFlashTick = state.regalFlashTick + 1
                )
            } else if (state.pageCount > 0) {
                state.copy(
                    workspaceMode = state.readerModeForDocument ?: state.workspaceMode,
                    pageIndex = card.pageOrChapterIndex.coerceIn(0, state.pageCount - 1),
                    activeStepIndex = card.tierOrParagraphIndex.coerceIn(0, (state.computedBlocks.size - 1).coerceAtLeast(0)),
                    regalFlashTick = state.regalFlashTick + 1
                )
            } else {
                state
            }
        }
        onPositionChanged(before)
    }

    fun setExportStatus(message: String?) {
        _readerState.update { it.copy(lastExportStatus = message) }
    }

    fun eraseAtNormalizedPoint(x: Float, y: Float, radius: Float = 0.035f) {
        val before = _readerState.value
        _readerState.update { state ->
            val page = state.pageIndex
            state.copy(
                strokes = state.strokes.filterNot { stroke ->
                    stroke.pageIndex == page && stroke.points.any { hypot(it.x - x, it.y - y) <= radius }
                },
                quadHighlights = state.quadHighlights.filterNot { q ->
                    q.pageIndex == page && x in q.bounds.left..q.bounds.right && y in q.bounds.top..q.bounds.bottom
                }
            )
        }
        val after = _readerState.value
        if (after.strokes.size != before.strokes.size || after.quadHighlights.size != before.quadHighlights.size) {
            persistAnnotations()
        }
    }

    fun undoLastAnnotation() {
        _readerState.update { state ->
            when {
                state.strokes.isNotEmpty() -> state.copy(strokes = state.strokes.dropLast(1))
                state.cornellStrokes.isNotEmpty() -> state.copy(cornellStrokes = state.cornellStrokes.dropLast(1))
                state.quadHighlights.isNotEmpty() -> {
                    val last = state.quadHighlights.last()
                    state.copy(
                        quadHighlights = state.quadHighlights.dropLast(1),
                        studyCards = state.studyCards.filterNot { it.id == last.id }
                    )
                }
                else -> state
            }
        }
        persistAnnotations()
    }

    fun clearPageAnnotations() {
        _readerState.update { state ->
            val page = state.pageIndex
            val removedQuadIds = state.quadHighlights.filter { it.pageIndex == page }.map { it.id }.toSet()
            state.copy(
                strokes = state.strokes.filterNot { it.pageIndex == page },
                quadHighlights = state.quadHighlights.filterNot { it.pageIndex == page },
                studyCards = state.studyCards.filterNot { it.id in removedQuadIds },
                regalFlashTick = state.regalFlashTick + 1
            )
        }
        persistAnnotations()
    }

    companion object {
        private const val MAX_RECENTS = 30
    }
}
