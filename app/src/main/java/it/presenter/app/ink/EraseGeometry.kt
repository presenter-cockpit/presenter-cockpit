package it.presenter.app.ink

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry of the partial eraser, with no Android dependencies (testable on the JVM).
 *
 * A stroke is a polyline of sampled points. The eraser, in one step of the gesture, is a "capsule": the
 * segment [ax],[ay]→[bx],[by] widened by [radius]. The polyline is sampled densely enough near the capsule
 * (step [step]) and split into consecutive erased and kept runs.
 */
object EraseGeometry {
    /** A point on the polyline: on segment [segment] (from point `segment` to the next) at fraction [t]. */
    data class Sample(val segment: Int, val t: Float)

    data class Run(val erased: Boolean, val samples: List<Sample>)

    /**
     * Splits the polyline [xs],[ys] (its first [n] points) into erased and kept runs. Returns null if the
     * eraser touches nothing, so the caller leaves the stroke as it is.
     */
    fun split(
        xs: FloatArray, ys: FloatArray, n: Int,
        ax: Float, ay: Float, bx: Float, by: Float,
        radius: Float, step: Float,
    ): List<Run>? {
        if (n <= 0) return null
        val minX = min(ax, bx) - radius
        val maxX = max(ax, bx) + radius
        val minY = min(ay, by) - radius
        val maxY = max(ay, by) + radius
        val samples = ArrayList<Sample>(n + 16)
        val erased = ArrayList<Boolean>(n + 16)
        var any = false

        fun add(segment: Int, t: Float, x: Float, y: Float) {
            val e = distanceToSegment(x, y, ax, ay, bx, by) <= radius
            any = any || e
            samples.add(Sample(segment, t))
            erased.add(e)
        }

        for (i in 0 until n - 1) {
            val x0 = xs[i]
            val y0 = ys[i]
            val x1 = xs[i + 1]
            val y1 = ys[i + 1]
            val near = max(x0, x1) >= minX && min(x0, x1) <= maxX && max(y0, y1) >= minY && min(y0, y1) <= maxY
            val pieces = if (near) max(1, ceil(hypot(x1 - x0, y1 - y0) / step).toInt()) else 1
            for (j in 0 until pieces) {
                val t = j.toFloat() / pieces
                add(i, t, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
            }
        }
        add(n - 1, 0f, xs[n - 1], ys[n - 1])
        if (!any) return null

        val runs = ArrayList<Run>()
        var start = 0
        for (k in 1..samples.size) {
            if (k == samples.size || erased[k] != erased[start]) {
                runs.add(Run(erased[start], samples.subList(start, k).toList()))
                start = k
            }
        }
        return runs
    }

    fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
