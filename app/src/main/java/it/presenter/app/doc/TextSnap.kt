package it.presenter.app.doc

import it.presenter.app.ink.Box
import it.presenter.app.ink.Geometry

/**
 * Highlighter snapped to text: from the freehand stroke to the PDF text lines it covers. Like a text
 * selection: from the first to the last word touched (whole words), one rectangle per line.
 */
object TextSnap {
    /**
     * Lines highlighted by stroke [stroke] (page points, width [width]) on page [page], or null if the stroke touches
     * no text.
     */
    fun snap(page: PdfTextIndex.PageText, stroke: FloatArray, width: Float): List<PageRect>? {
        val reachBox = Box.of(stroke)?.inflate(width + MAX_CHAR) ?: return null
        val text = page.text
        val boxes = page.boxes
        var first = -1
        var last = -1
        for (i in text.indices) {
            val l = boxes[4 * i]
            if (l.isNaN() || text[i].isWhitespace()) continue
            val t = boxes[4 * i + 1]
            val r = boxes[4 * i + 2]
            val b = boxes[4 * i + 3]
            val cx = (l + r) / 2f
            val cy = (t + b) / 2f
            if (!reachBox.contains(cx, cy)) continue
            // A character is taken if the stroke passes over its center (with the stroke's width).
            val reach = maxOf(width / 2f, (b - t) * 0.5f)
            if (Geometry.segmentPolyline(cx, cy, cx, cy, stroke, false) <= reach) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) return null
        while (first > 0 && !text[first - 1].isWhitespace()) first--
        while (last < text.length - 1 && !text[last + 1].isWhitespace()) last++
        return page.rects(first..last).takeIf { it.isNotEmpty() }
    }

    /** Quadrilaterals for [rects] (top-left, top-right, bottom-right, bottom-left), with a little vertical margin. */
    fun quads(rects: List<PageRect>): FloatArray {
        val out = FloatArray(rects.size * 8)
        for ((k, r) in rects.withIndex()) {
            val pad = (r.bottom - r.top) * PAD
            val top = r.top - pad
            val bottom = r.bottom + pad
            floatArrayOf(r.left, top, r.right, top, r.right, bottom, r.left, bottom).copyInto(out, 8 * k)
        }
        return out
    }

    /** Maximum character height considered in the search (page units). */
    private const val MAX_CHAR = 60f
    private const val PAD = 0.12f
}
