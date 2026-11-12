package it.presenter.app.ink

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pen gestures recognized from strokes, pure logic tested on the JVM.
 *
 * - **Scribble**: a dense stroke going back and forth many times in a small space; it erases the elements
 *   it covers and does not stay on the page.
 * - **Lasso circle**: an (almost) closed stroke at the end of which the tip stays still for a moment; it
 *   becomes a selection instead of ink (the pause is measured by the drawing surface).
 */
object PenGestures {
    /**
     * Stroke [pts] (flat list, page coordinates) is a scribble: long enough compared to the space it takes and
     * with many direction reversals along the main axis.
     */
    fun isScribble(pts: FloatArray): Boolean {
        val n = pts.size / 2
        if (n < 8) return false
        val box = Box.of(pts) ?: return false
        val diag = hypot(box.width, box.height)
        if (diag < MIN_SCRIBBLE_SIZE) return false
        val length = ShapeRecognizer.pathLength(pts, closed = false)
        if (length < SCRIBBLE_LENGTH_RATIO * diag) return false
        // Main axis: the one along which the scribble goes back and forth (the widest).
        val horizontal = box.width >= box.height
        var reversals = 0
        var direction = 0
        var anchor = if (horizontal) pts[0] else pts[1]
        val step = maxOf(diag * REVERSAL_STEP, 1f)
        for (k in 1 until n) {
            val v = if (horizontal) pts[2 * k] else pts[2 * k + 1]
            val d = v - anchor
            if (abs(d) < step) continue
            val dir = if (d > 0) 1 else -1
            if (direction != 0 && dir != direction) reversals++
            direction = dir
            anchor = v
        }
        return reversals >= MIN_REVERSALS
    }

    /**
     * Elements erased by a scribble [pts] of width [width]: those the stroke touches and whose center lies in
     * the scribble's area (so a long line that happens to pass there, but continues far away, does not vanish).
     */
    fun scribbled(elements: List<PageElement>, pts: FloatArray, width: Float): List<PageElement> {
        val zone = Box.of(pts)?.inflate(width + ZONE_PAD) ?: return emptyList()
        return elements.filter { e ->
            val b = e.bounds()
            if (!b.intersects(zone) || !zone.contains(b.centerX, b.centerY)) return@filter false
            var touched = false
            var k = 2
            while (k < pts.size && !touched) {
                touched = e.touches(pts[k - 2], pts[k - 1], pts[k], pts[k + 1], width / 2f)
                k += 2
            }
            touched
        }
    }

    /**
     * Stroke [pts] can be the lasso circle: large enough and closed (the end comes back near the start, or the
     * stroke loops around itself).
     */
    fun isLassoLoop(pts: FloatArray): Boolean {
        val n = pts.size / 2
        if (n < 6) return false
        val box = Box.of(pts) ?: return false
        val diag = hypot(box.width, box.height)
        if (diag < MIN_LASSO_SIZE) return false
        val gap = hypot(pts[0] - pts[2 * n - 2], pts[1] - pts[2 * n - 1])
        val length = ShapeRecognizer.pathLength(pts, closed = false)
        return gap < LASSO_CLOSE_RATIO * length && gap < 0.5f * diag
    }

    /** Minimum side (page units) of a scribble and of a lasso circle. */
    private const val MIN_SCRIBBLE_SIZE = 12f
    private const val MIN_LASSO_SIZE = 30f

    /** A scribble is at least this many times as long as the diagonal of the area it covers. */
    private const val SCRIBBLE_LENGTH_RATIO = 3.5f
    private const val REVERSAL_STEP = 0.15f
    private const val MIN_REVERSALS = 4
    private const val ZONE_PAD = 4f
    private const val LASSO_CLOSE_RATIO = 0.2f
}
