package com.example.inksyncboox.data

import android.content.Context
import com.example.inksyncboox.epub.EInkTypographyPreset
import com.example.inksyncboox.ink.BooxInkStroke
import com.example.inksyncboox.ink.NormalizedInkPoint
import com.example.inksyncboox.ink.QuadTextHighlight
import com.example.inksyncboox.neoflow.NormalizedRect
import com.example.inksyncboox.study.UnifiedStudyCard
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class DocKind { PDF, CBZ, EPUB }

/** A document the user has opened, with where they left off. */
data class RecentDocument(
    val uri: String,
    val title: String,
    val kind: DocKind,
    val lastOpenedMillis: Long,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val epubChapter: Int = 0,
    val epubParagraph: Int = 0,
    val epubOffset: Int = 0,
    val epubChapterCount: Int = 0
) {
    val key: String get() = documentKey(uri)

    val progressLabel: String
        get() = when (kind) {
            DocKind.EPUB -> if (epubChapterCount > 0) "Chapter ${epubChapter + 1} of $epubChapterCount" else "Not started"
            else -> if (pageCount > 0) "Page ${pageIndex + 1} of $pageCount" else "Not started"
        }
}

/** User preferences that apply across documents. */
data class ReaderSettings(
    val typography: EInkTypographyPreset = EInkTypographyPreset.MEDIUM,
    val epubTwoColumn: Boolean = false,
    /** Full GC16 flash every N page turns; 0 disables it. */
    val fullRefreshEveryNTurns: Int = 5,
    val contrastBoost: Boolean = true,
    val trimMargins: Boolean = false,
    /** Right-to-left page turning for manga. */
    val rightToLeft: Boolean = false,
    val panelSpotlight: Boolean = false,
    val darkGutter: Boolean = false
)

/** Everything the user has added to one document. */
data class DocumentAnnotations(
    val strokes: List<BooxInkStroke> = emptyList(),
    val notesStrokes: List<BooxInkStroke> = emptyList(),
    val quadHighlights: List<QuadTextHighlight> = emptyList(),
    /** EPUB paragraph id -> highlighter swatch id. */
    val epubHighlights: Map<String, String> = emptyMap(),
    val studyCards: List<UnifiedStudyCard> = emptyList()
)

fun documentKey(uri: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(20)
}

interface ReaderStore {
    fun loadSettings(): ReaderSettings
    fun saveSettings(settings: ReaderSettings)
    fun loadRecents(): List<RecentDocument>
    fun saveRecents(recents: List<RecentDocument>)
    fun loadAnnotations(key: String): DocumentAnnotations
    fun saveAnnotations(key: String, annotations: DocumentAnnotations)
}

/** In-memory store for tests and previews. */
class InMemoryReaderStore : ReaderStore {
    var settings = ReaderSettings()
    var recents: List<RecentDocument> = emptyList()
    val annotations = mutableMapOf<String, DocumentAnnotations>()

    override fun loadSettings() = settings
    override fun saveSettings(settings: ReaderSettings) { this.settings = settings }
    override fun loadRecents() = recents
    override fun saveRecents(recents: List<RecentDocument>) { this.recents = recents }
    override fun loadAnnotations(key: String) = annotations[key] ?: DocumentAnnotations()
    override fun saveAnnotations(key: String, annotations: DocumentAnnotations) {
        this.annotations[key] = annotations
    }
}

/** JSON files in the app's private storage. */
class FileReaderStore(context: Context) : ReaderStore {

    private val root = File(context.filesDir, "reader").apply { mkdirs() }
    private val settingsFile = File(root, "settings.json")
    private val recentsFile = File(root, "recents.json")
    private val annotationsDir = File(root, "annotations").apply { mkdirs() }

    private fun readJson(file: File): JSONObject? = try {
        if (file.exists()) JSONObject(file.readText()) else null
    } catch (_: Throwable) {
        null
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    @Synchronized
    override fun loadSettings(): ReaderSettings {
        val o = readJson(settingsFile) ?: return ReaderSettings()
        val d = ReaderSettings()
        return ReaderSettings(
            typography = EInkTypographyPreset.entries.firstOrNull { it.name == o.optString("typography") } ?: d.typography,
            epubTwoColumn = o.optBoolean("epubTwoColumn", d.epubTwoColumn),
            fullRefreshEveryNTurns = o.optInt("fullRefreshEveryNTurns", d.fullRefreshEveryNTurns),
            contrastBoost = o.optBoolean("contrastBoost", d.contrastBoost),
            trimMargins = o.optBoolean("trimMargins", d.trimMargins),
            rightToLeft = o.optBoolean("rightToLeft", d.rightToLeft),
            panelSpotlight = o.optBoolean("panelSpotlight", d.panelSpotlight),
            darkGutter = o.optBoolean("darkGutter", d.darkGutter)
        )
    }

    @Synchronized
    override fun saveSettings(settings: ReaderSettings) {
        val o = JSONObject()
            .put("typography", settings.typography.name)
            .put("epubTwoColumn", settings.epubTwoColumn)
            .put("fullRefreshEveryNTurns", settings.fullRefreshEveryNTurns)
            .put("contrastBoost", settings.contrastBoost)
            .put("trimMargins", settings.trimMargins)
            .put("rightToLeft", settings.rightToLeft)
            .put("panelSpotlight", settings.panelSpotlight)
            .put("darkGutter", settings.darkGutter)
        writeAtomically(settingsFile, o.toString())
    }

    @Synchronized
    override fun loadRecents(): List<RecentDocument> {
        val arr = readJson(recentsFile)?.optJSONArray("recents") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = DocKind.entries.firstOrNull { it.name == o.optString("kind") } ?: return@mapNotNull null
            RecentDocument(
                uri = o.optString("uri").ifEmpty { return@mapNotNull null },
                title = o.optString("title"),
                kind = kind,
                lastOpenedMillis = o.optLong("lastOpenedMillis"),
                pageIndex = o.optInt("pageIndex"),
                pageCount = o.optInt("pageCount"),
                epubChapter = o.optInt("epubChapter"),
                epubParagraph = o.optInt("epubParagraph"),
                epubOffset = o.optInt("epubOffset"),
                epubChapterCount = o.optInt("epubChapterCount")
            )
        }
    }

    @Synchronized
    override fun saveRecents(recents: List<RecentDocument>) {
        val arr = JSONArray()
        recents.forEach { r ->
            arr.put(
                JSONObject()
                    .put("uri", r.uri)
                    .put("title", r.title)
                    .put("kind", r.kind.name)
                    .put("lastOpenedMillis", r.lastOpenedMillis)
                    .put("pageIndex", r.pageIndex)
                    .put("pageCount", r.pageCount)
                    .put("epubChapter", r.epubChapter)
                    .put("epubParagraph", r.epubParagraph)
                    .put("epubOffset", r.epubOffset)
                    .put("epubChapterCount", r.epubChapterCount)
            )
        }
        writeAtomically(recentsFile, JSONObject().put("recents", arr).toString())
    }

    private fun annotationFile(key: String) = File(annotationsDir, "$key.json")

    @Synchronized
    override fun loadAnnotations(key: String): DocumentAnnotations {
        val o = readJson(annotationFile(key)) ?: return DocumentAnnotations()
        return DocumentAnnotations(
            strokes = o.optJSONArray("strokes").toStrokes(),
            notesStrokes = o.optJSONArray("notesStrokes").toStrokes(),
            quadHighlights = o.optJSONArray("quadHighlights").toQuads(),
            epubHighlights = o.optJSONObject("epubHighlights")?.let { h ->
                h.keys().asSequence().associateWith { h.optString(it) }
            } ?: emptyMap(),
            studyCards = o.optJSONArray("studyCards").toCards()
        )
    }

    @Synchronized
    override fun saveAnnotations(key: String, annotations: DocumentAnnotations) {
        val o = JSONObject()
            .put("strokes", annotations.strokes.toJson())
            .put("notesStrokes", annotations.notesStrokes.toJson())
            .put("quadHighlights", JSONArray().apply {
                annotations.quadHighlights.forEach { q ->
                    put(
                        JSONObject()
                            .put("id", q.id)
                            .put("pageIndex", q.pageIndex)
                            .put("rect", q.bounds.toJson())
                            .put("snippet", q.snippet)
                            .put("color", q.colorHex)
                    )
                }
            })
            .put("epubHighlights", JSONObject(annotations.epubHighlights))
            .put("studyCards", JSONArray().apply {
                annotations.studyCards.forEach { c ->
                    put(
                        JSONObject()
                            .put("id", c.id)
                            .put("sourceType", c.sourceType)
                            .put("documentTitle", c.documentTitle)
                            .put("locatorBadge", c.locatorBadge)
                            .put("pageOrChapterIndex", c.pageOrChapterIndex)
                            .put("tierOrParagraphIndex", c.tierOrParagraphIndex)
                            .put("quoteText", c.quoteText)
                            .put("swatchId", c.swatchId)
                            .put("color", c.colorHex)
                            .put("createdMillis", c.createdMillis)
                    )
                }
            })
        writeAtomically(annotationFile(key), o.toString())
    }

    private fun NormalizedRect.toJson() = JSONArray().put(left.toDouble()).put(top.toDouble())
        .put(right.toDouble()).put(bottom.toDouble())

    private fun JSONArray?.toRect(): NormalizedRect? {
        if (this == null || length() < 4) return null
        return NormalizedRect(
            optDouble(0).toFloat(), optDouble(1).toFloat(),
            optDouble(2).toFloat(), optDouble(3).toFloat()
        )
    }

    private fun List<BooxInkStroke>.toJson(): JSONArray = JSONArray().apply {
        forEach { s ->
            // Points are packed as a flat [x, y, pressure, x, y, pressure, ...] array.
            val pts = JSONArray()
            s.points.forEach { p ->
                pts.put(p.x.toDouble()).put(p.y.toDouble()).put(p.pressure.toDouble())
            }
            put(
                JSONObject()
                    .put("id", s.id)
                    .put("pageIndex", s.pageIndex)
                    .put("color", s.colorHex)
                    .put("width", s.baseWidthPt.toDouble())
                    .put("highlighter", s.isHighlighter)
                    .put("points", pts)
            )
        }
    }

    private fun JSONArray?.toStrokes(): List<BooxInkStroke> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            val pts = o.optJSONArray("points") ?: return@mapNotNull null
            val points = (0 until pts.length() / 3).map { j ->
                NormalizedInkPoint(
                    x = pts.optDouble(j * 3).toFloat(),
                    y = pts.optDouble(j * 3 + 1).toFloat(),
                    pressure = pts.optDouble(j * 3 + 2).toFloat()
                )
            }
            BooxInkStroke(
                id = o.optString("id"),
                pageIndex = o.optInt("pageIndex"),
                points = points,
                colorHex = o.optLong("color"),
                baseWidthPt = o.optDouble("width", 3.0).toFloat(),
                isHighlighter = o.optBoolean("highlighter")
            )
        }
    }

    private fun JSONArray?.toQuads(): List<QuadTextHighlight> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            QuadTextHighlight(
                id = o.optString("id"),
                pageIndex = o.optInt("pageIndex"),
                bounds = o.optJSONArray("rect").toRect() ?: return@mapNotNull null,
                snippet = o.optString("snippet"),
                colorHex = o.optLong("color")
            )
        }
    }

    private fun JSONArray?.toCards(): List<UnifiedStudyCard> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            UnifiedStudyCard(
                id = o.optString("id"),
                sourceType = o.optString("sourceType"),
                documentTitle = o.optString("documentTitle"),
                locatorBadge = o.optString("locatorBadge"),
                pageOrChapterIndex = o.optInt("pageOrChapterIndex"),
                tierOrParagraphIndex = o.optInt("tierOrParagraphIndex"),
                quoteText = o.optString("quoteText"),
                swatchId = o.optString("swatchId"),
                colorHex = o.optLong("color"),
                createdMillis = o.optLong("createdMillis")
            )
        }
    }
}
