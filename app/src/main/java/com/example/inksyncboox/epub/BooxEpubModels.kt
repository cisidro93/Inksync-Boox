package com.example.inksyncboox.epub

/**
 * Built-in Kindle-grade EPUB Reflow Document & Zero-Guilt Momentum Models.
 *
 * Designed specifically for 300 PPI Carta 1200 E-Ink typography on the Boox Note Air 4C:
 * - Paragraph-level CFI anchors so handwritten Wacom margin notes and Kaleido 3 highlights
 *   remain 100% locked to their exact paragraph even when the user changes font size,
 *   line height, or two-column reflow!
 * - Zero-Guilt "Welcome Back" context snapshots (no punitive daily streaks).
 */
data class EpubParagraph(
    val cfiId: String,
    val chapterIndex: Int,
    val indexInChapter: Int,
    val text: String,
    val isHeading: Boolean = false,
    val isBlockQuote: Boolean = false
)

data class EpubChapter(
    val index: Int,
    val title: String,
    val subtitle: String,
    val estimatedMinutes: Int,
    val paragraphs: List<EpubParagraph>
)

data class EpubBookDocument(
    val id: String,
    val title: String,
    val author: String,
    val categoryTag: String,
    val chapters: List<EpubChapter>
)

enum class EInkTypographyPreset(
    val label: String,
    val fontSizeSp: Float,
    val lineHeightMultiplier: Float
) {
    COMPACT_STUDY("Compact 17sp", 17f, 1.45f),
    KINDLE_CLASSIC("Kindle 20sp", 20f, 1.58f),
    RELAXED_SERIF("Relaxed 23sp", 23f, 1.68f),
    LARGE_PRINT("Large 26sp", 26f, 1.75f)
}

object BooxEpubSampleCatalog {

    val deepWorkOnEInkBook = EpubBookDocument(
        id = "epub_art_of_deep_reading_eink",
        title = "The Sanctuary of Slow Reading",
        author = "Dr. Elena Vance · Oxford Press",
        categoryTag = "EPUB · 300 PPI Reflow",
        chapters = listOf(
            EpubChapter(
                index = 0,
                title = "Chapter 1: Why Reflective Light Changes Cognition",
                subtitle = "Electrophoretic Microcapsules & Deep Attention",
                estimatedMinutes = 8,
                paragraphs = listOf(
                    EpubParagraph(
                        cfiId = "ch1_p1",
                        chapterIndex = 0,
                        indexInChapter = 0,
                        text = "1.1 The Physics of Quiet Paper",
                        isHeading = true
                    ),
                    EpubParagraph(
                        cfiId = "ch1_p2",
                        chapterIndex = 0,
                        indexInChapter = 1,
                        text = "When you read on a traditional transmissive OLED tablet, millions of microscopic emitters fire photons directly through the cornea at 120 cycles per second. Even when imperceptible to conscious vision, this photonic flicker primes the sympathetic nervous system for scanning, skimming, and rapid context-switching rather than contemplative synthesis."
                    ),
                    EpubParagraph(
                        cfiId = "ch1_p3",
                        chapterIndex = 0,
                        indexInChapter = 2,
                        text = "By contrast, an electrophoretic E-Ink display—such as the 300 PPI Carta 1200 layer beneath a Kaleido 3 filter—physically rearranges titanium dioxide and carbon-black pigments suspended in microcapsules. Once a page is set via a Regal waveform pass, zero power is consumed and only ambient room light reflects back to your eye.",
                        isBlockQuote = true
                    ),
                    EpubParagraph(
                        cfiId = "ch1_p4",
                        chapterIndex = 0,
                        indexInChapter = 3,
                        text = "1.2 The Myth of Punitive Reading Streaks",
                        isHeading = true
                    ),
                    EpubParagraph(
                        cfiId = "ch1_p5",
                        chapterIndex = 0,
                        indexInChapter = 4,
                        text = "Modern commercial reading apps have borrowed a toxic mechanic from social media: the daily streak counter. Turning literature and research into an obligation where missing a single Tuesday resets a 60-day counter transforms a sanctuary of curiosity into a chore."
                    ),
                    EpubParagraph(
                        cfiId = "ch1_p6",
                        chapterIndex = 0,
                        indexInChapter = 5,
                        text = "True mastery happens in rhythmic waves. What a scholar or deep reader actually needs when returning to a book after four days—or four weeks—is not a guilt notification, but an immediate, frictionless restoration of context: the exact paragraph you paused on, the last three passages that sparked an insight, and the handwritten margin notes you left in your own handwriting."
                    )
                )
            ),
            EpubChapter(
                index = 1,
                title = "Chapter 2: Marginalia as Extended Memory",
                subtitle = "Bridging Wacom EMR Ink with Reflowable Typography",
                estimatedMinutes = 11,
                paragraphs = listOf(
                    EpubParagraph(
                        cfiId = "ch2_p1",
                        chapterIndex = 1,
                        indexInChapter = 0,
                        text = "2.1 Why Static Margins Failed Reflowable Books",
                        isHeading = true
                    ),
                    EpubParagraph(
                        cfiId = "ch2_p2",
                        chapterIndex = 1,
                        indexInChapter = 1,
                        text = "For centuries, the greatest thinkers conversed with their books in the margins. Fermat's theorems, Darwin's evolutionary sketches, and Wittgenstein's logical critiques began as handwritten ink beside printed paragraphs. Yet the first generation of digital e-readers severed this link: you could either have reflowable text (EPUB) with clumsy pop-up keyboard notes, or fixed-layout pages (PDF) with tiny, cramped fonts."
                    ),
                    EpubParagraph(
                        cfiId = "ch2_p3",
                        chapterIndex = 1,
                        indexInChapter = 2,
                        text = "By anchoring Wacom EMR vector strokes and Cornell scratchpad cards directly to canonical paragraph identifiers (CFI), InkSync Boox restores the ancient art of marginalia without sacrificing dynamic font scaling.",
                        isBlockQuote = true
                    ),
                    EpubParagraph(
                        cfiId = "ch2_p4",
                        chapterIndex = 1,
                        indexInChapter = 3,
                        text = "2.2 The Two-Column Synthesis Workflow",
                        isHeading = true
                    ),
                    EpubParagraph(
                        cfiId = "ch2_p5",
                        chapterIndex = 1,
                        indexInChapter = 4,
                        text = "Try toggling the 'Margin Gutter' or 'Cornell Split' button in the top command bar right now. Notice how every paragraph you tap with your stylus or finger can be highlighted in Kaleido 3 pastel amber, mint, or cyan—and automatically pinned to your active Cornell study sheet with a one-tap backlink."
                    )
                )
            )
        )
    )
}
