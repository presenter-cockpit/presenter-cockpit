package it.presenter.app.ink

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PenGesturesTest {
    /** Dense horizontal zig-zag between x0 and x1, slowly going down. */
    private fun scribble(x0: Float, x1: Float, y: Float, passes: Int): FloatArray {
        val out = ArrayList<Float>()
        for (p in 0..passes) {
            val from = if (p % 2 == 0) x0 else x1
            val to = if (p % 2 == 0) x1 else x0
            for (s in 0 until 10) {
                out.add(from + (to - from) * s / 10f)
                out.add(y + p * 2f + s * 0.2f)
            }
        }
        return out.toFloatArray()
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
        FloatArray(42) { i -> val t = (i / 2) / 20f; if (i % 2 == 0) x0 + (x1 - x0) * t else y0 + (y1 - y0) * t }

    private fun circle(cx: Float, cy: Float, r: Float, turns: Float = 1.05f) =
        FloatArray(2 * 60) { i -> val a = 2 * PI * turns * (i / 2) / 59; if (i % 2 == 0) cx + r * cos(a).toFloat() else cy + r * sin(a).toFloat() }

    @Test
    fun `scribble recognized, lines and circles not`() {
        assertTrue(PenGestures.isScribble(scribble(100f, 160f, 100f, 8)))
        assertFalse(PenGestures.isScribble(line(0f, 0f, 300f, 20f)))
        assertFalse(PenGestures.isScribble(circle(200f, 200f, 60f)))
        // A single back and forth is not enough.
        assertFalse(PenGestures.isScribble(scribble(100f, 160f, 100f, 1)))
    }

    @Test
    fun `the scribble erases only what it covers`() {
        val under = Shape(ShapeType.LINE, floatArrayOf(110f, 105f, 150f, 108f), 0, 2f)
        val far = Shape(ShapeType.LINE, floatArrayOf(400f, 400f, 500f, 400f), 0, 2f)
        // Long line crossing the scribble but with its center far away: it stays.
        val long = Shape(ShapeType.LINE, floatArrayOf(130f, 0f, 130f, 900f), 0, 2f)
        val gone = PenGestures.scribbled(listOf(under, far, long), scribble(100f, 160f, 100f, 8), 3f)
        assertEquals(listOf(under), gone)
    }

    @Test
    fun `closed circle for the lasso`() {
        assertTrue(PenGestures.isLassoLoop(circle(300f, 300f, 80f)))
        assertFalse(PenGestures.isLassoLoop(circle(300f, 300f, 80f, turns = 0.6f)))
        assertFalse(PenGestures.isLassoLoop(line(0f, 0f, 200f, 0f)))
        assertFalse(PenGestures.isLassoLoop(circle(300f, 300f, 5f)))
    }
}
