package it.presenter.app.ink

import it.presenter.app.ink.SelectionGeometry.Guide
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Snapping between shapes: when dragging a vertex, moving or resizing a selection, points stick to those of
 * the other shapes on the page (vertices, sides, centers and extreme points of ellipses), side directions
 * become parallel or perpendicular, sizes become equal. Targets: only the unselected [Shape]s (neither
 * freehand strokes nor text boxes, user's choice). Pure geometry, tested on the JVM; thresholds are in
 * page units.
 */
object Snapping {
    /** What can be snapped to on a page, collected once at the start of the gesture. */
    class Targets(
        /** Snap points (x0, y0, x1, y1, …). */
        val points: FloatArray,
        /** Sides (x0, y0, x1, y1 for each). */
        val segments: FloatArray,
        /** The shapes, for equal size. */
        val shapes: List<Shape>,
    ) {
        val isEmpty get() = shapes.isEmpty()

        companion object {
            val EMPTY = Targets(FloatArray(0), FloatArray(0), emptyList())

            fun of(elements: List<PageElement>, exclude: Collection<PageElement>): Targets {
                // Elements are compared by identity (PageElement's equals).
                val excluded = exclude.toSet()
                val shapes = elements.filterIsInstance<Shape>().filter { it !in excluded }
                return Targets(anchorsOf(shapes), concat(shapes.map(::edges)), shapes)
            }
        }
    }

    /**
     * Snap points of a shape: the vertices; for curves only the ends; for ellipses the center and the 4
     * extreme points (the midpoints of the sides of the quadrilateral it is inscribed in).
     */
    fun anchors(s: Shape): FloatArray {
        val p = s.points
        return when (s.type) {
            ShapeType.LINE, ShapeType.POLYLINE, ShapeType.POLYGON -> p
            ShapeType.CURVE -> floatArrayOf(p[0], p[1], p[4], p[5])
            ShapeType.ELLIPSE -> {
                val out = FloatArray(10)
                out[0] = (p[0] + p[2] + p[4] + p[6]) / 4f
                out[1] = (p[1] + p[3] + p[5] + p[7]) / 4f
                for (k in 0 until 4) {
                    val n = (k + 1) % 4
                    out[2 + 2 * k] = (p[2 * k] + p[2 * n]) / 2f
                    out[3 + 2 * k] = (p[2 * k + 1] + p[2 * n + 1]) / 2f
                }
                out
            }
        }
    }

    /** Snap points of several shapes, one after the other. */
    fun anchorsOf(shapes: List<Shape>): FloatArray = concat(shapes.map(::anchors))

    private fun concat(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    /** Straight sides of a shape (curves and ellipses have none). */
    fun edges(s: Shape): FloatArray {
        if (s.type == ShapeType.CURVE || s.type == ShapeType.ELLIPSE) return FloatArray(0)
        val n = s.pointCount
        val count = if (s.type == ShapeType.POLYGON) n else n - 1
        val out = FloatArray(4 * count.coerceAtLeast(0))
        for (k in 0 until count) {
            val j = (k + 1) % n
            out[4 * k] = s.points[2 * k]
            out[4 * k + 1] = s.points[2 * k + 1]
            out[4 * k + 2] = s.points[2 * j]
            out[4 * k + 3] = s.points[2 * j + 1]
        }
        return out
    }

    /** Snapped point with the guides that show it. */
    class Snapped(val x: Float, val y: Float, val guides: List<Guide>)

    /**
     * Vertex [index] of [shape] dragged to ([x], [y]). Priority: on a point of another shape > on one of its
     * sides > side parallel or perpendicular to another (only for vertices of lines, polylines and polygons) >
     * alignment in x and in y, independently, with the points of the other shapes and the other vertices of
     * the same one.
     */
    fun snapVertex(shape: Shape, index: Int, x: Float, y: Float, targets: Targets, tolerance: Float): Snapped {
        // 1. Vertex on vertex.
        var best = tolerance
        var hit = -1
        val tp = targets.points
        for (k in 0 until tp.size / 2) {
            val d = hypot(tp[2 * k] - x, tp[2 * k + 1] - y)
            if (d < best) { best = d; hit = k }
        }
        if (hit >= 0) {
            val hx = tp[2 * hit]
            val hy = tp[2 * hit + 1]
            return Snapped(hx, hy, listOf(Guide.Point(hx, hy)))
        }
        // 2. On a side.
        best = tolerance
        var onX = 0f
        var onY = 0f
        val ts = targets.segments
        for (k in 0 until ts.size / 4) {
            val q = project(ts[4 * k], ts[4 * k + 1], ts[4 * k + 2], ts[4 * k + 3], x, y, clamp = true)
            val d = hypot(q[0] - x, q[1] - y)
            if (d < best) { best = d; onX = q[0]; onY = q[1]; hit = k }
        }
        if (hit >= 0) return Snapped(onX, onY, listOf(Guide.Point(onX, onY)))
        // 3. Parallel or perpendicular sides.
        parallel(shape, index, x, y, targets, tolerance)?.let { return it }
        // 4. Alignment with the points of the other shapes and with the other vertices of the same one (also the
        // corners of an ellipse's quadrilateral).
        val others = shape.points.filterIndexed { i, _ -> i / 2 != index }.toFloatArray()
        return align(x, y, concat(listOf(tp, others)), tolerance)
    }

    /** Alignment of a point in x and in y, independently, with the closest point per axis. */
    fun align(x: Float, y: Float, points: FloatArray, tolerance: Float): Snapped {
        var sx = x
        var sy = y
        var bestX = tolerance
        var bestY = tolerance
        var partnerX = -1
        var partnerY = -1
        for (j in 0 until points.size / 2) {
            val dx = abs(points[2 * j] - x)
            val dy = abs(points[2 * j + 1] - y)
            if (dx < bestX) { bestX = dx; sx = points[2 * j]; partnerX = j }
            if (dy < bestY) { bestY = dy; sy = points[2 * j + 1]; partnerY = j }
        }
        val guides = ArrayList<Guide>(2)
        if (partnerX >= 0) guides.add(Guide.Line(sx, points[2 * partnerX + 1], sx, sy))
        if (partnerY >= 0) guides.add(Guide.Line(points[2 * partnerY], sy, sx, sy))
        return Snapped(sx, sy, guides)
    }

    /**
     * The dragged vertex goes onto the line that, from the neighboring vertex left in place, is parallel (or
     * perpendicular) to a side of another shape or to a non-adjacent side of the same one; the smallest
     * correction within the threshold. Null if nothing snaps.
     */
    private fun parallel(shape: Shape, index: Int, x: Float, y: Float, targets: Targets, tolerance: Float): Snapped? {
        if (shape.type == ShapeType.CURVE || shape.type == ShapeType.ELLIPSE) return null
        val n = shape.pointCount
        val closed = shape.type == ShapeType.POLYGON
        val neighbors = buildList {
            if (index > 0 || closed) add((index - 1 + n) % n)
            if (index < n - 1 || closed) add((index + 1) % n)
        }.distinct().filter { it != index }
        if (neighbors.isEmpty()) return null
        // Reference directions: sides of the other shapes and sides of the same one that do not touch the vertex.
        val own = edges(shape)
        val refs = ArrayList<FloatArray>()
        for (k in 0 until targets.segments.size / 4) refs.add(targets.segments.copyOfRange(4 * k, 4 * k + 4))
        for (k in 0 until own.size / 4) {
            val a = k
            val b = (k + 1) % n
            if (a != index && b != index) refs.add(own.copyOfRange(4 * k, 4 * k + 4))
        }
        var best = tolerance
        var result: Snapped? = null
        for (nb in neighbors) {
            val ax = shape.points[2 * nb]
            val ay = shape.points[2 * nb + 1]
            if (hypot(x - ax, y - ay) < tolerance * 2) continue
            for (r in refs) {
                val dx = r[2] - r[0]
                val dy = r[3] - r[1]
                val len = hypot(dx, dy)
                if (len < 1e-3f) continue
                // Sides already horizontal or vertical: alignment takes care of them.
                if (abs(dx) / len > 0.999f || abs(dy) / len > 0.999f) continue
                for (perp in booleanArrayOf(false, true)) {
                    val ux = if (perp) -dy / len else dx / len
                    val uy = if (perp) dx / len else dy / len
                    val t = (x - ax) * ux + (y - ay) * uy
                    val px = ax + ux * t
                    val py = ay + uy * t
                    val d = hypot(px - x, py - y)
                    if (d < best) {
                        best = d
                        result = Snapped(px, py, listOf(Guide.Line(ax, ay, px, py), Guide.Line(r[0], r[1], r[2], r[3])))
                    }
                }
            }
        }
        return result
    }

    /** Projection of ([x], [y]) on the line (or, with [clamp], on the segment) from (x0, y0) to (x1, y1). */
    fun project(x0: Float, y0: Float, x1: Float, y1: Float, x: Float, y: Float, clamp: Boolean): FloatArray {
        val dx = x1 - x0
        val dy = y1 - y0
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-6f) return floatArrayOf(x0, y0)
        var t = ((x - x0) * dx + (y - y0) * dy) / l2
        if (clamp) t = t.coerceIn(0f, 1f)
        return floatArrayOf(x0 + dx * t, y0 + dy * t)
    }

    /** Snapped translation with the guides. */
    class Move(val dx: Float, val dy: Float, val guides: List<Guide>)

    /**
     * Moving the selection: [moving] are its snap points already moved; per axis the smallest correction
     * within the threshold towards a point of the other shapes.
     */
    fun snapMove(moving: FloatArray, targets: Targets, tolerance: Float): Move {
        val tp = targets.points
        var bestX = tolerance
        var bestY = tolerance
        var cx = 0f
        var cy = 0f
        var fromX = -1
        var toX = -1
        var fromY = -1
        var toY = -1
        for (i in 0 until moving.size / 2) {
            for (j in 0 until tp.size / 2) {
                val dx = tp[2 * j] - moving[2 * i]
                val dy = tp[2 * j + 1] - moving[2 * i + 1]
                if (abs(dx) < bestX) { bestX = abs(dx); cx = dx; fromX = i; toX = j }
                if (abs(dy) < bestY) { bestY = abs(dy); cy = dy; fromY = i; toY = j }
            }
        }
        val guides = ArrayList<Guide>(2)
        // With both corrections, does a point of the selection land exactly on a target? (At equal distance the
        // first found per axis may be another aligned point.)
        var hit = -1
        if (fromX >= 0 && fromY >= 0) {
            loop@ for (i in 0 until moving.size / 2) {
                for (j in 0 until tp.size / 2) {
                    if (abs(moving[2 * i] + cx - tp[2 * j]) < 1e-3f && abs(moving[2 * i + 1] + cy - tp[2 * j + 1]) < 1e-3f) { hit = j; break@loop }
                }
            }
        }
        if (hit >= 0) {
            guides.add(Guide.Point(tp[2 * hit], tp[2 * hit + 1]))
        } else {
            if (fromX >= 0) guides.add(Guide.Line(tp[2 * toX], tp[2 * toX + 1], tp[2 * toX], moving[2 * fromX + 1] + cy))
            if (fromY >= 0) guides.add(Guide.Line(tp[2 * toY], tp[2 * toY + 1], moving[2 * fromY] + cx, tp[2 * toY + 1]))
        }
        return Move(cx, cy, guides)
    }

    /** Family for equal size: ellipses with ellipses, polygons with polygons, open shapes with open shapes. */
    private fun family(t: ShapeType) = when (t) {
        ShapeType.ELLIPSE -> 0
        ShapeType.POLYGON -> 1
        else -> 2
    }

    /** Extent (minU, minV, maxU, maxV) of the outline of [s] in the axes rotated by [angle]. */
    fun extent(s: Shape, angle: Float): FloatArray {
        val cs = cos(angle)
        val sn = sin(angle)
        val pts = s.outline()
        var minU = Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (k in pts.indices step 2) {
            val u = pts[k] * cs + pts[k + 1] * sn
            val v = -pts[k] * sn + pts[k + 1] * cs
            minU = minOf(minU, u); maxU = maxOf(maxU, u)
            minV = minOf(minV, v); maxV = maxOf(maxV, v)
        }
        return floatArrayOf(minU, minV, maxU, maxV)
    }

    /** Snapped scales and, for each axis that snapped, the model shape (true = width). */
    class Scale(val sx: Float, val sy: Float, val models: List<Pair<Shape, Boolean>>)

    /**
     * Resizing a single shape [shape] (sizes in the frame rotated by [angle]): the width (if [scalesU]) or the
     * height (if [scalesV]) snap to those of a shape of the same family when close; with [uniform] a single
     * scale remains.
     */
    fun snapScale(
        shape: Shape, angle: Float, sx: Float, sy: Float, scalesU: Boolean, scalesV: Boolean,
        uniform: Boolean, targets: Targets, tolerance: Float,
    ): Scale {
        val e = extent(shape, angle)
        val w0 = e[2] - e[0]
        val h0 = e[3] - e[1]
        var bestU = tolerance
        var bestV = tolerance
        var modelU: Shape? = null
        var modelV: Shape? = null
        var snapU = sx
        var snapV = sy
        val fam = family(shape.type)
        for (t in targets.shapes) {
            if (family(t.type) != fam) continue
            val te = extent(t, angle)
            val tw = te[2] - te[0]
            val th = te[3] - te[1]
            if (scalesU && w0 > 1e-3f && abs(sx * w0 - tw) < bestU) { bestU = abs(sx * w0 - tw); snapU = tw / w0; modelU = t }
            if (scalesV && h0 > 1e-3f && abs(sy * h0 - th) < bestV) { bestV = abs(sy * h0 - th); snapV = th / h0; modelV = t }
        }
        if (uniform) {
            // A single scale: the axis with the smallest correction wins.
            if (modelU != null && (modelV == null || bestU <= bestV)) return Scale(snapU, snapU, listOf(modelU to true))
            if (modelV != null) return Scale(snapV, snapV, listOf(modelV to false))
            return Scale(sx, sy, emptyList())
        }
        return Scale(snapU, snapV, listOfNotNull(modelU?.let { it to true }, modelV?.let { it to false }))
    }

    /** Measure guide: segment along the width (or height) of the shape, just outside it. */
    fun measure(s: Shape, angle: Float, horizontal: Boolean, gap: Float): List<Guide> {
        val e = extent(s, angle)
        val a: FloatArray
        val b: FloatArray
        if (horizontal) {
            a = SelectionGeometry.toPage(e[0], e[1] - gap, angle)
            b = SelectionGeometry.toPage(e[2], e[1] - gap, angle)
        } else {
            a = SelectionGeometry.toPage(e[0] - gap, e[1], angle)
            b = SelectionGeometry.toPage(e[0] - gap, e[3], angle)
        }
        return listOf(Guide.Line(a[0], a[1], b[0], b[1]))
    }
}
