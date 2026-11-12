package it.presenter.app.laser

import it.presenter.app.tools.LaserStyle

/**
 * State of the laser pointer, in page coordinates, with times in `SystemClock.uptimeMillis()` (the same
 * clock as MotionEvent). Drawn on both the tablet and the projector by [LaserRenderer].
 */
class LaserTrail {
    /** Trail style: how long the path stays solid after lifting the pen (a setting). */
    var inkHoldMs = DEFAULT_INK_HOLD_MS

    /**
     * Shape made of several strokes (an arrow: shaft and head): the strokes of a shape fade together,
     * starting from the end of the last one (user's request).
     */
    class Figure {
        /** Strokes of the shape still being drawn. */
        var down = 0
        var endTime = -1L
    }

    /**
     * One laser stroke. [width] is the maximum width; with [pressure] each point has its own width,
     * following the pen pressure (filtered so it does not flicker).
     */
    class Gesture(val style: LaserStyle, val color: Int, val width: Float, val pressure: Boolean, val figure: Figure = Figure()) {
        var size = 0
            private set
        var xs = FloatArray(256)
            private set
        var ys = FloatArray(256)
            private set
        var ts = LongArray(256)
            private set
        var ws = FloatArray(256)
            private set

        private var smoothedPressure = -1f

        /** Width of the last point (the bright head). */
        val headWidth get() = if (size > 0) ws[size - 1] else width

        /** First point still visible (in COMET mode old points expire). */
        var start = 0

        var endTime = -1L
        val isDown get() = endTime < 0

        // Predicted point (not stored in the trail): brings the head closer to the pen tip.
        var hasPrediction = false
        var predX = 0f
        var predY = 0f

        /** [p]: pressure 0…1, or a negative value when not available (finger, mouse). */
        fun add(x: Float, y: Float, t: Long, p: Float) {
            val w = widthFor(p)
            if (size > 0 && xs[size - 1] == x && ys[size - 1] == y) {
                ts[size - 1] = t
                ws[size - 1] = w
                return
            }
            if (size == xs.size) grow()
            xs[size] = x
            ys[size] = y
            ts[size] = t
            ws[size] = w
            size++
        }

        private fun widthFor(p: Float): Float {
            if (!pressure || p < 0f) return width
            smoothedPressure = if (smoothedPressure < 0f) p else smoothedPressure + PRESSURE_SMOOTHING * (p - smoothedPressure)
            // Same response as the pen: 30% → 100%, more sensitive to light touches.
            val eased = 1f - (1f - smoothedPressure.coerceIn(0f, 1f)).let { it * it }
            return width * (MIN_PRESSURE_WIDTH + (1f - MIN_PRESSURE_WIDTH) * eased)
        }

        private fun grow() {
            // Before doubling, drop the points that have already expired.
            if (start > 0) {
                val n = size - start
                System.arraycopy(xs, start, xs, 0, n)
                System.arraycopy(ys, start, ys, 0, n)
                System.arraycopy(ts, start, ts, 0, n)
                System.arraycopy(ws, start, ws, 0, n)
                size = n
                start = 0
                if (size < xs.size) return
            }
            xs = xs.copyOf(xs.size * 2)
            ys = ys.copyOf(ys.size * 2)
            ts = ts.copyOf(ts.size * 2)
            ws = ws.copyOf(ws.size * 2)
        }
    }

    val gestures = ArrayList<Gesture>()
    var current: Gesture? = null
        private set

    // Pen hovering over the screen with the laser active: only the dot is shown.
    var hovering = false
        private set
    var hoverX = 0f
        private set
    var hoverY = 0f
        private set
    var hoverColor = 0
        private set
    var hoverWidth = 0f
        private set
    private var hoverEnd = -1L

    fun begin(style: LaserStyle, color: Int, width: Float, pressure: Boolean, x: Float, y: Float, t: Long, p: Float) {
        hovering = false
        hoverEnd = -1L
        // A Trail stroke that starts while the previous one is still solid belongs to the same shape.
        val last = gestures.lastOrNull()
        val figure = if (style == LaserStyle.FADING_INK && last != null && last.style == LaserStyle.FADING_INK && gestureAlpha(last, t) >= 1f) {
            last.figure
        } else Figure()
        figure.down++
        val g = Gesture(style, color, width, pressure, figure)
        g.add(x, y, t, p)
        gestures.add(g)
        current = g
    }

    fun add(x: Float, y: Float, t: Long, p: Float) {
        current?.add(x, y, t, p)
    }

    fun predict(x: Float, y: Float) {
        current?.apply {
            hasPrediction = true
            predX = x
            predY = y
        }
    }

    fun clearPrediction() {
        current?.hasPrediction = false
    }

    fun end(t: Long) {
        current?.apply {
            endTime = t
            hasPrediction = false
            figure.down = (figure.down - 1).coerceAtLeast(0)
            figure.endTime = maxOf(figure.endTime, t)
        }
        current = null
    }

    fun hover(color: Int, width: Float, x: Float, y: Float) {
        hovering = true
        hoverEnd = -1L
        hoverColor = color
        hoverWidth = width
        hoverX = x
        hoverY = y
    }

    fun hoverExit(t: Long) {
        if (hovering) {
            hovering = false
            hoverEnd = t
        }
    }

    /** Alpha of the hover dot (fades after the pen moves away). */
    fun hoverAlpha(now: Long): Float = when {
        hovering -> 1f
        hoverEnd < 0 -> 0f
        else -> (1f - (now - hoverEnd) / DOT_FADE_MS.toFloat()).coerceIn(0f, 1f)
    }

    fun clear() {
        gestures.clear()
        current = null
        hovering = false
        hoverEnd = -1L
    }

    /** Removes gestures that are no longer visible; true if something is left to animate. */
    fun prune(now: Long): Boolean {
        val it = gestures.iterator()
        while (it.hasNext()) {
            val g = it.next()
            if (!g.isDown && gestureAlpha(g, now) <= 0f && (g.style != LaserStyle.COMET || g.start >= g.size)) {
                it.remove()
            }
        }
        if (hoverEnd >= 0 && now - hoverEnd > DOT_FADE_MS) hoverEnd = -1L
        return gestures.isNotEmpty() || hovering || hoverEnd >= 0
    }

    /** Overall opacity of a gesture: 1 while drawing, then it fades. */
    fun gestureAlpha(g: Gesture, now: Long): Float {
        if (g.isDown) return 1f
        return when (g.style) {
            // The tail runs out by itself as it ages; only the dot fade matters here.
            LaserStyle.COMET -> if (g.start < g.size) 1f else 0f
            // The whole shape stays solid while one of its strokes is being drawn, then fades as one.
            LaserStyle.FADING_INK -> {
                val f = g.figure
                val since = if (f.down > 0) 0L else now - maxOf(f.endTime, g.endTime)
                when {
                    since <= inkHoldMs -> 1f
                    else -> (1f - (since - inkHoldMs) / INK_FADE_MS.toFloat()).coerceIn(0f, 1f)
                }
            }
        }
    }

    fun dotAlpha(g: Gesture, now: Long): Float =
        if (g.isDown) 1f else (1f - (now - g.endTime) / DOT_FADE_MS.toFloat()).coerceIn(0f, 1f)

    companion object {
        /** Tail duration in COMET mode. */
        const val COMET_TRAIL_MS = 380L

        /** In FADING_INK mode the path stays for [inkHoldMs] after lifting the pen… */
        const val DEFAULT_INK_HOLD_MS = 1500L

        /** …and then fades in this time. */
        const val INK_FADE_MS = 450L

        const val DOT_FADE_MS = 220L

        private const val MIN_PRESSURE_WIDTH = 0.3f
        private const val PRESSURE_SMOOTHING = 0.35f
    }
}
