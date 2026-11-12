package it.presenter.app.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EraseGeometryTest {
    /** Horizontal line from x=0 to x=100 (y=0), with only two sampled points. */
    private val xs = floatArrayOf(0f, 100f)
    private val ys = floatArrayOf(0f, 0f)

    private fun x(s: EraseGeometry.Sample) = xs[s.segment] + (if (s.segment + 1 < xs.size) (xs[s.segment + 1] - xs[s.segment]) * s.t else 0f)

    @Test
    fun `a distant eraser touches nothing`() {
        assertNull(EraseGeometry.split(xs, ys, 2, 50f, 30f, 50f, 30f, radius = 5f, step = 1f))
    }

    @Test
    fun `a hit in the middle splits the line in three, even between just two sampled points`() {
        val runs = EraseGeometry.split(xs, ys, 2, 50f, 0f, 50f, 0f, radius = 5f, step = 1f)!!
        assertEquals(listOf(false, true, false), runs.map { it.erased })
        // The erased part is within the eraser's radius.
        for (s in runs[1].samples) assertTrue(x(s) in 45f..55f)
        // The remaining parts reach the edge of the eraser.
        assertEquals(44f, x(runs[0].samples.last()), 1.01f)
        assertEquals(56f, x(runs[2].samples.first()), 1.01f)
        // The last original point is still there.
        assertEquals(100f, x(runs[2].samples.last()), 0.001f)
    }

    @Test
    fun `vertical pass along the gesture`() {
        val runs = EraseGeometry.split(xs, ys, 2, 20f, -30f, 20f, 30f, radius = 3f, step = 1f)!!
        assertEquals(listOf(false, true, false), runs.map { it.erased })
    }

    @Test
    fun `erasing an end leaves two parts`() {
        val runs = EraseGeometry.split(xs, ys, 2, 0f, 0f, 0f, 0f, radius = 10f, step = 1f)!!
        assertEquals(listOf(true, false), runs.map { it.erased })
        assertEquals(100f, x(runs[1].samples.last()), 0.001f)
    }

    @Test
    fun `an eraser larger than the stroke erases it all`() {
        val runs = EraseGeometry.split(xs, ys, 2, 50f, 0f, 50f, 0f, radius = 80f, step = 1f)!!
        assertEquals(1, runs.size)
        assertTrue(runs[0].erased)
    }

    @Test
    fun `single point`() {
        val px = floatArrayOf(10f)
        val py = floatArrayOf(10f)
        assertNull(EraseGeometry.split(px, py, 1, 50f, 50f, 50f, 50f, 5f, 1f))
        val runs = EraseGeometry.split(px, py, 1, 11f, 10f, 11f, 10f, 5f, 1f)!!
        assertEquals(1, runs.size)
        assertTrue(runs[0].erased)
    }

    @Test
    fun `far from the eraser no points are added`() {
        // Long polyline: only the segments near the eraser are sampled densely.
        val n = 50
        val lx = FloatArray(n) { it * 10f }
        val ly = FloatArray(n)
        val runs = EraseGeometry.split(lx, ly, n, 250f, 0f, 250f, 0f, radius = 2f, step = 0.5f)!!
        val total = runs.sumOf { it.samples.size }
        assertTrue("samples: $total", total < n + 60)
        assertFalse(runs.first().erased)
    }

    @Test
    fun `distance from a segment`() {
        assertEquals(5f, EraseGeometry.distanceToSegment(5f, 5f, 0f, 0f, 10f, 0f), 1e-4f)
        assertEquals(5f, EraseGeometry.distanceToSegment(-5f, 0f, 0f, 0f, 10f, 0f), 1e-4f)
        assertEquals(5f, EraseGeometry.distanceToSegment(3f, 4f, 0f, 0f, 0f, 0f), 1e-4f)
    }
}
