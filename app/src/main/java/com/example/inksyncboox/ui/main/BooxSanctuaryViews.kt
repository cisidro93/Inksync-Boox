package com.example.inksyncboox.ui.main

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inksyncboox.eink.BooxEpdController
import com.example.inksyncboox.ink.BooxStylusTool
import com.example.inksyncboox.ink.NormalizedInkPoint
import com.example.inksyncboox.study.BooxSidecarPersistence
import com.example.inksyncboox.study.BooxWorkspaceMode
import com.example.inksyncboox.study.CornellScratchpadTemplate
import com.example.inksyncboox.study.UnifiedStudyCard
import com.example.inksyncboox.theme.EInkBorderGray
import com.example.inksyncboox.theme.EInkMutedText
import com.example.inksyncboox.theme.EInkPaperWhite
import com.example.inksyncboox.theme.EInkPureBlack
import com.example.inksyncboox.theme.EInkSurfaceGray
import com.example.inksyncboox.theme.KaleidoAmberHighlight
import com.example.inksyncboox.theme.KaleidoCobaltInk
import com.example.inksyncboox.theme.KaleidoCyanHighlight
import com.example.inksyncboox.theme.KaleidoMintHighlight
import kotlin.math.roundToInt

/**
 * 1. UNIFIED SANCTUARY LIBRARY SHELF
 * Zero daily streak anxiety. Welcomes the reader back with exact context restoration,
 * side-by-side EPUB books, Academic NeoFlow PDFs, and Unified Study Binder stats.
 */
@Composable
fun BooxSanctuaryShelfView(
    state: BooxReaderState,
    onSelectMode: (BooxWorkspaceMode) -> Unit,
    onOpenLocalPdf: () -> Unit,
    onDismissWelcomeBanner: () -> Unit,
    onJumpToCard: (UnifiedStudyCard) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(EInkPaperWhite)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Zero-Guilt "Welcome Back" Context Restoration Card
        if (state.showWelcomeBackContextBanner) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(EInkSurfaceGray, RoundedCornerShape(6.dp))
                    .border(2.dp, EInkPureBlack, RoundedCornerShape(6.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(EInkPureBlack, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "WELCOME BACK · ZERO-STREAK SANCTUARY",
                                color = EInkPaperWhite,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "Pick up at your own rhythm—no daily streak timers.",
                            color = EInkMutedText,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    SanctuaryPillButton(label = "Dismiss", isPrimary = false, onClick = onDismissWelcomeBanner)
                }

                Text(
                    text = "Where You Left Off: You have ${state.studyCards.size} pinned insights and ${state.strokes.size + state.cornellStrokes.size} Wacom strokes across your active PDF & EPUB Desk.",
                    color = EInkPureBlack,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )

                val latestCard = state.studyCards.firstOrNull()
                if (latestCard != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(EInkPaperWhite, RoundedCornerShape(4.dp))
                            .border(1.5.dp, EInkPureBlack, RoundedCornerShape(4.dp))
                            .clickable { onJumpToCard(latestCard) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "LAST PINNED PASSAGE · [${latestCard.sourceType}] ${latestCard.locatorBadge}",
                                color = EInkMutedText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "\"${latestCard.quoteText}\"",
                                color = EInkPureBlack,
                                fontSize = 14.sp,
                                fontStyle = FontStyle.Italic,
                                fontFamily = FontFamily.Serif,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        SanctuaryPillButton(
                            label = "Resume Here →",
                            isPrimary = true,
                            onClick = { onJumpToCard(latestCard) }
                        )
                    }
                }
            }
        }

        // Unified Desk Shelf Cards: 1-Tap into Kindle EPUB or NeoFlow PDF
        Text(
            text = "ACTIVE READING & RESEARCH DESK (ONE PLACE FOR BOOKS + PAPERS)",
            color = EInkPureBlack,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Card 1: NeoReader-Grade Academic PDF with NeoFlow Smart Tiers
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(EInkPaperWhite, RoundedCornerShape(6.dp))
                    .border(2.5.dp, EInkPureBlack, RoundedCornerShape(6.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .background(KaleidoMintHighlight, RoundedCornerShape(4.dp))
                            .border(1.5.dp, EInkPureBlack, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "PDF · NEOFLOW ${state.gridPreset.badge} (${state.traversalFlow.symbol})",
                            color = EInkPureBlack,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = "Page ${state.pageIndex + 1}/${state.pageCount}",
                        color = EInkPureBlack,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Text(
                    text = state.documentTitle,
                    color = EInkPureBlack,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )

                Text(
                    text = "2-Column Academic Showcase · Active Tier: ${state.currentBlock.label} · 8% Connection Redundancy · Direct Wacom EMR Inking",
                    color = EInkMutedText,
                    fontSize = 13.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SanctuaryPillButton(
                        label = "Resume PDF NeoFlow →",
                        isPrimary = true,
                        onClick = { onSelectMode(BooxWorkspaceMode.PDF_NEOFLOW) }
                    )
                    SanctuaryPillButton(
                        label = "Open .PDF / .EPUB / .CBZ…",
                        isPrimary = false,
                        onClick = onOpenLocalPdf
                    )
                }
            }

            // Card 2: Kindle-Grade EPUB 300 PPI Bookerly Reflow
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(EInkPaperWhite, RoundedCornerShape(6.dp))
                    .border(2.5.dp, EInkPureBlack, RoundedCornerShape(6.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .background(KaleidoAmberHighlight, RoundedCornerShape(4.dp))
                            .border(1.5.dp, EInkPureBlack, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "KINDLE EPUB · 300 PPI REFLOW",
                            color = EInkPureBlack,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = "Ch ${state.epubChapterIndex + 1}/${state.activeEpubBook.chapters.size} · ~${state.currentEpubChapter.estimatedMinutes}m left",
                        color = EInkPureBlack,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Text(
                    text = state.activeEpubBook.title,
                    color = EInkPureBlack,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )

                Text(
                    text = "${state.activeEpubBook.author} · Typography: ${state.epubTypographyPreset.label} · ${state.epubHighlightedCfis.size} CFI Highlights",
                    color = EInkMutedText,
                    fontSize = 13.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SanctuaryPillButton(
                        label = "Read Kindle EPUB →",
                        isPrimary = true,
                        onClick = { onSelectMode(BooxWorkspaceMode.KINDLE_EPUB) }
                    )
                    SanctuaryPillButton(
                        label = "Open Study Binder (${state.studyCards.size})",
                        isPrimary = false,
                        onClick = { onSelectMode(BooxWorkspaceMode.STUDY_BINDER) }
                    )
                }
            }
        }

        // Boox Note Air 4C Hardware Readiness Diagnostic Strip
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(EInkPaperWhite, RoundedCornerShape(6.dp))
                .border(2.dp, EInkPureBlack, RoundedCornerShape(6.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "BOOX NOTE AIR 4C HARDWARE OPTIMIZATION DIAGNOSTICS",
                color = EInkPureBlack,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "• EPD Controller: ${BooxEpdController.hardwareModelSummary} · Active Mode: ${state.refreshMode.title} (Auto-Regal every ${state.autoRegalEveryNTurns} turns)",
                color = EInkPureBlack,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "• Palm Rejection Shield: ${if (state.palmShieldStylusOnly) "LOCKED TO WACOM STYLUS" else "ADAPTIVE HOVER + FINGER"} · Android System Edge Gestures Excluded",
                color = EInkPureBlack,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "• Kaleido 3 Surface Budget: 95% Pure #FFFFFF · BlendMode.Multiply Pastel Swatches · <50MB Resident PDF Heap",
                color = EInkPureBlack,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * 2. KINDLE-STYLE 300 PPI EPUB REFLOW READER
 * Combines Kindle's effortless serif typography and "Time left in chapter" footer with
 * 1-tap Kaleido 3 paragraph highlighting (anchored by CFI) and optional Split-Screen Cornell Scratchpad.
 */
@Composable
fun BooxKindleEpubReaderView(
    state: BooxReaderState,
    onCycleTypography: () -> Unit,
    onToggleTwoColumn: () -> Unit,
    onToggleParagraphHighlight: (String, String, Int, Int) -> Unit,
    onStepPrevChapter: () -> Unit,
    onStepNextChapter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chapter = state.currentEpubChapter
    val preset = state.epubTypographyPreset

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(EInkPaperWhite)
            .border(2.dp, EInkPureBlack)
    ) {
        // Kindle Typography & Column Control Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(EInkSurfaceGray)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${state.activeEpubBook.title} · ${chapter.title}",
                    color = EInkPureBlack,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Tap any paragraph to toggle Kaleido 3 CFI Highlight (${state.activeSwatch.label}) & auto-pin to Study Hub",
                    color = EInkMutedText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SanctuaryPillButton(
                    label = "Aa: ${preset.label}",
                    isPrimary = false,
                    onClick = onCycleTypography
                )
                SanctuaryPillButton(
                    label = if (state.epubTwoColumnMode) "Layout: 2-Col Book" else "Layout: 1-Col Kindle",
                    isPrimary = state.epubTwoColumnMode,
                    onClick = onToggleTwoColumn
                )
            }
        }

        HorizontalDivider(thickness = 2.dp, color = EInkPureBlack)

        // Bookerly-Style 300 PPI Reflow Body
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 20.dp)
        ) {
            if (state.epubTwoColumnMode) {
                val mid = (chapter.paragraphs.size + 1) / 2
                val leftCol = chapter.paragraphs.take(mid)
                val rightCol = chapter.paragraphs.drop(mid)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        leftCol.forEach { para ->
                            EpubParagraphBlock(
                                paragraph = para,
                                fontSizeSp = preset.fontSizeSp,
                                lineHeightMultiplier = preset.lineHeightMultiplier,
                                highlightSwatch = state.epubHighlightedCfis[para.cfiId],
                                onClick = {
                                    onToggleParagraphHighlight(
                                        para.cfiId,
                                        para.text,
                                        para.chapterIndex,
                                        para.indexInChapter
                                    )
                                }
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .fillMaxHeight()
                            .background(EInkBorderGray)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        rightCol.forEach { para ->
                            EpubParagraphBlock(
                                paragraph = para,
                                fontSizeSp = preset.fontSizeSp,
                                lineHeightMultiplier = preset.lineHeightMultiplier,
                                highlightSwatch = state.epubHighlightedCfis[para.cfiId],
                                onClick = {
                                    onToggleParagraphHighlight(
                                        para.cfiId,
                                        para.text,
                                        para.chapterIndex,
                                        para.indexInChapter
                                    )
                                }
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    chapter.paragraphs.forEach { para ->
                        EpubParagraphBlock(
                            paragraph = para,
                            fontSizeSp = preset.fontSizeSp,
                            lineHeightMultiplier = preset.lineHeightMultiplier,
                            highlightSwatch = state.epubHighlightedCfis[para.cfiId],
                            onClick = {
                                onToggleParagraphHighlight(
                                    para.cfiId,
                                    para.text,
                                    para.chapterIndex,
                                    para.indexInChapter
                                )
                            }
                        )
                    }
                }
            }
        }

        HorizontalDivider(thickness = 1.5.dp, color = EInkPureBlack)

        // Whisper-Quiet Kindle Pace & Progress Footer (Zero Streak Pressure)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(EInkPaperWhite)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SanctuaryPillButton(label = "◀ Prev Chapter", isPrimary = false, onClick = onStepPrevChapter)

            Text(
                text = "Chapter ${chapter.index + 1} of ${state.activeEpubBook.chapters.size} · ~${chapter.estimatedMinutes} mins left in chapter · CFI Anchors Active",
                color = EInkPureBlack,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            SanctuaryPillButton(label = "Next Chapter ▶", isPrimary = true, onClick = onStepNextChapter)
        }
    }
}

@Composable
private fun EpubParagraphBlock(
    paragraph: com.example.inksyncboox.epub.EpubParagraph,
    fontSizeSp: Float,
    lineHeightMultiplier: Float,
    highlightSwatch: com.example.inksyncboox.theme.KaleidoInkSwatch?,
    onClick: () -> Unit
) {
    val bgColor = highlightSwatch?.color?.copy(alpha = 0.45f) ?: Color.Transparent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(4.dp))
            .then(
                if (highlightSwatch != null || paragraph.isBlockQuote) {
                    Modifier.border(1.5.dp, EInkPureBlack, RoundedCornerShape(4.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (highlightSwatch != null || paragraph.isBlockQuote) 12.dp else 4.dp,
                vertical = 6.dp
            )
    ) {
        if (highlightSwatch != null) {
            Text(
                text = "★ PINNED CFI [${paragraph.cfiId.uppercase()}] · ${highlightSwatch.label}",
                color = EInkPureBlack,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(3.dp))
        }
        Text(
            text = paragraph.text,
            color = EInkPureBlack,
            fontSize = if (paragraph.isHeading) (fontSizeSp + 3f).sp else fontSizeSp.sp,
            lineHeight = (fontSizeSp * lineHeightMultiplier).sp,
            fontWeight = if (paragraph.isHeading) FontWeight.Black else FontWeight.Normal,
            fontStyle = if (paragraph.isBlockQuote) FontStyle.Italic else FontStyle.Normal,
            fontFamily = FontFamily.Serif
        )
    }
}

/**
 * 3. SPLIT-SCREEN CORNELL & DOT-GRID WACOM SCRATCHPAD
 * Sits right beside either your PDF NeoFlow Tier or your Kindle EPUB book so you never
 * run out of margin space and never have to leave your book to take handwritten notes.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BooxCornellScratchpadPane(
    state: BooxReaderState,
    onCycleTemplate: () -> Unit,
    onCommitScratchpadStroke: (List<NormalizedInkPoint>, String) -> Unit,
    onClearScratchpad: () -> Unit,
    modifier: Modifier = Modifier
) {
    val livePoints = remember { mutableStateListOf<NormalizedInkPoint>() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(EInkPaperWhite)
            .border(2.dp, EInkPureBlack)
    ) {
        // Scratchpad Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(EInkSurfaceGray)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "WACOM CORNELL SCRATCHPAD",
                    color = EInkPureBlack,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = state.cornellTemplate.label,
                    color = EInkMutedText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SanctuaryPillButton(label = "Grid", isPrimary = false, onClick = onCycleTemplate)
                SanctuaryPillButton(label = "Clear", isPrimary = false, onClick = onClearScratchpad)
            }
        }

        HorizontalDivider(thickness = 1.5.dp, color = EInkPureBlack)

        // Pinned Cue Preview from Current Book/PDF
        val latestCard = state.studyCards.firstOrNull()
        if (latestCard != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KaleidoCyanHighlight.copy(alpha = 0.28f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "LINKED CUE · ${latestCard.locatorBadge}",
                    color = EInkPureBlack,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "\"${latestCard.quoteText}\"",
                    color = EInkPureBlack,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Serif,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider(thickness = 1.5.dp, color = EInkPureBlack)
        }

        // Direct Wacom EMR Scratchpad Canvas
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
        ) {
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInteropFilter { event ->
                        val pressure = event.pressure.coerceIn(0.1f, 1f)
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                livePoints.clear()
                                livePoints.add(
                                    NormalizedInkPoint(
                                        (event.x / widthPx).coerceIn(0f, 1f),
                                        (event.y / heightPx).coerceIn(0f, 1f),
                                        pressure
                                    )
                                )
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                for (h in 0 until event.historySize) {
                                    livePoints.add(
                                        NormalizedInkPoint(
                                            (event.getHistoricalX(h) / widthPx).coerceIn(0f, 1f),
                                            (event.getHistoricalY(h) / heightPx).coerceIn(0f, 1f),
                                            event.getHistoricalPressure(h).coerceIn(0.1f, 1f)
                                        )
                                    )
                                }
                                livePoints.add(
                                    NormalizedInkPoint(
                                        (event.x / widthPx).coerceIn(0f, 1f),
                                        (event.y / heightPx).coerceIn(0f, 1f),
                                        pressure
                                    )
                                )
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                val committed = livePoints.toList()
                                livePoints.clear()
                                onCommitScratchpadStroke(
                                    committed,
                                    "Cornell Scratchpad Stroke · ${(pressure * 4096).roundToInt()}/4096"
                                )
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                // Draw E-Ink Paper Template (Cornell Cue Margin, Ruled Lines, or Dot Matrix)
                when (state.cornellTemplate) {
                    CornellScratchpadTemplate.CORNELL_CUE_GRID -> {
                        val cueX = size.width * 0.28f
                        val summaryY = size.height * 0.80f
                        drawLine(
                            color = EInkPureBlack,
                            start = Offset(cueX, 0f),
                            end = Offset(cueX, summaryY),
                            strokeWidth = 2f
                        )
                        drawLine(
                            color = EInkPureBlack,
                            start = Offset(0f, summaryY),
                            end = Offset(size.width, summaryY),
                            strokeWidth = 2f
                        )
                        val step = 36f
                        var y = step
                        while (y < summaryY) {
                            var x = cueX + step
                            while (x < size.width) {
                                drawCircle(color = EInkBorderGray, radius = 1.8f, center = Offset(x, y))
                                x += step
                            }
                            y += step
                        }
                    }
                    CornellScratchpadTemplate.RULED_ACADEMIC -> {
                        val lineStep = 44f
                        var y = lineStep
                        while (y < size.height) {
                            drawLine(
                                color = EInkBorderGray,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1.5f
                            )
                            y += lineStep
                        }
                    }
                    CornellScratchpadTemplate.ISOMETRIC_DOT -> {
                        val step = 34f
                        var y = step
                        while (y < size.height) {
                            var x = step
                            while (x < size.width) {
                                drawCircle(color = EInkPureBlack, radius = 1.6f, center = Offset(x, y))
                                x += step
                            }
                            y += step
                        }
                    }
                }

                // Draw Committed Scratchpad Strokes
                state.cornellStrokes.forEach { stroke ->
                    if (stroke.points.size >= 2) {
                        val path = Path()
                        val first = stroke.points.first()
                        path.moveTo(first.x * size.width, first.y * size.height)
                        for (i in 1 until stroke.points.size) {
                            val pt = stroke.points[i]
                            path.lineTo(pt.x * size.width, pt.y * size.height)
                        }
                        drawPath(
                            path = path,
                            color = if (stroke.isHighlighter) stroke.composeColor.copy(alpha = 0.5f) else stroke.composeColor,
                            style = Stroke(
                                width = if (stroke.isHighlighter) 28f else stroke.baseWidthPt * 2.1f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            ),
                            blendMode = if (stroke.isHighlighter) BlendMode.Multiply else BlendMode.SrcOver
                        )
                    }
                }

                // Draw Live Stroke
                if (livePoints.size >= 2) {
                    val path = Path()
                    val first = livePoints.first()
                    path.moveTo(first.x * size.width, first.y * size.height)
                    for (i in 1 until livePoints.size) {
                        val pt = livePoints[i]
                        path.lineTo(pt.x * size.width, pt.y * size.height)
                    }
                    drawPath(
                        path = path,
                        color = state.activeSwatch.color,
                        style = Stroke(width = state.strokeWidthPt * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
        }
    }
}

/**
 * 4. UNIFIED STUDY HUB BINDER & SIDECAR EXPORT (.md + .inksync.json)
 * Collects every PDF NeoFlow highlight and Kindle EPUB CFI highlight in one searchable place
 * with 1-tap backlinks to jump straight to that exact tier or paragraph.
 */
@Composable
fun BooxStudyHubBinderView(
    state: BooxReaderState,
    onJumpToCard: (UnifiedStudyCard) -> Unit,
    onExportSidecars: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(EInkPaperWhite)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Export & Sync Control Banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(EInkSurfaceGray, RoundedCornerShape(6.dp))
                .border(2.dp, EInkPureBlack, RoundedCornerShape(6.dp))
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "UNIFIED STUDY HUB & MARKDOWN SIDECAR SYNC",
                    color = EInkPureBlack,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = state.lastSidecarExportStatus,
                    color = EInkMutedText,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            SanctuaryPillButton(
                label = "Export .MD + .JSON Sidecar Now",
                isPrimary = true,
                onClick = {
                    val (mdFile, jsonFile) = BooxSidecarPersistence.exportSidecarFiles(
                        context = context,
                        documentTitle = state.documentTitle,
                        cards = state.studyCards,
                        strokes = state.strokes + state.cornellStrokes
                    )
                    onExportSidecars("Saved: ${mdFile.name} & ${jsonFile.name} (${state.studyCards.size} cards)")
                }
            )
        }

        // Cards List
        state.studyCards.forEach { card ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(EInkPaperWhite, RoundedCornerShape(6.dp))
                    .border(2.dp, EInkPureBlack, RoundedCornerShape(6.dp))
                    .clickable { onJumpToCard(card) }
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(Color(card.colorHex.toULong()), CircleShape)
                                .border(1.5.dp, EInkPureBlack, CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .background(EInkPureBlack, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = card.sourceType,
                                color = EInkPaperWhite,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "${card.documentTitle} · ${card.locatorBadge}",
                            color = EInkPureBlack,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Text(
                        text = "\"${card.quoteText}\"",
                        color = EInkPureBlack,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Serif
                    )

                    if (card.reflectionCue.isNotBlank()) {
                        Text(
                            text = "Cornell Synthesis: ${card.reflectionCue}",
                            color = EInkMutedText,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))
                SanctuaryPillButton(
                    label = "Jump to Source →",
                    isPrimary = false,
                    onClick = { onJumpToCard(card) }
                )
            }
        }
    }
}

@Composable
internal fun SanctuaryPillButton(
    label: String,
    isPrimary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(4.dp),
        color = if (isPrimary) EInkPureBlack else EInkPaperWhite,
        border = BorderStroke(2.dp, EInkPureBlack)
    ) {
        Text(
            text = label,
            color = if (isPrimary) EInkPaperWhite else EInkPureBlack,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
