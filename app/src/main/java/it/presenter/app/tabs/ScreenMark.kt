package it.presenter.app.tabs

/**
 * What a terminal shows at a given moment, enough to tell when its text moves: the strokes drawn over it
 * are tied to the visible lines, so they disappear as soon as the terminal scrolls (as over the code).
 */
data class ScreenMark(
    val rows: Int,
    val cols: Int,
    /** Full-screen programs (less, vim, tmux) use the alternate screen. */
    val alternate: Boolean,
    /** Lines kept above the screen. It grows when output pushes lines out of the top. */
    val scrollback: Int,
    /** Text of each visible row, from the top. */
    val lines: List<String>,
) {
    /**
     * True if from this mark to [after] the text scrolled or was mostly replaced: the size changed, lines went
     * into the scrollback, or the rows show the same text shifted up or down (full-screen programs scroll by
     * redrawing). Only lines that are not blank count, and a shift must explain clearly more lines than staying
     * put: otherwise typing, or the same output printed twice, would look like a scroll. Typing on a line and
     * output that fills the empty rows below do not count.
     */
    fun movedTo(after: ScreenMark): Boolean {
        if (rows != after.rows || cols != after.cols || alternate != after.alternate) return true
        if (scrollback != after.scrollback) return true
        val n = minOf(lines.size, after.lines.size)
        // Rows with text: unchanged, and changed (rows that were empty and got text are not counted).
        var same = 0
        var replaced = 0
        for (i in 0 until n) {
            if (lines[i].isBlank()) continue
            if (lines[i] == after.lines[i]) same++ else replaced++
        }
        // Only empty rows were filled, or nothing changed.
        if (replaced == 0) return false
        for (k in 1 until n) {
            val best = maxOf(shiftMatches(after.lines, k, n), shiftMatches(after.lines, -k, n))
            // At least two lines: a single one (a new prompt) can match by chance.
            if (best >= MIN_REPLACED && best > 2 * same) return true
        }
        // A new page (clear, a full-screen program that redraws): most of the text that was there changed.
        return replaced >= MIN_REPLACED && replaced > same
    }

    /** Non-blank rows of [after] that show the row [k] lines below (above if negative) of this mark. */
    private fun shiftMatches(after: List<String>, k: Int, n: Int): Int {
        var count = 0
        for (i in 0 until n) {
            val j = i + k
            if (j !in 0 until n) continue
            val text = after[i]
            if (text.isNotBlank() && text == lines[j]) count++
        }
        return count
    }

    private companion object {
        /** Fewer lines changed or shifted are typing or chance (a new prompt), not a scroll or a new page. */
        const val MIN_REPLACED = 2
    }
}
