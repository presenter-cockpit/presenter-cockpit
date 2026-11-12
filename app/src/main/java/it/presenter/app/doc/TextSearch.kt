package it.presenter.app.doc

import java.text.Normalizer

/**
 * Text search that ignores case, accents and ligatures ("ﬁ" = "fi"), as expected when searching a PDF.
 * It works on a normalized version of the text that remembers, for each of its characters, the original
 * character it comes from: so results map back to the original indices (and to the positions on the page).
 */
object TextSearch {
    class Normalized(val text: String, private val origin: IntArray) {
        fun originOf(i: Int) = origin[i]
    }

    fun normalize(s: CharSequence): Normalized {
        val out = StringBuilder(s.length)
        val origin = ArrayList<Int>(s.length)
        for (i in s.indices) {
            val decomposed = Normalizer.normalize(s[i].toString(), Normalizer.Form.NFKD)
            for (c in decomposed) {
                // Diacritical marks separated by NFKD (accents): removed.
                if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
                val folded = if (c.isWhitespace()) ' ' else c.lowercaseChar()
                // Consecutive spaces count as one (line breaks, multiple spaces between words).
                if (folded == ' ' && out.isNotEmpty() && out.last() == ' ') continue
                out.append(folded)
                origin.add(i)
            }
        }
        return Normalized(out.toString(), origin.toIntArray())
    }

    /** Ranges (first and last character included) of the original text where [query] appears. */
    fun find(text: Normalized, query: String): List<IntRange> {
        val q = normalize(query.trim()).text
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val i = text.text.indexOf(q, from)
            if (i < 0) break
            hits.add(text.originOf(i)..text.originOf(i + q.length - 1))
            from = i + 1
        }
        return hits
    }

    /** Snippet of [text] around [range], for the list of results. */
    fun snippet(text: String, range: IntRange, context: Int = 40): String {
        val start = (range.first - context).coerceAtLeast(0)
        val end = (range.last + 1 + context).coerceAtMost(text.length)
        val body = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
        return (if (start > 0) "…" else "") + body + (if (end < text.length) "…" else "")
    }
}
