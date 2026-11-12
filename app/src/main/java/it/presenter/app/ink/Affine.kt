package it.presenter.app.ink

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Affine transformation of the plane (in page coordinates): x' = a·x + c·y + e, y' = b·x + d·y + f. Used to
 * move, resize and rotate page elements; pure Kotlin, so the geometry is tested on the JVM (Android's
 * `Matrix` does not work there).
 */
class Affine(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val e: Float = 0f,
    val f: Float = 0f,
) {
    fun mapX(x: Float, y: Float) = a * x + c * y + e
    fun mapY(x: Float, y: Float) = b * x + d * y + f

    /** Transformed copy of a flat list x0, y0, x1, y1, … */
    fun mapPoints(points: FloatArray): FloatArray = FloatArray(points.size).also { out ->
        for (k in points.indices step 2) {
            out[k] = mapX(points[k], points[k + 1])
            out[k + 1] = mapY(points[k], points[k + 1])
        }
    }

    /** First this transformation, then [next]. */
    fun then(next: Affine) = Affine(
        a = next.a * a + next.c * b,
        b = next.b * a + next.d * b,
        c = next.a * c + next.c * d,
        d = next.b * c + next.d * d,
        e = next.a * e + next.c * f + next.e,
        f = next.b * e + next.d * f + next.f,
    )

    /** Average scale factor (for widths and text sizes). */
    val scale: Float get() = sqrt(abs(a * d - b * c))

    /** Rotation (radians) applied to the x axis. */
    val rotation: Float get() = atan2(b, a)

    val isIdentity get() = a == 1f && b == 0f && c == 0f && d == 1f && e == 0f && f == 0f

    companion object {
        val IDENTITY = Affine()

        fun translate(dx: Float, dy: Float) = Affine(e = dx, f = dy)

        /** Scale by [sx], [sy] along the axes of a frame rotated by [angle] with origin at [px], [py]. */
        fun scaleAbout(sx: Float, sy: Float, px: Float, py: Float, angle: Float = 0f): Affine =
            translate(-px, -py).then(rotate(-angle)).then(Affine(a = sx, d = sy)).then(rotate(angle)).then(translate(px, py))

        fun rotate(angle: Float, px: Float = 0f, py: Float = 0f): Affine {
            val cs = cos(angle)
            val sn = sin(angle)
            val r = Affine(a = cs, b = sn, c = -sn, d = cs)
            return if (px == 0f && py == 0f) r else translate(-px, -py).then(r).then(translate(px, py))
        }
    }
}

/** Axis-aligned box, in page coordinates. */
data class Box(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val width get() = maxX - minX
    val height get() = maxY - minY
    val centerX get() = (minX + maxX) / 2f
    val centerY get() = (minY + maxY) / 2f

    fun inflate(r: Float) = Box(minX - r, minY - r, maxX + r, maxY + r)

    fun intersects(o: Box) = maxX >= o.minX && minX <= o.maxX && maxY >= o.minY && minY <= o.maxY

    fun contains(x: Float, y: Float) = x in minX..maxX && y in minY..maxY

    fun union(o: Box) = Box(minOf(minX, o.minX), minOf(minY, o.minY), maxOf(maxX, o.maxX), maxOf(maxY, o.maxY))

    companion object {
        /** Box of a flat list of points, or null if it is empty. */
        fun of(points: FloatArray): Box? {
            if (points.size < 2) return null
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (k in points.indices step 2) {
                minX = minOf(minX, points[k]); maxX = maxOf(maxX, points[k])
                minY = minOf(minY, points[k + 1]); maxY = maxOf(maxY, points[k + 1])
            }
            return Box(minX, minY, maxX, maxY)
        }
    }
}

/** Elementary geometry on page points (pure, tested on the JVM). */
object Geometry {
    /** Distance of point ([px], [py]) from segment ([x0], [y0])–([x1], [y1]). */
    fun pointSegment(px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - x0) * dx + (py - y0) * dy) / len2).coerceIn(0f, 1f)
        val qx = x0 + t * dx - px
        val qy = y0 + t * dy - py
        return sqrt(qx * qx + qy * qy)
    }

    /** Minimum distance between two segments. */
    fun segmentSegment(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Float {
        if (segmentsCross(ax, ay, bx, by, cx, cy, dx, dy)) return 0f
        return minOf(
            minOf(pointSegment(ax, ay, cx, cy, dx, dy), pointSegment(bx, by, cx, cy, dx, dy)),
            minOf(pointSegment(cx, cy, ax, ay, bx, by), pointSegment(dx, dy, ax, ay, bx, by)),
        )
    }

    private fun segmentsCross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
        fun cross(ox: Float, oy: Float, px: Float, py: Float, qx: Float, qy: Float) = (px - ox) * (qy - oy) - (py - oy) * (qx - ox)
        val d1 = cross(cx, cy, dx, dy, ax, ay)
        val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy)
        val d4 = cross(ax, ay, bx, by, dx, dy)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    /** Minimum distance between segment ([x0], [y0])–([x1], [y1]) and a polyline (closed if [closed]). */
    fun segmentPolyline(x0: Float, y0: Float, x1: Float, y1: Float, pts: FloatArray, closed: Boolean): Float {
        val n = pts.size / 2
        if (n == 0) return Float.MAX_VALUE
        if (n == 1) return pointSegment(pts[0], pts[1], x0, y0, x1, y1)
        var best = Float.MAX_VALUE
        val last = if (closed) n else n - 1
        for (i in 0 until last) {
            val j = (i + 1) % n
            best = minOf(best, segmentSegment(x0, y0, x1, y1, pts[2 * i], pts[2 * i + 1], pts[2 * j], pts[2 * j + 1]))
        }
        return best
    }

    /** Point inside polygon [pts] (even-odd rule). */
    fun insidePolygon(x: Float, y: Float, pts: FloatArray): Boolean {
        val n = pts.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = pts[2 * i]
            val yi = pts[2 * i + 1]
            val xj = pts[2 * j]
            val yj = pts[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}
