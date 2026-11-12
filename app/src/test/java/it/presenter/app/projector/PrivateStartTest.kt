package it.presenter.app.projector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateStartTest {
    private val now = 10_000_000_000L
    private val hour = 60 * 60 * 1000L

    @Test
    fun offMeansNever() {
        assertFalse(PrivateStart.shouldStart(enabled = false, firstStart = true, now = now, lastAwayAt = 0L))
        assertFalse(PrivateStart.shouldStart(enabled = false, firstStart = false, now = now, lastAwayAt = now - 5 * hour))
    }

    @Test
    fun firstStartOfTheApp() {
        // Even if the app was left shortly before (killed by the system and reopened).
        assertTrue(PrivateStart.shouldStart(enabled = true, firstStart = true, now = now, lastAwayAt = now - 60_000L))
    }

    @Test
    fun comingBackAfterMoreThanTwoHours() {
        assertTrue(PrivateStart.shouldStart(enabled = true, firstStart = false, now = now, lastAwayAt = now - 2 * hour - 1))
        assertFalse(PrivateStart.shouldStart(enabled = true, firstStart = false, now = now, lastAwayAt = now - 2 * hour))
        assertFalse(PrivateStart.shouldStart(enabled = true, firstStart = false, now = now, lastAwayAt = now - 10 * 60_000L))
    }
}
