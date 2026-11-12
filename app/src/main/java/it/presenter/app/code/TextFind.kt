package it.presenter.app.code

/** Search in the text of a file and positions of its lines (IDE: find, go to line). */
object TextFind {
    /** Starts of [query] in [text], ignoring case, without overlaps; none for an empty query. */
    fun matches(text: String, query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var i = text.indexOf(query, ignoreCase = true)
        while (i >= 0) {
            out += i
            i = text.indexOf(query, i + query.length, ignoreCase = true)
        }
        return out
    }

    /** Offset where line [line] starts (counted from 1); beyond the last line, the last one. */
    fun lineStart(text: String, line: Int): Int {
        var start = 0
        repeat(line.coerceAtLeast(1) - 1) {
            val next = text.indexOf('\n', start)
            if (next < 0) return start
            start = next + 1
        }
        return start
    }

    /** Line (counted from 0) of [offset]. */
    fun lineOf(text: String, offset: Int): Int {
        var n = 0
        for (k in 0 until offset.coerceIn(0, text.length)) if (text[k] == '\n') n++
        return n
    }
}
