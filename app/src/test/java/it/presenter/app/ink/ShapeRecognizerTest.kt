package it.presenter.app.ink

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeRecognizerTest {
    private val rnd = Random(7)

    /** Stroke through [corners] (polyline), densely sampled and with some tremor. */
    private fun stroke(vararg corners: Float, jitter: Float = 1.2f, closeGap: Float = 0f): FloatArray {
        val out = ArrayList<Float>()
        for (k in 0 until corners.size / 2 - 1) {
            val x0 = corners[2 * k]
            val y0 = corners[2 * k + 1]
            val x1 = corners[2 * k + 2]
            val y1 = corners[2 * k + 3]
            for (s in 0 until 30) {
                val t = s / 30f
                out.add(x0 + (x1 - x0) * t + rnd.nextFloat() * jitter - jitter / 2)
                out.add(y0 + (y1 - y0) * t + rnd.nextFloat() * jitter - jitter / 2)
            }
        }
        out.add(corners[corners.size - 2] + closeGap)
        out.add(corners[corners.size - 1] + closeGap)
        return out.toFloatArray()
    }

    private fun ellipse(cx: Float, cy: Float, a: Float, b: Float, jitter: Float = 1.5f): FloatArray {
        val out = ArrayList<Float>()
        for (k in 0..100) {
            val t = 2 * PI * k / 96 // a little past the full turn, as drawn by hand
            out.add(cx + a * cos(t).toFloat() + rnd.nextFloat() * jitter - jitter / 2)
            out.add(cy + b * sin(t).toFloat() + rnd.nextFloat() * jitter - jitter / 2)
        }
        return out.toFloatArray()
    }

    @Test
    fun `almost straight stroke is a line, straightened if almost horizontal`() {
        val r = ShapeRecognizer.recognize(stroke(100f, 100f, 300f, 108f))
        assertEquals(ShapeType.LINE, r.type)
        assertEquals(r.points[1], r.points[3], 0.01f)
    }

    @Test
    fun `smooth arc is a curve`() {
        val pts = ArrayList<Float>()
        for (k in 0..60) {
            val t = k / 60f
            pts.add(100f + 300f * t)
            pts.add(300f - 400f * t * (1 - t))
        }
        assertEquals(ShapeType.CURVE, ShapeRecognizer.recognize(pts.toFloatArray()).type)
    }

    @Test
    fun `an open V is a polyline with its corner`() {
        val r = ShapeRecognizer.recognize(stroke(100f, 100f, 200f, 300f, 300f, 100f))
        assertEquals(ShapeType.POLYLINE, r.type)
        assertEquals(3, r.points.size / 2)
    }

    @Test
    fun `closed triangle`() {
        val r = ShapeRecognizer.recognize(stroke(200f, 100f, 300f, 280f, 100f, 280f, 200f, 100f, closeGap = 4f))
        assertEquals(ShapeType.POLYGON, r.type)
        assertEquals(3, r.points.size / 2)
    }

    @Test
    fun `almost rectangular quadrilateral becomes a straight rectangle`() {
        val r = ShapeRecognizer.recognize(stroke(100f, 100f, 305f, 104f, 302f, 250f, 98f, 246f, 101f, 100f, closeGap = 3f))
        assertEquals(ShapeType.POLYGON, r.type)
        assertEquals(4, r.points.size / 2)
        val xs = (0 until 4).map { r.points[2 * it] }.toSet().map { Math.round(it * 10) }.toSet()
        val ys = (0 until 4).map { r.points[2 * it + 1] }.map { Math.round(it * 10) }.toSet()
        assertEquals(2, xs.size)
        assertEquals(2, ys.size)
    }

    @Test
    fun `circle and ellipse`() {
        assertEquals(ShapeType.ELLIPSE, ShapeRecognizer.recognize(ellipse(300f, 300f, 100f, 100f)).type)
        val e = ShapeRecognizer.recognize(ellipse(300f, 300f, 160f, 70f))
        assertEquals(ShapeType.ELLIPSE, e.type)
        val box = Box.of(e.points)!!
        assertEquals(320f, box.width, 12f)
        assertEquals(140f, box.height, 12f)
    }

    @Test
    fun `the recognized ellipse is the stroke's`() {
        val e = Shape(ShapeType.ELLIPSE, ShapeRecognizer.recognize(ellipse(300f, 200f, 120f, 80f)).points, 0, 2f)
        val o = e.outline()
        for (k in o.indices step 2) {
            val u = (o[k] - 300f) / 120f
            val v = (o[k + 1] - 200f) / 80f
            assertEquals(1f, u * u + v * v, 0.12f)
        }
    }

    @Test
    fun `the curve passes through its middle point`() {
        val s = Shape(ShapeType.CURVE, floatArrayOf(0f, 0f, 50f, -40f, 100f, 0f), 0, 2f)
        val o = s.outline()
        val mid = Shape.CURVE_SAMPLES / 2
        assertEquals(50f, o[2 * mid], 0.01f)
        assertEquals(-40f, o[2 * mid + 1], 0.01f)
    }

    @Test
    fun `the arrow points at the end of the line`() {
        val s = Shape(ShapeType.LINE, floatArrayOf(0f, 0f, 100f, 0f), 0, 2f, arrow = true)
        val head = s.arrowHead()!!
        assertEquals(100f, head[0], 0.01f)
        assertTrue(head[2] < 100f && head[4] < 100f)
        assertTrue(Shape(ShapeType.POLYGON, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f), 0, 1f, arrow = true).arrowHead() == null)
    }
}
