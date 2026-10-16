package com.example.inksyncboox.ui.main

import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inksyncboox.data.DataRepository
import com.example.inksyncboox.eink.BooxRefreshMode
import com.example.inksyncboox.ink.BooxInkStroke
import com.example.inksyncboox.ink.BooxStylusTool
import com.example.inksyncboox.ink.NormalizedInkPoint
import com.example.inksyncboox.ink.QuadTextHighlight
import com.example.inksyncboox.neoflow.BooxGridPreset
import com.example.inksyncboox.neoflow.BooxSectionBlock
import com.example.inksyncboox.neoflow.BooxTraversalFlow
import com.example.inksyncboox.neoflow.NeoFlowEngine
import com.example.inksyncboox.neoflow.NormalizedRect
import com.example.inksyncboox.theme.BooxPenSwatches
import com.example.inksyncboox.theme.KaleidoInkSwatch
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
    val highContrastBAndW: Boolean = false,
    val activeStylusTool: BooxStylusTool = BooxStylusTool.NAVIGATE,
    val activeSwatch: KaleidoInkSwatch = BooxPenSwatches.first(),
    val strokeWidthPt: Float = 3.2f,
    val strokes: List<BooxInkStroke> = emptyList(),
    val quadHighlights: List<QuadTextHighlight> = emptyList(),
    val regalFlashTick: Int = 0,
    val lastStylusTelemetry: String = "Wacom EMR Ready · 4096 Levels"
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
}

class MainScreenViewModel(dataRepository: DataRepository) : ViewModel() {

    val uiState: StateFlow<MainScreenUiState> =
        dataRepository.data
            .map<List<String>, MainScreenUiState>(MainScreenUiState::Success)
            .catch { emit(MainScreenUiState.Error(it)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainScreenUiState.Loading)

    private val _readerState = MutableStateFlow(BooxReaderState())
    val readerState: StateFlow<BooxReaderState> = _readerState.asStateFlow()

    fun updateDocumentMetadata(title: String, pageCount: Int) {
        _readerState.update { state ->
            state.copy(
                documentTitle = title,
                pageCount = pageCount.coerceAtLeast(1),
                pageIndex = 0,
                activeStepIndex = 0
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
            val totalBlocks = state.computedBlocks.size
            if (state.activeStepIndex + 1 < totalBlocks) {
                state.copy(
                    activeStepIndex = state.activeStepIndex + 1,
                    regalFlashTick = state.regalFlashTick + 1
                )
            } else if (state.pageIndex + 1 < state.pageCount) {
                state.copy(
                    pageIndex = state.pageIndex + 1,
                    activeStepIndex = 0,
                    regalFlashTick = state.regalFlashTick + 1
                )
            } else {
                state
            }
        }
    }

    fun stepBackwardTierOrPage() {
        _readerState.update { state ->
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

    fun updateCustomBlockOverride(stepIndex: Int, rect: NormalizedRect) {
        _readerState.update { state ->
            val updated = state.customOverrides.toMutableMap()
            updated[stepIndex] = rect
            state.copy(customOverrides = updated)
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
            val hlColor = if (state.activeSwatch.isHighlighter) {
                state.activeSwatch.color
            } else {
                BooxPenSwatches.first { it.isHighlighter }.color
            }
            val item = QuadTextHighlight(
                id = UUID.randomUUID().toString(),
                pageIndex = state.pageIndex,
                bounds = bounds,
                snippet = "Page ${state.pageIndex + 1} · ${state.currentBlock.label}",
                colorHex = hlColor.value.toLong()
            )
            state.copy(
                quadHighlights = state.quadHighlights + item,
                lastStylusTelemetry = telemetry
            )
        }
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
