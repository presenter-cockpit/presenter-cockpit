package it.presenter.app.session

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.Stroke
import it.presenter.app.R
import it.presenter.app.canvas.Viewport
import it.presenter.app.doc.DocSource
import it.presenter.app.doc.LinkTarget
import it.presenter.app.doc.PageEdits
import it.presenter.app.doc.PageRect
import it.presenter.app.doc.PresenterDocument
import it.presenter.app.doc.Slides
import it.presenter.app.doc.TextSnap
import it.presenter.app.ink.Affine
import it.presenter.app.ink.Box
import it.presenter.app.ink.Handwriting
import it.presenter.app.ink.HandwritingLines
import it.presenter.app.ink.InkStroke
import it.presenter.app.ink.LineDash
import it.presenter.app.ink.PageElement
import it.presenter.app.ink.PageInk
import it.presenter.app.ink.SelectionGeometry
import it.presenter.app.ink.Shape
import it.presenter.app.ink.TextBox
import it.presenter.app.ink.TextFont
import it.presenter.app.ink.TextHighlight
import it.presenter.app.ink.TextLayouts
import it.presenter.app.laser.LaserTrail
import it.presenter.app.remote.RemoteDocLinks
import it.presenter.app.remote.RemoteShell
import it.presenter.app.remote.SavedPasswords
import it.presenter.app.remote.ServerProfile
import it.presenter.app.settings.Limit
import it.presenter.app.settings.Prefs
import it.presenter.app.settings.Settings
import it.presenter.app.tabs.BrowserTab
import it.presenter.app.tabs.CodeInk
import it.presenter.app.tabs.EditorTab
import it.presenter.app.tabs.LocalShell
import it.presenter.app.tabs.TerminalTab
import it.presenter.app.tabs.WorkTab
import it.presenter.app.tabs.WorkTabStore
import it.presenter.app.tools.ToolEditorRequest
import it.presenter.app.tools.ToolKind
import it.presenter.app.tools.ToolPreset
import it.presenter.app.tools.ToolRef
import it.presenter.app.tools.ToolState
import it.presenter.app.workspace.Workspace
import it.presenter.app.workspace.WorkspaceDoc
import it.presenter.app.workspace.WorkspaceStore
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONObject

/** A page of a document. */
data class PageRef(val doc: PresenterDocument, val index: Int)

/**
 * Animated move from one page to another, drawn the same on tablet and projector. [fromTablet] and
 * [fromMirror] fix the framing of the outgoing page (even when zoomed).
 */
class PageTransition(
    val from: PageRef?,
    val to: PageRef,
    val kind: Kind,
    /** +1: the new page enters from the right; -1: from the left. */
    val direction: Int,
    val fromTablet: Matrix,
    /** Part of the page the projector was showing (null = whole page). */
    val fromMirror: RectF?,
) {
    enum class Kind { SLIDE, FADE }

    var progress = 0f
        internal set
}

/**
 * Shared state of the presentation: drawn by the tablet and mirrored by the projector. Lives on the
 * main thread (the Presentation runs there too). Properties read by the UI are Compose state; Views
 * update through [Listener].
 */
class PresenterSession(context: Context) {
    private val appContext = context.applicationContext
    val prefs = Prefs(context)
    val tools = ToolState(prefs)

    /** Hints at the first use of non-obvious features. */
    val hints = Hints(prefs)
    val viewport = Viewport()
    val laser = LaserTrail()
    /**
     * Laser over code and browser tabs, with the same trail as the page laser. Units like the page: width
     * of the tab area = 1000, height = 1000 × [workAspect]; the projector fits it to its screen.
     */
    val workTrail = LaserTrail()

    /**
     * The laser is anchored to the tab's content: the points of [workTrail] are relative to the start of the
     * editor text (like the strokes of [CodeInk]) or to the corner of the terminal
     * grid (same units, 1000 = width of the tab area). The projector, which lays out differently, puts them
     * back over the same lines and columns.
     */
    var workLaserInText = false

    /** Width (px) of the tab area on the tablet. */
    var workWidth = 1f
    val settings = Settings(prefs) {
        laser.inkHoldMs = it
        workTrail.inkHoldMs = it
    }

    /** Editor of a tool open next to the rail, or null. */
    var toolEditor by mutableStateOf<ToolEditorRequest?>(null)

    /** Settings screen open (here and not in the Activity: it survives a language change). */
    var settingsOpen by mutableStateOf(false)

    /** Settings opened from "+" to add a server: go to Servers and keys, with the dialog open. */
    var addServerRequested by mutableStateOf(false)

    val documents = mutableStateListOf<PresenterDocument>()
    var current by mutableStateOf<PresenterDocument?>(null)
        private set

    val currentRef: PageRef? get() = current?.let { PageRef(it, it.pageIndex) }
    val currentInk: PageInk? get() = current?.let { it.inkOf(it.pageIndex) }

    var transition: PageTransition? = null
        private set
    private var animator: ValueAnimator? = null
    private var swipe: PageTransition? = null

    /** Description of the external display, or null if there is none. */
    var projectorInfo by mutableStateOf<String?>(null)

    /**
     * Copies of the strokes in progress, built from the same tablet input, to show them live on the
     * projector (the tablet draws them with Ink's front buffer).
     */
    class LiveStroke(val stroke: InProgressStroke, val target: PageInk, val downTime: Long)

    val liveStrokes = LinkedHashMap<InProgressStrokeId, LiveStroke>()

    /** Projector windows shown on the external displays (set by `ProjectorController`). */
    var projectorWindows = 0
        set(value) {
            field = value
            if (!mirrorActive) liveStrokes.clear()
        }

    /**
     * Window to share open (`AudienceActivity`): what the audience sees in a task of its own, to share in a
     * video call as "one app". It counts as one more audience output.
     */
    var shareWindow: Boolean
        get() = shareWindowState
        set(value) {
            if (value == shareWindowState) return
            shareWindowState = value
            if (!mirrorActive) liveStrokes.clear()
            notify(Change.PROJECTOR)
        }
    private var shareWindowState by mutableStateOf(false)

    /** An external display found (enabled or not), for the outputs menu. */
    data class ExternalDisplay(val id: Int, val name: String, val width: Int, val height: Int, val trusted: Boolean, val enabled: Boolean)

    /** External displays found, set by `ProjectorController`. */
    var externalDisplays by mutableStateOf<List<ExternalDisplay>>(emptyList())

    private val displayChoices by lazy { prefs.displayChoices.toMutableMap() }

    /** Whether the display [name] shows what the audience sees: the choice made in the menu, or [trusted]. */
    fun displayEnabled(name: String, trusted: Boolean): Boolean = displayChoices[name] ?: trusted

    fun setDisplayEnabled(name: String, enabled: Boolean) {
        displayChoices[name] = enabled
        prefs.displayChoices = displayChoices
        notify(Change.PROJECTOR)
    }

    /**
     * Called at each touch started on Cockpit's window (not on its popups), while the outputs menu is open: it
     * closes, and the touch still reaches what is under it.
     */
    var onWindowTouch: (() -> Unit)? = null

    /** Someone shows what the audience sees: strokes in progress are copied for them, freezing is possible. */
    val mirrorActive get() = projectorWindows > 0 || shareWindow

    /** Area of the notes panel, in window coordinates: wet ink must not pass over it. */
    var notesBoundsInWindow: RectF? = null
        set(value) {
            if (field == value) return
            field = value
            notify(Change.LAYOUT)
        }

    // ------------------------------------------------------------------ notifications to the Views

    /**
     * [FADE]: vanishing strokes are fading (redraw, nothing to save).
     * [PROJECTOR]: what the audience sees changes (black, mirroring, frozen, colors).
     */
    enum class Change { PAGE, BITMAP, INK, LIVE, LASER, VIEWPORT, TRANSITION, LAYOUT, FADE, PROJECTOR }

    fun interface Listener {
        fun onChanged(change: Change)
    }

    private var listeners = emptyArray<Listener>()
    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners = listeners.filter { it !== l }.toTypedArray()
    }

    fun notify(change: Change) {
        if (change == Change.PAGE || change == Change.INK || change == Change.VIEWPORT) persistVersion++
        // The selection bar and the text box follow the page on screen.
        if ((selection != null || textEdit != null) && change != Change.LASER && change != Change.LIVE && change != Change.FADE) screenTick++
        for (l in listeners) l.onChanged(change)
    }

    /** Grows at every change to save with the session (page, ink, framing). */
    var persistVersion = 0L
        private set

    // ------------------------------------------------------------------ code, terminal, browser tabs

    /** Tabs that are not PDFs (terminals, IDEs, browsers). */
    val workTabs = mutableStateListOf<WorkTab>()

    /**
     * Order of the tabs in the top bar, PDFs ([PresenterDocument]) and work tabs mixed, as the user arranged
     * them by dragging. Tabs not in it yet (just opened) go at the end.
     */
    private val tabOrder = mutableStateListOf<Any>()

    /** All tabs, in bar order. */
    val orderedTabs: List<Any> get() = TabOrder.arrange(tabOrder.toList(), documents + workTabs)

    /** Moves the tab at [from] (in [orderedTabs]) to [to]. */
    fun moveTab(from: Int, to: Int) {
        val moved = TabOrder.move(orderedTabs, from, to)
        tabOrder.clear()
        tabOrder.addAll(moved)
    }

    /** Stable key of a tab, to save the order: the PDF's file, the id for the others. */
    private fun tabKey(tab: Any): String = when (tab) {
        is PresenterDocument -> "pdf:" + SessionStore.sourceToJson(tab.source)
        is WorkTab -> "work:" + tab.id
        else -> ""
    }

    /** Tab order to save (session, workspaces). */
    fun orderKeys(): List<String> = orderedTabs.map(::tabKey)

    /** Saved order, after reopening the tabs; without one ([keys] empty) PDFs first, then the others. */
    fun applyOrder(keys: List<String>) {
        val ordered = TabOrder.byKeys(keys, documents + workTabs, ::tabKey)
        tabOrder.clear()
        tabOrder.addAll(ordered)
    }

    /** Work tab in front, or null if the PDF [current] is in front. */
    var activeWork by mutableStateOf<WorkTab?>(null)
        private set

    /** Pen near a work tab, with any tool (editor and terminal stop accepting text). */
    var workStylusNear by mutableStateOf(false)

    /** Height / width of the tab area on the tablet (see [workTrail]). */
    var workAspect = 0.6f

    /** Grows at every laser event on the tabs: tablet and projector redraw the trail. */
    var workTrailFrame by mutableIntStateOf(0)

    /** Keys of the servers already seen (accepted at the first connection). */
    val knownHosts = File(context.filesDir, "ssh/known_hosts")

    /** Saved servers. */
    val servers = mutableStateListOf<ServerProfile>().apply {
        addAll(ServerProfile.listFromJson(prefs.serversJson))
    }

    fun saveServer(server: ServerProfile) {
        val i = servers.indexOfFirst { it.id == server.id }
        if (i >= 0) servers[i] = server else servers.add(server)
        prefs.serversJson = ServerProfile.listToJson(servers)
    }

    init {
        // PDFs on a server: one connection per server, with the password of an already connected tab if needed.
        RemoteDocLinks.configure(
            servers = { servers.toList() },
            knownHosts = knownHosts,
            passwordOf = { server ->
                workTabs.toList().firstNotNullOfOrNull { t ->
                    val link = when (t) {
                        is EditorTab -> t.link
                        is TerminalTab -> t.link
                        is BrowserTab -> null
                    }
                    link?.takeIf { it.server.sameAccount(server) }?.password
                }
            },
        )
    }

    /**
     * Opens a PDF (from an IDE: the [DocSource] and the tab to put it after). Set by `MainActivity`, which
     * reads the file on a worker thread and then calls [open].
     */
    var onOpenPdf: ((source: DocSource, after: Any?) -> Unit)? = null

    /** New whiteboard from the "+" menu (set by `MainActivity`). */
    var onNewBoard: (() -> Unit)? = null

    /** Fingerprint for the SSH key, then the block (set by `MainActivity`; used by the remote PDF picker). */
    var unlockKey: ((then: () -> Unit) -> Unit)? = null

    /** Fingerprint for the server's saved password, which is then passed to the block (like [unlockKey]). */
    var unlockPassword: ((server: ServerProfile, then: (String) -> Unit) -> Unit)? = null

    fun deleteServer(id: String) {
        servers.removeAll { it.id == id }
        SavedPasswords.remove(id)
        prefs.serversJson = ServerProfile.listToJson(servers)
    }

    /** Opens work tab [tab]: at the end of the bar, or right after [after]. */
    fun openWork(tab: WorkTab, after: Any? = null) {
        // New tabs start as the setting says; afterwards each has its own state.
        tab.inverted = defaultInverted(tab)
        currentWorkspace?.fontByKind?.get(invertKind(tab))?.let { size -> tab.fontSize = size.coerceIn(WorkTab.FONT_RANGE) }
        watchFont(tab)
        workTabs.add(tab)
        if (after != null) {
            val ordered = TabOrder.insertAfter(orderedTabs, tab, after)
            tabOrder.clear()
            tabOrder.addAll(ordered)
        }
        showWork(tab)
        when (tab) {
            is TerminalTab -> tab.start()
            is EditorTab -> tab.refresh()
            is BrowserTab -> Unit
        }
    }

    /** Work tabs of the saved session (or of a workspace): reopened and reconnected. */
    fun restoreWork(entries: List<JSONObject>, active: Int) {
        val tabs = entries.mapNotNull { WorkTabStore.fromJson(it, knownHosts, servers) }
        for (t in tabs) {
            watchFont(t)
            workTabs.add(t)
            when (t) {
                is TerminalTab -> t.start()
                is EditorTab -> t.refresh()
                is BrowserTab -> Unit
            }
        }
        tabs.getOrNull(active)?.let(::showWork)
    }

    /**
     * "IDE here" from the tree of an IDE: if an IDE of the same user on the same machine already has [dir] as
     * its root, switch to it; otherwise open a new one there, right after [editor].
     */
    fun ideAt(editor: EditorTab, dir: String) {
        // IDE not connected yet: the root folder is unknown.
        if (dir.isBlank()) return
        // The current profile (its options may have changed after the IDE was opened).
        val server = editor.server?.let { s -> servers.firstOrNull { it.id == s.id } ?: s }
        fun same(a: String) = a.trimEnd('/').ifEmpty { "/" } == dir.trimEnd('/').ifEmpty { "/" }
        val open = workTabs.filterIsInstance<EditorTab>().firstOrNull { same(it.root) && it.sameMachine(server) }
        if (open != null) {
            showWork(open)
            return
        }
        openWork(if (server != null) EditorTab.remote(server, knownHosts, dir) else EditorTab.local(dir), after = editor)
    }

    /**
     * "Terminal here" from the tree of an IDE on a server or on the tablet: if a terminal of the same user
     * on the same machine is already exactly in folder [dir], switch to it (the most recent); otherwise
     * open a new one there, right after the IDE. Terminal folders are read on the server (all together,
     * within [TERMINAL_QUERY_MS]; locally from `/proc`); a disconnected one counts with its last known folder.
     */
    fun terminalAt(editor: EditorTab, dir: String) {
        // The current profile (its options, tmux included, may have changed after the IDE was opened).
        val server = editor.server?.let { s -> servers.firstOrNull { it.id == s.id } ?: s }
        if (server == null && !LocalShell.canOpenIn(dir)) return
        // IDE not connected yet: the root folder is unknown.
        if (dir.isBlank()) return
        // A second tap while searching would open two terminals in the same folder.
        if (terminalLookup) return
        terminalLookup = true
        val terminals = workTabs.filterIsInstance<TerminalTab>()
            .filter { it.sameMachine(server) }
            .sortedByDescending { it.shownAt }
        Thread {
            try {
                val pool = Executors.newCachedThreadPool()
                val target = pool.submit<String?> {
                    // Locally symbolic links are resolved here (like `cd … && pwd -P` on the server).
                    if (server == null) runCatching { File(dir).canonicalPath }.getOrNull()
                    else editor.link?.runIfConnected(RemoteShell.resolveDirQuery(dir), TERMINAL_QUERY_MS)?.trim()
                }
                val dirs = terminals.map { t -> pool.submit<String?> { t.currentDir(TERMINAL_QUERY_MS) } }
                val deadline = System.currentTimeMillis() + TERMINAL_QUERY_MS + 500
                fun <T> Future<T>.within(): T? =
                    runCatching { get((deadline - System.currentTimeMillis()).coerceAtLeast(1), TimeUnit.MILLISECONDS) }.getOrNull()
                val resolved = target.within()?.takeIf { it.startsWith("/") } ?: dir
                val found = terminals.getOrNull(RemoteShell.pickTerminal(resolved, dirs.mapIndexed { i, f -> f.within() ?: terminals[i].cwd }))
                // Without interrupting: the remaining queries end by themselves (at most after their timeout).
                pool.shutdown()
                mainHandler.post {
                    when {
                        found != null && found in workTabs -> showWork(found)
                        editor in workTabs -> openWork(
                            TerminalTab(server, knownHosts).also { t -> t.cwd = resolved },
                            after = editor,
                        )
                    }
                }
            } finally {
                // Even after an unexpected error: otherwise "Terminal here" would stay blocked.
                mainHandler.post { terminalLookup = false }
            }
        }.start()
    }

    /**
     * Rereads the folder of the terminals of [editor]'s machine (for the icon in the tree): remotely only
     * those with tmux, locally all of them. Called when opening or returning to the IDE, not continuously;
     * without tmux the folder is already updated after every Enter.
     */
    fun refreshTerminalDirs(editor: EditorTab) {
        val server = editor.server
        // Locally reading is cheap: all of the tablet's terminals.
        val terminals = workTabs.filterIsInstance<TerminalTab>()
            .filter { (server == null && it.isLocal) || (it.usingTmux && it.sameMachine(server)) }
        for (t in terminals) Thread { runCatching { t.currentDir(TERMINAL_QUERY_MS) } }.start()
    }

    /**
     * Terminal on [editor]'s machine that is (by its last known folder) exactly in folder [dir]; the most
     * recent if more than one, null if none.
     */
    fun terminalIn(editor: EditorTab, dir: String): TerminalTab? {
        val server = editor.server
        if (server == null && !LocalShell.canOpenIn(dir)) return null
        val target = RemoteShell.normalizeDir(dir)
        return workTabs.filterIsInstance<TerminalTab>()
            .filter { t -> t.sameMachine(server) && t.knownDir?.let(RemoteShell::normalizeDir) == target }
            .maxByOrNull { it.shownAt }
    }

    /** "Terminal here" in progress (see [terminalAt]); main thread only. */
    private var terminalLookup = false

    fun showWork(tab: WorkTab) {
        tab.shownAt = System.currentTimeMillis()
        endTextEdit()
        selection = null
        overviewOpen = false
        navigationOpen = false
        activeWork = tab
        adaptTool(tab)
        laser.clear()
        notify(Change.PROJECTOR)
    }

    /** Tool to restore when leaving a tab where you can only point (see [adaptTool]). */
    private var toolBeforePointer: ToolRef? = null

    /**
     * On terminal and browser the pen does not draw: entering with a tool that is not a laser switches to the
     * last laser used, and going back to a PDF or editor restores the previous tool (user's request). If a
     * non-laser tool was picked by hand meanwhile, that one stays.
     */
    private fun adaptTool(tab: WorkTab?) {
        val pointerOnly = tab is TerminalTab || tab is BrowserTab
        val isLaser = tools.preset(tools.selected)?.kind == ToolKind.LASER
        if (pointerOnly) {
            if (toolBeforePointer == null && !isLaser) {
                val laser = tools.lastLaser() ?: return
                toolBeforePointer = tools.selected
                tools.select(laser)
            }
        } else {
            val before = toolBeforePointer ?: return
            toolBeforePointer = null
            if (isLaser) tools.select(before)
        }
    }

    /** Back to the PDFs (the PDF tab [current], if any). */
    fun showPdf() {
        if (activeWork == null) return
        activeWork = null
        adaptTool(null)
        notify(Change.PROJECTOR)
        notify(Change.PAGE)
    }

    /** Code tab with unsaved changes being closed (confirmation dialog), or null. */
    var closingWork by mutableStateOf<WorkTab?>(null)

    /**
     * Closes the tab; [discard]: closed by the user, it will not come back with a workspace. If it was in
     * front, the tab beside it in the bar comes in front (PDF or not).
     */
    fun closeWork(tab: WorkTab, discard: Boolean = false) {
        val i = workTabs.indexOf(tab)
        if (i < 0) return
        val next = if (activeWork === tab) TabOrder.neighbor(orderedTabs, tab) else null
        workTabs.removeAt(i)
        tabOrder.remove(tab)
        if (discard) tab.discard()
        tab.close()
        if (activeWork === tab) {
            when (next) {
                is WorkTab -> showWork(next)
                is PresenterDocument -> switchTo(next)
                else -> showPdf()
            }
        }
    }

    init {
        // When the network is back, remote tabs reconnect at once (without waiting for the growing delay).
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Handler(Looper.getMainLooper()).post {
                        workTabs.forEach { it.onNetworkAvailable() }
                        // Remote PDFs without a server retry at the next check, without waiting.
                        documents.forEach { (it.source as? DocSource.Remote)?.retrySoon() }
                    }
                }
            })
        }
    }

    // ------------------------------------------------------------------ projector and audience

    enum class ProjectorMode {
        /** The audience sees the presentation. */
        PRESENT,

        /** The projector stays connected but shows black (the pause screen). */
        BLACK,

        /** No presentation: Android mirrors the tablet screen (tools and notes included). */
        MIRROR,

        /**
         * Private start: the projector is connected but shows only the logo, until the presenter decides to
         * begin (the tab and workspace at launch may not be the right ones).
         */
        PRIVATE,
    }

    private var projectorModeState by mutableStateOf(ProjectorMode.PRESENT)

    var projectorMode: ProjectorMode
        get() = projectorModeState
        set(value) {
            if (value == projectorModeState) return
            projectorModeState = value
            // When mirroring the audience sees the tablet: a frozen image no longer makes sense.
            if (value == ProjectorMode.MIRROR) {
                freezeRequested = false
                frozen = null
            }
            notify(Change.PROJECTOR)
        }

    /**
     * Tap on the projector button (or B): black on/off; when mirroring or in private start, back to the
     * presentation.
     */
    fun toggleBlack() {
        projectorMode = if (projectorMode == ProjectorMode.PRESENT) ProjectorMode.BLACK else ProjectorMode.PRESENT
    }

    /**
     * What the audience sees frozen (a snapshot of the projector), or null. On the tablet you move freely; a
     * small window shows the preview.
     */
    var frozen by mutableStateOf<Bitmap?>(null)
        private set

    /** The projector must take its snapshot at the next draw ([MirrorView]). */
    var freezeRequested = false
        private set

    /** Inverted colors on the projector for the tab in front (PDF or work tab). */
    val inverted: Boolean get() = activeWork?.inverted ?: current?.inverted ?: false

    /** Inverts the colors of the tab in front; the state stays with the tab (user's request). */
    fun toggleInverted() {
        val work = activeWork
        val value: Boolean
        if (work != null) {
            work.inverted = !work.inverted
            value = work.inverted
        } else {
            val doc = current ?: return
            doc.inverted = !doc.inverted
            value = doc.inverted
            persistVersion++
        }
        // The workspace remembers the last choice for this kind of tab.
        currentWorkspace?.let { ws -> updateWorkspace(ws.copy(invertedByKind = ws.invertedByKind + (invertKind(work) to value))) }
        notify(Change.PROJECTOR)
    }

    /** The font size changed in a tab becomes that of new tabs of its kind in the workspace in use. */
    private fun watchFont(tab: WorkTab) {
        tab.onFontChanged = { size ->
            currentWorkspace?.let { ws ->
                val kind = invertKind(tab)
                if (ws.fontByKind[kind] != size) updateWorkspace(ws.copy(fontByKind = ws.fontByKind + (kind to size)))
            }
        }
    }

    private fun invertKind(work: WorkTab?): String = when (work) {
        null -> "pdf"
        is TerminalTab -> "terminal"
        is EditorTab -> "editor"
        is BrowserTab -> "browser"
    }

    /** Inverted colors of a new tab: the last choice for that kind in the workspace in use, or the setting. */
    private fun defaultInverted(work: WorkTab?): Boolean =
        currentWorkspace?.invertedByKind?.get(invertKind(work)) ?: settings.projectorInverted

    fun toggleFreeze() {
        if (frozen != null || freezeRequested) {
            freezeRequested = false
            frozen = null
        } else if (mirrorActive && projectorMode == ProjectorMode.PRESENT) {
            // Freeze the page that arrived, not a scroll halfway.
            finishTransition()
            freezeRequested = true
        }
        notify(Change.PROJECTOR)
        // Every output took its snapshot while notified; one not ready yet uses the first (see `MirrorView`).
        freezeRequested = false
    }

    /**
     * Called by each audience output with its snapshot requested by [toggleFreeze] (each keeps its own, in its
     * proportions): the first one is the preview on the tablet.
     */
    fun onFrozen(bitmap: Bitmap) {
        if (frozen == null) frozen = bitmap
    }

    /** No audience output left: nothing stays frozen. */
    fun clearFrozen() {
        freezeRequested = false
        frozen = null
    }

    /** Spotlighted area on the page ("spotlight" tool), drawn on tablet and projector. */
    class Spotlight(val ref: PageRef, val left: Float, val top: Float, val right: Float, val bottom: Float)

    var spotlight: Spotlight? = null
        set(value) {
            field = value
            notify(Change.LAYOUT)
        }

    /** Grid overview open (tablet only). */
    var overviewOpen by mutableStateOf(false)

    /** Keyboard shortcuts summary open. */
    var shortcutsOpen by mutableStateOf(false)

    /** End time of the presentation (epoch ms), 0 if not set. */
    var presentationEnd by mutableLongStateOf(prefs.presentationEnd.takeIf { it > System.currentTimeMillis() - PRESENTATION_END_KEEP_MS } ?: 0L)
        private set

    /**
     * When the timer was set, or the stopwatch started (epoch ms; 0 if neither): the elapsed time counts from
     * here. A stopwatch alone is forgotten after [STOPWATCH_KEEP_MS].
     */
    var presentationStart by mutableLongStateOf(
        prefs.presentationStart.takeIf { it > 0L && (presentationEnd > 0L || System.currentTimeMillis() - it < STOPWATCH_KEEP_MS) } ?: 0L,
    )
        private set

    private fun updatePresentationStart(time: Long) {
        presentationStart = time
        prefs.presentationStart = time
    }

    /** How close the end is (timer at the top, background around the slide on the tablet); see [TimerPhase]. */
    var timerPhase by mutableStateOf(TimerPhase.NONE)
        private set

    private val timerHandler = Handler(Looper.getMainLooper())

    /** Every second while an end is set: the phase changes on time, the tablet redraws only when it does. */
    private val timerTick = object : Runnable {
        override fun run() {
            val end = presentationEnd
            val phase = if (end > 0L) TimerPhase.of(end - System.currentTimeMillis(), settings.timerYellowMinutes, settings.timerOrangeMinutes) else TimerPhase.NONE
            if (phase != timerPhase) {
                timerPhase = phase
                notify(Change.LAYOUT)
            }
            timerHandler.removeCallbacks(this)
            if (end > 0L) timerHandler.postDelayed(this, 1000L)
        }
    }

    init {
        timerTick.run()
    }

    /** Stopwatch only, without an end time: the elapsed time from now. */
    fun startStopwatch() {
        updatePresentationEnd(0L)
        updatePresentationStart(System.currentTimeMillis())
    }

    /** The time is forgotten 3 hours after the end even with the app always open (Back does not close it). */
    fun expirePresentationEnd(now: Long) {
        if (presentationEnd > 0L && now > presentationEnd + PRESENTATION_END_KEEP_MS) updatePresentationEnd(0L)
        if (presentationEnd == 0L && presentationStart > 0L && now - presentationStart > STOPWATCH_KEEP_MS) updatePresentationStart(0L)
    }

    /** Sets the end time ([time], epoch ms; 0 removes the timer and the stopwatch). */
    fun updatePresentationEnd(time: Long) {
        // The elapsed time counts from when a timer is first set (a stopwatch already running goes on).
        if (time > 0L && presentationStart == 0L) updatePresentationStart(System.currentTimeMillis())
        if (time == 0L) updatePresentationStart(0L)
        presentationEnd = time
        prefs.presentationEnd = time
        timerTick.run()
        // The workspace remembers the time (a recurring talk usually ends around then).
        if (time > 0L) currentWorkspace?.let { ws ->
            val c = Calendar.getInstance().apply { timeInMillis = time }
            updateWorkspace(ws.copy(presentationEndMinutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)))
        }
    }

    // ------------------------------------------------------------------ workspaces

    val workspaces = mutableStateListOf<Workspace>().apply {
        addAll(WorkspaceStore.read(context))
    }

    var currentWorkspaceId by mutableStateOf(prefs.currentWorkspace?.takeIf { id -> workspaces.any { it.id == id } })
        private set

    val currentWorkspace get() = workspaces.firstOrNull { it.id == currentWorkspaceId }

    fun setCurrentWorkspace(id: String?) {
        currentWorkspaceId = id
        prefs.currentWorkspace = id
    }

    /** Snapshot of the open tabs as the content of a workspace. */
    fun captureWorkspace(base: Workspace): Workspace = base.copy(
        docs = documents.map { WorkspaceDoc(it.source, it.pageIndex, it.inverted) },
        active = documents.indexOf(current).coerceAtLeast(0),
        work = workTabs.map { WorkTabStore.toJson(it) },
        activeWork = workTabs.indexOf(activeWork),
        order = orderKeys(),
    )

    /** Writing of workspaces.json off the main thread (in order, one at a time). */
    private val workspaceWriter = Executors.newSingleThreadExecutor { Thread(it, "workspaces") }

    private fun persistWorkspaces() {
        val list = workspaces.toList()
        workspaceWriter.execute { runCatching { WorkspaceStore.write(appContext, list) } }
    }

    fun updateWorkspace(ws: Workspace) {
        val i = workspaces.indexOfFirst { it.id == ws.id }
        if (i >= 0) workspaces[i] = ws else workspaces.add(ws)
        persistWorkspaces()
    }

    /** Preview position moved without a workspace in use: kept until the app closes. */
    private var loosePreviewPosition by mutableStateOf<Pair<Float, Float>?>(null)

    /** Where the previews of what the audience sees are ([Workspace.previewPosition]); null = default corner. */
    val previewPosition: Pair<Float, Float>? get() = currentWorkspace.let { ws -> if (ws != null) ws.previewPosition else loosePreviewPosition }

    fun setPreviewPosition(position: Pair<Float, Float>) {
        val ws = currentWorkspace
        if (ws != null) updateWorkspace(ws.copy(previewPosition = position)) else loosePreviewPosition = position
    }

    /** New workspace with the tabs open now, which becomes the one in use. */
    fun saveAsWorkspace(name: String) {
        val ws = captureWorkspace(Workspace(name = name))
        updateWorkspace(ws)
        setCurrentWorkspace(ws.id)
    }

    fun deleteWorkspace(id: String) {
        workspaces.removeAll { it.id == id }
        persistWorkspaces()
        if (currentWorkspaceId == id) setCurrentWorkspace(null)
    }

    /** The workspace in use follows the open tabs (called with the session save). */
    fun syncCurrentWorkspace() {
        val ws = currentWorkspace ?: return
        val updated = captureWorkspace(ws)
        if (updated.toJson().toString() != ws.toJson().toString()) updateWorkspace(updated)
    }

    /** Closes all tabs (PDFs and work tabs) before opening a workspace. */
    fun closeEverything() {
        endTextEdit()
        selection = null
        // No tab must come in front while the others close.
        showPdf()
        workTabs.toList().forEach { closeWork(it) }
        closeAll()
        tabOrder.clear()
    }

    // ------------------------------------------------------------------ documents

    /** Opens [doc]: at the end of the bar, or right after tab [after] (the IDE it comes from). */
    fun open(doc: PresenterDocument, after: Any? = null) {
        // A PDF just opened comes to the front even if a code tab was in front.
        activeWork = null
        adaptTool(null)
        doc.inverted = defaultInverted(null)
        documents.add(doc)
        if (after != null) {
            val ordered = TabOrder.insertAfter(orderedTabs, doc, after)
            tabOrder.clear()
            tabOrder.addAll(ordered)
        }
        show(PageRef(doc, doc.pageIndex), PageTransition.Kind.FADE, 0)
    }

    /** Reopens the saved session: documents already loaded, the active one and the framing. */
    fun restore(docs: List<PresenterDocument>, active: Int, viewportState: FloatArray?) {
        if (docs.isEmpty()) return
        documents.addAll(docs)
        val doc = docs.getOrElse(active) { docs.first() }
        show(PageRef(doc, doc.pageIndex), PageTransition.Kind.FADE, 0)
        viewportState?.let {
            viewport.restoreState(it)
            notify(Change.VIEWPORT)
        }
    }

    fun switchTo(doc: PresenterDocument) {
        showPdf()
        if (doc === current) return
        show(PageRef(doc, doc.pageIndex), PageTransition.Kind.FADE, 0)
    }

    /**
     * Closes the PDF tab [doc]. If it was in front, the tab beside it in the bar comes in front (PDF or not); the
     * PDF behind the work tabs becomes the nearest one in the bar.
     */
    fun close(doc: PresenterDocument) {
        val i = documents.indexOf(doc)
        if (i < 0) return
        val bar = orderedTabs
        val beside = if (doc === current && activeWork == null) TabOrder.neighbor(bar, doc) else null
        if (doc === current) {
            finishTransition()
            val next = beside as? PresenterDocument ?: TabOrder.neighbor(bar.filterIsInstance<PresenterDocument>(), doc)
            documents.removeAt(i)
            // The closed document must not appear in the fade: start from empty.
            current = null
            if (next != null) show(PageRef(next, next.pageIndex), PageTransition.Kind.FADE, 0) else commit(null)
        } else {
            // The running fade could still draw the document being closed.
            if (transition?.from?.doc === doc) finishTransition()
            documents.removeAt(i)
        }
        tabOrder.remove(doc)
        // Nothing must stay attached to the closed document.
        if (textEdit?.doc === doc) textEdit = null
        if (selection?.doc === doc) selection = null
        if (searchHighlight?.doc === doc) searchHighlight = null
        jumpHistory.removeAll { it.doc === doc }
        val inks = inksOf(doc)
        liveStrokes.values.removeAll { it.target in inks }
        doc.close()
        releaseLinkOf(doc)
        if (beside is WorkTab) showWork(beside)
    }

    /** Last PDF on that server closed: the shared connection is no longer needed. */
    private fun releaseLinkOf(doc: PresenterDocument) {
        val src = doc.source as? DocSource.Remote ?: return
        releaseRemoteLink(src.serverId)
    }

    /** Closes the connection of the PDFs of server [serverId] if no open PDF uses it. */
    fun releaseRemoteLink(serverId: String) {
        if (documents.none { (it.source as? DocSource.Remote)?.serverId == serverId }) {
            RemoteDocLinks.release(serverId)
        }
    }

    /** A stroke or a selection drag is in progress on [doc]: not the moment to replace it. */
    fun isEditing(doc: PresenterDocument): Boolean {
        val inks = inksOf(doc)
        return liveStrokes.values.any { it.target in inks } || selection?.let { it.doc === doc && it.ink.isChanging } == true
    }

    private fun inksOf(doc: PresenterDocument) = (0 until doc.pageCount).mapNotNull { doc.inkIfPresent(it) }.toSet()

    /**
     * The PDF [old] was reread from disk as [fresh] (changed by someone else): it takes its place in the
     * same tab, on the same page (if it still exists), with notes and colors as before. The unsaved changes
     * of [old] are lost.
     *
     * With [newIndex] (pages edited from the overview, [PresenterDocument.edited]) [fresh] is the same
     * document with the pages in another order: the current page and the jumps follow the pages.
     */
    fun replace(old: PresenterDocument, fresh: PresenterDocument, newIndex: IntArray? = null) {
        val i = documents.indexOf(old)
        if (i < 0) {
            fresh.close()
            return
        }
        fun mapped(page: Int) = (if (newIndex != null) PageEdits.follow(page, newIndex) else page).coerceIn(0, fresh.pageCount - 1)
        fresh.pageIndex = mapped(old.pageIndex)
        fresh.notesVisible = old.notesVisible && fresh.notesAvailable
        fresh.inverted = old.inverted
        if (newIndex != null) fresh.changedOnDisk = old.changedOnDisk
        val t = transition
        if (old === current || t?.from?.doc === old || t?.to?.doc === old) finishTransition()
        if (textEdit?.doc === old) textEdit = null
        if (selection?.doc === old) selection = null
        if (searchHighlight?.doc === old) searchHighlight = null
        documents[i] = fresh
        val o = tabOrder.indexOf(old)
        if (o >= 0) tabOrder[o] = fresh
        jumpHistory.replaceAll { if (it.doc === old) PageRef(fresh, mapped(it.index)) else it }
        val inks = inksOf(old)
        liveStrokes.values.removeAll { it.target in inks }
        // Without a fade: the page is the same, only the content changes.
        if (old === current) commit(PageRef(fresh, fresh.pageIndex))
        old.close()
    }

    fun closeAll() {
        finishTransition()
        val closed = documents.toList()
        closed.forEach { it.close() }
        documents.clear()
        closed.forEach(::releaseLinkOf)
        jumpHistory.clear()
        commit(null)
    }

    // ------------------------------------------------------------------ jumps (links, outline, search)

    /** Pages jumped from, the most recent last: the "back" icon returns there. */
    val jumpHistory = mutableStateListOf<PageRef>()

    /** Navigation panel (go to page, outline, search) open. */
    var navigationOpen by mutableStateOf(false)

    /** Search result highlighted on the page, tablet only. */
    class SearchHighlight(val doc: PresenterDocument, val page: Int, val rects: List<PageRect>)

    var searchHighlight: SearchHighlight? = null
        set(value) {
            field = value
            notify(Change.LAYOUT)
        }

    /** External link touched, waiting for confirmation to open it in the browser. */
    var pendingUri by mutableStateOf<String?>(null)

    /**
     * Jump to [page] of [doc] (link, outline, go to page, search): the starting page enters the history.
     * Turning pages and swiping do not.
     */
    fun jumpTo(doc: PresenterDocument, page: Int, record: Boolean = true) {
        finishTransition()
        val from = currentRef ?: return
        val target = page.coerceIn(0, doc.pageCount - 1)
        if (from.doc === doc && from.index == target) return
        // Several jumps in a row from the same panel (search results): only the first counts.
        if (record) jumpHistory.add(from)
        while (jumpHistory.size > MAX_JUMP_HISTORY) jumpHistory.removeAt(0)
        showJump(PageRef(doc, target), from)
    }

    /** Back to the page of the last jump. */
    fun jumpBack() {
        finishTransition()
        val back = jumpHistory.removeLastOrNull() ?: return
        if (back.doc !in documents) return jumpBack()
        showJump(back, currentRef)
    }

    private fun showJump(to: PageRef, from: PageRef?) {
        val kind = if (from?.doc === to.doc) PageTransition.Kind.SLIDE else PageTransition.Kind.FADE
        val direction = if (from != null && from.doc === to.doc && to.index < from.index) -1 else 1
        show(to, kind, if (kind == PageTransition.Kind.FADE) 0 else direction)
    }

    /**
     * Finger tap at point ([x], [y]) of the current page: follows the link under it (internal at once,
     * external after confirmation). False if there is no link there.
     */
    fun followLinkAt(x: Float, y: Float): Boolean {
        val ref = currentRef ?: return false
        val link = ref.doc.structure?.linkAt(ref.index, x, y) ?: return false
        when (val target = link.target) {
            is LinkTarget.Page -> jumpTo(ref.doc, target.index)
            is LinkTarget.Uri -> pendingUri = target.uri
        }
        return true
    }

    // ------------------------------------------------------------------ pages

    /** Slides of the document for navigation: Beamer overlays (if on in the settings) or one slide per page. */
    fun navSlides(doc: PresenterDocument): Slides = if (settings.beamerOverlays) doc.slides else doc.singlePages

    /** Forward one slide (to its first overlay): arrows, keys, remote, swipe. */
    fun nextPage() = goToSlide(1)

    /** Back one slide (to its last overlay). */
    fun previousPage() = goToSlide(-1)

    /**
     * Page reached going forward (+1) or back (-1) from page [ref], or null. Slides hidden in the .pdfpc are
     * skipped, as in PDFPC.
     */
    fun slideTarget(ref: PageRef, direction: Int): Int? {
        val slides = navSlides(ref.doc)
        return if (direction > 0) slides.nextSlide(ref.index, ref.doc::isHidden) else slides.previousSlide(ref.index, ref.doc::isHidden)
    }

    private fun goToSlide(direction: Int) {
        // First close any swipe or animation: the page actually shown is what counts.
        finishTransition()
        val ref = currentRef ?: return
        val target = slideTarget(ref, direction) ?: return
        show(PageRef(ref.doc, target), PageTransition.Kind.SLIDE, direction)
    }

    /**
     * Finger tap: shows the next overlay of the same slide, instantly (overlays add parts to the same page:
     * no scrolling). False if there is none.
     */
    fun nextOverlay(): Boolean {
        if (!settings.beamerOverlays) return false
        finishTransition()
        val ref = currentRef ?: return false
        val target = ref.doc.slides.nextOverlay(ref.index) ?: return false
        commit(PageRef(ref.doc, target))
        return true
    }

    private fun show(ref: PageRef, kind: PageTransition.Kind, direction: Int) {
        finishTransition()
        val t = newTransition(ref, kind, direction)
        commit(ref)
        if (!settings.animatedTransitions) {
            // Transitions off: the new page appears at once.
            transition = t
            finishTransition()
            return
        }
        animate(t, 1f, if (kind == PageTransition.Kind.SLIDE) SLIDE_MS else FADE_MS)
    }

    private fun newTransition(to: PageRef, kind: PageTransition.Kind, direction: Int): PageTransition {
        val fromMirror = if (viewport.isZoomed) viewport.visiblePageRect(RectF()) else null
        return PageTransition(currentRef, to, kind, direction, Matrix(viewport.pageToView), fromMirror).also {
            transition = it
            laser.clear()
        }
    }

    /** Brings page [ref] to the front (without animation: [transition] handles that). */
    private fun commit(ref: PageRef?) {
        val previous = current
        // The spotlight applies only to the page it was drawn on.
        if (spotlight != null && spotlight?.ref != ref) spotlight = null
        // Selection and text being edited belong to the page being left.
        if (ref == null || ref.doc !== previous || ref.doc.inkOf(ref.index) !== selection?.ink) selection = null
        if (textEdit != null && (ref == null || ref.doc.inkOf(ref.index) !== textEdit?.ink)) endTextEdit()
        current = ref?.doc
        if (ref != null) {
            ref.doc.pageIndex = ref.index
            // Zoom and sheet position stay: only the page changes.
            viewport.setPageHeight(ref.doc.pageHeight(ref.index))
            ref.doc.pages.request(ref.index) { notify(Change.BITMAP) }
            // Pages reachable from here: next overlay, next and previous slide.
            val slides = navSlides(ref.doc)
            ref.doc.pages.prefetch(
                listOfNotNull(slides.nextOverlay(ref.index), slideTarget(ref, 1), slideTarget(ref, -1)),
            )
        }
        if (previous != null && previous !== ref?.doc) previous.pages.trimTo(previous.pageIndex)
        laser.clear()
        notify(Change.PAGE)
    }

    // ------------------------------------------------------------------ finger swipe

    /** Starts a swipe towards the next page ([direction] = 1) or the previous one (-1). */
    fun beginSwipe(direction: Int): Boolean {
        finishTransition()
        val ref = currentRef ?: return false
        val target = slideTarget(ref, direction) ?: return false
        ref.doc.pages.request(target) { notify(Change.BITMAP) }
        swipe = newTransition(PageRef(ref.doc, target), PageTransition.Kind.SLIDE, direction)
        notify(Change.TRANSITION)
        return true
    }

    fun updateSwipe(progress: Float) {
        val t = swipe ?: return
        t.progress = progress.coerceIn(0f, 1f)
        notify(Change.TRANSITION)
    }

    /** End of the swipe: [complete] = the new page stays, otherwise it goes back. */
    fun endSwipe(complete: Boolean) {
        val t = swipe ?: return
        swipe = null
        if (complete) {
            commit(t.to)
            animate(t, 1f, (SLIDE_MS * (1f - t.progress)).toLong().coerceAtLeast(MIN_SETTLE_MS))
        } else {
            animate(t, 0f, (SLIDE_MS * t.progress).toLong().coerceAtLeast(MIN_SETTLE_MS))
        }
    }

    fun cancelSwipe() {
        if (swipe == null) return
        swipe = null
        transition = null
        notify(Change.TRANSITION)
    }

    /** Ends any running animation at once (e.g. when the pen touches the screen). */
    fun finishTransition() {
        swipe?.let { s ->
            swipe = null
            transition = null
            if (s.progress > 0.5f) commit(s.to) else notify(Change.TRANSITION)
        }
        animator?.let {
            animator = null
            it.end()
        }
        if (transition != null) {
            transition = null
            notify(Change.TRANSITION)
        }
    }

    private fun animate(t: PageTransition, target: Float, duration: Long) {
        transition = t
        val a = ValueAnimator.ofFloat(t.progress, target).setDuration(duration)
        a.interpolator = DecelerateInterpolator(1.6f)
        a.addUpdateListener {
            t.progress = it.animatedValue as Float
            notify(Change.TRANSITION)
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator === animation) animator = null
                if (transition === t) {
                    transition = null
                    notify(Change.TRANSITION)
                }
            }
        })
        animator = a
        a.start()
    }

    // ------------------------------------------------------------------ ink

    fun onInkEdited(doc: PresenterDocument) {
        doc.markEdited()
        notify(Change.INK)
    }

    fun undo() {
        endTextEdit()
        selection = null
        val doc = current ?: return
        if (doc.inkOf(doc.pageIndex).undo()) onInkEdited(doc)
    }

    fun redo() {
        endTextEdit()
        selection = null
        val doc = current ?: return
        if (doc.inkOf(doc.pageIndex).redo()) onInkEdited(doc)
    }

    fun clearPage() {
        endTextEdit()
        selection = null
        val doc = current ?: return
        val ink = doc.inkOf(doc.pageIndex)
        if (ink.isEmpty && ink.fading.isEmpty()) return
        val hadStrokes = !ink.isEmpty
        ink.clear()
        if (hadStrokes) onInkEdited(doc) else notify(Change.FADE)
    }

    // ------------------------------------------------------------------ selection (shapes and lasso)

    private var selectionState by mutableStateOf<Selection?>(null)

    /** Selected elements, tablet only; null if there is no selection. */
    var selection: Selection?
        get() = selectionState
        set(value) {
            if (value === selectionState) return
            // A slider (width, color) left halfway must not stay open on the page.
            selectionState?.let { old -> if (value?.ink !== old.ink && old.ink.endChange()) old.doc.markEdited() }
            selectionState = value
            notify(Change.LAYOUT)
        }

    /** Grows when the selection or the text box must be redrawn elsewhere on screen (zoom, page). */
    var screenTick by mutableIntStateOf(0)
        private set

    /** Outline of the lasso in progress (page coordinates), drawn on the tablet only. */
    var lassoPath: FloatArray? = null

    /** Snapping guides while dragging a vertex, moving or resizing the selection. */
    var guides: List<SelectionGeometry.Guide> = emptyList()

    /** Clipboard: elements copied with the lasso, to paste on another page (also of another document). */
    var clipboard by mutableStateOf<List<PageElement>?>(null)

    /** Applies [change] to every element of the selection as a single undoable edit. */
    fun editSelection(change: (PageElement) -> PageElement) {
        val sel = selection ?: return
        val after = sel.elements.map(change)
        // Nothing changed (same color, style already on): no undo step and no document to save.
        if (after.indices.all { after[it] === sel.elements[it] }) return
        sel.ink.replace(sel.elements, after)
        selection = sel.with(elements = after)
        onInkEdited(sel.doc)
    }

    /** Like [editSelection], but live (width slider): becomes a single edit at [endLiveSelectionEdit]. */
    fun editSelectionLive(change: (PageElement) -> PageElement) {
        val sel = selection ?: return
        sel.ink.beginChange()
        val after = sel.elements.map(change)
        sel.ink.replaceLive(sel.elements, after)
        selection = sel.with(elements = after)
        notify(Change.INK)
    }

    fun endLiveSelectionEdit() {
        val sel = selection ?: return
        if (sel.ink.endChange()) onInkEdited(sel.doc)
    }

    fun recolorSelection(color: Int) = editSelection { if ((it.color xor color) and 0xFFFFFF == 0) it else it.recolored(color) }

    /** Style of the selected shapes (the other elements stay as they were). */
    fun styleSelection(dash: LineDash? = null, fill: Int? = null, arrow: Boolean? = null) = editSelection { e ->
        if (e is Shape && ((dash ?: e.dash) != e.dash || (fill ?: e.fill) != e.fill || (arrow ?: e.arrow) != e.arrow)) {
            e.copy(dash = dash ?: e.dash, fill = fill ?: e.fill, arrow = arrow ?: e.arrow)
        } else {
            e
        }
    }

    fun deleteSelection() {
        val sel = selection ?: return
        sel.ink.remove(sel.elements)
        selection = null
        onInkEdited(sel.doc)
    }

    /** Copy of the selection moved slightly, which becomes the new selection. */
    fun duplicateSelection() {
        val sel = selection ?: return
        val shift = Affine.translate(DUPLICATE_OFFSET, DUPLICATE_OFFSET)
        val copies = sel.elements.map { it.duplicate().transformed(shift) }
        sel.ink.add(copies)
        selection = sel.with(elements = copies)
        onInkEdited(sel.doc)
    }

    fun copySelection() {
        clipboard = selection?.elements?.map { it.duplicate() }
    }

    /** Pastes the clipboard into the current page, in the same position (moved slightly if already there). */
    fun paste() {
        val items = clipboard ?: return
        val doc = current ?: return
        endTextEdit()
        val ink = doc.inkOf(doc.pageIndex)
        val samePlace = items.any { c -> ink.elements.any { it.bounds() == c.bounds() } }
        val shift = if (samePlace) Affine.translate(DUPLICATE_OFFSET, DUPLICATE_OFFSET) else Affine.IDENTITY
        val copies = items.map { it.duplicate().transformed(shift) }
        ink.add(copies)
        selection = Selection(doc, ink, copies)
        onInkEdited(doc)
    }

    // ------------------------------------------------------------------ highlighter snapped to text

    private val textWorker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The highlighter stroke [stroke], just added to [ink] on page [page], becomes a straight highlight of
     * the text lines it covers, as soon as the page text is ready (extracting it takes a few tenths of a
     * second). If there is no text underneath it stays as it is.
     */
    fun snapHighlight(doc: PresenterDocument, ink: PageInk, page: Int, stroke: InkStroke) {
        val points = stroke.samplePoints()
        val width = stroke.strokeWidth
        textWorker.execute {
            val rects = TextSnap.snap(doc.pageText(page), points, width) ?: return@execute
            val highlight = TextHighlight(TextSnap.quads(rects), stroke.color)
            mainHandler.post { applySnap(doc, ink, stroke, highlight) }
        }
    }

    private fun applySnap(doc: PresenterDocument, ink: PageInk, stroke: InkStroke, highlight: TextHighlight) {
        if (doc !in documents || stroke !in ink.elements) return
        // During a gesture on the page (handles, eraser, text) wait for it to end.
        if (ink.isChanging) {
            mainHandler.postDelayed({ applySnap(doc, ink, stroke, highlight) }, SNAP_RETRY_MS)
            return
        }
        if (ink.swap(stroke, highlight)) {
            selection?.let { sel ->
                if (stroke in sel.elements) selection = sel.with(elements = sel.elements.map { if (it === stroke) highlight else it })
            }
            doc.markEdited()
            notify(Change.INK)
        }
    }

    // ------------------------------------------------------------------ handwriting → text

    /** Short message for the user (snackbar), to show and then clear. */
    var notice by mutableStateOf<Int?>(null)

    /** Notice about a feature missing in this distribution (snackbar), to show and then clear. */
    var limitNotice by mutableStateOf<Limit?>(null)

    /**
     * Like [notice], for a message with a path: the string (with `%1$s`) and the path, which the snackbar
     * truncates at the start only if it does not fit.
     */
    var noticePath by mutableStateOf<Pair<Int, String>?>(null)

    /** Language of handwriting recognition: chosen in the selection bar, otherwise the app language. */
    var handwritingLanguage by mutableStateOf(
        prefs.handwritingLanguage
            ?: if (Locale.getDefault().language == "it") "it" else "en",
    )
        private set

    fun updateHandwritingLanguage(language: String) {
        handwritingLanguage = language
        prefs.handwritingLanguage = language
    }

    private var handwritingToken: Any? = null

    /** Recognition in progress (the selection bar shows the wait). */
    var handwritingBusy by mutableStateOf(false)
        private set

    /** The selected strokes become a text box in the same position and color, as a single undoable edit. */
    fun convertSelectionToText() {
        val sel = selection ?: return
        val strokes = sel.elements.filterIsInstance<InkStroke>()
        if (strokes.isEmpty() || strokes.size != sel.elements.size || handwritingBusy) return
        handwritingBusy = true
        // Without network the model download may hang: after a while the icon is enabled again.
        val token = Any()
        handwritingToken = token
        mainHandler.postDelayed({ if (handwritingToken === token && handwritingBusy) handwritingBusy = false }, HANDWRITING_TIMEOUT_MS)
        // Several written lines become as many lines of text, each recognized on its own; slanted lines are cut
        // along their slant and straightened for the recognizer.
        val points = strokes.map { it.samplePoints() }
        val slant = HandwritingLines.slant(points)
        val boxes = HandwritingLines.boxes(points, slant, strokes.map { it.strokeWidth / 2f })
        val split = HandwritingLines.split(boxes)
        val lines = split.map { line -> line.map { strokes[it] } }
        val lineHeight = HandwritingLines.lineHeight(split.map { line -> line.map { boxes[it] }.reduce(Box::union) })
        // Box of the writing along its slant (straightened frame): the text box takes its place.
        val frame = boxes.reduce(Box::union)
        Handwriting.recognize(lines, slant, handwritingLanguage) { result ->
            when (result) {
                Handwriting.Result.Downloading -> notice = R.string.handwriting_downloading
                is Handwriting.Result.Failed -> {
                    handwritingBusy = false
                    notice = R.string.handwriting_failed
                }
                is Handwriting.Result.Text -> {
                    handwritingBusy = false
                    replaceStrokesWithText(sel, strokes, lineHeight, slant, frame, result.text)
                }
            }
        }
    }

    /**
     * [lineHeight]: height of a written line, along the writing; [slant]: angle of the writing, [frame] its box
     * in the frame turned by -[slant] (see [HandwritingLines.boxes]).
     */
    private fun replaceStrokesWithText(
        sel: Selection,
        strokes: List<InkStroke>,
        lineHeight: Float,
        slant: Float,
        frame: Box,
        text: String,
    ) {
        val ink = sel.ink
        if (sel.doc !in documents) return
        // A text box open on this page is closed first (its edit is a step of its own).
        if (textEdit?.ink === ink) endTextEdit()
        // A gesture in progress on the page (handles, eraser): wait for it to end.
        if (ink.isChanging) {
            mainHandler.postDelayed({ replaceStrokesWithText(sel, strokes, lineHeight, slant, frame, text) }, SNAP_RETRY_MS)
            return
        }
        // Meanwhile the strokes may have been moved, recolored or erased: tell the user.
        if (strokes.any { it !in ink.elements }) {
            notice = R.string.handwriting_changed
            return
        }
        // Font size from the height of a written line (~60% of it), however many lines there are.
        val size = (lineHeight * HANDWRITING_SIZE_RATIO).coerceIn(ToolPreset.sizeRange(ToolKind.TEXT))
        val color = strokes.first().color or 0xFF000000.toInt()
        // Font and style of the last text tool used (user's choice): like the boxes typed by hand.
        val style = tools.lastText()
        // Writing almost horizontal: a horizontal box over the strokes. Clearly slanted (user's choice): the box has
        // the same slant, over the writing (its corner turned back from the straightened frame).
        val turned = abs(slant) >= HANDWRITING_TURN
        val box = if (turned) frame else strokes.map { it.bounds() }.reduce(Box::union)
        val angle = if (turned) slant else 0f
        val x = box.minX * cos(angle) - box.minY * sin(angle)
        val y = box.minX * sin(angle) + box.minY * cos(angle)
        var textBox = TextBox(
            text, x, y, box.width.coerceAtLeast(60f) * 1.15f, size * TextBox.LINE_SPACING, size, color,
            font = style?.font ?: TextFont.SANS, bold = style?.bold == true, italic = style?.italic == true, rotation = angle,
        )
        textBox = TextLayouts.fit(textBox)
        // In the drawing order, where the lowest of the strokes was (no stroke comes before it, so the index holds).
        val first = ink.elements.indexOfFirst { it in strokes }
        val after = ink.elements.filter { it !in strokes }.toMutableList()
        after.add(first.coerceIn(0, after.size), textBox)
        ink.commit(after)
        // The new box is selected only if that page is still the current one.
        if (currentInk === ink) selection = Selection(sel.doc, ink, listOf(textBox))
        onInkEdited(sel.doc)
    }

    // ------------------------------------------------------------------ text boxes

    /** A note is being typed in the notes panel: the keys go to it, not to the slides. */
    var notesEditing by mutableStateOf(false)

    /** Text box being edited (text field over the page, on the tablet), or null. */
    var textEdit by mutableStateOf<TextEdit?>(null)
        private set

    /** Opens [box] for editing; [isNew]: it is not in the page yet. */
    fun beginTextEdit(doc: PresenterDocument, ink: PageInk, box: TextBox, isNew: Boolean, cursor: Int = -1) {
        endTextEdit()
        selection = null
        ink.beginChange()
        val fitted = TextLayouts.fit(box)
        if (isNew) ink.setLive(ink.elements + fitted) else ink.replaceLive(listOf(box), listOf(fitted))
        textEdit = TextEdit(doc, ink, fitted, original = if (isNew) null else box, cursor = cursor)
        notify(Change.INK)
    }

    /** The box being edited becomes [box] (text, position, width or style changed). */
    fun updateTextEdit(box: TextBox) {
        val edit = textEdit ?: return
        if (box.sameContent(edit.box)) return
        val fitted = TextLayouts.fit(box)
        edit.ink.replaceLive(listOf(edit.box), listOf(fitted))
        textEdit = edit.with(fitted)
        // The document must be saved already while typing: the session and closing the tab know it.
        if (edit.original?.sameContent(fitted) != true && !(edit.original == null && fitted.text.isBlank())) edit.doc.markEdited()
        notify(Change.INK)
    }

    /** End of editing: an empty box disappears; the rest is a single undoable edit. */
    fun endTextEdit() {
        val edit = textEdit ?: return
        textEdit = null
        when {
            // Opened and closed without changing anything: the page goes back as it was, with no undo step.
            edit.original?.sameContent(edit.box) == true -> edit.ink.cancelChange()
            edit.box.text.isBlank() -> edit.ink.setLive(edit.ink.elements.filter { it !== edit.box })
        }
        if (edit.ink.endChange()) onInkEdited(edit.doc) else notify(Change.INK)
    }

    // ------------------------------------------------------------------ vanishing ink

    private val fadingInks = LinkedHashSet<PageInk>()
    private val fadeHandler = Handler(Looper.getMainLooper())
    private val fadeTick = Runnable { tickFading() }

    /** Strokes of the vanishing pen: they stay [fadeAfterMs], then fade by themselves. */
    fun addFading(ink: PageInk, strokes: Collection<Stroke>, fadeAfterMs: Long) {
        ink.addFading(strokes, SystemClock.uptimeMillis() + fadeAfterMs)
        fadingInks.add(ink)
        notify(Change.FADE)
        fadeHandler.removeCallbacks(fadeTick)
        tickFading()
    }

    /**
     * Redraws only when needed: every frame during a fade, otherwise it reschedules for the start of the
     * next one. Removes the strokes that are gone.
     */
    private fun tickFading() {
        val now = SystemClock.uptimeMillis()
        var next = Long.MAX_VALUE
        var redraw = false
        val it = fadingInks.iterator()
        while (it.hasNext()) {
            val ink = it.next()
            if (ink.pruneFading(now)) redraw = true
            if (ink.fading.isEmpty()) {
                it.remove()
                continue
            }
            for (f in ink.fading) {
                if (now >= f.fadeStart) {
                    redraw = true
                    next = minOf(next, now + FRAME_MS)
                } else {
                    next = minOf(next, f.fadeStart)
                }
            }
        }
        if (redraw) notify(Change.FADE)
        if (next != Long.MAX_VALUE) fadeHandler.postDelayed(fadeTick, (next - now).coerceAtLeast(1L))
    }

    private companion object {
        const val SNAP_RETRY_MS = 300L
        const val TERMINAL_QUERY_MS = 1_000
        const val SLIDE_MS = 300L
        const val FADE_MS = 220L
        const val MIN_SETTLE_MS = 90L
        const val FRAME_MS = 16L
        const val MAX_JUMP_HISTORY = 50

        /** Handwriting slanted at least this much (radians, 5°) gives a text box with the same slant. */
        const val HANDWRITING_TURN = 0.0873f

        /** Font size relative to the height of a handwritten line. */
        const val HANDWRITING_SIZE_RATIO = 0.6f
        const val HANDWRITING_TIMEOUT_MS = 90_000L

        /** An end time older than this (app reopened the next day) is forgotten. */
        const val PRESENTATION_END_KEEP_MS = 3 * 60 * 60 * 1000L

        /** A stopwatch without an end time is forgotten after this. */
        const val STOPWATCH_KEEP_MS = 8 * 60 * 60 * 1000L

        /** Offset (page units) of a duplicated or pasted copy over the original. */
        const val DUPLICATE_OFFSET = 16f
    }
}
