package it.presenter.app.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Geometry of the selection (shapes and lasso), pure and tested on the JVM: oriented box with its handles,
 * the transformations derived from them, the guides shown while snapping ([Snapping]) and the lasso.
 */
object SelectionGeometry {
    /** Box handles: corners and sides clockwise from the top left, then the rotation. */
    enum class Handle(val u: Int, val v: Int) {
        TOP_LEFT(-1, -1), TOP(0, -1), TOP_RIGHT(1, -1), RIGHT(1, 0),
        BOTTOM_RIGHT(1, 1), BOTTOM(0, 1), BOTTOM_LEFT(-1, 1), LEFT(-1, 0),
        ROTATE(0, -2),
    }

    /**
     * Box of [elements] in the axes rotated by [angle], enlarged by [pad]: (minU, minV, maxU, maxV) in the
     * rotated frame. [toPage] goes back to the page.
     */
    fun localBox(elements: List<PageElement>, angle: Float, pad: Float): FloatArray {
        val cs = cos(angle)
        val sn = sin(angle)
        var minU = Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (e in elements) {
            val pts = e.samplePoints()
            val half = (e.strokeWidth ?: 0f) / 2f
            for (k in pts.indices step 2) {
                val u = pts[k] * cs + pts[k + 1] * sn
                val v = -pts[k] * sn + pts[k + 1] * cs
                minU = minOf(minU, u - half); maxU = maxOf(maxU, u + half)
                minV = minOf(minV, v - half); maxV = maxOf(maxV, v + half)
            }
        }
        if (minU > maxU) return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(minU - pad, minV - pad, maxU + pad, maxV + pad)
    }

    /** Point ([u], [v]) of the frame rotated by [angle], in page coordinates. */
    fun toPage(u: Float, v: Float, angle: Float): FloatArray {
        val cs = cos(angle)
        val sn = sin(angle)
        return floatArrayOf(u * cs - v * sn, u * sn + v * cs)
    }

    /** The 4 corners of the box in page coordinates (top-left, top-right, bottom-right, bottom-left). */
    fun corners(box: FloatArray, angle: Float): FloatArray =
        toPage(box[0], box[1], angle) + toPage(box[2], box[1], angle) + toPage(box[2], box[3], angle) + toPage(box[0], box[3], angle)

    /** Position of a handle in page coordinates; the rotation one is [rotateOffset] above the top side. */
    fun handlePosition(h: Handle, box: FloatArray, angle: Float, rotateOffset: Float): FloatArray {
        val mu = (box[0] + box[2]) / 2f
        val mv = (box[1] + box[3]) / 2f
        val u = when (h.u) { -1 -> box[0]; 1 -> box[2]; else -> mu }
        val v = when (h.v) {
            -1 -> box[1]
            1 -> box[3]
            -2 -> box[1] - rotateOffset
            else -> mv
        }
        return toPage(u, v, angle)
    }

    /**
     * Resize by dragging handle [h] of [box] to page point ([x], [y]): the opposite side or corner stays put.
     * The scale does not go below [MIN_SCALE] and does not flip. With [uniform] (corners) both scales are equal.
     */
    fun resize(h: Handle, box: FloatArray, angle: Float, x: Float, y: Float, uniform: Boolean): Affine {
        val s = resizeScales(h, box, angle, x, y, uniform)
        return resizeAffine(h, box, angle, s[0], s[1])
    }

    /** The scales (sx, sy) of [resize], to snap before building the transformation with [resizeAffine]. */
    fun resizeScales(h: Handle, box: FloatArray, angle: Float, x: Float, y: Float, uniform: Boolean): FloatArray {
        val cs = cos(angle)
        val sn = sin(angle)
        val u = x * cs + y * sn
        val v = -x * sn + y * cs
        val w = (box[2] - box[0]).coerceAtLeast(1e-3f)
        val hh = (box[3] - box[1]).coerceAtLeast(1e-3f)
        val anchorU = if (h.u < 0) box[2] else box[0]
        val anchorV = if (h.v < 0) box[3] else box[1]
        var sx = if (h.u == 0) 1f else ((u - anchorU) * h.u / w).coerceAtLeast(MIN_SCALE)
        var sy = if (h.v == 0) 1f else ((v - anchorV) * h.v / hh).coerceAtLeast(MIN_SCALE)
        if (uniform && h.u != 0 && h.v != 0) {
            val s = maxOf(sx, sy)
            sx = s
            sy = s
        }
        return floatArrayOf(sx, sy)
    }

    /** Scale ([sx], [sy]) around the side or corner opposite handle [h]. */
    fun resizeAffine(h: Handle, box: FloatArray, angle: Float, sx: Float, sy: Float): Affine {
        val anchorU = if (h.u < 0) box[2] else box[0]
        val anchorV = if (h.v < 0) box[3] else box[1]
        val anchor = toPage(if (h.u == 0) box[0] else anchorU, if (h.v == 0) box[1] else anchorV, angle)
        return Affine.scaleAbout(sx, sy, anchor[0], anchor[1], angle)
    }

    /**
     * Orientation of the box of [elements]: that of the text boxes and shapes (a shape's longest side), if they
     * all share it, folded within ±45° (the box turned by 90° is the same); otherwise 0. Rotating then snaps to
     * the horizontal and vertical of the elements themselves ([rotation]), not to 90° from where they were.
     */
    fun naturalAngle(elements: List<PageElement>): Float {
        val angles = elements.map { orientationOf(it) ?: return 0f }
        val first = angles.firstOrNull()?.let(::fold) ?: return 0f
        return if (angles.all { abs(fold(it - first)) < SAME_ANGLE }) first else 0f
    }

    /** Direction of a text box (its rotation) or of a shape (its longest side); none for strokes and highlights. */
    private fun orientationOf(e: PageElement): Float? = when (e) {
        is TextBox -> e.rotation
        is Shape -> {
            val p = e.points
            val n = p.size / 2
            val closed = e.type == ShapeType.POLYGON || e.type == ShapeType.ELLIPSE
            (0 until if (closed) n else n - 1).maxByOrNull { k ->
                val j = (k + 1) % n
                hypot(p[2 * j] - p[2 * k], p[2 * j + 1] - p[2 * k + 1])
            }?.let { k ->
                val j = (k + 1) % n
                atan2(p[2 * j + 1] - p[2 * k + 1], p[2 * j] - p[2 * k])
            }
        }
        else -> null
    }

    /** [a] brought within ±45° by quarter turns. */
    private fun fold(a: Float): Float {
        val quarter = (PI / 2).toFloat()
        return a - Math.round(a / quarter) * quarter
    }

    /**
     * Rotation by dragging the handle from ([x0], [y0]) to ([x1], [y1]) around ([cx], [cy]): the added angle,
     * snapped so that [startAngle] + angle falls on a multiple of 90° when it passes close.
     */
    fun rotation(cx: Float, cy: Float, x0: Float, y0: Float, x1: Float, y1: Float, startAngle: Float): Float {
        val delta = atan2(y1 - cy, x1 - cx) - atan2(y0 - cy, x0 - cx)
        val total = startAngle + delta
        return ShapeRecognizer.snapAngle(total, ROTATION_SNAP) - startAngle
    }

    /** Guide shown while snapping (tablet only): segment between two aligned points, or point hit. */
    sealed interface Guide {
        class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : Guide
        class Point(val x: Float, val y: Float) : Guide
    }

    /** Point ([x], [y]) is inside the oriented box (with a margin [pad]). */
    fun inside(box: FloatArray, angle: Float, x: Float, y: Float, pad: Float = 0f): Boolean {
        val cs = cos(angle)
        val sn = sin(angle)
        val u = x * cs + y * sn
        val v = -x * sn + y * cs
        return u >= box[0] - pad && u <= box[2] + pad && v >= box[1] - pad && v <= box[3] + pad
    }

    /** Fraction of the points of [e] inside polygon [lasso]: decides whether the lasso takes the element. */
    fun lassoCoverage(e: PageElement, lasso: FloatArray): Float {
        val pts = e.samplePoints()
        val n = pts.size / 2
        if (n == 0) return 0f
        var inside = 0
        for (k in 0 until n) if (Geometry.insidePolygon(pts[2 * k], pts[2 * k + 1], lasso)) inside++
        return inside.toFloat() / n
    }

    /** Distance between two points. */
    fun distance(x0: Float, y0: Float, x1: Float, y1: Float) = hypot(x1 - x0, y1 - y0)

    const val MIN_SCALE = 0.05f
    private val ROTATION_SNAP = (5 * PI / 180).toFloat()

    /** Orientations closer than this (radians) are the same. */
    private const val SAME_ANGLE = 1e-3f
}
