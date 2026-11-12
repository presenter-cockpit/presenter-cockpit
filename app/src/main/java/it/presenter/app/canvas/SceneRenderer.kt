package it.presenter.app.canvas

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.InProgressStroke
import it.presenter.app.ink.ElementPainter
import it.presenter.app.ink.PAGE_WIDTH
import it.presenter.app.ink.PageElement
import it.presenter.app.ink.PageInk
import it.presenter.app.session.PageRef
import it.presenter.app.session.PageTransition
import it.presenter.app.session.PresenterSession

/**
 * Draws the current page, or the animated move between two pages, on a surface: used by both tablet and
 * projector, each with its own framing ([Framing]).
 */
class SceneRenderer(private val shadow: Boolean) {

    /** How a surface frames the pages. */
    interface Framing {
        /** Current page, with any zoom. */
        fun current(ref: PageRef, out: Matrix)

        /** Page entering a transition, framed as it will be at the end. */
        fun incoming(ref: PageRef, out: Matrix)

        /** Page leaving, framed as it was at the start of the transition. */
        fun outgoing(t: PageTransition, out: Matrix)
    }

    private val strokeRenderer: CanvasStrokeRenderer = CanvasStrokeRenderer.create()
    private val painter = ElementPainter()

    /**
     * The scene then goes through a filter that inverts the colors (projector): the spotlight veil and its
     * shadow are drawn light, so after the inversion they darken.
     */
    var inverted = false

    /** Element not to draw (the text box being typed on the tablet), or null. */
    var hidden: PageElement? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    private val bitmapMatrix = Matrix()
    private val m1 = Matrix()
    private val m2 = Matrix()
    private val rect = RectF()
    private val dimPath = Path()
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }

    fun draw(canvas: Canvas, session: PresenterSession, framing: Framing, width: Int) {
        val t = session.transition
        if (t == null) {
            val ref = session.currentRef ?: return
            framing.current(ref, m1)
            drawPage(canvas, session, ref, m1)
            return
        }
        val p = t.progress
        when (t.kind) {
            PageTransition.Kind.SLIDE -> {
                val from = t.from
                if (from != null) framing.outgoing(t, m1)
                framing.incoming(t.to, m2)
                val shift = slideDistance(t.direction, width, onScreenRect(from, m1), onScreenRect(t.to, m2)!!)
                if (from != null) {
                    m1.postTranslate(-t.direction * p * shift, 0f)
                    drawPage(canvas, session, from, m1)
                }
                m2.postTranslate(t.direction * (1f - p) * shift, 0f)
                drawPage(canvas, session, t.to, m2)
            }
            PageTransition.Kind.FADE -> {
                t.from?.let { from ->
                    framing.outgoing(t, m1)
                    canvas.saveLayerAlpha(null, ((1f - p) * 255).toInt())
                    drawPage(canvas, session, from, m1)
                    canvas.restore()
                }
                framing.incoming(t.to, m2)
                canvas.saveLayerAlpha(null, (p * 255).toInt())
                drawPage(canvas, session, t.to, m2)
                canvas.restore()
            }
        }
    }

    private fun onScreenRect(ref: PageRef?, m: Matrix): RectF? {
        ref ?: return null
        return RectF(0f, 0f, PAGE_WIDTH, ref.doc.pageHeight(ref.index)).also { m.mapRect(it) }
    }

    /** White page, PDF content and strokes (clipped at the page edge). */
    fun drawPage(canvas: Canvas, session: PresenterSession, ref: PageRef, pageToScreen: Matrix) {
        val height = ref.doc.pageHeight(ref.index)
        rect.set(0f, 0f, PAGE_WIDTH, height)
        pageToScreen.mapRect(rect)
        if (shadow) canvas.drawRect(rect.left + 3f, rect.top + 4f, rect.right + 3f, rect.bottom + 4f, shadowPaint)
        canvas.drawRect(rect, pagePaint)
        drawContent(canvas, session, ref, pageToScreen, rect)
        session.spotlight?.takeIf { it.ref == ref }?.let { drawSpotlight(canvas, it, pageToScreen, RectF(rect)) }
    }

    /** PDF content and strokes of the page, clipped to [clip] (screen coordinates). */
    private fun drawContent(canvas: Canvas, session: PresenterSession, ref: PageRef, pageToScreen: Matrix, clip: RectF) {
        val doc = ref.doc
        val height = doc.pageHeight(ref.index)
        val bmp = doc.pages.cached(ref.index)
        if (bmp != null) {
            bitmapMatrix.setScale(PAGE_WIDTH / bmp.width, height / bmp.height)
            bitmapMatrix.postConcat(pageToScreen)
            canvas.save()
            canvas.clipRect(clip)
            canvas.drawBitmap(bmp, bitmapMatrix, bitmapPaint)
            canvas.restore()
        } else {
            doc.pages.request(ref.index) { session.notify(PresenterSession.Change.BITMAP) }
        }
        // Without creating an empty ink for every page drawn.
        val ink = doc.inkIfPresent(ref.index) ?: return
        val elements = ink.elements
        val ghosts = ink.ghosts
        val fading = ink.fading
        if (elements.isEmpty() && ghosts.isEmpty() && fading.isEmpty()) return
        canvas.save()
        canvas.clipRect(clip)
        // Ink's renderer does not apply the matrix to the canvas (it uses it only for antialiasing).
        canvas.concat(pageToScreen)
        painter.draw(canvas, elements, pageToScreen, hidden)
        // Parts just erased, faint while the eraser is down: a single transparent layer.
        if (ghosts.isNotEmpty()) {
            canvas.saveLayerAlpha(0f, 0f, PAGE_WIDTH, height, PageInk.GHOST_ALPHA)
            for (e in ghosts) painter.drawOne(canvas, e, pageToScreen)
            canvas.restore()
        }
        // Vanishing pen: solid until the fade starts, then more and more transparent.
        if (fading.isNotEmpty()) {
            val now = SystemClock.uptimeMillis()
            for (f in fading) {
                val alpha = PageInk.fadingAlpha(f, now)
                if (alpha <= 0f) continue
                if (alpha < 1f) canvas.saveLayerAlpha(0f, 0f, PAGE_WIDTH, height, (alpha * 255).toInt())
                strokeRenderer.draw(canvas, f.stroke, pageToScreen)
                if (alpha < 1f) canvas.restore()
            }
        }
        canvas.restore()
    }

    /**
     * Spotlight: outside the rectangle the page darkens, with a soft edge; inside it stays as it is (no
     * magnification: user's choice).
     */
    private fun drawSpotlight(canvas: Canvas, s: PresenterSession.Spotlight, pageToScreen: Matrix, page: RectF) {
        val area = RectF(s.left, s.top, s.right, s.bottom).also { pageToScreen.mapRect(it) }
        val blur = SPOTLIGHT_SOFT * pageToScreen.mapRadius(1f)
        dimPath.reset()
        dimPath.fillType = Path.FillType.EVEN_ODD
        // The outer edge of the veil is well outside the page: the soft edge is needed only around the hole.
        dimPath.addRect(page.left - 4 * blur, page.top - 4 * blur, page.right + 4 * blur, page.bottom + 4 * blur, Path.Direction.CW)
        dimPath.addRoundRect(area, blur, blur, Path.Direction.CW)
        dimPaint.maskFilter = if (blur > 0.5f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
        dimPaint.color = if (inverted) 0x99FFFFFF.toInt() else 0x99000000.toInt()
        canvas.save()
        canvas.clipRect(page)
        canvas.drawPath(dimPath, dimPaint)
        canvas.restore()
    }

    /** Stroke in progress (copy for the projector), clipped to the page [pageHeight]. */
    fun drawInProgress(canvas: Canvas, stroke: InProgressStroke, pageHeight: Float, pageToScreen: Matrix) {
        rect.set(0f, 0f, PAGE_WIDTH, pageHeight)
        pageToScreen.mapRect(rect)
        canvas.save()
        canvas.clipRect(rect)
        canvas.concat(pageToScreen)
        strokeRenderer.draw(canvas, stroke, pageToScreen)
        canvas.restore()
    }

    companion object {
        /** Width of the soft edge around the spotlight (page units). */
        private const val SPOTLIGHT_SOFT = 14f

        /** Gap between the two pages while sliding, as a fraction of the width. */
        private const val SLIDE_GAP = 0.04f

        /**
         * How far the pages slide during the transition: enough for the outgoing one ([from], position on
         * screen) to leave the screen exactly at the end and the incoming one ([to]) to appear at once from the
         * edge, even with narrow pages or pages moved sideways. [direction] = 1: pages slide left; -1: right.
         */
        fun slideDistance(direction: Int, surfaceWidth: Int, from: RectF?, to: RectF): Float {
            val w = surfaceWidth.toFloat()
            val out = from?.let { if (direction > 0) it.right else w - it.left } ?: 0f
            val inc = if (direction > 0) w - to.left else to.right
            return maxOf(out, inc, 1f) + SLIDE_GAP * w
        }
    }
}
