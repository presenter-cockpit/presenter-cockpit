package it.presenter.app.laser

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import it.presenter.app.ink.withAlpha
import it.presenter.app.tools.LaserStyle
import kotlin.math.pow

/**
 * Draws the laser with a "neon" effect: semi-transparent colored glow, solid body and an almost white
 * core. Each gesture is composed in its own layer: segments overlap at the joints and, drawn directly
 * with alpha < 1, would leave darker "grains".
 */
class LaserRenderer {
    private val glowPaint = strokePaint()
    private val bodyPaint = strokePaint()
    private val corePaint = strokePaint()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloShaders = HashMap<Int, Shader>()
    private val bounds = RectF()

    /**
     * Draws the current state; [pageToScreen] maps page coordinates to canvas coordinates. Returns true if
     * another frame is needed (animation running).
     */
    fun draw(canvas: Canvas, trail: LaserTrail, pageToScreen: Matrix, now: Long): Boolean {
        val animating = prune(trail, now)
        if (!animating) return false
        canvas.save()
        canvas.concat(pageToScreen)
        for (g in trail.gestures) drawGesture(canvas, trail, g, now)
        val hoverA = trail.hoverAlpha(now)
        if (hoverA > 0f) drawDot(canvas, trail.hoverX, trail.hoverY, trail.hoverColor, trail.hoverWidth, hoverA * 0.85f)
        canvas.restore()
        return true
    }

    private fun prune(trail: LaserTrail, now: Long): Boolean {
        for (g in trail.gestures) {
            if (g.style == LaserStyle.COMET) {
                // The last point always stays while the pen is down: it is the position of the bright dot.
                val keep = if (g.isDown) g.size - 1 else g.size
                while (g.start < keep && now - g.ts[g.start] > LaserTrail.COMET_TRAIL_MS) g.start++
            }
        }
        return trail.prune(now)
    }

    private fun drawGesture(canvas: Canvas, trail: LaserTrail, g: LaserTrail.Gesture, now: Long) {
        if (g.size == 0) return
        val alpha = trail.gestureAlpha(g, now)
        val visible = g.size - g.start
        if (alpha > 0f && (visible >= 2 || (visible >= 1 && g.hasPrediction))) {
            computeBounds(g)
            val pad = g.width * GLOW_WIDTH * 1.5f
            bounds.inset(-pad, -pad)
            val light = blend(g.color, Color.WHITE, 0.5f)
            glowPaint.color = g.color
            bodyPaint.color = g.color
            corePaint.color = light

            canvas.saveLayerAlpha(bounds, (alpha * 255).toInt())
            canvas.saveLayerAlpha(bounds, GLOW_ALPHA)
            drawSegments(canvas, g, now, glowPaint, GLOW_WIDTH)
            canvas.restore()
            drawSegments(canvas, g, now, bodyPaint, BODY_WIDTH)
            drawSegments(canvas, g, now, corePaint, CORE_WIDTH)
            canvas.restore()
        }
        val dotA = trail.dotAlpha(g, now)
        if (dotA > 0f) {
            val last = g.size - 1
            val x = if (g.hasPrediction) g.predX else g.xs[last]
            val y = if (g.hasPrediction) g.predY else g.ys[last]
            drawDot(canvas, x, y, g.color, g.headWidth, dotA)
        }
    }

    private fun drawSegments(canvas: Canvas, g: LaserTrail.Gesture, now: Long, paint: Paint, widthFactor: Float) {
        val comet = g.style == LaserStyle.COMET
        var px = g.xs[g.start]
        var py = g.ys[g.start]
        for (i in g.start + 1 until g.size) {
            val x = g.xs[i]
            val y = g.ys[i]
            val w = (g.ws[i - 1] + g.ws[i]) * 0.5f * widthFactor
            paint.strokeWidth = if (comet) w * taper(now - g.ts[i - 1]) else w
            canvas.drawLine(px, py, x, y, paint)
            px = x
            py = y
        }
        if (g.hasPrediction) {
            paint.strokeWidth = g.headWidth * widthFactor
            canvas.drawLine(px, py, g.predX, g.predY, paint)
        }
    }

    /** Relative width of a tail point by age: 1 at the head, 0 at the end. */
    private fun taper(age: Long): Float {
        val f = (1f - age / LaserTrail.COMET_TRAIL_MS.toFloat()).coerceIn(0f, 1f)
        return f.pow(0.7f)
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float, color: Int, width: Float, alpha: Float) {
        val a = (alpha * 255).toInt()
        // Glow: unit radial gradient scaled around the point (no allocation per frame).
        haloPaint.shader = haloShaders.getOrPut(color) {
            RadialGradient(
                0f, 0f, 1f,
                intArrayOf(withAlpha(color, 0xAA), withAlpha(color, 0x44), withAlpha(color, 0)),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        haloPaint.alpha = a
        val r = width * HALO_RADIUS
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(r, r)
        canvas.drawCircle(0f, 0f, 1f, haloPaint)
        canvas.restore()

        dotPaint.color = color
        dotPaint.alpha = a
        canvas.drawCircle(x, y, width * 0.95f, dotPaint)
        dotPaint.color = blend(color, Color.WHITE, 0.8f)
        dotPaint.alpha = a
        canvas.drawCircle(x, y, width * 0.5f, dotPaint)
    }

    private fun computeBounds(g: LaserTrail.Gesture) {
        var minX = g.xs[g.start]
        var maxX = minX
        var minY = g.ys[g.start]
        var maxY = minY
        for (i in g.start + 1 until g.size) {
            val x = g.xs[i]
            val y = g.ys[i]
            if (x < minX) minX = x else if (x > maxX) maxX = x
            if (y < minY) minY = y else if (y > maxY) maxY = y
        }
        if (g.hasPrediction) {
            minX = minOf(minX, g.predX); maxX = maxOf(maxX, g.predX)
            minY = minOf(minY, g.predY); maxY = maxOf(maxY, g.predY)
        }
        bounds.set(minX, minY, maxX, maxY)
    }

    private fun strokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun blend(c: Int, to: Int, t: Float): Int {
        fun ch(a: Int, b: Int) = (a + (b - a) * t).toInt().coerceIn(0, 255)
        return Color.argb(
            255,
            ch(Color.red(c), Color.red(to)),
            ch(Color.green(c), Color.green(to)),
            ch(Color.blue(c), Color.blue(to)),
        )
    }

    private companion object {
        const val GLOW_WIDTH = 3.4f
        const val GLOW_ALPHA = 70
        const val BODY_WIDTH = 1.5f
        const val CORE_WIDTH = 0.55f
        const val HALO_RADIUS = 4.2f
    }
}
