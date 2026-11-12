package it.presenter.app.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import it.presenter.app.ink.ElementPainter
import it.presenter.app.ink.PAGE_WIDTH
import it.presenter.app.ink.SelectionGeometry
import it.presenter.app.laser.LaserRenderer
import it.presenter.app.session.PageRef
import it.presenter.app.session.PageTransition
import it.presenter.app.session.PresenterSession
import it.presenter.app.session.TimerPhase
import kotlin.math.hypot

/** The tablet's "dry" layer: background, page (or transition) and strokes already handed over by Ink. */
@SuppressLint("ViewConstructor")
class PageLayerView(context: Context, private val session: PresenterSession) : View(context) {
    private val scene = SceneRenderer(shadow = true)

    private val framing = object : SceneRenderer.Framing {
        override fun current(ref: PageRef, out: Matrix) = out.set(session.viewport.pageToView)
        override fun incoming(ref: PageRef, out: Matrix) = session.viewport.matrixFor(ref.doc.pageHeight(ref.index), out)
        override fun outgoing(t: PageTransition, out: Matrix) = out.set(t.fromTablet)
    }

    override fun onDraw(canvas: Canvas) {
        // The text box being edited is drawn by the text field on top of it.
        scene.hidden = session.textEdit?.box
        // Around the slide, the color of the timer when the end is near (tablet only, user's choice).
        canvas.drawColor(
            when (session.timerPhase) {
                TimerPhase.NONE -> DESK_COLOR
                TimerPhase.YELLOW -> DESK_YELLOW
                TimerPhase.ORANGE -> DESK_ORANGE
                TimerPhase.RED -> DESK_RED
            },
        )
        scene.draw(canvas, session, framing, width)
    }

    companion object {
        const val DESK_COLOR = 0xFF1B1C1F.toInt()

        /** Background in the timer phases: dark enough for the slide to stay the brightest thing. */
        const val DESK_YELLOW = 0xFF6B5A00.toInt()
        const val DESK_ORANGE = 0xFF7A3A00.toInt()
        const val DESK_RED = 0xFF7F1414.toInt()
    }
}

/** Layer above the ink: laser, eraser circle, selection, overlay dots, search results. Animates only when needed. */
@SuppressLint("ViewConstructor")
class OverlayView(context: Context, private val session: PresenterSession) : View(context) {
    private val laserRenderer = LaserRenderer()
    private val eraserStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(200, 90, 90, 90)
    }
    private val eraserFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 0, 0, 0) }

    private var eraserVisible = false
    private var eraserX = 0f
    private var eraserY = 0f
    private var eraserR = 0f

    /** Shows the eraser circle in view coordinates (radius in px). */
    fun showEraser(x: Float, y: Float, r: Float) {
        eraserVisible = true
        eraserX = x
        eraserY = y
        eraserR = r
        invalidate()
    }

    /**
     * Beamer overlays, presenter only: at the bottom right of the slide a dot per overlay, dark for those
     * already shown and grey for the missing ones. So you know whether to tap again (next overlay) or swipe
     * (next slide).
     */
    private fun drawOverlayDots(canvas: Canvas) {
        val ref = session.currentRef ?: return
        if (!session.settings.beamerOverlays) return
        val pages = ref.doc.slides.pagesOf(ref.index)
        val count = pages.count()
        if (count < 2) return
        val shown = ref.index - pages.first + 1
        pageRect.set(0f, 0f, PAGE_WIDTH, ref.doc.pageHeight(ref.index))
        session.viewport.pageToView.mapRect(pageRect)
        val r = DOT_RADIUS_DP * density
        val gap = DOT_GAP_DP * density
        val margin = DOT_MARGIN_DP * density
        val width = count * 2 * r + (count - 1) * gap
        val cy = pageRect.bottom - margin - r
        val left = pageRect.right - margin - width
        canvas.drawRoundRect(left - r, cy - 2 * r, left + width + r, cy + 2 * r, 2 * r, 2 * r, dotBackdrop)
        for (i in 0 until count) {
            val cx = left + r + i * (2 * r + gap)
            canvas.drawCircle(cx, cy, r, if (i < shown) dotShown else dotPending)
        }
    }

    fun hideEraser() {
        if (!eraserVisible) return
        eraserVisible = false
        invalidate()
    }

    /**
     * Selection, tablet only: dashed box with handles (corners, sides, rotation) or the shape's vertices,
     * the snapping guides and the lasso while it is being drawn.
     */
    private fun drawSelection(canvas: Canvas) {
        val m = session.viewport.pageToView
        session.lassoPath?.let { lasso ->
            selPath.reset()
            ElementPainter.addPolygon(selPath, m.let { mm -> FloatArray(lasso.size).also { mm.mapPoints(it, lasso) } }, closed = false)
            canvas.drawPath(selPath, lassoPaint)
        }
        val sel = session.selection ?: return
        val ref = session.currentRef ?: return
        if (ref.doc.inkOf(ref.index) !== sel.ink) return
        val k = m.mapRadius(1f).coerceAtLeast(1e-3f)
        val shape = sel.shape
        for (g in session.guides) {
            when (g) {
                is SelectionGeometry.Guide.Line -> {
                    val pts = floatArrayOf(g.x0, g.y0, g.x1, g.y1)
                    m.mapPoints(pts)
                    // The guide extends a little beyond the two aligned points.
                    val len = hypot(pts[2] - pts[0], pts[3] - pts[1])
                    val ex = if (len > 1e-3f) (pts[2] - pts[0]) / len * GUIDE_EXTRA_DP * density else 0f
                    val ey = if (len > 1e-3f) (pts[3] - pts[1]) / len * GUIDE_EXTRA_DP * density else GUIDE_EXTRA_DP * density
                    canvas.drawLine(pts[0] - ex, pts[1] - ey, pts[2] + ex, pts[3] + ey, guidePaint)
                }
                is SelectionGeometry.Guide.Point -> {
                    // Point hit: a ring around the target.
                    val pts = floatArrayOf(g.x, g.y)
                    m.mapPoints(pts)
                    canvas.drawCircle(pts[0], pts[1], GUIDE_POINT_DP * density, guidePaint)
                }
            }
        }
        // Selected shape: only the vertices (filled dots); the box after a tap on the shape.
        if (shape != null && sel.showsVertices) {
            val pts = shape.points.copyOf()
            m.mapPoints(pts)
            for (i in 0 until pts.size / 2) {
                canvas.drawCircle(pts[2 * i], pts[2 * i + 1], HANDLE_RADIUS_DP * density, handleFill)
                canvas.drawCircle(pts[2 * i], pts[2 * i + 1], HANDLE_RADIUS_DP * density * 0.7f, handleDot)
            }
            return
        }
        val box = sel.box(sel.padDp * density / k)
        val corners = SelectionGeometry.corners(box, sel.angle)
        m.mapPoints(corners)
        selPath.reset()
        ElementPainter.addPolygon(selPath, corners, closed = true)
        canvas.drawPath(selPath, boxPaint)
        val rotate = SelectionGeometry.handlePosition(SelectionGeometry.Handle.ROTATE, box, sel.angle, ROTATE_OFFSET_DP * density / k)
        val top = SelectionGeometry.handlePosition(SelectionGeometry.Handle.TOP, box, sel.angle, 0f)
        val line = rotate + top
        m.mapPoints(line)
        canvas.drawLine(line[0], line[1], line[2], line[3], boxPaint)
        for (h in SelectionGeometry.Handle.entries) {
            val p = SelectionGeometry.handlePosition(h, box, sel.angle, ROTATE_OFFSET_DP * density / k)
            m.mapPoints(p)
            if (h == SelectionGeometry.Handle.ROTATE) {
                canvas.drawCircle(p[0], p[1], HANDLE_RADIUS_DP * density * 1.25f, handleFill)
                canvas.drawCircle(p[0], p[1], HANDLE_RADIUS_DP * density * 1.25f, handleStroke)
                canvas.drawCircle(p[0], p[1], HANDLE_RADIUS_DP * density * 0.45f, handleDot)
            } else {
                drawHandle(canvas, p[0], p[1])
            }
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, HANDLE_RADIUS_DP * density, handleFill)
        canvas.drawCircle(x, y, HANDLE_RADIUS_DP * density, handleStroke)
    }

    private val selPath = Path()
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = SELECTION_COLOR
        pathEffect = DashPathEffect(floatArrayOf(6f * resources.displayMetrics.density, 4f * resources.displayMetrics.density), 0f)
    }
    private val lassoPaint = Paint(boxPaint)
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = SELECTION_COLOR
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SELECTION_COLOR }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = SELECTION_COLOR
    }

    companion object {
        private const val DOT_RADIUS_DP = 4.5f
        private const val DOT_GAP_DP = 6f
        private const val DOT_MARGIN_DP = 14f
        private const val HANDLE_RADIUS_DP = 7f
        private const val GUIDE_EXTRA_DP = 24f
        private const val GUIDE_POINT_DP = 9f
        private const val SELECTION_COLOR = 0xFF1E88E5.toInt()

        /** Distance of the rotation handle from the top side of the box. */
        const val ROTATE_OFFSET_DP = 32f
    }

    private val highlightPaint = Paint().apply { color = Color.argb(110, 255, 214, 0) }
    private val dotShown = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(66, 66, 66) }
    private val dotPending = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(189, 189, 189) }
    private val dotBackdrop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val pageRect = RectF()
    private val density = resources.displayMetrics.density
    private val highlightRect = RectF()

    override fun onDraw(canvas: Canvas) {
        if (session.transition != null) return
        // Search result: tablet only, above the page and the strokes.
        session.searchHighlight?.let { h ->
            val ref = session.currentRef
            if (ref != null && ref.doc === h.doc && ref.index == h.page) {
                for (r in h.rects) {
                    highlightRect.set(r.left, r.top, r.right, r.bottom)
                    session.viewport.pageToView.mapRect(highlightRect)
                    canvas.drawRoundRect(highlightRect, 4f, 4f, highlightPaint)
                }
            }
        }
        drawOverlayDots(canvas)
        drawSelection(canvas)
        val now = SystemClock.uptimeMillis()
        if (laserRenderer.draw(canvas, session.laser, session.viewport.pageToView, now)) {
            postInvalidateOnAnimation()
        }
        if (eraserVisible) {
            canvas.drawCircle(eraserX, eraserY, eraserR, eraserFill)
            canvas.drawCircle(eraserX, eraserY, eraserR, eraserStroke)
        }
    }
}
