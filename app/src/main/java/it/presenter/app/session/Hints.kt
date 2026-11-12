package it.presenter.app.session

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import it.presenter.app.R
import it.presenter.app.settings.Prefs

/** Hint shown the first time a non-obvious feature is needed. */
enum class Hint(@param:StringRes val text: Int) {
    HELP(R.string.hint_help),
    SCRIBBLE(R.string.hint_scribble),
    CIRCLE_LASSO(R.string.hint_circle_lasso),
    DUAL(R.string.hint_dual),
    SHAPES(R.string.hint_shapes),
    OVERLAYS(R.string.hint_overlays),
}

/**
 * First-use hints: one at a time, in a card at the bottom of the tablet (never on the projector); once
 * closed, it never comes back. They can all be turned off or restarted from the settings
 * (`Prefs.hintsEnabled`, `Prefs.hintsSeen`).
 */
class Hints(private val prefs: Prefs) {
    var enabled by mutableStateOf(prefs.hintsEnabled)
        private set

    /** Hint on screen, or null. */
    var current by mutableStateOf<Hint?>(null)
        private set

    private val queue = ArrayDeque<Hint>()

    /** [hint] is useful now: it is shown if not seen yet (after the current one). */
    fun offer(hint: Hint) {
        if (!enabled || hint.name in prefs.hintsSeen || hint == current || hint in queue) return
        if (current == null) current = hint else queue.addLast(hint)
    }

    /** Closed by the user: seen, never repeated; the next one in the queue follows. */
    fun dismiss() {
        val shown = current ?: return
        prefs.hintsSeen = prefs.hintsSeen + shown.name
        current = queue.removeFirstOrNull()
    }

    /** On: the hints start over. Off: none, not even the current one. */
    fun updateEnabled(on: Boolean) {
        enabled = on
        prefs.hintsEnabled = on
        if (on) {
            prefs.hintsSeen = emptySet()
        } else {
            current = null
            queue.clear()
        }
    }
}
