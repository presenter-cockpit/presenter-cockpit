package it.presenter.app.doc

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import it.presenter.app.ink.PAGE_WIDTH
import java.io.File
import kotlin.math.abs

/**
 * Text of the PDF page by page, with the position of each character in page coordinates: used by the
 * search and by the highlighter snapped to text. Built on demand, at the first search, on a worker thread.
 */
class PdfTextIndex(val pages: List<PageText>) {

    /** Text of a page; [boxes] has 4 values (left, top, right, bottom) per character, NaN for added spaces. */
    class PageText(val text: String, val boxes: FloatArray) {
        val normalized by lazy { TextSearch.normalize(text) }

        /** Rectangles of the characters [range], merged per line. */
        fun rects(range: IntRange): List<PageRect> {
            val out = ArrayList<PageRect>()
            var cur: PageRect? = null
            for (i in range) {
                val l = boxes[4 * i]
                if (l.isNaN()) continue
                val r = PageRect(l, boxes[4 * i + 1], boxes[4 * i + 2], boxes[4 * i + 3])
                val c = cur
                cur = if (c != null && abs(c.top - r.top) < (c.bottom - c.top) * 0.5f && r.left >= c.left) {
                    PageRect(c.left, minOf(c.top, r.top), maxOf(c.right, r.right), maxOf(c.bottom, r.bottom))
                } else {
                    c?.let(out::add)
                    r
                }
            }
            cur?.let(out::add)
            return out
        }
    }

    class Hit(val page: Int, val range: IntRange, val snippet: String)

    fun search(query: String, limit: Int = MAX_HITS): List<Hit> {
        val hits = ArrayList<Hit>()
        for ((p, page) in pages.withIndex()) {
            for (r in TextSearch.find(page.normalized, query)) {
                hits.add(Hit(p, r, TextSearch.snippet(page.text, r)))
                if (hits.size >= limit) return hits
            }
        }
        return hits
    }

    companion object {
        const val MAX_HITS = 500

        fun build(file: File): PdfTextIndex = PdfInkStore.load(file).use { doc ->
            PdfTextIndex((0 until doc.numberOfPages).map { extract(doc, it) })
        }

        /** Text of page [index] alone (snapping highlighter, before any search). */
        fun page(file: File, index: Int): PageText = PdfInkStore.load(file).use { doc ->
            if (index in 0 until doc.numberOfPages) extract(doc, index) else PageText("", FloatArray(0))
        }

        private fun extract(doc: PDDocument, index: Int): PageText {
            val page = doc.getPage(index)
            val box = page.cropBox
            val sideways = page.rotation % 180 != 0
            // Text positions are in points on the displayed page: a single factor for both axes.
            val k = PAGE_WIDTH / (if (sideways) box.height else box.width)
            val collector = Collector(k)
            runCatching {
                collector.startPage = index + 1
                collector.endPage = index + 1
                collector.getText(doc)
            }
            return collector.result()
        }
    }

    private class Collector(private val k: Float) : PDFTextStripper() {
        private val text = StringBuilder()
        private val boxes = ArrayList<Float>()

        init {
            sortByPosition = true
        }

        fun result() = PageText(text.toString(), boxes.toFloatArray())

        private fun gap(c: Char) {
            text.append(c)
            repeat(4) { boxes.add(Float.NaN) }
        }

        override fun writeString(string: String, positions: MutableList<TextPosition>) {
            for (tp in positions) {
                // Horizontal text only: vertical writing in the margins (Beamer) is often drawn twice and would pollute
                // search and snippets.
                if (tp.dir != 0f) continue
                val left = tp.xDirAdj * k
                val bottom = tp.yDirAdj * k
                val box = floatArrayOf(left, bottom - tp.heightDir * k, left + tp.widthDirAdj * k, bottom)
                // A ligature ("ﬁ") is one glyph but several characters: all with the same position.
                for (c in tp.unicode.orEmpty()) {
                    text.append(c)
                    box.forEach(boxes::add)
                }
            }
        }

        override fun writeWordSeparator() = gap(' ')
        override fun writeLineSeparator() = gap('\n')
    }
}
