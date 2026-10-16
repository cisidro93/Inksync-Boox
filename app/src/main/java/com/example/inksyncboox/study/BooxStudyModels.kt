package com.example.inksyncboox.study

import android.content.Context
import com.example.inksyncboox.ink.BooxInkStroke
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Unified Study Hub & Cornell Note Card Model.
 *
 * Bridges both PDF (NeoFlow Smart Tiers) and EPUB (Kindle Reflow CFI) into a single
 * searchable binder, with automatic `.inksync.json` + `.md` Markdown sidecar export
 * so annotations survive app updates and sync cleanly via Syncthing/Drive.
 */
enum class BooxWorkspaceMode(
    val title: String,
    val badge: String,
    val subtitle: String
) {
    SANCTUARY_SHELF(
        title = "Library Sanctuary",
        badge = "SHELF",
        subtitle = "Unified EPUB + PDF + Panels Comics Desk · Zero Daily Streak Guilt"
    ),
    PANELS_COMICS(
        title = "Panels Studio",
        badge = "PANELS",
        subtitle = "Guided Panel Spotlight · 2-Page Spread (+1 Cover) · Manga RTL & Comic LTR"
    ),
    PDF_NEOFLOW(
        title = "PDF NeoFlow",
        badge = "PDF",
        subtitle = "M×N Matrix Tiers · Wacom EMR Ink · Kaleido 3 Pastels"
    ),
    KINDLE_EPUB(
        title = "Kindle Reflow",
        badge = "EPUB",
        subtitle = "300 PPI Bookerly Serif · CFI Margin Ink · Pace Telemetry"
    ),
    STUDY_BINDER(
        title = "Study Hub & Export",
        badge = "HUB",
        subtitle = "Unified Highlights, Handwritten Cornell Cards & Markdown Sync"
    )
}

data class UnifiedStudyCard(
    val id: String,
    val sourceType: String, // "PDF" or "EPUB"
    val documentTitle: String,
    val locatorBadge: String, // e.g. "Page 1 · Tier 2/4" or "Ch 1 · ¶3 (ch1_p3)"
    val pageOrChapterIndex: Int,
    val tierOrParagraphIndex: Int,
    val quoteText: String,
    val reflectionCue: String,
    val swatchId: String,
    val colorHex: Long,
    val timestampLabel: String
)

enum class CornellScratchpadTemplate(val label: String) {
    CORNELL_CUE_GRID("Cornell Cue + Dot Grid"),
    RULED_ACADEMIC("7mm Ruled Legal"),
    ISOMETRIC_DOT("5mm Dot Matrix")
}

object BooxSidecarPersistence {

    /**
     * Exports all unified study cards and Wacom stroke counts to both human-readable Markdown (`.md`)
     * and structured JSON (`.inksync.json`) inside the app's accessible files directory.
     */
    fun exportSidecarFiles(
        context: Context,
        documentTitle: String,
        cards: List<UnifiedStudyCard>,
        strokes: List<BooxInkStroke>
    ): Pair<File, File> {
        val dir = File(context.filesDir, "InkSyncSidecars").apply { mkdirs() }
        val safeBaseName = documentTitle
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('_')
            .ifEmpty { "inksync_notebook" }

        val mdFile = File(dir, "$safeBaseName.md")
        val jsonFile = File(dir, "$safeBaseName.inksync.json")

        val mdBuilder = StringBuilder().apply {
            appendLine("# $documentTitle — InkSync Boox Study Binder")
            appendLine("> Generated on Onyx Boox Note Air 4C · Kaleido 3 E-Ink Edition")
            appendLine("> Total Pinned Cards: ${cards.size} · Total Wacom EMR Strokes: ${strokes.size}")
            appendLine()
            appendLine("## Unified Highlights & Cornell Synthesis")
            appendLine()
            if (cards.isEmpty()) {
                appendLine("_No highlights pinned yet._")
            } else {
                cards.forEachIndexed { idx, card ->
                    appendLine("### ${idx + 1}. [${card.sourceType}] ${card.locatorBadge} (${card.swatchId.uppercase()})")
                    appendLine("> \"${card.quoteText}\"")
                    if (card.reflectionCue.isNotBlank()) {
                        appendLine()
                        appendLine("- **Cornell Synthesis / Cue:** ${card.reflectionCue}")
                    }
                    appendLine()
                }
            }
        }
        mdFile.writeText(mdBuilder.toString())

        val rootJson = JSONObject().apply {
            put("documentTitle", documentTitle)
            put("totalStrokes", strokes.size)
            val cardsArray = JSONArray()
            cards.forEach { card ->
                cardsArray.put(
                    JSONObject().apply {
                        put("id", card.id)
                        put("sourceType", card.sourceType)
                        put("locatorBadge", card.locatorBadge)
                        put("quoteText", card.quoteText)
                        put("reflectionCue", card.reflectionCue)
                        put("swatchId", card.swatchId)
                    }
                )
            }
            put("studyCards", cardsArray)
        }
        jsonFile.writeText(rootJson.toString(2))

        return mdFile to jsonFile
    }
}
