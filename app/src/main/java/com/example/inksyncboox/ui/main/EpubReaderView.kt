package com.example.inksyncboox.ui.main

import android.graphics.BitmapFactory
import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inksyncboox.epub.EInkTypographyPreset
import com.example.inksyncboox.epub.EpubAnchor
import com.example.inksyncboox.epub.EpubParagraph
import com.example.inksyncboox.epub.EpubPaging
import com.example.inksyncboox.theme.EInkBorderGray
import com.example.inksyncboox.theme.EInkMutedText
import com.example.inksyncboox.theme.EInkPaperWhite
import com.example.inksyncboox.theme.EInkPureBlack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/** One slice of a paragraph placed in a column: characters [start, end) of paragraph [paragraph]. */
private data class EpubFragment(
    val paragraph: Int,
    val start: Int,
    val end: Int,
    val gapBeforePx: Int,
    /** Display size for image blocks; 0 for text. */
    val imageWidthPx: Int = 0,
    val imageHeightPx: Int = 0
)

private class EpubPageLayout(val columns: List<List<EpubFragment>>) {
    val start: EpubAnchor
        get() = columns.firstOrNull()?.firstOrNull()?.let { EpubAnchor(it.paragraph, it.start) }
            ?: EpubAnchor.START
}

private val BOOK_FONT = FontFamily.Serif

private fun bodyStyle(preset: EInkTypographyPreset) = TextStyle(
    color = EInkPureBlack,
    fontFamily = BOOK_FONT,
    fontSize = preset.fontSizeSp.sp,
    lineHeight = (preset.fontSizeSp * preset.lineHeightMultiplier).sp,
    letterSpacing = 0.sp,
    textAlign = TextAlign.Start,
    // Greedy line breaking without hyphenation keeps measured and rendered line breaks identical,
    // which the paginator depends on.
    lineBreak = LineBreak.Simple,
    hyphens = Hyphens.None
)

private fun styleFor(paragraph: EpubParagraph, preset: EInkTypographyPreset): TextStyle {
    val base = bodyStyle(preset)
    return when {
        paragraph.isHeading -> base.copy(
            fontSize = (preset.fontSizeSp + 4f).sp,
            lineHeight = ((preset.fontSizeSp + 4f) * 1.3f).sp,
            fontWeight = FontWeight.Bold
        )
        paragraph.isBlockQuote -> base.copy(fontStyle = FontStyle.Italic)
        else -> base
    }
}

private fun EpubParagraph.toAnnotated(): AnnotatedString = buildAnnotatedString {
    append(text)
    spans.forEach { span ->
        if (span.end <= text.length && span.start < span.end) {
            addStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null
                ),
                span.start,
                span.end
            )
        }
    }
}

/**
 * Splits a chapter into screen-sized pages. Each page has [columnsPerPage] columns; paragraphs
 * that don't fit are split at a line boundary and continue in the next column or page.
 */
private fun paginateChapter(
    paragraphs: List<EpubParagraph>,
    texts: List<AnnotatedString>,
    preset: EInkTypographyPreset,
    measurer: TextMeasurer,
    density: Density,
    columnWidthPx: Int,
    columnHeightPx: Int,
    columnsPerPage: Int,
    blockQuoteIndentPx: Int
): List<EpubPageLayout> {
    if (columnWidthPx <= 0 || columnHeightPx <= 0) return emptyList()
    val pages = mutableListOf<EpubPageLayout>()
    val columns = mutableListOf<List<EpubFragment>>()
    var current = mutableListOf<EpubFragment>()
    var used = 0
    val bodyLinePx = with(density) { (preset.fontSizeSp * preset.lineHeightMultiplier).sp.toPx() }
    val paragraphGapPx = ceil(bodyLinePx * 0.45f).toInt()
    val headingGapPx = ceil(bodyLinePx * 1.2f).toInt()

    fun closeColumn() {
        columns += current
        current = mutableListOf()
        used = 0
        if (columns.size == columnsPerPage) {
            pages += EpubPageLayout(columns.toList())
            columns.clear()
        }
    }

    paragraphs.forEachIndexed { pIdx, para ->
        if (para.isImage) {
            // Treat image pixels as dp (roughly how publishers size them), never wider than the
            // column, and shrink to fit the column height. Images are never split across pages.
            val aspect = para.imageHeight.toFloat() / para.imageWidth.coerceAtLeast(1)
            var w = minOf(columnWidthPx.toFloat(), para.imageWidth * density.density)
            var h = w * aspect
            if (h > columnHeightPx) {
                h = columnHeightPx.toFloat()
                w = h / aspect
            }
            var gap = if (current.isEmpty()) 0 else paragraphGapPx
            if (current.isNotEmpty() && used + gap + h > columnHeightPx) {
                closeColumn()
                gap = 0
            }
            current += EpubFragment(pIdx, 0, 0, gap, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
            used += gap + ceil(h).toInt()
            return@forEachIndexed
        }
        val indent = if (para.isBlockQuote) blockQuoteIndentPx else 0
        // Measure 2px narrower than we render: a slightly wider render can only fit the same
        // or more text per line, so rendered fragments never need more lines than measured.
        val width = (columnWidthPx - indent - 2).coerceAtLeast(1)
        val layout: TextLayoutResult = measurer.measure(
            text = texts[pIdx],
            style = styleFor(para, preset),
            constraints = Constraints(maxWidth = width),
            density = density
        )
        var line = 0
        var deferredHeading = false
        while (line < layout.lineCount) {
            val gap = when {
                current.isEmpty() -> 0
                para.isHeading -> headingGapPx
                else -> paragraphGapPx
            }
            val avail = columnHeightPx - used - gap
            val top = layout.getLineTop(line)
            var last = line - 1
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= avail) last++

            if (last < line) {
                if (current.isEmpty()) {
                    last = line // A single line taller than the column: place it anyway.
                } else {
                    closeColumn()
                    continue
                }
            }

            val height = ceil(layout.getLineBottom(last) - top).toInt()
            // Keep headings with the paragraph that follows them.
            if (para.isHeading && !deferredHeading && current.isNotEmpty() &&
                last == layout.lineCount - 1 && avail - height < bodyLinePx * 2
            ) {
                deferredHeading = true
                closeColumn()
                continue
            }

            val start = if (line == 0) 0 else layout.getLineStart(line)
            val end = if (last == layout.lineCount - 1) texts[pIdx].length else layout.getLineEnd(last)
            current += EpubFragment(pIdx, start, end, gap)
            used += gap + height
            line = last + 1
            if (line < layout.lineCount) closeColumn()
        }
    }
    if (current.isNotEmpty()) columns += current
    if (columns.isNotEmpty()) pages += EpubPageLayout(columns.toList())
    return pages
}

/**
 * Paginated EPUB reader: one screen per page, page turns by tap zones or hardware keys.
 * Long-press a paragraph to highlight it.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun EpubReaderView(
    state: BooxReaderState,
    onPagesComputed: (chapter: Int, pageStarts: List<EpubAnchor>) -> Unit,
    onStepPrev: () -> Unit,
    onStepNext: () -> Unit,
    onToggleOverlay: () -> Unit,
    onToggleParagraphHighlight: (String, String, Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val book = state.activeEpubBook
    val chapter = state.currentEpubChapter
    if (book == null || chapter == null) {
        Box(modifier.fillMaxSize().background(EInkPaperWhite), contentAlignment = Alignment.Center) {
            Text("No book open", color = EInkMutedText, fontSize = 16.sp)
        }
        return
    }
    val preset = state.settings.typography
    val columnsPerPage = if (state.settings.epubTwoColumn) 2 else 1
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val texts = remember(book.id, chapter.index) { chapter.paragraphs.map { it.toAnnotated() } }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(EInkPaperWhite)) {
        val screenWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val screenHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val lastTapX = remember { mutableFloatStateOf(screenWidthPx / 2f) }
        val lastTapY = remember { mutableFloatStateOf(screenHeightPx / 2f) }

        fun dispatchTap() {
            val action = TapZones.resolve(
                state.settings.tapLayout, lastTapX.floatValue, lastTapY.floatValue,
                screenWidthPx, screenHeightPx, rightToLeft = false
            )
            when (action) {
                TapAction.PREVIOUS -> onStepPrev()
                TapAction.NEXT -> onStepNext()
                TapAction.MENU -> onToggleOverlay()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerInteropFilter { event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        lastTapX.floatValue = event.x
                        lastTapY.floatValue = event.y
                    }
                    false
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dispatchTap() }
                )
                .padding(horizontal = 44.dp, vertical = 24.dp)
        ) {
            // Running header
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = book.title,
                    color = EInkMutedText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = chapter.title,
                    color = EInkMutedText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
            Spacer(Modifier.height(16.dp))

            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val columnGapPx = with(density) { 40.dp.roundToPx() }
                val totalWidthPx = constraints.maxWidth
                val columnWidthPx = if (columnsPerPage == 2) (totalWidthPx - columnGapPx) / 2 else totalWidthPx
                val columnHeightPx = constraints.maxHeight - 4
                val indentPx = with(density) { 24.dp.roundToPx() }

                val pages = remember(book.id, chapter.index, preset, columnsPerPage, columnWidthPx, columnHeightPx, density) {
                    paginateChapter(
                        paragraphs = chapter.paragraphs,
                        texts = texts,
                        preset = preset,
                        measurer = measurer,
                        density = density,
                        columnWidthPx = columnWidthPx,
                        columnHeightPx = columnHeightPx,
                        columnsPerPage = columnsPerPage,
                        blockQuoteIndentPx = indentPx
                    )
                }
                val starts = remember(pages) { pages.map { it.start } }
                LaunchedEffect(starts, chapter.index) {
                    onPagesComputed(chapter.index, starts)
                }

                val pageIndex = EpubPaging.pageIndexFor(starts, state.epubAnchor)
                val page = pages.getOrNull(pageIndex)
                if (page != null) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        page.columns.forEachIndexed { colIdx, fragments ->
                            if (colIdx > 0) {
                                Box(Modifier.width(with(density) { columnGapPx.toDp() }).fillMaxHeight()) {
                                    Box(
                                        Modifier
                                            .align(Alignment.Center)
                                            .width(1.dp)
                                            .fillMaxHeight()
                                            .background(EInkBorderGray)
                                    )
                                }
                            }
                            Column(modifier = Modifier.width(with(density) { columnWidthPx.toDp() }).fillMaxHeight()) {
                                fragments.forEach { fragment ->
                                    val para = chapter.paragraphs[fragment.paragraph]
                                    val highlight = state.epubHighlightedCfis[para.cfiId]
                                    if (fragment.gapBeforePx > 0) {
                                        Spacer(Modifier.height(with(density) { fragment.gapBeforePx.toDp() }))
                                    }
                                    if (para.isImage) {
                                        EpubImage(
                                            path = para.imagePath.orEmpty(),
                                            description = para.text,
                                            widthPx = fragment.imageWidthPx,
                                            heightPx = fragment.imageHeightPx,
                                            onClick = { dispatchTap() }
                                        )
                                        return@forEach
                                    }
                                    BasicText(
                                        text = texts[fragment.paragraph].subSequence(fragment.start, fragment.end),
                                        style = styleFor(para, preset),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(if (highlight != null) Modifier.background(highlight.color.copy(alpha = 0.5f)) else Modifier)
                                            .combinedClickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = { dispatchTap() },
                                                onLongClick = {
                                                    onToggleParagraphHighlight(para.cfiId, para.text, para.chapterIndex, para.indexInChapter)
                                                }
                                            )
                                            .padding(start = if (para.isBlockQuote) with(density) { indentPx.toDp() } else 0.dp)
                                    )
                                }
                            }
                        }
                    }
                }

            }

            Spacer(Modifier.height(12.dp))
            val pageCount = state.epubPageStarts.size
            Text(
                text = if (pageCount == 0) " " else
                    "Page ${state.epubPageIndex + 1} of $pageCount  ·  Chapter ${chapter.index + 1} of ${book.chapters.size}",
                color = EInkMutedText,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Decodes an EPUB image at roughly its display size (keeps memory low) and shows it centered. */
@Composable
private fun EpubImage(
    path: String,
    description: String,
    widthPx: Int,
    heightPx: Int,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, path, widthPx) {
        value = withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= widthPx && widthPx > 0) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (_: Throwable) {
                null
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { heightPx.toDp() })
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = description.ifEmpty { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            )
        }
    }
}
