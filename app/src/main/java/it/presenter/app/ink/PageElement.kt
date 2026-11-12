package it.presenter.app.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * An element drawn on a page, in page coordinates: freehand stroke ([InkStroke]), shape ([Shape]), text
 * box ([TextBox]) or highlight snapped to text ([TextHighlight]).
 *
 * Elements are **immutable**: every change (move, recolor, change a vertex) creates a new one, so
 * undo/redo simply keeps the lists before and after. Equality is identity: two equal copies (e.g. after
 * "duplicate") stay distinct elements.
 */
sealed interface PageElement {
    /** Color (ARGB) of the outline, the text or the highlight. */
    val color: Int

    /** Bounding box of the element, including its width. */
    fun bounds(): Box

    fun transformed(t: Affine): PageElement

    /** Same element with another color (the kind's own opacity stays, e.g. highlighter). */
    fun recolored(color: Int): PageElement

    /** Width of the stroke or line; null where it makes no sense (text, highlight). */
    val strokeWidth: Float?

    fun withStrokeWidth(width: Float): PageElement

    /** The eraser (segment [r] wide) touches the element. */
    fun touches(x0: Float, y0: Float, x1: Float, y1: Float, r: Float): Boolean

    /** Representative points (flat list), to decide whether the lasso takes the element. */
    fun samplePoints(): FloatArray

    /** Drawn in the highlighter's single layer (overlaps do not add up). */
    val isHighlight: Boolean
}

/** New color with the old opacity. */
internal fun keepAlpha(old: Int, new: Int): Int = (new and 0x00FFFFFF) or (old and 0xFF000000.toInt())

enum class ShapeType {
    /** Two points. */
    LINE,

    /** Three points: start, point on the curve halfway, end (quadratic Bézier through the second). */
    CURVE,

    /** Open polyline. */
    POLYLINE,

    /** Closed polygon (triangle, rectangle, …). */
    POLYGON,

    /** Ellipse inscribed in the 4 vertices of its rectangle (also rotated or skewed). */
    ELLIPSE,
}

enum class LineDash { SOLID, DASHED, DOTTED }

/**
 * Geometric shape: control points [points] (x0, y0, x1, y1, …) of type [type]. [fill] = 0: no fill (closed
 * shapes only). [arrow]: arrow head at the end (open shapes only).
 */
class Shape(
    val type: ShapeType,
    val points: FloatArray,
    override val color: Int,
    val width: Float,
    val fill: Int = 0,
    val dash: LineDash = LineDash.SOLID,
    val arrow: Boolean = false,
) : PageElement {
    val closed get() = type == ShapeType.POLYGON || type == ShapeType.ELLIPSE
    val hasArrow get() = arrow && !closed
    val pointCount get() = points.size / 2

    fun copy(
        type: ShapeType = this.type,
        points: FloatArray = this.points,
        color: Int = this.color,
        width: Float = this.width,
        fill: Int = this.fill,
        dash: LineDash = this.dash,
        arrow: Boolean = this.arrow,
    ) = Shape(type, points, color, width, fill, dash, arrow)

    /** Point [i] moved to ([x], [y]). */
    fun withPoint(i: Int, x: Float, y: Float): Shape {
        if (type == ShapeType.ELLIPSE) ellipseWithCorner(i, x, y)?.let { return copy(points = it) }
        return copy(points = points.copyOf().also { it[2 * i] = x; it[2 * i + 1] = y })
    }

    /**
     * Ellipse with corner [i] of its parallelogram moved to ([x], [y]): the opposite corner stays put and the
     * sides keep their direction, so the shape stays a parallelogram (stretched or shortened) and the
     * inscribed ellipse follows it. Null if the sides are degenerate.
     */
    private fun ellipseWithCorner(i: Int, x: Float, y: Float): FloatArray? {
        val o = (i + 2) % 4
        val a = (i + 1) % 4
        val b = (i + 3) % 4
        val ox = points[2 * o]
        val oy = points[2 * o + 1]
        val d1x = points[2 * a] - ox
        val d1y = points[2 * a + 1] - oy
        val d2x = points[2 * b] - ox
        val d2y = points[2 * b + 1] - oy
        val det = d1x * d2y - d1y * d2x
        if (abs(det) < 1e-6f) return null
        // (x, y) − O = s·d1 + t·d2
        val px = x - ox
        val py = y - oy
        val s = (px * d2y - py * d2x) / det
        val t = (d1x * py - d1y * px) / det
        return points.copyOf().also {
            it[2 * i] = x; it[2 * i + 1] = y
            it[2 * a] = ox + s * d1x; it[2 * a + 1] = oy + s * d1y
            it[2 * b] = ox + t * d2x; it[2 * b + 1] = oy + t * d2y
        }
    }

    /**
     * Outline as a polyline (flat list): the points for lines, polylines and polygons; sampled for curve and
     * ellipse. For closed shapes the last point does not repeat the first.
     */
    fun outline(): FloatArray = when (type) {
        ShapeType.LINE, ShapeType.POLYLINE, ShapeType.POLYGON -> points
        ShapeType.CURVE -> {
            val (cx, cy) = curveControl()
            FloatArray(2 * (CURVE_SAMPLES + 1)).also { out ->
                for (k in 0..CURVE_SAMPLES) {
                    val t = k.toFloat() / CURVE_SAMPLES
                    val u = 1 - t
                    out[2 * k] = u * u * points[0] + 2 * u * t * cx + t * t * points[4]
                    out[2 * k + 1] = u * u * points[1] + 2 * u * t * cy + t * t * points[5]
                }
            }
        }
        ShapeType.ELLIPSE -> {
            val mx = (points[0] + points[2] + points[4] + points[6]) / 4f
            val my = (points[1] + points[3] + points[5] + points[7]) / 4f
            // Semi-axes: half the sides of the parallelogram (vertices in order 0-1-2-3).
            val ux = (points[2] - points[0]) / 2f
            val uy = (points[3] - points[1]) / 2f
            val vx = (points[6] - points[0]) / 2f
            val vy = (points[7] - points[1]) / 2f
            FloatArray(2 * ELLIPSE_SAMPLES).also { out ->
                for (k in 0 until ELLIPSE_SAMPLES) {
                    val a = 2 * PI * k / ELLIPSE_SAMPLES
                    val cs = cos(a).toFloat()
                    val sn = sin(a).toFloat()
                    out[2 * k] = mx + ux * cs + vx * sn
                    out[2 * k + 1] = my + uy * cs + vy * sn
                }
            }
        }
    }

    /** Bézier control point such that the curve passes through the middle point. */
    fun curveControl(): Pair<Float, Float> =
        (2 * points[2] - (points[0] + points[4]) / 2f) to (2 * points[3] - (points[1] + points[5]) / 2f)

    /** Triangle of the arrow head (6 values), oriented like the last segment of the shape; or null. */
    fun arrowHead(): FloatArray? {
        if (!hasArrow) return null
        val o = outline()
        val n = o.size / 2
        if (n < 2) return null
        val tipX = o[2 * n - 2]
        val tipY = o[2 * n - 1]
        // Direction taken a little further back, so a curve has its head aligned with its tail.
        val len = ARROW_LENGTH * width.coerceAtLeast(1f) + 6f
        var k = n - 2
        while (k > 0 && hypot(tipX - o[2 * k], tipY - o[2 * k + 1]) < len * 0.5f) k--
        val angle = atan2(tipY - o[2 * k + 1], tipX - o[2 * k])
        val spread = ARROW_SPREAD
        return floatArrayOf(
            tipX, tipY,
            tipX - len * cos(angle - spread), tipY - len * sin(angle - spread),
            tipX - len * cos(angle + spread), tipY - len * sin(angle + spread),
        )
    }

    override fun bounds(): Box {
        var box = Box.of(outline())!!
        arrowHead()?.let { box = box.union(Box.of(it)!!) }
        return box.inflate(width / 2f)
    }

    /** The points move, the line width stays (user's choice): a shape made larger keeps the line it was drawn with. */
    override fun transformed(t: Affine) = copy(points = t.mapPoints(points))

    override fun recolored(color: Int) = copy(color = color)

    override val strokeWidth get() = width

    override fun withStrokeWidth(width: Float) = copy(width = width)

    override fun touches(x0: Float, y0: Float, x1: Float, y1: Float, r: Float): Boolean {
        val o = outline()
        if (Geometry.segmentPolyline(x0, y0, x1, y1, o, closed) <= r + width / 2f) return true
        if (closed && fill != 0 && Geometry.insidePolygon(x1, y1, o)) return true
        return arrowHead()?.let { Geometry.segmentPolyline(x0, y0, x1, y1, it, true) <= r } == true
    }

    override fun samplePoints() = outline()

    override val isHighlight get() = false

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)

    companion object {
        const val CURVE_SAMPLES = 32
        const val ELLIPSE_SAMPLES = 72
        private const val ARROW_LENGTH = 4f
        private const val ARROW_SPREAD = 0.45f
    }
}

/**
 * Font of a text box. The names are saved (PDF, session, presets): never rename them. [HAND] is a
 * handwriting font bundled with the app (Indie Flower, `res/font`, see `TextLayouts.typeface`).
 */
enum class TextFont {
    SANS, SERIF, MONO, HAND;

    companion object {
        /**
         * Font saved as [name]. Other handwriting fonts (`HAND_…`), offered for a while and no longer available,
         * are read as [HAND]. Any other unknown name is an error, as with `valueOf`.
         */
        fun of(name: String): TextFont = entries.firstOrNull { it.name == name } ?: if (name.startsWith("HAND_")) HAND else valueOf(name)
    }
}

/**
 * Text box: top-left corner ([x], [y]), width [width] (the text wraps), height [height] measured from the
 * layout, font size [size], all in page units; [rotation] in radians around the corner.
 */
class TextBox(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val size: Float,
    override val color: Int,
    val font: TextFont = TextFont.SANS,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val rotation: Float = 0f,
) : PageElement {
    fun copy(
        text: String = this.text,
        x: Float = this.x,
        y: Float = this.y,
        width: Float = this.width,
        height: Float = this.height,
        size: Float = this.size,
        color: Int = this.color,
        font: TextFont = this.font,
        bold: Boolean = this.bold,
        italic: Boolean = this.italic,
        rotation: Float = this.rotation,
    ) = TextBox(text, x, y, width, height, size, color, font, bold, italic, rotation)

    /** Same content (not the height: the layout recomputes it). Elements are only equal to themselves. */
    fun sameContent(o: TextBox) = text == o.text && x == o.x && y == o.y && width == o.width && size == o.size &&
        color == o.color && font == o.font && bold == o.bold && italic == o.italic && rotation == o.rotation

    /** From box coordinates (origin at the corner, not rotated) to page coordinates. */
    val toPage: Affine get() = Affine.rotate(rotation).then(Affine.translate(x, y))

    /** The 4 corners in the page, clockwise from the top left. */
    fun corners(): FloatArray = toPage.mapPoints(floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height))

    /** Page point ([px], [py]) falls in the box, enlarged by [pad]. */
    fun contains(px: Float, py: Float, pad: Float = 0f): Boolean {
        val back = Affine.translate(-x, -y).then(Affine.rotate(-rotation))
        val lx = back.mapX(px, py)
        val ly = back.mapY(px, py)
        return lx >= -pad && lx <= width + pad && ly >= -pad && ly <= height + pad
    }

    override fun bounds() = Box.of(corners())!!

    override fun transformed(t: Affine): TextBox {
        val s = t.scale
        // Sizes follow the average scale: the text is not distorted.
        return copy(
            x = t.mapX(x, y), y = t.mapY(x, y),
            width = width * s, height = height * s, size = size * s,
            rotation = rotation + t.rotation,
        )
    }

    override fun recolored(color: Int) = copy(color = color)

    override val strokeWidth: Float? get() = null

    override fun withStrokeWidth(width: Float) = this

    override fun touches(x0: Float, y0: Float, x1: Float, y1: Float, r: Float): Boolean =
        contains(x1, y1, r) || contains(x0, y0, r) || Geometry.segmentPolyline(x0, y0, x1, y1, corners(), true) <= r

    override fun samplePoints(): FloatArray = corners() + toPage.mapPoints(floatArrayOf(width / 2, height / 2))

    override val isHighlight get() = false

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)

    companion object {
        /** Line spacing, in multiples of the font size. */
        const val LINE_SPACING = 1.25f
    }
}

/**
 * Highlight snapped to text: one quadrilateral per line ([quads], 8 values each: top-left, top-right,
 * bottom-right, bottom-left corners). [color] already has the highlighter's opacity.
 */
class TextHighlight(val quads: FloatArray, override val color: Int) : PageElement {
    val quadCount get() = quads.size / 8

    fun quad(i: Int): FloatArray = quads.copyOfRange(8 * i, 8 * i + 8)

    override fun bounds() = Box.of(quads)!!

    override fun transformed(t: Affine) = TextHighlight(t.mapPoints(quads), color)

    override fun recolored(color: Int) = TextHighlight(quads, keepAlpha(this.color, color))

    override val strokeWidth: Float? get() = null

    override fun withStrokeWidth(width: Float) = this

    override fun touches(x0: Float, y0: Float, x1: Float, y1: Float, r: Float): Boolean {
        for (i in 0 until quadCount) {
            val q = quad(i)
            if (Geometry.insidePolygon(x1, y1, q) || Geometry.segmentPolyline(x0, y0, x1, y1, q, true) <= r) return true
        }
        return false
    }

    override fun samplePoints() = quads

    override val isHighlight get() = true

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}
