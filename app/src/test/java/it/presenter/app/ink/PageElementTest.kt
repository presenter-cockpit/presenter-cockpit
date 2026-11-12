package it.presenter.app.ink

import kotlin.math.PI
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageElementTest {
    @Test
    fun `affine transforms composed in the right order`() {
        val t = Affine.translate(10f, 0f).then(Affine.rotate((PI / 2).toFloat()))
        assertEquals(0f, t.mapX(0f, 0f), 1e-4f)
        assertEquals(10f, t.mapY(0f, 0f), 1e-4f)
        val s = Affine.scaleAbout(2f, 3f, 10f, 10f)
        assertEquals(10f, s.mapX(10f, 10f), 1e-4f)
        assertEquals(30f, s.mapX(20f, 10f), 1e-4f)
        assertEquals(40f, s.mapY(10f, 20f), 1e-4f)
        assertEquals(1f, Affine.rotate(0.7f).scale, 1e-4f)
    }

    @Test
    fun `scale along rotated axes`() {
        // Frame rotated by 90°: the local "x" is the page's y.
        val s = Affine.scaleAbout(2f, 1f, 0f, 0f, (PI / 2).toFloat())
        assertEquals(0f, s.mapX(0f, 5f), 1e-4f)
        assertEquals(10f, s.mapY(0f, 5f), 1e-4f)
        assertEquals(5f, s.mapX(5f, 0f), 1e-4f)
    }

    @Test
    fun `shapes transformed and touched by the eraser`() {
        val square = Shape(ShapeType.POLYGON, floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f), 0xFF000000.toInt(), 2f)
        assertTrue(square.touches(50f, -5f, 50f, -5f, 5f))
        assertFalse(square.touches(50f, 50f, 50f, 50f, 5f))
        val filled = square.copy(fill = 0x80FF0000.toInt())
        assertTrue(filled.touches(50f, 50f, 50f, 50f, 5f))
        val moved = square.transformed(Affine.translate(10f, 20f).then(Affine.scaleAbout(2f, 2f, 0f, 0f)))
        assertEquals(20f, moved.points[0], 1e-4f)
        assertEquals(40f, moved.points[1], 1e-4f)
        // Twice as large, with the same line width.
        assertEquals(2f, moved.width, 1e-4f)
        assertEquals(Box(19f, 39f, 221f, 241f), moved.bounds())
    }

    @Test
    fun `rotated text box`() {
        val t = TextBox("hello", 100f, 100f, 200f, 40f, 30f, 0xFF000000.toInt())
        assertTrue(t.contains(150f, 120f))
        assertFalse(t.contains(150f, 160f))
        val r = t.transformed(Affine.rotate((PI / 2).toFloat(), 100f, 100f))
        // Rotated by 90° around the corner: it now extends downwards.
        assertTrue(r.contains(80f, 250f))
        assertFalse(r.contains(150f, 120f))
        val big = t.transformed(Affine.scaleAbout(2f, 2f, 100f, 100f))
        assertEquals(60f, big.size, 1e-4f)
        assertEquals(400f, big.width, 1e-4f)
    }

    @Test
    fun `recolored highlight keeps its opacity`() {
        val h = TextHighlight(floatArrayOf(0f, 0f, 10f, 0f, 10f, 5f, 0f, 5f), 0x66FFEB3B)
        val c = h.recolored(0xFF40C4FF.toInt())
        assertEquals(0x6640C4FF, c.color)
        assertTrue(c.touches(5f, 2f, 5f, 2f, 0.5f))
        assertTrue(c.isHighlight)
    }

    @Test
    fun `elements are only equal to themselves`() {
        val a = Shape(ShapeType.LINE, floatArrayOf(0f, 0f, 1f, 1f), 0, 1f)
        val b = a.copy()
        assertFalse(a == b)
        assertEquals(1, listOf(a, b).filter { it == a }.size)
    }

    @Test
    fun `dragged ellipse corner keeps a parallelogram with the opposite corner fixed`() {
        // Rectangle 0,0 – 100,0 – 100,50 – 0,50.
        val e = Shape(ShapeType.ELLIPSE, floatArrayOf(0f, 0f, 100f, 0f, 100f, 50f, 0f, 50f), 0, 2f)
        val p = e.withPoint(2, 160f, 80f).points
        assertArrayEquals(floatArrayOf(0f, 0f, 160f, 0f, 160f, 80f, 0f, 80f), p, 1e-3f)
        // The center follows: halfway between the opposite corners.
        val o = e.withPoint(0, -20f, -10f).points
        assertArrayEquals(floatArrayOf(-20f, -10f, 100f, -10f, 100f, 50f, -20f, 50f), o, 1e-3f)
    }

    @Test
    fun `the dot just added is removed without leaving undo steps`() {
        val ink = PageInk()
        val a = Shape(ShapeType.LINE, floatArrayOf(0f, 0f, 1f, 1f), 0, 1f)
        val dot = Shape(ShapeType.LINE, floatArrayOf(5f, 5f, 5f, 5f), 0, 1f)
        ink.add(listOf(a))
        ink.add(listOf(dot))
        ink.retract(dot)
        assertEquals(listOf(a), ink.elements.toList())
        assertTrue(ink.undo())
        assertTrue(ink.elements.isEmpty())
        assertFalse(ink.undo())
    }

    @Test
    fun `undoing back to the saved state leaves the page unchanged`() {
        val ink = PageInk()
        val a = Shape(ShapeType.LINE, floatArrayOf(0f, 0f, 1f, 1f), 0, 1f)
        val b = Shape(ShapeType.LINE, floatArrayOf(2f, 2f, 3f, 3f), 0, 1f)
        ink.restore(listOf(a))
        assertFalse(ink.changedSinceSave)
        ink.add(listOf(b))
        assertTrue(ink.changedSinceSave)
        ink.undo()
        assertFalse(ink.changedSinceSave)
        ink.redo()
        assertTrue(ink.changedSinceSave)
        // Saved with b: undoing now changes the page again.
        ink.markSaved(ink.elements)
        assertFalse(ink.changedSinceSave)
        ink.undo()
        assertTrue(ink.changedSinceSave)
        // Same content but another element (a copy): still a change to save.
        ink.redo()
        ink.replace(listOf(b), listOf(Shape(ShapeType.LINE, floatArrayOf(2f, 2f, 3f, 3f), 0, 1f)))
        assertTrue(ink.changedSinceSave)
    }
}
