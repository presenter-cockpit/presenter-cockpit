package it.presenter.app.ink

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionGeometryTest {
    private fun deg(d: Double) = Math.toRadians(d).toFloat()

    private fun text(rotation: Float) = TextBox("naïve café", 100f, 100f, 300f, 40f, 32f, 0, rotation = rotation)

    /** Rectangle 200 × 80 turned by [angle] around the origin. */
    private fun rectangle(angle: Float): Shape {
        val c = cos(angle)
        val s = sin(angle)
        val corners = floatArrayOf(0f, 0f, 200f, 0f, 200f, 80f, 0f, 80f)
        val p = FloatArray(8) { i -> if (i % 2 == 0) corners[i] * c - corners[i + 1] * s else corners[i - 1] * s + corners[i] * c }
        return Shape(ShapeType.POLYGON, p, 0, 2f)
    }

    @Test
    fun `the box takes the orientation of text boxes and shapes`() {
        assertEquals(deg(17.0), SelectionGeometry.naturalAngle(listOf(text(deg(17.0)))), 1e-4f)
        // Turned by more than 45°: the same box turned by a quarter.
        assertEquals(deg(10.0), SelectionGeometry.naturalAngle(listOf(text(deg(100.0)))), 1e-4f)
        assertEquals(deg(-30.0), SelectionGeometry.naturalAngle(listOf(rectangle(deg(-30.0)))), 1e-4f)
        // Rectangle turned by 60°: its longest side, folded (the box is the same).
        assertEquals(deg(-30.0), SelectionGeometry.naturalAngle(listOf(rectangle(deg(60.0)))), 1e-4f)
        assertEquals(deg(12.0), SelectionGeometry.naturalAngle(listOf(text(deg(12.0)), rectangle(deg(102.0)))), 1e-4f)
    }

    @Test
    fun `without a common orientation the box is straight`() {
        assertEquals(0f, SelectionGeometry.naturalAngle(listOf(text(deg(12.0)), text(deg(20.0)))), 0f)
        val highlight = TextHighlight(floatArrayOf(0f, 0f, 10f, 0f, 10f, 5f, 0f, 5f), 0x66FFEB3B)
        assertEquals(0f, SelectionGeometry.naturalAngle(listOf(text(deg(12.0)), highlight)), 0f)
        assertEquals(0f, SelectionGeometry.naturalAngle(emptyList()), 0f)
    }

    @Test
    fun `rotation snaps the element to horizontal and vertical`() {
        // Text slanted by 17°: turning it back by about 15° makes it exactly horizontal.
        val start = deg(17.0)
        fun turned(byDeg: Double): Float {
            val a = Math.toRadians(-90.0)
            val b = a + Math.toRadians(byDeg)
            return SelectionGeometry.rotation(0f, 0f, cos(a).toFloat(), sin(a).toFloat(), cos(b).toFloat(), sin(b).toFloat(), start)
        }
        assertEquals(-start, turned(-15.0), 1e-4f)
        assertEquals((PI / 2).toFloat() - start, turned(70.0), 1e-4f)
        // Far from the main positions: as dragged.
        assertEquals(deg(-8.0), turned(-8.0), 1e-4f)
    }
}
