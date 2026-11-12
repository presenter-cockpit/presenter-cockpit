package it.presenter.app.tabs

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import it.presenter.app.doc.PresenterDocument
import it.presenter.app.remote.SshLink
import java.util.UUID

/**
 * A tab that is not a PDF: terminal (SSH or local), IDE (local or remote folder) or browser. PDF tabs
 * are [PresenterDocument]s.
 */
sealed class WorkTab(val id: String = UUID.randomUUID().toString()) {
    abstract val title: String

    /** Font size on the tablet (sp), remembered per tab and saved in the session. */
    private var fontState by mutableFloatStateOf(DEFAULT_FONT_SP)
    var fontSize: Float
        get() = fontState
        set(value) {
            if (value == fontState) return
            fontState = value
            onFontChanged?.invoke(value)
        }

    /** Called when the user changes the font size (the workspace remembers the last choice). */
    var onFontChanged: ((Float) -> Unit)? = null

    /** Magnification on the projector relative to the tablet (editor; the terminal adapts by itself). */
    var projectorScale by mutableFloatStateOf(DEFAULT_PROJECTOR_SCALE)

    /** Inverted colors on the projector for this tab (icon at the top, remembered in the session). */
    var inverted by mutableStateOf(false)

    /**
     * On-screen keyboard requested with the icon above terminal or editor (off: touching the content does
     * not open it; a physical keyboard always works). User's request.
     */
    var softKeyboard by mutableStateOf(false)

    /** The on-screen keyboard actually appeared (to recognize when the system closes it). */
    var imeShown = false

    /** Last time in front (ms): among several terminals in the same folder the most recent wins. */
    var shownAt = 0L

    /** Releases connections and threads (tab closed). */
    open fun close() = Unit

    /** The user closed the tab and it will not come back (before [close]): free what is left on the server. */
    open fun discard() = Unit

    /** The network is back: remote tabs retry at once instead of waiting for the growing delay. */
    open fun onNetworkAvailable() = Unit

    companion object {
        const val DEFAULT_FONT_SP = 14f
        const val DEFAULT_PROJECTOR_SCALE = 1.6f
        val FONT_RANGE = 8f..32f
    }
}

/** State of a remote connection (or of the local shell), shown at the top of the tab. */
sealed interface LinkStatus {
    object Connecting : LinkStatus
    object Connected : LinkStatus

    /** Connection lost: retrying in [seconds] seconds (or as soon as the network is back). */
    class Waiting(val seconds: Int, val reason: SshLink.NetError? = null) : LinkStatus

    /** The fingerprint is needed to unlock the app's key. */
    object Locked : LinkStatus

    /** There is a saved password for the server: the fingerprint is needed to use it. */
    object PasswordLocked : LinkStatus

    /**
     * The password is needed (key missing or refused). [wrong]: the one given does not work.
     * [keyRejected]: the server does not accept the app's key (after the login, installing it is offered).
     */
    class NeedsPassword(val wrong: Boolean, val keyRejected: Boolean = false) : LinkStatus

    /** The server's key changed: no automatic connection. */
    object HostKeyChanged : LinkStatus

    /** The app's key is no longer valid: regenerate it in the settings (or use the password). */
    object KeyInvalid : LinkStatus

    /** The shell ended (exit): a tap opens a new one. */
    object Ended : LinkStatus
}

/** Reconnection with growing delays (1, 2, 4… up to 30 s); [now] skips the wait. */
internal class Backoff(private val action: () -> Unit, private val onWait: (Int) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var delay = 1
    private val run = Runnable { action() }

    fun schedule() {
        main.removeCallbacks(run)
        onWait(delay)
        main.postDelayed(run, delay * 1000L)
        delay = (delay * 2).coerceAtMost(MAX_S)
    }

    fun now() {
        main.removeCallbacks(run)
        action()
    }

    fun reset() {
        delay = 1
        main.removeCallbacks(run)
    }

    fun cancel() = main.removeCallbacks(run)

    private companion object {
        const val MAX_S = 30
    }
}

/** State observable from Compose, updated on the main thread. */
internal class MainState<T>(initial: T) {
    var value by mutableStateOf(initial)
        private set
    private val main = Handler(Looper.getMainLooper())

    fun post(v: T) {
        if (Looper.myLooper() == Looper.getMainLooper()) value = v else main.post { value = v }
    }
}
