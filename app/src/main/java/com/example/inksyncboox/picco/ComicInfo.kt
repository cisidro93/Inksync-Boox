package com.example.inksyncboox.picco

/**
 * The fields of a comic's `ComicInfo.xml` (the Anansi/ComicRack schema used by Mihon, Komga,
 * Kavita and most taggers) that matter for the Picco export.
 */
data class ComicInfo(
    val title: String? = null,
    val series: String? = null,
    val number: String? = null,
    val volume: String? = null,
    val writer: String? = null,
    /** Manga = "YesAndRightToLeft". */
    val rightToLeft: Boolean = false
) {
    /** Book details for the export; [fallbackTitle] is the file name without extension. */
    fun meta(fallbackTitle: String): PiccoMeta {
        val s = series
        val bookTitle = when {
            s != null -> buildString {
                append(s)
                if (volume != null) append(" Vol. ").append(volume)
                if (number != null) append(" #").append(number)
                val t = title
                if (t != null && !t.equals(s, ignoreCase = true)) append(" – ").append(t)
            }
            title != null -> title
            else -> fallbackTitle
        }
        return PiccoMeta(
            title = bookTitle,
            author = writer.orEmpty(),
            series = s,
            seriesIndex = if (s != null) number ?: volume else null
        )
    }

    companion object {
        /** Lenient parse: reads the first occurrence of each tag; returns null if nothing useful is found. */
        fun parse(xml: String?): ComicInfo? {
            if (xml.isNullOrBlank()) return null
            fun tag(name: String): String? {
                val m = Regex("<$name(?:\\s[^>]*)?>([\\s\\S]*?)</$name\\s*>", RegexOption.IGNORE_CASE).find(xml) ?: return null
                val raw = m.groupValues[1]
                val text = unescape(
                    raw.replace(Regex("^\\s*<!\\[CDATA\\[([\\s\\S]*)]]>\\s*$"), "$1")
                ).replace(Regex("\\s+"), " ").trim()
                return text.ifEmpty { null }
            }
            val info = ComicInfo(
                title = tag("Title"),
                series = tag("Series"),
                number = tag("Number"),
                volume = tag("Volume")?.takeIf { it != "-1" && it != "0" },
                writer = tag("Writer")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(3)?.joinToString(", "),
                rightToLeft = tag("Manga").equals("YesAndRightToLeft", ignoreCase = true)
            )
            return if (info == ComicInfo()) null else info
        }

        private fun unescape(s: String): String {
            if ('&' !in s) return s
            return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(s) { m ->
                val e = m.groupValues[1]
                when {
                    e == "amp" -> "&"
                    e == "lt" -> "<"
                    e == "gt" -> ">"
                    e == "quot" -> "\""
                    e == "apos" -> "'"
                    e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
                    else -> e.substring(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
                }
            }
        }
    }
}
