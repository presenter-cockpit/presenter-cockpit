package it.presenter.app.projector

import android.view.Display
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudienceDisplaysTest {
    private val trusted = 1 shl 7

    @Test
    fun `trusted displays are on by default, those of other apps are not`() {
        // HDMI monitor: secure, protected buffers, presentation, trusted.
        assertTrue(AudienceDisplays.isTrusted(Display.FLAG_SECURE or Display.FLAG_SUPPORTS_PROTECTED_BUFFERS or Display.FLAG_PRESENTATION or trusted))
        // "Simulate secondary displays" (seen in the emulator: FLAG_PRESENTATION, FLAG_TRUSTED).
        assertTrue(AudienceDisplays.isTrusted(Display.FLAG_PRESENTATION or trusted))
        // Google Meet sharing the whole screen (seen on the tablet: only FLAG_PRESENTATION).
        assertFalse(AudienceDisplays.isTrusted(Display.FLAG_PRESENTATION))
        // Teams: private as well.
        assertFalse(AudienceDisplays.isTrusted(Display.FLAG_PRESENTATION or Display.FLAG_PRIVATE))
    }
}
