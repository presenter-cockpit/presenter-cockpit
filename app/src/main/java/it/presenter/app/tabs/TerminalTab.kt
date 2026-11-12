package it.presenter.app.tabs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import com.jcraft.jsch.ChannelExec
import it.presenter.app.PresenterApp
import it.presenter.app.R
import it.presenter.app.remote.RemoteShell
import it.presenter.app.remote.ServerProfile
import it.presenter.app.remote.SshLink
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

/**
 * SSH terminal on [server], or **local** (Android shell on the tablet, [LocalPty]) if [server] is null.
 * The emulator (ConnectBot's libvterm) is independent of the view, so both the tablet (with the
 * keyboard) and the projector (larger) draw it. Remote: if the connection drops it retries with growing
 * delays; once back, with tmux (server option) the tab's session is resumed, without it a new shell
 * opens in the last folder ([RemoteShell]). Local: the shell lives as long as the app; reopened, a new
 * one starts in the last folder.
 */
class TerminalTab(val server: ServerProfile?, knownHosts: File, id: String? = null) : WorkTab(id ?: UUID.randomUUID().toString()) {
    /** Name of the shell's current folder ([knownDir]); until it is known, the server name. */
    override val title: String
        get() = knownDir?.trimEnd('/')?.let { dir -> dir.substringAfterLast('/').ifEmpty { "/" } } ?: server?.label ?: "sh"

    /** Android shell on the tablet. */
    val isLocal: Boolean get() = server == null

    /** On the same machine as [other] (null: the tablet), with the same user. */
    fun sameMachine(other: ServerProfile?): Boolean = if (other == null) server == null else server?.sameAccount(other) == true

    val link: SshLink? = server?.let { SshLink(it, knownHosts) }
    private val io = Executors.newSingleThreadExecutor { Thread(it, "ssh-terminal") }
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "ssh-write") }

    private val statusState = MainState<LinkStatus>(LinkStatus.Connecting)
    val status: LinkStatus get() = statusState.value

    /** Current rows and columns (the projector uses them to draw the same grid). */
    var size by mutableStateOf(24 to 80)
        private set

    /**
     * Terminal on the tablet: area (px) and font (px). The projector does not resize the grid (the tablet
     * decides it) and derives the font scale from these, so it shows the same rows and columns.
     */
    var viewWidthPx by mutableFloatStateOf(0f)
    var viewHeightPx by mutableFloatStateOf(0f)
    var fontPx by mutableFloatStateOf(0f)

    /** Top-left corner of the grid in the coordinates of `ref` (to anchor the laser to it). */
    var originIn: ((ref: LayoutCoordinates) -> Offset?)? = null

    @Volatile
    private var channel: ChannelExec? = null

    private val dirState = MainState<String?>(null)

    /**
     * Last known folder of the shell, observable (tab name, icon in the IDE tree). Updated without polling:
     * when the shell starts, after every Enter and when going back to an IDE of the same machine
     * ([currentDir]); see [cwd].
     */
    val knownDir: String? get() = dirState.value

    /** Last folder of the shell (without tmux), saved with the tab: a new shell starts there. */
    @Volatile
    var cwd: String? = null
        set(value) {
            field = value
            dirState.post(value)
        }

    @Volatile
    private var shellPid: Int? = null

    private val tmuxState = MainState(false)

    /** The tab is inside tmux (swiping becomes the mouse wheel, for tmux's history). */
    val usingTmux: Boolean get() = tmuxState.value

    val tmuxSession: String get() = RemoteShell.tmuxSession(id)

    private val markers = RemoteShell.Markers()
    private val aux = Executors.newSingleThreadScheduledExecutor { Thread(it, "ssh-cwd") }
    private var cwdQuery: ScheduledFuture<*>? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var closed = false

    /** Ctrl and Alt of the extra keys row (one tap: applies to the next key). */
    val modifiers = StickyModifiers()

    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        initialRows = 24,
        initialCols = 80,
        defaultForeground = Color(0xFFE6E6E6),
        defaultBackground = Color(0xFF16181C),
        onKeyboardInput = { bytes -> send(bytes) },
        onResize = { d ->
            size = d.rows to d.columns
            // Not on the "io" thread: there the shell read would keep it queued while the connection is open.
            exec(writer) {
                runCatching { channel?.setPtySize(d.columns, d.rows, d.widthPixels, d.heightPixels) }
                runCatching { pty?.resize(d.rows, d.columns) }
            }
        },
    )

    private val backoff = Backoff(action = { start() }, onWait = { statusState.post(LinkStatus.Waiting(it, link?.lastNetError)) })

    /** Local shell running (only with [isLocal]). */
    @Volatile
    private var pty: LocalPty? = null

    /** Connects and opens the shell (also after a drop: a new shell). */
    fun start() {
        if (closed) return
        statusState.post(LinkStatus.Connecting)
        exec(io) { if (isLocal) openLocalShell() else openShell() }
    }

    /** Password given by the user: retry with it. */
    fun retryWithPassword(password: String, fromStore: Boolean = false, saveBlob: String? = null) {
        link?.usePassword(password, fromStore, saveBlob)
        start()
    }

    /**
     * Android shell ([LocalShell.SHELL]) in the last folder (or the starting one); reads as long as someone
     * keeps the terminal open. When the shell ends (exit) the programs left in the background are hung up
     * too, so the read ends.
     */
    private fun openLocalShell() {
        shellPid = null
        val dir = cwd?.takeIf { File(it).isDirectory } ?: LocalShell.home
        val (rows, cols) = size
        val p = try {
            LocalPty.start(LocalShell.SHELL, arrayOf("sh"), LocalShell.environment(), dir, rows, cols)
        } catch (e: Throwable) {
            note(text(R.string.terminal_note_no_shell, e.message.orEmpty()))
            statusState.post(LinkStatus.Ended)
            return
        }
        // The waiter reaps the shell (no zombie processes); from then on signals go only to the remaining
        // programs of its session, never to its pid (which may be reused).
        Thread({
            p.waitFor()
            p.exited = true
            if (closed) return@Thread
            // Shell ended: hang up the background programs too, then kill them.
            p.hangup()
            Thread.sleep(HANGUP_WAIT_MS)
            if (pty === p) p.hangup(force = true)
        }, "local-shell-wait").start()
        if (closed) {
            p.hangup(force = true)
            p.close()
            return
        }
        pty = p
        output = p.output
        shellPid = p.pid
        cwd = dir
        // A resize that arrived while the shell was starting (pty still null) would be lost.
        val (r, c) = size
        if (r != rows || c != cols) runCatching { p.resize(r, c) }
        statusState.post(LinkStatus.Connected)
        val buffer = ByteArray(8192)
        try {
            while (!closed) {
                val n = p.input.read(buffer)
                if (n < 0) break
                if (n > 0) emulator.writeInput(buffer, 0, n)
            }
        } catch (_: IOException) {
            // I/O error: nobody keeps the terminal open any more (the shell ended).
        }
        output = null
        pty = null
        p.close()
        if (closed) return
        note(text(R.string.remote_ended))
        statusState.post(LinkStatus.Ended)
    }

    override fun onNetworkAvailable() {
        if (status is LinkStatus.Waiting) backoff.now()
    }

    private fun openShell() {
        val server = server ?: return
        val link = link ?: return
        val session = try {
            link.connect()
        } catch (e: SshLink.Failure) {
            when (e) {
                is SshLink.Failure.NeedsUnlock -> statusState.post(LinkStatus.Locked)
                is SshLink.Failure.PasswordLocked -> statusState.post(LinkStatus.PasswordLocked)
                is SshLink.Failure.KeyInvalid -> statusState.post(LinkStatus.KeyInvalid)
                is SshLink.Failure.NeedsPassword -> statusState.post(LinkStatus.NeedsPassword(wrong = link.password != null, keyRejected = link.keyRejected))
                is SshLink.Failure.HostKeyChanged -> statusState.post(LinkStatus.HostKeyChanged)
                is SshLink.Failure.Network -> if (!closed) backoff.schedule()
            }
            if (closed) link.disconnect()
            return
        }
        shellPid = null
        var stream: InputStream? = null
        val ch = try {
            (session.openChannel("exec") as ChannelExec).apply {
                setCommand(RemoteShell.launcher(if (server.tmux) tmuxSession else null, cwd ?: server.dir))
                setPty(true)
                setPtyType("xterm-256color")
                setPtySize(size.second, size.first, 0, 0)
                // Before connect: JSch drops what arrives earlier (here the PID, the first thing printed).
                stream = inputStream
                connect(CHANNEL_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            link.disconnect()
            if (!closed) backoff.schedule()
            return
        }
        // Tab closed while connecting: no orphaned session.
        if (closed) {
            runCatching { ch.disconnect() }
            link.disconnect()
            return
        }
        channel = ch
        // The view may have changed size during the connection (tab just reopened): tmux redraws for the size
        // it knows, so it gets the real one.
        val (rows, cols) = size
        exec(writer) { runCatching { ch.setPtySize(cols, rows, 0, 0) } }
        output = ch.outputStream
        val input = stream ?: return
        backoff.reset()
        statusState.post(LinkStatus.Connected)
        val buffer = ByteArray(8192)
        try {
            while (!closed) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) {
                    for (m in markers.feed(buffer, 0, n)) onMarker(m)
                    emulator.writeInput(buffer, 0, n)
                }
            }
        } catch (_: Exception) {
            // Read interrupted: connection dropped (told apart below).
        }
        output = null
        channel = null
        if (closed) return
        if (ch.isClosed && ch.exitStatus >= 0 && link.isConnected) {
            // The shell ended by itself (exit): wait for a tap to open another one.
            note(text(R.string.remote_ended))
            statusState.post(LinkStatus.Ended)
        } else {
            link.disconnect()
            note(text(R.string.terminal_note_lost))
            backoff.schedule()
        }
    }

    private fun onMarker(m: String) {
        when {
            m == "tmux" -> {
                tmuxState.post(true)
                scheduleCwdQuery(tmux = true)
            }
            m == "notmux" -> {
                tmuxState.post(false)
                note(text(R.string.terminal_note_no_tmux), newLine = false)
            }
            m.startsWith("pid=") -> {
                tmuxState.post(false)
                shellPid = m.removePrefix("pid=").toIntOrNull()
                scheduleCwdQuery(tmux = false)
            }
        }
    }

    /**
     * Current folder of the shell (on the server with tmux: the active pane; locally from `/proc`), within
     * [timeoutMs]; if it cannot be read (disconnected, shell not started yet) the last known one. Blocks.
     */
    fun currentDir(timeoutMs: Int): String? {
        if (isLocal) return (pty?.cwd() ?: cwd).also { d -> if (d != null && d != cwd) cwd = d }
        val link = link ?: return cwd
        val pid = shellPid
        val now = when {
            usingTmux -> link.runIfConnected(RemoteShell.tmuxDirQuery(tmuxSession), timeoutMs)
            pid != null -> link.runIfConnected(RemoteShell.cwdQuery(pid), timeoutMs)
            else -> null
        }?.trim()?.takeIf { it.startsWith("/") }
        if (now != null) cwd = now
        return now ?: cwd
    }

    /**
     * When the shell starts and after an Enter (plus a moment for the command): reread the shell's folder
     * (with tmux: the active pane), which also names the tab.
     */
    private fun scheduleCwdQuery(tmux: Boolean = usingTmux) {
        val pid = shellPid
        if (closed || (pid == null && !tmux)) return
        cwdQuery?.cancel(false)
        if (isLocal) {
            cwdQuery = runCatching {
                aux.schedule({ pty?.cwd()?.let { if (it != cwd) cwd = it } }, CWD_DELAY_MS, TimeUnit.MILLISECONDS)
            }.getOrNull()
            return
        }
        val link = link ?: return
        cwdQuery = runCatching {
            aux.schedule({
                val query = when {
                    closed -> null
                    tmux -> RemoteShell.tmuxDirQuery(tmuxSession)
                    pid != null && shellPid == pid -> RemoteShell.cwdQuery(pid)
                    else -> null
                }
                if (query != null) link.runIfConnected(query)?.trim()?.takeIf { it.startsWith("/") }?.let { cwd = it }
            }, CWD_DELAY_MS, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    /**
     * Closed by the user (the tab's "x"): the tmux session on the server too, as it would never be resumed.
     * When switching workspace the tab comes back instead, and the session stays.
     */
    override fun discard() {
        val link = link ?: return
        if (!usingTmux) return
        val name = tmuxSession
        Thread { link.runIfConnected(RemoteShell.killTmux(name)) }.also { discarding = it }.start()
    }

    @Volatile
    private var discarding: Thread? = null

    /** One mouse wheel step (SGR sequence, the one tmux asks for) at cell [col], [row] (from 1). */
    fun wheel(up: Boolean, col: Int, row: Int) = send("\u001b[<${if (up) 64 else 65};$col;${row}M".toByteArray())

    private fun send(bytes: ByteArray) {
        if (bytes.any { it == '\r'.code.toByte() || it == '\n'.code.toByte() }) scheduleCwdQuery()
        exec(writer) {
            runCatching {
                output?.apply {
                    write(bytes)
                    flush()
                }
            }
        }
    }

    /**
     * Runs [block] on [executor] if the tab is still open: after closing, the threads are stopped and a
     * late key or resize would crash the app.
     */
    private fun exec(executor: ExecutorService, block: () -> Unit) {
        if (closed) return
        runCatching { executor.execute { if (!closed) block() } }
    }

    /** Note of the app inside the terminal, between brackets, in the app's language. */
    private fun note(text: String, newLine: Boolean = true) =
        emulator.writeInput("${if (newLine) "\r\n" else ""}[$text]\r\n".toByteArray())

    private fun text(id: Int, vararg args: Any): String = PresenterApp.context.getString(id, *args)

    override fun close() {
        closed = true
        backoff.cancel()
        // Not on the "io" thread: there the shell read is blocked and closing would wait forever.
        Thread {
            // The tmux session is closed before disconnecting from the server.
            runCatching { discarding?.join(KILL_WAIT_MS) }
            runCatching { channel?.disconnect() }
            link?.close()
            // Local shell: hang up the shell and its programs (the read then ends by itself).
            pty?.let { p ->
                p.hangup()
                runCatching { Thread.sleep(HANGUP_WAIT_MS) }
                p.hangup(force = true)
            }
        }.start()
        io.shutdown()
        writer.shutdown()
        aux.shutdownNow()
    }

    /** "Sticky" Ctrl and Alt of the extra keys row: they apply to the next key. */
    class StickyModifiers : ModifierManager {
        var ctrl by mutableStateOf(false)
        var alt by mutableStateOf(false)
        override fun isCtrlActive() = ctrl
        override fun isAltActive() = alt
        override fun isShiftActive() = false
        override fun clearTransients() {
            ctrl = false
            alt = false
        }
    }

    companion object {
        private const val CHANNEL_TIMEOUT_MS = 10_000
        private const val CWD_DELAY_MS = 1_000L
        private const val KILL_WAIT_MS = 5_000L
        private const val HANGUP_WAIT_MS = 1_000L
    }
}
