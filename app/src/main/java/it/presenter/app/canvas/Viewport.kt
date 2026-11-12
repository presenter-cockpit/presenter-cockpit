package it.presenter.app.canvas

import android.graphics.Matrix
import android.graphics.RectF
import it.presenter.app.ink.PAGE_WIDTH
import kotlin.math.abs

/**
 * Transformation between page coordinates and the tablet view's coordinates: the page is fitted to the
 * view ("fit") and then zoomed/moved with the fingers.
 *
 * Zoom and pan ([user]) stay when changing page or document: the new page appears in the same position.
 * A double tap ([reset]) goes back to the whole page.
 */
class Viewport {
    private var viewW = 1f
    private var viewH = 1f
    private var pageH = 1f

    private val user = Matrix()
    private val scratchMatrix = Matrix()
    private val scratchRect = RectF()
    val pageToView = Matrix()
    val viewToPage = Matrix()

    var zoom = 1f
        private set

    /** Page zoomed in: the projector shows only the framed part, otherwise the whole page. */
    val isZoomed get() = zoom > 1.01f

    fun setViewSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        viewW = w.toFloat()
        viewH = h.toFloat()
        update()
    }

    /** Current page [height] high ([PAGE_WIDTH] wide); zoom and pan stay as they were. */
    fun setPageHeight(height: Float) {
        pageH = height
        update()
    }

    /** Zoom and pan, to save with the session: zoom followed by the 9 matrix values. */
    fun saveState(): FloatArray = FloatArray(10).also {
        it[0] = zoom
        val values = FloatArray(9)
        user.getValues(values)
        values.copyInto(it, 1)
    }

    fun restoreState(state: FloatArray) {
        if (state.size != 10) return
        zoom = state[0].coerceIn(MIN_ZOOM, MAX_ZOOM)
        user.setValues(state.copyOfRange(1, 10))
        update()
    }

    /** Whole page, centered: a double tap (which comes back here) would change nothing. */
    val isReset get() = user.isIdentity

    fun reset() {
        user.reset()
        zoom = 1f
        update()
    }

    /** "Raw" zoom of the gesture in progress, before snapping to 1 ([ZOOM_SNAP]). */
    private var gestureZoom = 1f

    /** Start (or restart, when the number of fingers changes) of a pinch/pan gesture. */
    fun beginGesture() {
        gestureZoom = zoom
    }

    /**
     * Applies a pinch/pan step: the point under the fingers follows the translation ([dx],[dy]), then the
     * zoom [scale] around the new finger position ([focusX],[focusY]). Near 1 the zoom stays snapped to 1,
     * so a slight tremor of the fingers does not make the page oscillate (nor the projector switch between
     * whole page and zoomed part).
     */
    fun applyGesture(scale: Float, focusX: Float, focusY: Float, dx: Float, dy: Float) {
        gestureZoom = (gestureZoom * scale).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val newZoom = if (abs(gestureZoom - 1f) < ZOOM_SNAP) 1f else gestureZoom
        val s = newZoom / zoom
        zoom = newZoom
        user.postTranslate(dx, dy)
        if (s != 1f) user.postScale(s, s, focusX, focusY)
        update()
    }

    /** Rectangle of the page visible on the tablet, in page coordinates. */
    fun visiblePageRect(out: RectF): RectF = visibleRectFor(pageToView, out)

    /** Like [visiblePageRect], for a page [height] high framed like the current one. */
    fun visiblePageRectFor(height: Float, out: RectF): RectF {
        matrixFor(height, scratchMatrix)
        return visibleRectFor(scratchMatrix, out)
    }

    /** Framing of a page [height] high with the current zoom and pan. */
    fun matrixFor(height: Float, out: Matrix) {
        fitMatrix(height, out)
        out.postConcat(user)
    }

    /** "Whole page" framing of a page [height] high ([PAGE_WIDTH] wide). */
    fun fitMatrix(height: Float, out: Matrix) {
        val margin = MARGIN_FRACTION * minOf(viewW, viewH)
        val s = minOf((viewW - 2 * margin) / PAGE_WIDTH, (viewH - 2 * margin) / height)
        out.setScale(s, s)
        out.postTranslate((viewW - PAGE_WIDTH * s) / 2f, (viewH - height * s) / 2f)
    }

    private fun visibleRectFor(pageToScreen: Matrix, out: RectF): RectF {
        val inverse = Matrix()
        pageToScreen.invert(inverse)
        out.set(0f, 0f, viewW, viewH)
        inverse.mapRect(out)
        return out
    }

    private fun update() {
        clampPan()
        matrixFor(pageH, pageToView)
        pageToView.invert(viewToPage)
    }

    /**
     * The sheet moves freely but is never lost: at least [MIN_VISIBLE_FRACTION] of it per side stays
     * visible (or all of it, if smaller).
     */
    private fun clampPan() {
        // Before layout the view has no size: clamping now would move the sheet at random.
        if (viewW <= 1f || viewH <= 1f) return
        val m = scratchMatrix.also { matrixFor(pageH, it) }
        val r = scratchRect.apply { set(0f, 0f, PAGE_WIDTH, pageH) }.also { m.mapRect(it) }
        val keepX = minOf(r.width(), viewW) * MIN_VISIBLE_FRACTION
        val keepY = minOf(r.height(), viewH) * MIN_VISIBLE_FRACTION
        val dx = when {
            r.right < keepX -> keepX - r.right
            r.left > viewW - keepX -> viewW - keepX - r.left
            else -> 0f
        }
        val dy = when {
            r.bottom < keepY -> keepY - r.bottom
            r.top > viewH - keepY -> viewH - keepY - r.top
            else -> 0f
        }
        if (dx != 0f || dy != 0f) user.postTranslate(dx, dy)
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 6f
        private const val ZOOM_SNAP = 0.05f
        private const val MARGIN_FRACTION = 0.01f
        private const val MIN_VISIBLE_FRACTION = 0.25f
    }
}
