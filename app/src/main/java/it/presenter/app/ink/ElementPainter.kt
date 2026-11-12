package it.presenter.app.ink

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import it.presenter.app.PresenterApp
import it.presenter.app.R
import java.util.Collections
import java.util.WeakHashMap

/**
 * Draws the elements of a page on a canvas already transformed to page coordinates (tablet and
 * projector). Strokes with Ink's renderer, shapes with [Path], text with [StaticLayout].
 */
class ElementPainter {
    private val strokeRenderer: CanvasStrokeRenderer = CanvasStrokeRenderer.create()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    /**
     * Draws [elements] in order, but with the highlights first and in a single layer per color: where two
     * highlights overlap the color does not add up, as inside a single stroke, and the text written on top
     * stays solid. [pageToScreen] is only for Ink's antialiasing (the canvas is already transformed).
     * [hidden]: element not to draw (a text box being edited on the tablet).
     */
    fun draw(canvas: Canvas, elements: List<PageElement>, pageToScreen: Matrix, hidden: PageElement? = null) {
        var highlights: LinkedHashMap<Int, MutableList<PageElement>>? = null
        for (e in elements) {
            if (e.isHighlight && e !== hidden) {
                val map = highlights ?: LinkedHashMap<Int, MutableList<PageElement>>().also { highlights = it }
                map.getOrPut(e.color) { ArrayList() }.add(e)
            }
        }
        highlights?.forEach { (color, group) ->
            val box = group.map { it.bounds() }.reduce(Box::union).inflate(2f)
            canvas.saveLayerAlpha(box.minX, box.minY, box.maxX, box.maxY, color ushr 24)
            for (e in group) drawOpaque(canvas, e, pageToScreen)
            canvas.restore()
        }
        for (e in elements) if (!e.isHighlight && e !== hidden) drawOne(canvas, e, pageToScreen)
    }

    /** An element as it is (highlights too, with their opacity: for the eraser's ghosts). */
    fun drawOne(canvas: Canvas, e: PageElement, pageToScreen: Matrix) {
        when (e) {
            is InkStroke -> strokeRenderer.draw(canvas, e.stroke, pageToScreen)
            is Shape -> drawShape(canvas, e)
            is TextBox -> drawText(canvas, e)
            is TextHighlight -> drawQuads(canvas, e, e.color)
        }
    }

    private fun drawOpaque(canvas: Canvas, e: PageElement, pageToScreen: Matrix) {
        when (e) {
            is InkStroke -> strokeRenderer.draw(canvas, e.opaque, pageToScreen)
            is TextHighlight -> drawQuads(canvas, e, e.color or OPAQUE)
            else -> drawOne(canvas, e, pageToScreen)
        }
    }

    private fun drawQuads(canvas: Canvas, h: TextHighlight, color: Int) {
        path.reset()
        for (i in 0 until h.quadCount) addPolygon(path, h.quad(i), closed = true)
        fill.color = color
        canvas.drawPath(path, fill)
    }

    private fun drawShape(canvas: Canvas, s: Shape) {
        val outline = s.outline()
        path.reset()
        addPolygon(path, outline, s.closed)
        if (s.closed && s.fill != 0) {
            fill.color = s.fill
            canvas.drawPath(path, fill)
        }
        line.color = s.color
        line.strokeWidth = s.width
        line.pathEffect = dashEffect(s.dash, s.width)
        canvas.drawPath(path, line)
        line.pathEffect = null
        s.arrowHead()?.let { head ->
            path.reset()
            addPolygon(path, head, closed = true)
            fill.color = s.color
            canvas.drawPath(path, fill)
            // The outline rounds the corners of the arrow head like those of the line.
            line.strokeWidth = s.width
            canvas.drawPath(path, line)
        }
    }

    private fun drawText(canvas: Canvas, t: TextBox) {
        val layout = TextLayouts.layout(t)
        canvas.save()
        canvas.translate(t.x, t.y)
        canvas.rotate(Math.toDegrees(t.rotation.toDouble()).toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    companion object {
        private const val OPAQUE = 0xFF000000.toInt()

        fun addPolygon(path: Path, pts: FloatArray, closed: Boolean) {
            if (pts.size < 2) return
            path.moveTo(pts[0], pts[1])
            for (k in 2 until pts.size step 2) path.lineTo(pts[k], pts[k + 1])
            if (closed) path.close()
        }

        /** Dashes and dots proportional to the width. */
        fun dashEffect(dash: LineDash, width: Float): DashPathEffect? = when (dash) {
            LineDash.SOLID -> null
            LineDash.DASHED -> DashPathEffect(floatArrayOf(4f * width + 2f, 3f * width + 2f), 0f)
            // With a round cap a zero-length dash is a filled dot.
            LineDash.DOTTED -> DashPathEffect(floatArrayOf(0.01f, 2.2f * width + 1f), 0f)
        }
    }
}

/**
 * Layout of the text boxes, in page units. Layouts are kept in memory as long as the box exists (boxes
 * are immutable: every edit creates a new one).
 */
object TextLayouts {
    /** Used by the main thread (drawing); saving into the PDF lays out on its own ([outlines]). */
    private val cache: MutableMap<TextBox, StaticLayout> = Collections.synchronizedMap(WeakHashMap())

    /**
     * The handwriting font, bundled with the app (Indie Flower, SIL Open Font License, listed in
     * `ui/Licenses.kt`; user's choice). Glyphs it lacks (arrows, some math) come from the system fonts, as
     * usual on Android. Loaded once (also from the thread that saves the PDF); without the app's resources
     * (JVM tests) the system's handwriting font.
     */
    private val handFace: Typeface by lazy {
        runCatching { ResourcesCompat.getFont(PresenterApp.context, R.font.indie_flower) }
            .getOrNull() ?: Typeface.create("casual", Typeface.NORMAL)
    }

    fun typeface(font: TextFont, bold: Boolean, italic: Boolean): Typeface {
        val family = when (font) {
            TextFont.SANS -> Typeface.SANS_SERIF
            TextFont.SERIF -> Typeface.SERIF
            TextFont.MONO -> Typeface.MONOSPACE
            TextFont.HAND -> handFace
        }
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(family, style)
    }

    fun paint(t: TextBox) = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        textSize = t.size
        color = t.color
        typeface = typeface(t.font, t.bold, t.italic)
    }

    fun layout(t: TextBox): StaticLayout = cache.getOrPut(t) { build(t) }

    private fun build(t: TextBox): StaticLayout =
        StaticLayout.Builder.obtain(t.text, 0, t.text.length, paint(t), t.width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()

    /** Position in the text of [t] closest to page point ([px], [py]): where to put the cursor. */
    fun offsetAt(t: TextBox, px: Float, py: Float): Int {
        val back = Affine.translate(-t.x, -t.y).then(Affine.rotate(-t.rotation))
        val lx = back.mapX(px, py)
        val ly = back.mapY(px, py)
        val layout = layout(t)
        val line = layout.getLineForVertical(ly.toInt().coerceAtLeast(0))
        return layout.getOffsetForHorizontal(line, lx).coerceIn(0, t.text.length)
    }

    /** The box with the height of its laid-out text (at least one line). */
    fun fit(t: TextBox): TextBox {
        val h = build(t).height.toFloat().coerceAtLeast(t.size * TextBox.LINE_SPACING)
        return if (h == t.height) t else t.copy(height = h)
    }

    /**
     * Glyph outlines, in page coordinates, as polygons (flat lists): to write the text into the PDF as vector
     * paths, identical to the screen with any font.
     */
    fun outlines(t: TextBox, tolerance: Float = 0.05f): List<FloatArray> {
        val layout = build(t)
        val paint = paint(t)
        val glyphs = Path()
        val linePath = Path()
        for (l in 0 until layout.lineCount) {
            val start = layout.getLineStart(l)
            val end = layout.getLineVisibleEnd(l)
            if (end <= start) continue
            linePath.reset()
            paint.getTextPath(t.text, start, end, layout.getLineLeft(l), layout.getLineBaseline(l).toFloat(), linePath)
            glyphs.addPath(linePath)
        }
        val m = Matrix().apply {
            setRotate(Math.toDegrees(t.rotation.toDouble()).toFloat())
            postTranslate(t.x, t.y)
        }
        glyphs.transform(m)
        // approximate: (fraction, x, y) per point; two points with the same fraction = a new contour.
        val pts = glyphs.approximate(tolerance)
        val out = ArrayList<FloatArray>()
        var current = ArrayList<Float>()
        var lastFraction = Float.NaN
        for (k in pts.indices step 3) {
            val f = pts[k]
            if (f == lastFraction && current.isNotEmpty()) {
                if (current.size >= 6) out.add(current.toFloatArray())
                current = ArrayList()
            }
            current.add(pts[k + 1])
            current.add(pts[k + 2])
            lastFraction = f
        }
        if (current.size >= 6) out.add(current.toFloatArray())
        return out
    }
}
