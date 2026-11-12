package it.presenter.app.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnappingTest {
    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) =
        Shape(ShapeType.POLYGON, floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1), 0, 2f)

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) =
        Shape(ShapeType.ELLIPSE, floatArrayOf(cx - rx, cy - ry, cx + rx, cy - ry, cx + rx, cy + ry, cx - rx, cy + ry), 0, 2f)

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) = Shape(ShapeType.LINE, floatArrayOf(x0, y0, x1, y1), 0, 2f)

    private val tol = 10f

    @Test
    fun vertexSnapsOntoVertexOfAnotherShape() {
        val box = rect(100f, 100f, 200f, 200f)
        val l = line(0f, 0f, 50f, 50f)
        val r = Snapping.snapVertex(l, 1, 196f, 195f, Snapping.Targets.of(listOf(box, l), listOf(l)), tol)
        assertEquals(200f, r.x, 1e-4f)
        assertEquals(200f, r.y, 1e-4f)
        assertTrue(r.guides.single() is SelectionGeometry.Guide.Point)
    }

    @Test
    fun vertexSnapsOntoSideWhenNoVertexIsNear() {
        val box = rect(100f, 100f, 200f, 200f)
        val l = line(0f, 0f, 50f, 50f)
        val r = Snapping.snapVertex(l, 1, 150f, 104f, Snapping.Targets.of(listOf(box), emptyList()), tol)
        assertEquals(150f, r.x, 1e-4f)
        assertEquals(100f, r.y, 1e-4f)
    }

    @Test
    fun vertexAlignsOnEachAxisIndependently() {
        val box = rect(100f, 100f, 200f, 200f)
        val l = line(0f, 400f, 50f, 50f)
        // Far from vertices and sides: x aligns to 200 (rectangle), y to 400 (the other end of the line).
        val r = Snapping.snapVertex(l, 1, 205f, 395f, Snapping.Targets.of(listOf(box), emptyList()), tol)
        assertEquals(200f, r.x, 1e-4f)
        assertEquals(400f, r.y, 1e-4f)
        assertEquals(2, r.guides.size)
    }

    @Test
    fun nothingSnapsBeyondTheTolerance() {
        val box = rect(100f, 100f, 200f, 200f)
        val l = line(0f, 0f, 50f, 50f)
        val r = Snapping.snapVertex(l, 1, 260f, 300f, Snapping.Targets.of(listOf(box), emptyList()), tol)
        assertEquals(260f, r.x, 1e-4f)
        assertEquals(300f, r.y, 1e-4f)
        assertTrue(r.guides.isEmpty())
    }

    @Test
    fun nearestTargetWins() {
        val a = rect(100f, 100f, 200f, 200f)
        val b = rect(203f, 300f, 260f, 360f)
        val l = line(0f, 600f, 50f, 50f)
        // x = 204: the vertex of b (203) is closer than that of a (200).
        val r = Snapping.snapVertex(l, 1, 204f, 500f, Snapping.Targets.of(listOf(a, b), emptyList()), tol)
        assertEquals(203f, r.x, 1e-4f)
    }

    @Test
    fun edgeBecomesParallelToAnotherShape() {
        // Reference side at 45°; the line starts at (0, 300) and its end goes almost to 45°.
        val ref = line(500f, 500f, 600f, 600f)
        val l = line(0f, 300f, 100f, 395f)
        val r = Snapping.snapVertex(l, 1, 100f, 395f, Snapping.Targets.of(listOf(ref), emptyList()), tol)
        assertEquals(r.x - 0f, r.y - 300f, 1e-3f)
    }

    @Test
    fun edgeBecomesPerpendicularToAnotherShape() {
        val ref = line(500f, 500f, 600f, 600f)
        val l = line(0f, 300f, 100f, 205f)
        val r = Snapping.snapVertex(l, 1, 100f, 205f, Snapping.Targets.of(listOf(ref), emptyList()), tol)
        // Direction (1, -1).
        assertEquals(r.x - 0f, -(r.y - 300f), 1e-3f)
    }

    @Test
    fun ellipseAnchorsAreCenterAndExtremes() {
        val a = Snapping.anchors(ellipse(100f, 100f, 50f, 30f))
        assertEquals(10, a.size)
        assertEquals(100f, a[0], 1e-4f)
        assertEquals(100f, a[1], 1e-4f)
        val pts = (0 until 5).map { a[2 * it] to a[2 * it + 1] }.toSet()
        assertTrue((100f to 70f) in pts)
        assertTrue((150f to 100f) in pts)
        assertTrue((100f to 130f) in pts)
        assertTrue((50f to 100f) in pts)
    }

    @Test
    fun movingCircleLinesUpWithAnotherCircle() {
        val fixed = ellipse(100f, 100f, 40f, 40f)
        val moving = ellipse(304f, 106f, 40f, 40f)
        val m = Snapping.snapMove(Snapping.anchors(moving), Snapping.Targets.of(listOf(fixed), emptyList()), tol)
        // In a row: same centers in y (same top and bottom ends); in x no point nearby.
        assertEquals(-6f, m.dy, 1e-4f)
        assertEquals(0f, m.dx, 1e-4f)
    }

    @Test
    fun movingCornerOntoCornerSnapsBothAxes() {
        val fixed = rect(100f, 100f, 200f, 200f)
        val moving = rect(203f, 196f, 260f, 260f)
        val m = Snapping.snapMove(Snapping.anchors(moving), Snapping.Targets.of(listOf(fixed), emptyList()), tol)
        assertEquals(-3f, m.dx, 1e-4f)
        assertEquals(4f, m.dy, 1e-4f)
        assertTrue(m.guides.single() is SelectionGeometry.Guide.Point)
    }

    @Test
    fun resizedEllipseTakesTheSizeOfAnother() {
        val model = ellipse(100f, 100f, 50f, 30f)
        val e = ellipse(400f, 100f, 40f, 30f)
        // Width 80 → 1.23 × 80 = 98.4, close to 100: it snaps to 1.25.
        val s = Snapping.snapScale(e, 0f, 1.23f, 1f, scalesU = true, scalesV = false, uniform = false, Snapping.Targets.of(listOf(model), emptyList()), tol)
        assertEquals(1.25f, s.sx, 1e-4f)
        assertEquals(1f, s.sy, 1e-4f)
        assertTrue(s.models.single().second)
    }

    @Test
    fun sizeSnapsOnlyWithinTheSameFamily() {
        val model = rect(0f, 0f, 100f, 60f)
        val e = ellipse(400f, 100f, 40f, 30f)
        val s = Snapping.snapScale(e, 0f, 1.24f, 1f, scalesU = true, scalesV = false, uniform = false, Snapping.Targets.of(listOf(model), emptyList()), tol)
        assertEquals(1.24f, s.sx, 1e-4f)
        assertTrue(s.models.isEmpty())
    }

    @Test
    fun uniformResizeKeepsOneScale() {
        val model = rect(0f, 0f, 100f, 300f)
        val r = rect(400f, 0f, 480f, 100f)
        val s = Snapping.snapScale(r, 0f, 1.22f, 1.22f, scalesU = true, scalesV = true, uniform = true, Snapping.Targets.of(listOf(model), emptyList()), tol)
        assertEquals(1.25f, s.sx, 1e-4f)
        assertEquals(s.sx, s.sy, 0f)
    }
}
