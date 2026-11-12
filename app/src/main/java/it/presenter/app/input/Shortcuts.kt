package it.presenter.app.input

import android.view.KeyEvent
import androidx.annotation.StringRes
import it.presenter.app.R

/** Actions reachable from the keyboard (external keyboard and presentation remotes). */
enum class ShortcutAction(@param:StringRes val label: Int) {
    NEXT(R.string.shortcut_next),
    PREVIOUS(R.string.shortcut_previous),
    FIRST(R.string.shortcut_first),
    LAST(R.string.shortcut_last),
    BLACK(R.string.shortcut_black),
    FREEZE(R.string.shortcut_freeze),
    GOTO(R.string.shortcut_goto),
    OVERVIEW(R.string.shortcut_overview),
    NOTES(R.string.shortcut_notes),
    BOOKMARK(R.string.shortcut_bookmark),
    UNDO(R.string.shortcut_undo),
    REDO(R.string.shortcut_redo),
    SAVE(R.string.shortcut_save),
    COPY(R.string.shortcut_copy),
    CUT(R.string.shortcut_cut),
    PASTE(R.string.shortcut_paste),
    DUPLICATE(R.string.shortcut_duplicate),
    DELETE(R.string.shortcut_delete),
    ESCAPE(R.string.shortcut_escape),
    HELP(R.string.shortcut_help),
}

/**
 * A key: code (with Ctrl/Shift) or printed character ([char], for "?" and "." which sit in different
 * places on Italian and US layouts). [label] is how the key is shown in the summary.
 */
class Key(val keyCode: Int = KeyEvent.KEYCODE_UNKNOWN, val ctrl: Boolean = false, val shift: Boolean = false, val char: Char? = null, val label: String) {
    fun matches(event: KeyEvent): Boolean {
        // Alt and Meta combinations belong to the system and to other apps (B is not Alt+B).
        if (event.isAltPressed || event.isMetaPressed) return false
        if (char != null) return event.unicodeChar.toChar() == char && !event.isCtrlPressed
        return event.keyCode == keyCode && event.isCtrlPressed == ctrl && (!shift || event.isShiftPressed)
    }
}

class Shortcut(val action: ShortcutAction, val keys: List<Key>)

/**
 * Single registry of the shortcuts, as in PDFPC: both the key handling in
 * `MainActivity.dispatchKeyEvent` and the summary opened with "?" are built from it.
 */
object Shortcuts {
    private fun k(code: Int, label: String, ctrl: Boolean = false, shift: Boolean = false) = Key(code, ctrl, shift, label = label)
    private fun c(char: Char) = Key(char = char, label = char.toString())

    val all: List<Shortcut> = listOf(
        Shortcut(
            ShortcutAction.NEXT,
            listOf(
                k(KeyEvent.KEYCODE_PAGE_DOWN, "PgDn"), k(KeyEvent.KEYCODE_DPAD_RIGHT, "→"), k(KeyEvent.KEYCODE_DPAD_DOWN, "↓"),
                k(KeyEvent.KEYCODE_SPACE, "Space"), k(KeyEvent.KEYCODE_MEDIA_NEXT, "⏭"),
            ),
        ),
        Shortcut(
            ShortcutAction.PREVIOUS,
            listOf(
                k(KeyEvent.KEYCODE_PAGE_UP, "PgUp"), k(KeyEvent.KEYCODE_DPAD_LEFT, "←"), k(KeyEvent.KEYCODE_DPAD_UP, "↑"),
                k(KeyEvent.KEYCODE_DEL, "⌫"), k(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "⏮"),
            ),
        ),
        Shortcut(ShortcutAction.FIRST, listOf(k(KeyEvent.KEYCODE_MOVE_HOME, "Home"))),
        Shortcut(ShortcutAction.LAST, listOf(k(KeyEvent.KEYCODE_MOVE_END, "End"))),
        // B and period: presentation remotes send one of the two for the black screen.
        Shortcut(ShortcutAction.BLACK, listOf(k(KeyEvent.KEYCODE_B, "B"), c('.'))),
        Shortcut(ShortcutAction.FREEZE, listOf(k(KeyEvent.KEYCODE_F, "F"))),
        Shortcut(ShortcutAction.GOTO, listOf(k(KeyEvent.KEYCODE_G, "G"))),
        Shortcut(ShortcutAction.OVERVIEW, listOf(k(KeyEvent.KEYCODE_TAB, "Tab"))),
        Shortcut(ShortcutAction.NOTES, listOf(k(KeyEvent.KEYCODE_N, "N"))),
        Shortcut(ShortcutAction.BOOKMARK, listOf(k(KeyEvent.KEYCODE_M, "M"))),
        Shortcut(ShortcutAction.UNDO, listOf(k(KeyEvent.KEYCODE_Z, "Ctrl+Z", ctrl = true))),
        Shortcut(ShortcutAction.REDO, listOf(k(KeyEvent.KEYCODE_Y, "Ctrl+Y", ctrl = true), k(KeyEvent.KEYCODE_Z, "Ctrl+Shift+Z", ctrl = true, shift = true))),
        Shortcut(ShortcutAction.SAVE, listOf(k(KeyEvent.KEYCODE_S, "Ctrl+S", ctrl = true))),
        // Lasso selection and clipboard (paste goes to the current page, also in another document).
        Shortcut(ShortcutAction.COPY, listOf(k(KeyEvent.KEYCODE_C, "Ctrl+C", ctrl = true))),
        Shortcut(ShortcutAction.CUT, listOf(k(KeyEvent.KEYCODE_X, "Ctrl+X", ctrl = true))),
        Shortcut(ShortcutAction.PASTE, listOf(k(KeyEvent.KEYCODE_V, "Ctrl+V", ctrl = true))),
        Shortcut(ShortcutAction.DUPLICATE, listOf(k(KeyEvent.KEYCODE_D, "Ctrl+D", ctrl = true))),
        Shortcut(ShortcutAction.DELETE, listOf(k(KeyEvent.KEYCODE_FORWARD_DEL, "Del"))),
        Shortcut(ShortcutAction.ESCAPE, listOf(k(KeyEvent.KEYCODE_ESCAPE, "Esc"))),
        Shortcut(ShortcutAction.HELP, listOf(c('?'))),
    )

    /** Action of the key [event], or null. Combinations with Shift are checked before those without. */
    fun find(event: KeyEvent): ShortcutAction? {
        for (s in all) for (key in s.keys) if (key.char == null && key.shift && key.matches(event)) return s.action
        for (s in all) for (key in s.keys) if (key.matches(event)) return s.action
        return null
    }
}
