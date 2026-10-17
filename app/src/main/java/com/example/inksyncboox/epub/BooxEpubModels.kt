package com.example.inksyncboox.epub

/**
 * EPUB document model used by the reflow reader.
 *
 * Paragraphs carry a stable id (`chX_pY`) so highlights survive font-size and column changes.
 */

/** Bold/italic run inside a paragraph, as character offsets into [EpubParagraph.text]. */
data class EpubInlineSpan(
    val start: Int,
    val end: Int,
    val bold: Boolean,
    val italic: Boolean
)

data class EpubParagraph(
    val cfiId: String,
    val chapterIndex: Int,
    val indexInChapter: Int,
    val text: String,
    val isHeading: Boolean = false,
    val isBlockQuote: Boolean = false,
    val spans: List<EpubInlineSpan> = emptyList(),
    /** Set for image blocks: file in the app cache, and its pixel size. [text] holds the alt text. */
    val imagePath: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
) {
    val isImage: Boolean get() = imagePath != null
}

data class EpubChapter(
    val index: Int,
    val title: String,
    val estimatedMinutes: Int,
    val paragraphs: List<EpubParagraph>
)

data class EpubBookDocument(
    val id: String,
    val title: String,
    val author: String,
    val chapters: List<EpubChapter>
)

enum class EInkTypographyPreset(
    val label: String,
    val fontSizeSp: Float,
    val lineHeightMultiplier: Float
) {
    SMALL("Small", 17f, 1.45f),
    MEDIUM("Medium", 20f, 1.5f),
    LARGE("Large", 23f, 1.55f),
    EXTRA_LARGE("Extra large", 26f, 1.6f)
}

/**
 * A reading position inside a chapter: paragraph index plus character offset.
 * [END] means "the last page of the chapter" and is resolved once the chapter is paginated.
 */
data class EpubAnchor(val paragraph: Int, val offset: Int) : Comparable<EpubAnchor> {
    override fun compareTo(other: EpubAnchor): Int =
        compareValuesBy(this, other, { it.paragraph }, { it.offset })

    companion object {
        val START = EpubAnchor(0, 0)
        val END = EpubAnchor(Int.MAX_VALUE, 0)
    }
}

/** Pure page-navigation helpers, kept free of Android types so they can be unit tested. */
object EpubPaging {

    /** Index of the page that contains [anchor], given each page's starting anchor in order. */
    fun pageIndexFor(pageStarts: List<EpubAnchor>, anchor: EpubAnchor): Int {
        if (pageStarts.isEmpty()) return 0
        val idx = pageStarts.indexOfLast { it <= anchor }
        return idx.coerceAtLeast(0)
    }

    sealed interface Step {
        /** Stay in the same chapter and move to this anchor. */
        data class SamePage(val anchor: EpubAnchor) : Step
        /** Move to another chapter; pagination for it must be recomputed. */
        data class OtherChapter(val chapter: Int, val anchor: EpubAnchor) : Step
        /** Already at the beginning or end of the book. */
        data object None : Step
    }

    fun next(pageStarts: List<EpubAnchor>, anchor: EpubAnchor, chapter: Int, chapterCount: Int): Step {
        if (pageStarts.isEmpty()) return Step.None
        val current = pageIndexFor(pageStarts, anchor)
        return when {
            current + 1 < pageStarts.size -> Step.SamePage(pageStarts[current + 1])
            chapter + 1 < chapterCount -> Step.OtherChapter(chapter + 1, EpubAnchor.START)
            else -> Step.None
        }
    }

    fun previous(pageStarts: List<EpubAnchor>, anchor: EpubAnchor, chapter: Int): Step {
        if (pageStarts.isEmpty()) return Step.None
        val current = pageIndexFor(pageStarts, anchor)
        return when {
            current > 0 -> Step.SamePage(pageStarts[current - 1])
            chapter > 0 -> Step.OtherChapter(chapter - 1, EpubAnchor.END)
            else -> Step.None
        }
    }
}
