package it.presenter.app.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShortcutsTest {
    private fun key(code: Int, meta: Int = 0) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)

    @Test
    fun `keys with and without modifiers`() {
        assertEquals(ShortcutAction.BLACK, Shortcuts.find(key(KeyEvent.KEYCODE_B)))
        assertEquals(ShortcutAction.SAVE, Shortcuts.find(key(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON)))
        assertEquals(ShortcutAction.REDO, Shortcuts.find(key(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)))
        // Alt and Meta combinations are not shortcuts of the app.
        assertNull(Shortcuts.find(key(KeyEvent.KEYCODE_B, KeyEvent.META_ALT_ON)))
        assertNull(Shortcuts.find(key(KeyEvent.KEYCODE_F, KeyEvent.META_META_ON)))
        assertNull(Shortcuts.find(key(KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON)))
    }
}
