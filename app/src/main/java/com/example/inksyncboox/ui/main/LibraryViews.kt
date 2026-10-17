package com.example.inksyncboox.ui.main

import android.text.format.DateUtils
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inksyncboox.data.DocKind
import com.example.inksyncboox.data.RecentDocument
import com.example.inksyncboox.eink.BooxEpdController
import com.example.inksyncboox.ink.NormalizedInkPoint
import com.example.inksyncboox.study.CornellScratchpadTemplate
import com.example.inksyncboox.study.UnifiedStudyCard
import com.example.inksyncboox.theme.EInkBorderGray
import com.example.inksyncboox.theme.EInkMutedText
import com.example.inksyncboox.theme.EInkPaperWhite
import com.example.inksyncboox.theme.EInkPureBlack
import com.example.inksyncboox.theme.EInkSurfaceGray

/** Black-on-white pill button sized for finger taps on e-ink. */
@Composable
internal fun EInkActionPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = if (isSelected) EInkPureBlack else EInkPaperWhite,
        border = BorderStroke(1.dp, if (isSelected) EInkPureBlack else EInkMutedText)
    ) {
        Text(
            text = label,
            color = if (isSelected) EInkPaperWhite else EInkPureBlack,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun KindBadge(kind: DocKind) {
    Box(
        modifier = Modifier
            .border(1.dp, EInkPureBlack, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(kind.name, color = EInkPureBlack, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Library: open a file or pick up a recent document where you left off. */
@Composable
fun LibraryScreen(
    state: BooxReaderState,
    onOpenFile: () -> Unit,
    onOpenRecent: (RecentDocument) -> Unit,
    onRemoveRecent: (RecentDocument) -> Unit,
    onBackToReader: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().background(EInkPaperWhite)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Library",
                color = EInkPureBlack,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                modifier = Modifier.weight(1f)
            )
            if (state.document != null) {
                EInkActionPill(label = "Back to ${state.document.kind.name}", isSelected = false, onClick = onBackToReader)
                Spacer(Modifier.width(12.dp))
            }
            EInkActionPill(label = "Open file", isSelected = true, onClick = onOpenFile)
        }
        HorizontalDivider(thickness = 1.dp, color = EInkPureBlack)

        state.errorMessage?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(EInkSurfaceGray)
                    .padding(horizontal = 32.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(message, color = EInkPureBlack, fontSize = 15.sp, modifier = Modifier.weight(1f))
                EInkActionPill(label = "Dismiss", isSelected = false, onClick = onDismissError)
            }
            HorizontalDivider(thickness = 1.dp, color = EInkBorderGray)
        }

        if (state.isLoading) {
            Text(
                "Opening…",
                color = EInkPureBlack,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
            )
        }

        if (state.recentDocuments.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Open a PDF, EPUB, or CBZ to start reading.",
                    color = EInkPureBlack,
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Documents you open appear here with your place saved.",
                    color = EInkMutedText,
                    fontSize = 15.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(state.recentDocuments, key = { it.uri }) { recent ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRecent(recent) }
                            .padding(horizontal = 32.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        KindBadge(recent.kind)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                recent.title,
                                color = EInkPureBlack,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${recent.progressLabel}  ·  ${
                                    DateUtils.getRelativeTimeSpanString(recent.lastOpenedMillis)
                                }",
                                color = EInkMutedText,
                                fontSize = 14.sp
                            )
                        }
                        Text(
                            "Remove",
                            color = EInkMutedText,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable { onRemoveRecent(recent) }
                                .padding(12.dp)
                        )
                    }
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = EInkBorderGray,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = EInkBorderGray)
        Text(
            BooxEpdController.statusSummary,
            color = EInkMutedText,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp)
        )
    }
}

/** Highlights saved in the open document, with Markdown export. */
@Composable
fun HighlightsScreen(
    state: BooxReaderState,
    onBack: () -> Unit,
    onJumpToCard: (UnifiedStudyCard) -> Unit,
    onDeleteCard: (UnifiedStudyCard) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().background(EInkPaperWhite)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EInkActionPill(label = "← Back", isSelected = false, onClick = onBack)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Highlights", color = EInkPureBlack, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                Text(
                    state.document?.title.orEmpty(),
                    color = EInkMutedText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            EInkActionPill(label = "Export Markdown", isSelected = true, onClick = onExport)
        }
        state.lastExportStatus?.let {
            Text(it, color = EInkMutedText, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 32.dp))
        }
        HorizontalDivider(thickness = 1.dp, color = EInkPureBlack, modifier = Modifier.padding(top = 8.dp))

        if (state.studyCards.isEmpty()) {
            Text(
                "No highlights yet. In a book, long-press a paragraph. In a PDF or comic, use the Area tool from the pen bar.",
                color = EInkMutedText,
                fontSize = 16.sp,
                modifier = Modifier.padding(32.dp)
            )
        } else {
            val sorted = remember(state.studyCards) {
                state.studyCards.sortedWith(compareBy({ it.pageOrChapterIndex }, { it.tierOrParagraphIndex }))
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(sorted, key = { it.id }) { card ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJumpToCard(card) }
                            .padding(horizontal = 32.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            Modifier
                                .padding(top = 4.dp)
                                .size(14.dp)
                                .background(Color(card.colorHex.toULong()), CircleShape)
                                .border(1.dp, EInkPureBlack, CircleShape)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(card.locatorBadge, color = EInkMutedText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                card.quoteText,
                                color = EInkPureBlack,
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Serif,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            "Delete",
                            color = EInkMutedText,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable { onDeleteCard(card) }.padding(start = 12.dp, top = 2.dp, bottom = 8.dp)
                        )
                    }
                    HorizontalDivider(thickness = 1.dp, color = EInkBorderGray, modifier = Modifier.padding(horizontal = 32.dp))
                }
            }
        }
    }
}

/** Side-by-side handwriting pane for notes while reading. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NotesPane(
    state: BooxReaderState,
    onCycleTemplate: () -> Unit,
    onCommitStroke: (List<NormalizedInkPoint>) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val livePoints = remember { mutableStateListOf<NormalizedInkPoint>() }

    Column(modifier = modifier.fillMaxSize().background(EInkPaperWhite)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Notes", color = EInkPureBlack, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            EInkActionPill(label = state.cornellTemplate.label, isSelected = false, onClick = onCycleTemplate)
            Spacer(Modifier.width(8.dp))
            EInkActionPill(label = "Clear", isSelected = false, onClick = onClear)
        }
        HorizontalDivider(thickness = 1.dp, color = EInkPureBlack)

        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

            fun point(x: Float, y: Float, p: Float) =
                NormalizedInkPoint((x / widthPx).coerceIn(0f, 1f), (y / heightPx).coerceIn(0f, 1f), p.coerceIn(0.1f, 1f))

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInteropFilter { event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                livePoints.clear()
                                livePoints.add(point(event.x, event.y, event.pressure))
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                for (h in 0 until event.historySize) {
                                    livePoints.add(point(event.getHistoricalX(h), event.getHistoricalY(h), event.getHistoricalPressure(h)))
                                }
                                livePoints.add(point(event.x, event.y, event.pressure))
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                val committed = livePoints.toList()
                                livePoints.clear()
                                onCommitStroke(committed)
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                when (state.cornellTemplate) {
                    CornellScratchpadTemplate.CORNELL_CUE_GRID -> {
                        val cueX = size.width * 0.28f
                        val summaryY = size.height * 0.80f
                        drawLine(EInkPureBlack, Offset(cueX, 0f), Offset(cueX, summaryY), strokeWidth = 2f)
                        drawLine(EInkPureBlack, Offset(0f, summaryY), Offset(size.width, summaryY), strokeWidth = 2f)
                    }
                    CornellScratchpadTemplate.RULED_ACADEMIC -> {
                        var y = 56f
                        while (y < size.height) {
                            drawLine(EInkBorderGray, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
                            y += 56f
                        }
                    }
                    CornellScratchpadTemplate.ISOMETRIC_DOT -> {
                        var y = 48f
                        while (y < size.height) {
                            var x = 48f
                            while (x < size.width) {
                                drawCircle(EInkMutedText, radius = 2f, center = Offset(x, y))
                                x += 48f
                            }
                            y += 48f
                        }
                    }
                }

                fun drawStroke(points: List<NormalizedInkPoint>, color: Color, width: Float, highlighter: Boolean) {
                    if (points.size < 2) return
                    val path = Path()
                    path.moveTo(points.first().x * size.width, points.first().y * size.height)
                    for (i in 1 until points.size) path.lineTo(points[i].x * size.width, points[i].y * size.height)
                    drawPath(
                        path = path,
                        color = if (highlighter) color.copy(alpha = 0.5f) else color,
                        style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        blendMode = if (highlighter) BlendMode.Multiply else BlendMode.SrcOver
                    )
                }

                state.cornellStrokes.forEach { s ->
                    drawStroke(s.points, s.composeColor, if (s.isHighlighter) 28f else s.baseWidthPt * 2.1f, s.isHighlighter)
                }
                drawStroke(livePoints.toList(), state.activeSwatch.color, state.strokeWidthPt * 2.2f, false)
            }
        }
    }
}
