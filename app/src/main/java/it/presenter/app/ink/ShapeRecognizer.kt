package it.presenter.app.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shape recognition from a freehand stroke (flat list of points in page coordinates). It always returns a
 * shape: if it resembles nothing precise, the simplified polyline of the stroke.
 *
 * - Open stroke: **line** if almost straight; **curve** (3-point Bézier) if smooth; otherwise **polyline**
 *   with its corners.
 * - Closed stroke (the end comes back near the start): **ellipse** if roundish; otherwise a polygon:
 *   **triangle**, **rectangle** (also rotated; straightened when its sides are almost horizontal and
 *   vertical) or any polygon.
 */
object ShapeRecognizer {
    /** Recognized type and control points. */
    class Result(val type: ShapeType, val points: FloatArray)

    fun recognize(raw: FloatArray): Result {
        val pts = dedupe(raw)
        val n = pts.size / 2
        if (n < 2) return Result(ShapeType.LINE, floatArrayOf(raw[0], raw[1], raw[0] + 0.1f, raw[1]))
        val length = pathLength(pts, closed = false)
        val box = Box.of(pts)!!
        val diag = hypot(box.width, box.height).coerceAtLeast(1f)
        val gap = hypot(pts[0] - pts[2 * n - 2], pts[1] - pts[2 * n - 1])
        val closed = n >= 4 && gap < CLOSE_RATIO * length && gap < 0.35f * diag
        return if (closed) closedShape(pts, diag) else openShape(pts, length)
    }

    // ------------------------------------------------------------------ open shapes

    private fun openShape(pts: FloatArray, length: Float): Result {
        val n = pts.size / 2
        val x0 = pts[0]
        val y0 = pts[1]
        val x1 = pts[2 * n - 2]
        val y1 = pts[2 * n - 1]
        val chord = hypot(x1 - x0, y1 - y0)
        val lineDev = maxDeviation(pts, floatArrayOf(x0, y0, x1, y1), closed = false)
        if (chord > 0f && lineDev < LINE_TOLERANCE * chord && length < 1.15f * chord) {
            return Result(ShapeType.LINE, snapLine(x0, y0, x1, y1))
        }
        val tol = (OPEN_TOLERANCE * length).coerceAtLeast(MIN_TOLERANCE)
        val corners = simplify(pts, tol)
        // Curve: passes through the point at half length; it wins if it follows the stroke better than the
        // polyline with its corners and the stroke has no sharp corners.
        val mid = pointAtLength(pts, length / 2f)
        val curve = Shape(ShapeType.CURVE, floatArrayOf(x0, y0, mid[0], mid[1], x1, y1), 0, 1f)
        val curveDev = maxDeviation(pts, curve.outline(), closed = false)
        val sharp = hasSharpCorner(corners, closed = false)
        if (!sharp && curveDev < CURVE_TOLERANCE * length) return Result(ShapeType.CURVE, curve.points)
        if (corners.size == 4) return Result(ShapeType.LINE, snapLine(corners[0], corners[1], corners[2], corners[3]))
        return Result(ShapeType.POLYLINE, corners)
    }

    /** Almost horizontal or vertical line: straightened. */
    private fun snapLine(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
        val angle = atan2(y1 - y0, x1 - x0)
        val snapped = snapAngle(angle, AXIS_SNAP)
        if (snapped == angle) return floatArrayOf(x0, y0, x1, y1)
        val len = hypot(x1 - x0, y1 - y0)
        val mx = (x0 + x1) / 2f
        val my = (y0 + y1) / 2f
        val dx = cos(snapped) * len / 2f
        val dy = sin(snapped) * len / 2f
        return floatArrayOf(mx - dx, my - dy, mx + dx, my + dy)
    }

    // ------------------------------------------------------------------ closed shapes

    private fun closedShape(pts: FloatArray, diag: Float): Result {
        val ellipse = fitEllipse(pts)
        val corners = cleanCorners(simplifyClosed(pts, (CLOSED_TOLERANCE * diag).coerceAtLeast(MIN_TOLERANCE)))
        val count = corners.size / 2
        if (ellipse != null && (ellipse.second < ELLIPSE_TOLERANCE || count >= 6 || count < 3)) {
            return Result(ShapeType.ELLIPSE, ellipse.first)
        }
        // Almost flat closed stroke (there and back): a line between the two farthest points.
        if (count < 3) return if (corners.size >= 4) Result(ShapeType.LINE, snapLine(corners[0], corners[1], corners[2], corners[3])) else openShape(pts, pathLength(pts, closed = false))
        if (count == 4 && rightAngled(corners)) return Result(ShapeType.POLYGON, rectangle(corners))
        return Result(ShapeType.POLYGON, corners)
    }

    /**
     * Ellipse with the principal axes of the stroke (principal component analysis) as the 4 corners of the
     * rectangle containing it, plus the average relative error of the points against the ellipse.
     */
    private fun fitEllipse(pts: FloatArray): Pair<FloatArray, Float>? {
        val n = pts.size / 2
        var cx = 0f
        var cy = 0f
        for (k in 0 until n) { cx += pts[2 * k]; cy += pts[2 * k + 1] }
        cx /= n; cy /= n
        var sxx = 0f
        var syy = 0f
        var sxy = 0f
        for (k in 0 until n) {
            val dx = pts[2 * k] - cx
            val dy = pts[2 * k + 1] - cy
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        var angle = 0.5f * atan2(2 * sxy, sxx - syy)
        angle = snapAngle(angle, ELLIPSE_AXIS_SNAP)
        val ux = cos(angle)
        val uy = sin(angle)
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (k in 0 until n) {
            val dx = pts[2 * k] - cx
            val dy = pts[2 * k + 1] - cy
            val u = dx * ux + dy * uy
            val v = -dx * uy + dy * ux
            minU = minOf(minU, u); maxU = maxOf(maxU, u)
            minV = minOf(minV, v); maxV = maxOf(maxV, v)
        }
        val a = (maxU - minU) / 2f
        val b = (maxV - minV) / 2f
        if (a < 1e-3f || b < 1e-3f) return null
        val mu = (maxU + minU) / 2f
        val mv = (maxV + minV) / 2f
        var err = 0f
        for (k in 0 until n) {
            val dx = pts[2 * k] - cx
            val dy = pts[2 * k + 1] - cy
            val u = (dx * ux + dy * uy - mu) / a
            val v = (-dx * uy + dy * ux - mv) / b
            err += abs(sqrt(u * u + v * v) - 1f)
        }
        val ccx = cx + mu * ux - mv * uy
        val ccy = cy + mu * uy + mv * ux
        fun corner(su: Float, sv: Float) = floatArrayOf(ccx + su * a * ux - sv * b * uy, ccy + su * a * uy + sv * b * ux)
        val rect = corner(-1f, -1f) + corner(1f, -1f) + corner(1f, 1f) + corner(-1f, 1f)
        return rect to err / n
    }

    /** The 4 corners form almost right angles. */
    private fun rightAngled(c: FloatArray): Boolean {
        for (i in 0 until 4) {
            val p = (i + 3) % 4
            val q = (i + 1) % 4
            val ax = c[2 * p] - c[2 * i]
            val ay = c[2 * p + 1] - c[2 * i + 1]
            val bx = c[2 * q] - c[2 * i]
            val by = c[2 * q + 1] - c[2 * i + 1]
            val cosine = (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by)).coerceAtLeast(1e-6f)
            if (abs(cosine) > RIGHT_ANGLE_COS) return false
        }
        return true
    }

    /**
     * Exact rectangle from an almost rectangular quadrilateral: average orientation of the sides (circular
     * mean of 4·angle), straightened if almost aligned with the axes; sides from the projections of the
     * vertices. Vertices in the same order the stroke followed.
     */
    private fun rectangle(c: FloatArray): FloatArray {
        var sx = 0f
        var sy = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val a = atan2(c[2 * j + 1] - c[2 * i + 1], c[2 * j] - c[2 * i])
            sx += cos(4 * a); sy += sin(4 * a)
        }
        val angle = snapAngle(atan2(sy, sx) / 4f, AXIS_SNAP)
        val ux = cos(angle)
        val uy = sin(angle)
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val u = c[2 * i] * ux + c[2 * i + 1] * uy
            val v = -c[2 * i] * uy + c[2 * i + 1] * ux
            minU = minOf(minU, u); maxU = maxOf(maxU, u)
            minV = minOf(minV, v); maxV = maxOf(maxV, v)
        }
        // Each vertex goes to the closest rectangle corner, so the order stays the stroke's.
        val out = FloatArray(8)
        val midU = (minU + maxU) / 2f
        val midV = (minV + maxV) / 2f
        for (i in 0 until 4) {
            val u = c[2 * i] * ux + c[2 * i + 1] * uy
            val v = -c[2 * i] * uy + c[2 * i + 1] * ux
            val ru = if (u < midU) minU else maxU
            val rv = if (v < midV) minV else maxV
            out[2 * i] = ru * ux - rv * uy
            out[2 * i + 1] = ru * uy + rv * ux
        }
        return out
    }

    /** Angle snapped to the nearest multiple of 90° if closer than [tolerance] radians. */
    fun snapAngle(angle: Float, tolerance: Float): Float {
        val quarter = (PI / 2).toFloat()
        val nearest = Math.round(angle / quarter) * quarter
        return if (abs(angle - nearest) < tolerance) nearest else angle
    }

    // ------------------------------------------------------------------ simplification

    /** Douglas-Peucker on an open polyline: the corners remain. */
    fun simplify(pts: FloatArray, tol: Float): FloatArray {
        val n = pts.size / 2
        if (n <= 2) return pts
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        rdp(pts, 0, n - 1, tol, keep)
        return pick(pts, keep)
    }

    /** Douglas-Peucker on a closed path: it starts from the two points farthest apart. */
    private fun simplifyClosed(pts: FloatArray, tol: Float): FloatArray {
        val n = pts.size / 2
        var far = 0
        var best = 0f
        for (k in 1 until n) {
            val d = hypot(pts[2 * k] - pts[0], pts[2 * k + 1] - pts[1])
            if (d > best) { best = d; far = k }
        }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[far] = true
        rdp(pts, 0, far, tol, keep)
        // Second half: from "far" back to the first point.
        val tail = FloatArray(2 * (n - far + 1))
        for (k in far until n) { tail[2 * (k - far)] = pts[2 * k]; tail[2 * (k - far) + 1] = pts[2 * k + 1] }
        tail[tail.size - 2] = pts[0]
        tail[tail.size - 1] = pts[1]
        val keepTail = BooleanArray(n - far + 1)
        keepTail[0] = true
        keepTail[keepTail.size - 1] = true
        rdp(tail, 0, keepTail.size - 1, tol, keepTail)
        for (k in 1 until keepTail.size - 1) if (keepTail[k]) keep[far + k] = true
        return pick(pts, keep)
    }

    private fun rdp(pts: FloatArray, from: Int, to: Int, tol: Float, keep: BooleanArray) {
        if (to <= from + 1) return
        var index = -1
        var best = 0f
        for (k in from + 1 until to) {
            val d = Geometry.pointSegment(pts[2 * k], pts[2 * k + 1], pts[2 * from], pts[2 * from + 1], pts[2 * to], pts[2 * to + 1])
            if (d > best) { best = d; index = k }
        }
        if (best > tol && index >= 0) {
            keep[index] = true
            rdp(pts, from, index, tol, keep)
            rdp(pts, index, to, tol, keep)
        }
    }

    private fun pick(pts: FloatArray, keep: BooleanArray): FloatArray {
        val out = ArrayList<Float>()
        for (k in keep.indices) if (keep[k]) { out.add(pts[2 * k]); out.add(pts[2 * k + 1]) }
        return out.toFloatArray()
    }

    /** Vertices of a closed polygon without those almost aligned with their neighbors or too close together. */
    private fun cleanCorners(c: FloatArray): FloatArray {
        var pts = c
        var changed = true
        while (changed && pts.size / 2 > 3) {
            changed = false
            val n = pts.size / 2
            val perimeter = pathLength(pts, closed = true)
            for (i in 0 until n) {
                val p = (i + n - 1) % n
                val q = (i + 1) % n
                val ax = pts[2 * i] - pts[2 * p]
                val ay = pts[2 * i + 1] - pts[2 * p + 1]
                val bx = pts[2 * q] - pts[2 * i]
                val by = pts[2 * q + 1] - pts[2 * i + 1]
                val turn = abs(angleBetween(ax, ay, bx, by))
                val short = hypot(ax, ay) < MIN_EDGE_RATIO * perimeter
                if (turn < MIN_TURN || short) {
                    pts = pts.copyOfRange(0, 2 * i) + pts.copyOfRange(2 * i + 2, pts.size)
                    changed = true
                    break
                }
            }
        }
        return pts
    }

    private fun hasSharpCorner(c: FloatArray, closed: Boolean): Boolean {
        val n = c.size / 2
        val range = if (closed) 0 until n else 1 until n - 1
        for (i in range) {
            val p = (i + n - 1) % n
            val q = (i + 1) % n
            val turn = abs(angleBetween(c[2 * i] - c[2 * p], c[2 * i + 1] - c[2 * p + 1], c[2 * q] - c[2 * i], c[2 * q + 1] - c[2 * i + 1]))
            if (turn > SHARP_TURN) return true
        }
        return false
    }

    private fun angleBetween(ax: Float, ay: Float, bx: Float, by: Float): Float = atan2(ax * by - ay * bx, ax * bx + ay * by)

    // ------------------------------------------------------------------ measures

    private fun dedupe(raw: FloatArray): FloatArray {
        val out = ArrayList<Float>(raw.size)
        for (k in raw.indices step 2) {
            val n = out.size
            if (n >= 2 && hypot(raw[k] - out[n - 2], raw[k + 1] - out[n - 1]) < 0.05f) continue
            out.add(raw[k]); out.add(raw[k + 1])
        }
        return out.toFloatArray()
    }

    fun pathLength(pts: FloatArray, closed: Boolean): Float {
        val n = pts.size / 2
        var len = 0f
        for (k in 1 until n) len += hypot(pts[2 * k] - pts[2 * k - 2], pts[2 * k + 1] - pts[2 * k - 1])
        if (closed && n > 1) len += hypot(pts[0] - pts[2 * n - 2], pts[1] - pts[2 * n - 1])
        return len
    }

    private fun pointAtLength(pts: FloatArray, target: Float): FloatArray {
        var acc = 0f
        for (k in 1 until pts.size / 2) {
            val seg = hypot(pts[2 * k] - pts[2 * k - 2], pts[2 * k + 1] - pts[2 * k - 1])
            if (acc + seg >= target && seg > 0f) {
                val t = (target - acc) / seg
                return floatArrayOf(pts[2 * k - 2] + t * (pts[2 * k] - pts[2 * k - 2]), pts[2 * k - 1] + t * (pts[2 * k + 1] - pts[2 * k - 1]))
            }
            acc += seg
        }
        return floatArrayOf(pts[pts.size - 2], pts[pts.size - 1])
    }

    /** Maximum distance of the stroke's points from polyline [shape]. */
    private fun maxDeviation(pts: FloatArray, shape: FloatArray, closed: Boolean): Float {
        var worst = 0f
        for (k in pts.indices step 2) {
            val d = Geometry.segmentPolyline(pts[k], pts[k + 1], pts[k], pts[k + 1], shape, closed)
            worst = maxOf(worst, d)
        }
        return worst
    }

    /** The stroke is closed if the end comes back within this fraction of the length from the start. */
    private const val CLOSE_RATIO = 0.17f
    private const val LINE_TOLERANCE = 0.06f
    private const val OPEN_TOLERANCE = 0.035f
    private const val CURVE_TOLERANCE = 0.045f
    private const val CLOSED_TOLERANCE = 0.06f
    private const val MIN_TOLERANCE = 1.5f
    private const val ELLIPSE_TOLERANCE = 0.075f
    private const val RIGHT_ANGLE_COS = 0.30f
    private val AXIS_SNAP = (6 * PI / 180).toFloat()
    private val ELLIPSE_AXIS_SNAP = (10 * PI / 180).toFloat()
    private val MIN_TURN = (22 * PI / 180).toFloat()
    private val SHARP_TURN = (55 * PI / 180).toFloat()
    private const val MIN_EDGE_RATIO = 0.04f
}
