package com.example.inksyncboox.epub

import android.content.Context
import android.net.Uri
import android.graphics.Typeface
import android.text.Html
import android.text.style.StyleSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.math.max

/**
 * `.epub` parser.
 *
 * Parses any DRM-free `.epub` file selected by the user via Android's Storage Access Framework (`Uri`):
 * 1. Streams the ZIP archive entries in memory-safe chunks.
 * 2. Parses `META-INF/container.xml` to locate the package `.opf` descriptor.
 * 3. Parses `<metadata>`, `<manifest>`, and `<spine>` from the `.opf` to determine exact reading order.
 * 4. Extracts and strips/normalizes XHTML chapter bodies into `EpubChapter` and `EpubParagraph` blocks
 *    with deterministic paragraph IDs (`chX_pY`) so highlights stay attached, keeping bold/italic runs.
 *
 * Limitations: images, tables and CSS layout are not rendered; DRM-protected books are not supported.
 */
object RealEpubParser {

    suspend fun parseEpubFromUri(
        context: Context,
        uri: Uri,
        fallbackFileName: String = "Imported Book.epub"
    ): EpubBookDocument? = withContext(Dispatchers.IO) {
        val entries = mutableMapOf<String, ByteArray>()

        context.contentResolver.openInputStream(uri)?.use { rawStream ->
            ZipInputStream(rawStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.replace('\\', '/')
                        val lower = name.lowercase()
                        // Only buffer text/xml/opf/xhtml/ncx entries (skip giant embedded fonts/videos)
                        if (lower.endsWith(".xml") ||
                            lower.endsWith(".opf") ||
                            lower.endsWith(".xhtml") ||
                            lower.endsWith(".html") ||
                            lower.endsWith(".htm") ||
                            lower.endsWith(".ncx")
                        ) {
                            val bytes = zip.readBytes()
                            if (bytes.size <= 4 * 1024 * 1024) {
                                entries[name] = bytes
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: return@withContext null

        if (entries.isEmpty()) return@withContext null

        // 1. Locate OPF path via META-INF/container.xml (or fallback to first .opf entry)
        val containerBytes = entries["META-INF/container.xml"]
        val opfPath = containerBytes?.let { parseContainerXmlForOpfPath(it) }
            ?: entries.keys.firstOrNull { it.lowercase().endsWith(".opf") }
            ?: return@withContext null

        val opfBytes = entries[opfPath] ?: entries.entries.firstOrNull {
            it.key.equals(opfPath, ignoreCase = true)
        }?.value ?: return@withContext null

        val opfDir = opfPath.substringBeforeLast('/', "")
        val parsedOpf = parseOpfDocument(opfBytes, fallbackFileName)

        // 2. Build ordered chapters from spine -> manifest hrefs
        val chapters = mutableListOf<EpubChapter>()
        val orderedHrefs = if (parsedOpf.spineIds.isNotEmpty()) {
            parsedOpf.spineIds.mapNotNull { parsedOpf.manifestById[it] }
        } else {
            parsedOpf.manifestById.values.filter {
                val l = it.lowercase()
                l.endsWith(".xhtml") || l.endsWith(".html") || l.endsWith(".htm")
            }
        }

        var chapterIdx = 0
        for (href in orderedHrefs) {
            val resolvedPath = resolveRelativePath(opfDir, href)
            val htmlBytes = entries[resolvedPath]
                ?: entries[href]
                ?: entries.entries.firstOrNull { it.key.endsWith(href, ignoreCase = true) }?.value
                ?: continue

            val extracted = extractChapterFromHtml(
                htmlBytes = htmlBytes,
                chapterIndex = chapterIdx,
                defaultChapterTitle = "Section ${chapterIdx + 1}"
            )
            if (extracted != null && extracted.paragraphs.isNotEmpty()) {
                chapters.add(extracted)
                chapterIdx++
            }
        }

        // Fallback if spine had unconventional IDs: scan all HTML/XHTML files in alphabetical order
        if (chapters.isEmpty()) {
            entries.keys
                .filter {
                    val l = it.lowercase()
                    l.endsWith(".xhtml") || l.endsWith(".html") || l.endsWith(".htm")
                }
                .sorted()
                .forEach { key ->
                    val bytes = entries[key] ?: return@forEach
                    val extracted = extractChapterFromHtml(
                        htmlBytes = bytes,
                        chapterIndex = chapterIdx,
                        defaultChapterTitle = key.substringAfterLast('/').substringBeforeLast('.')
                    )
                    if (extracted != null && extracted.paragraphs.isNotEmpty()) {
                        chapters.add(extracted)
                        chapterIdx++
                    }
                }
        }

        if (chapters.isEmpty()) return@withContext null

        EpubBookDocument(
            id = "epub_${uri.toString().hashCode()}",
            title = parsedOpf.title.ifBlank { fallbackFileName.removeSuffix(".epub") },
            author = parsedOpf.author,
            chapters = chapters
        )
    }

    private fun parseContainerXmlForOpfPath(bytes: ByteArray): String? {
        return try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name.endsWith("rootfile", ignoreCase = true)) {
                    val fullPath = parser.getAttributeValue(null, "full-path")
                    if (!fullPath.isNullOrBlank()) return fullPath
                }
                event = parser.next()
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private data class OpfMetadataResult(
        val title: String,
        val author: String,
        val manifestById: Map<String, String>,
        val spineIds: List<String>
    )

    private fun parseOpfDocument(bytes: ByteArray, fallbackTitle: String): OpfMetadataResult {
        var title = ""
        var author = ""
        val manifest = linkedMapOf<String, String>()
        val spine = mutableListOf<String>()

        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
            var event = parser.eventType
            var currentTag = ""

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name.lowercase()
                        if (currentTag.endsWith("item")) {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                                manifest[id] = href.substringBefore('#')
                            }
                        } else if (currentTag.endsWith("itemref")) {
                            val idref = parser.getAttributeValue(null, "idref")
                            if (!idref.isNullOrBlank()) {
                                spine.add(idref)
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            if (currentTag.endsWith("title") && title.isEmpty()) {
                                title = text
                            } else if ((currentTag.endsWith("creator") || currentTag.endsWith("author")) && author.isEmpty()) {
                                author = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        currentTag = ""
                    }
                }
                event = parser.next()
            }
        } catch (_: Throwable) {
        }

        return OpfMetadataResult(
            title = title.ifBlank { fallbackTitle.removeSuffix(".epub") },
            author = author,
            manifestById = manifest,
            spineIds = spine
        )
    }

    private fun resolveRelativePath(baseDir: String, relativeHref: String): String {
        val cleanHref = relativeHref.substringBefore('#').trimStart('/')
        if (baseDir.isBlank()) return cleanHref
        val rawParts = "$baseDir/$cleanHref".split('/')
        val normalized = mutableListOf<String>()
        for (part in rawParts) {
            when (part) {
                "", "." -> Unit
                ".." -> if (normalized.isNotEmpty()) normalized.removeAt(normalized.lastIndex)
                else -> normalized.add(part)
            }
        }
        return normalized.joinToString("/")
    }

    private fun decodeHtml(bytes: ByteArray): String {
        // Honour an explicit XML/HTML encoding declaration; EPUB requires UTF-8 or UTF-16 but
        // older books sometimes declare windows-1252 / iso-8859-1.
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1)
        val declared = Regex("(?i)(?:encoding|charset)\\s*=\\s*[\"']?([A-Za-z0-9._-]+)")
            .find(head)?.groupValues?.getOrNull(1)
        val charset = try {
            declared?.let { java.nio.charset.Charset.forName(it) } ?: Charsets.UTF_8
        } catch (_: Throwable) {
            Charsets.UTF_8
        }
        return String(bytes, charset).removePrefix("\uFEFF")
    }

    /**
     * Converts an HTML fragment to plain text with collapsed whitespace, keeping bold/italic
     * runs as [EpubInlineSpan]s whose offsets refer to the collapsed text.
     */
    internal fun htmlToStyledText(innerHtml: String): Pair<String, List<EpubInlineSpan>> {
        val spanned = Html.fromHtml(innerHtml, Html.FROM_HTML_MODE_LEGACY)
        val raw = spanned.toString()
        val out = StringBuilder(raw.length)
        val map = IntArray(raw.length + 1)
        var lastWasSpace = true // drops leading whitespace
        for (i in raw.indices) {
            map[i] = out.length
            val c = raw[i]
            if (c == '\uFFFC') continue // embedded image placeholder
            if (c.isWhitespace()) {
                if (!lastWasSpace) {
                    out.append(' ')
                    lastWasSpace = true
                }
            } else {
                out.append(c)
                lastWasSpace = false
            }
        }
        map[raw.length] = out.length
        while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
        val textLength = out.length

        val spans = spanned.getSpans(0, spanned.length, StyleSpan::class.java).mapNotNull { span ->
            val bold = span.style == Typeface.BOLD || span.style == Typeface.BOLD_ITALIC
            val italic = span.style == Typeface.ITALIC || span.style == Typeface.BOLD_ITALIC
            if (!bold && !italic) return@mapNotNull null
            val s = map[spanned.getSpanStart(span).coerceIn(0, raw.length)].coerceAtMost(textLength)
            val e = map[spanned.getSpanEnd(span).coerceIn(0, raw.length)].coerceAtMost(textLength)
            if (e > s) EpubInlineSpan(s, e, bold, italic) else null
        }
        return out.toString() to spans
    }

    private fun extractChapterFromHtml(
        htmlBytes: ByteArray,
        chapterIndex: Int,
        defaultChapterTitle: String
    ): EpubChapter? {
        val rawHtml = try {
            decodeHtml(htmlBytes)
        } catch (_: Throwable) {
            return null
        }

        // Remove <head>, <style>, and <script> blocks so CSS rules don't appear in text
        val cleanedHtml = rawHtml
            .replace(Regex("(?is)<head\\b[^>]*>.*?</head>"), "")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), "")
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), "")

        // Extract heading if present
        val headingMatch = Regex("(?is)<h[1-3]\\b[^>]*>(.*?)</h[1-3]>").find(cleanedHtml)
        val detectedHeading = headingMatch?.groupValues?.getOrNull(1)?.let {
            htmlToStyledText(it).first
        }?.takeIf { it.isNotBlank() }

        // Split into block-level paragraphs (<p>, <h1>..<h6>, <blockquote>, <li>)
        val blockMatches = Regex("(?is)<(h[1-6]|p|blockquote|li)\\b[^>]*>(.*?)</\\1>").findAll(cleanedHtml).toList()

        val paragraphs = mutableListOf<EpubParagraph>()

        if (blockMatches.isNotEmpty()) {
            var pIdx = 0
            for (match in blockMatches) {
                val tag = match.groupValues[1].lowercase()
                val (text, spans) = htmlToStyledText(match.groupValues[2])
                if (text.isNotEmpty()) {
                    paragraphs.add(
                        EpubParagraph(
                            cfiId = "ch${chapterIndex + 1}_p${pIdx + 1}",
                            chapterIndex = chapterIndex,
                            indexInChapter = pIdx,
                            text = text,
                            isHeading = tag.startsWith("h"),
                            isBlockQuote = tag == "blockquote",
                            spans = spans
                        )
                    )
                    pIdx++
                }
            }
        } else {
            // Fallback for plain/div-based XHTML chapters
            val plainAll = Html.fromHtml(cleanedHtml, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace("\uFFFC", "")
            plainAll.split(Regex("\\n\\s*\\n|\\r\\n\\s*\\r\\n"))
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotEmpty() }
                .forEachIndexed { pIdx, line ->
                    paragraphs.add(
                        EpubParagraph(
                            cfiId = "ch${chapterIndex + 1}_p${pIdx + 1}",
                            chapterIndex = chapterIndex,
                            indexInChapter = pIdx,
                            text = line,
                            isHeading = pIdx == 0 && line.length < 90
                        )
                    )
                }
        }

        if (paragraphs.isEmpty()) return null

        val wordCount = paragraphs.sumOf { it.text.split(' ').size }
        val estimatedMins = max(1, wordCount / 220)

        return EpubChapter(
            index = chapterIndex,
            title = detectedHeading ?: defaultChapterTitle,
            estimatedMinutes = estimatedMins,
            paragraphs = paragraphs
        )
    }
}
