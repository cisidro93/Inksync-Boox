package com.example.inksyncboox.study

/** Top-level screens. Reader modes follow the open document's type. */
enum class BooxWorkspaceMode(val title: String) {
    LIBRARY("Library"),
    PDF_NEOFLOW("Document"),
    PANELS_COMICS("Comic"),
    KINDLE_EPUB("Book"),
    STUDY_BINDER("Highlights")
}

/** A highlight saved from a PDF/comic page or an EPUB paragraph. */
data class UnifiedStudyCard(
    val id: String,
    val sourceType: String, // "PDF" or "EPUB"
    val documentTitle: String,
    val locatorBadge: String, // e.g. "Page 3" or "Chapter 2, paragraph 5"
    val pageOrChapterIndex: Int,
    val tierOrParagraphIndex: Int,
    val quoteText: String,
    val swatchId: String,
    val colorHex: Long,
    val createdMillis: Long = 0L
)

enum class CornellScratchpadTemplate(val label: String) {
    CORNELL_CUE_GRID("Cornell"),
    RULED_ACADEMIC("Ruled"),
    ISOMETRIC_DOT("Dot grid")
}

/** Markdown export of a document's highlights. */
object HighlightsMarkdown {

    fun build(documentTitle: String, cards: List<UnifiedStudyCard>): String = buildString {
        appendLine("# $documentTitle")
        appendLine()
        if (cards.isEmpty()) {
            appendLine("_No highlights yet._")
            return@buildString
        }
        cards.sortedWith(compareBy({ it.pageOrChapterIndex }, { it.tierOrParagraphIndex }))
            .forEach { card ->
                appendLine("## ${card.locatorBadge}")
                appendLine()
                card.quoteText.lines().forEach { appendLine("> $it") }
                appendLine()
            }
    }

    fun suggestedFileName(documentTitle: String): String {
        val base = documentTitle.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .ifEmpty { "highlights" }
        return "$base - highlights.md"
    }
}
