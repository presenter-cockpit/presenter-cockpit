package it.presenter.app.session

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerPhaseTest {
    private fun phase(leftMinutes: Double) = TimerPhase.of((leftMinutes * 60_000).toLong(), yellowMinutes = 10, orangeMinutes = 5)

    @Test
    fun `yellow at ten minutes, orange at five, red when the time is up`() {
        assertEquals(TimerPhase.NONE, phase(10.1))
        assertEquals(TimerPhase.YELLOW, phase(10.0))
        assertEquals(TimerPhase.YELLOW, phase(5.1))
        assertEquals(TimerPhase.ORANGE, phase(5.0))
        assertEquals(TimerPhase.ORANGE, phase(0.01))
        assertEquals(TimerPhase.RED, phase(0.0))
        assertEquals(TimerPhase.RED, phase(-3.0))
    }
}
