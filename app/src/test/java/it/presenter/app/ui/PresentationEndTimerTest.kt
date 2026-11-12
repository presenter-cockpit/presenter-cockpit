package it.presenter.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PresentationEndTimerTest {
    @Test
    fun `time left and past the end`() {
        assertEquals("12:34", formatLeft((12 * 60 + 34) * 1000L))
        assertEquals("0:01", formatLeft(200L))
        assertEquals("1:05:00", formatLeft(65 * 60_000L))
        assertEquals("+3:20", formatLeft(-(3 * 60 + 20) * 1000L))
    }
}
