package it.presenter.app.laser

import it.presenter.app.tools.LaserStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LaserTrailTest {
    private fun LaserTrail.stroke(t0: Long, t1: Long) {
        begin(LaserStyle.FADING_INK, 0, 4f, false, 0f, 0f, t0, -1f)
        add(10f, 10f, t1, -1f)
        end(t1)
    }

    @Test
    fun `the strokes of an arrow fade together`() {
        val trail = LaserTrail().apply { inkHoldMs = 1500 }
        trail.stroke(0, 400)        // shaft
        trail.stroke(1200, 1500)    // head, while the shaft is still solid
        val (shaft, head) = trail.gestures
        assertSame(shaft.figure, head.figure)
        // The shaft alone would have faded already at 400 + 1500 + 450; with the head it stays until 1500 + 1500.
        assertEquals(1f, trail.gestureAlpha(shaft, 2900), 0f)
        assertEquals(trail.gestureAlpha(head, 3200), trail.gestureAlpha(shaft, 3200), 0f)
        assertTrue(trail.gestureAlpha(shaft, 3200) in 0.01f..0.99f)
    }

    @Test
    fun `a stroke after the fade starts a new shape`() {
        val trail = LaserTrail().apply { inkHoldMs = 1500 }
        trail.stroke(0, 100)
        trail.stroke(1700, 1800)
        val (a, b) = trail.gestures
        assertTrue(a.figure !== b.figure)
    }
}
