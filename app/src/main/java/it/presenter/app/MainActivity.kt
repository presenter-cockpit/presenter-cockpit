package it.presenter.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import it.presenter.app.canvas.PresenterCanvas
import it.presenter.app.doc.DocSource
import it.presenter.app.doc.DocWatcher
import it.presenter.app.doc.PresenterDocument
import it.presenter.app.files.FileBrowserDialog
import it.presenter.app.files.TreeBrowserDialog
import it.presenter.app.files.TreeFiles
import it.presenter.app.files.hasAllFilesAccess
import it.presenter.app.input.ShortcutAction
import it.presenter.app.input.Shortcuts
import it.presenter.app.projector.AudienceActivity
import it.presenter.app.projector.PrivateStart
import it.presenter.app.projector.ProjectorController
import it.presenter.app.projector.keepScreenOn
import it.presenter.app.remote.RemoteDocLinks
import it.presenter.app.remote.SavedPasswords
import it.presenter.app.remote.ServerProfile
import it.presenter.app.remote.SshKeys
import it.presenter.app.session.DocumentFlows
import it.presenter.app.session.Hint
import it.presenter.app.session.PresenterSession
import it.presenter.app.session.RecentDocs
import it.presenter.app.settings.Distribution
import it.presenter.app.settings.RailSide
import it.presenter.app.tabs.BrowserTab
import it.presenter.app.tabs.EditorTab
import it.presenter.app.tabs.LinkStatus
import it.presenter.app.tabs.TerminalTab
import it.presenter.app.tools.ToolKind
import it.presenter.app.tools.ToolState
import it.presenter.app.ui.AppSnackbar
import it.presenter.app.ui.Biometrics
import it.presenter.app.ui.ConfirmDialog
import it.presenter.app.ui.FrozenPreview
import it.presenter.app.ui.HelpDialog
import it.presenter.app.ui.HintCard
import it.presenter.app.ui.LimitMessage
import it.presenter.app.ui.NavigationPanel
import it.presenter.app.ui.NotesPanel
import it.presenter.app.ui.Overview
import it.presenter.app.ui.PathMessage
import it.presenter.app.ui.PausePreview
import it.presenter.app.ui.PresenterTheme
import it.presenter.app.ui.PreviewArea
import it.presenter.app.ui.PrivateStartPreview
import it.presenter.app.ui.RemoteAuth
import it.presenter.app.ui.SelectionBar
import it.presenter.app.ui.SettingsScreen
import it.presenter.app.ui.SharePreview
import it.presenter.app.ui.TextEditOverlay
import it.presenter.app.ui.TextMessage
import it.presenter.app.ui.ToolRail
import it.presenter.app.ui.TopBar
import it.presenter.app.ui.UnsavedDialog
import it.presenter.app.ui.WithTooltip
import it.presenter.app.ui.WorkTabContent
import java.io.File
import java.util.concurrent.Executors
import java.util.function.Consumer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop

// FragmentActivity (a ComponentActivity) because the fingerprint dialog (BiometricPrompt) requires it.
class MainActivity : FragmentActivity() {

    /** The session lives in the Application: it survives the Activity being recreated. */
    private val app get() = application as PresenterApp
    private val session get() = app.session
    private lateinit var projector: ProjectorController

    /** Opening, saving, reloading, session and workspaces (see [DocumentFlows]). */
    private lateinit var flows: DocumentFlows

    /** PDF surface: takes the focus back when returning from the work tabs. */
    private var canvasView: PresenterCanvas? = null

    /** The system picker covers the app: the projector must stay on the slide. */
    private var keepProjectorWhileAway = false

    /**
     * Translucent while the window to share is open ([AudienceActivity]): its task,
     * behind, stays visible and keeps drawing for the video call.
     */
    private var translucent = false

    private fun updateTranslucent() {
        if (session.shareWindow == translucent) return
        translucent = session.shareWindow
        // Public only from Android 11: on Android 10 the window to share stays covered while Cockpit is in front.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setTranslucent(translucent)
    }

    private val shareWindowListener = PresenterSession.Listener { change ->
        if (change == PresenterSession.Change.PROJECTOR) updateTranslucent()
    }

    /**
     * Android 15+: the screen is being recorded with this app visible (a video call sharing the whole screen,
     * or "one app" on Cockpit): without the window to share the participants see the tablet.
     */
    private val recordingCallback: Consumer<Int>? =
        if (Build.VERSION.SDK_INT >= 35) Consumer { state ->
            // Not with the window to share, nor with an untrusted display turned on: that is likely the one of the app
            // recording, which then sends what the audience sees.
            if (state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE && !session.shareWindow &&
                session.externalDisplays.none { it.enabled && !it.trusted }
            ) {
                session.notice = R.string.share_window_recorded
            }
        } else null

    // UI state tied to the Activity.
    private var showBrowser by mutableStateOf(false)
    /** Picker on the granted folders ("store" distribution). */
    private var showTreeBrowser by mutableStateOf(false)
    private var allFilesAccess by mutableStateOf(false)
    private var message: String?
        get() = flows.message
        set(value) {
            flows.message = value
        }
    private var limitMessage by mutableStateOf<LimitMessage?>(null)
    /** Do not disturb was handled for this connection of the external display. */
    private var dndHandled = false

    private val notifications get() = getSystemService(NotificationManager::class.java)

    /**
     * External display connected: Do not disturb on, once per connection, if the setting asks for it and the
     * user had not turned it on already. Without the access to it, a message says where to allow it.
     */
    private fun startDnd() {
        if (dndHandled || !session.settings.dndWithDisplay) return
        dndHandled = true
        if (notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        if (!notifications.isNotificationPolicyAccessGranted) {
            message = getString(R.string.dnd_need_access)
            return
        }
        runCatching { notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }.onSuccess {
            session.prefs.dndByApp = true
            message = getString(R.string.dnd_on)
        }
    }

    /** The external display is gone: Do not disturb goes back off, if the app turned it on. */
    private fun endDnd() {
        dndHandled = false
        if (!session.prefs.dndByApp) return
        session.prefs.dndByApp = false
        if (notifications.isNotificationPolicyAccessGranted) {
            runCatching { notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
        }
    }

    /** Message with a path, waiting for the snackbar (see [PathMessage]). */
    private var pathMessage by mutableStateOf<PathMessage?>(null)

    private val systemPicker = registerForActivityResult(PickPdf()) { uri ->
        if (uri != null) {
            // Persistent permission (also for writing, if the provider grants it): needed to save.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            session.prefs.lastPickerUri = uri.toString()
            flows.open(DocSource.fromUri(this, uri))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        projector = ProjectorController(this, session)
        flows = DocumentFlows(
            app,
            worker = Executors.newSingleThreadExecutor { r -> Thread(r, "documents") },
            remoteWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "remote-docs") },
            main = { block -> runOnUiThread(block) },
            later = { ms, block -> handler.postDelayed(block, ms) },
        )
        // The window to share left in front and then sent away too (home screen…): the displays go back to the tablet.
        AudienceActivity.onStopped = stopped@{
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || keepProjectorWhileAway) return@stopped
            projector.stop()
        }
        // PDFs opened from an IDE (tap in the tree) or from the "+" menu (PDF on a server).
        session.onOpenPdf = openPdfHook
        session.onNewBoard = newBoardHook
        session.unlockKey = unlockKeyHook
        session.unlockPassword = unlockPasswordHook
        setContent { PresenterTheme { Screen() } }
        // The document passed by another app opens after the saved session has been restored.
        val startIntent = intent
        if (app.sessionRestored) handleIntent(startIntent) else flows.restoreSession { handleIntent(startIntent) }
    }

    @Composable
    private fun Screen() {
        val snackbar = remember { SnackbarHostState() }
        // Going back to the PDFs (or closing a tab) closes the terminal's or editor's keyboard.
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        LaunchedEffect(session.activeWork) {
            if (session.activeWork == null) {
                focus.clearFocus(force = true)
                keyboard?.hide()
                // The focus goes back to the page: the remote's first key is not lost.
                canvasView?.requestFocus()
            }
        }
        // Short messages from the session (e.g. handwriting): they become the snackbar.
        LaunchedEffect(session.notice) {
            session.notice?.let {
                message = getString(it)
                session.notice = null
            }
        }
        LaunchedEffect(session.noticePath) {
            session.noticePath?.let { (res, path) ->
                pathMessage = PathMessage(getString(res, path), getString(res, PathMessage.MARK), path)
                session.noticePath = null
            }
        }
        // Messages one after the other: a new one waits for the one shown (it would replace it before it can be
        // read). The same message arrived again while it was shown is not repeated.
        val queue = remember { Channel<SnackbarVisuals>(Channel.UNLIMITED) }
        LaunchedEffect(Unit) {
            var last: String? = null
            var lastEnd = 0L
            for (m in queue) {
                if (m.message == last && SystemClock.uptimeMillis() - lastEnd < REPEAT_GAP_MS) continue
                snackbar.showSnackbar(m)
                last = m.message
                lastEnd = SystemClock.uptimeMillis()
            }
        }
        // Separate: clearing noticePath would restart the effect above.
        LaunchedEffect(pathMessage) {
            pathMessage?.let {
                queue.trySend(it)
                pathMessage = null
            }
        }
        LaunchedEffect(message) {
            message?.let {
                queue.trySend(TextMessage(it))
                message = null
            }
        }
        // Feature missing in this distribution: a longer notice, with the icon for the project page.
        LaunchedEffect(session.limitNotice) {
            session.limitNotice?.let { limit ->
                limitMessage = LimitMessage(getString(limit.message), Distribution.projectUrl)
                session.limitNotice = null
            }
        }
        LaunchedEffect(limitMessage) {
            limitMessage?.let {
                queue.trySend(it)
                limitMessage = null
            }
        }
        // Do not disturb (a setting, on by default): turned on when an external display is connected and off when
        // it goes, if it was the app that turned it on. Leaving the app also stops the projector: that is not a
        // disconnection.
        LaunchedEffect(Unit) {
            snapshotFlow { session.projectorInfo != null }.collect { connected ->
                if (connected) startDnd() else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) endDnd()
            }
        }
        // Back does not close the app (the open documents would be lost): it moves it to the background.
        BackHandler { moveTaskToBack(true) }
        BackHandler(enabled = session.selection != null) { session.selection = null }
        BackHandler(enabled = session.toolEditor != null) { session.toolEditor = null }

        Box(Modifier.fillMaxSize()) {
            val railRight = session.settings.railSide == RailSide.RIGHT
            Row(Modifier.fillMaxSize()) {
                if (!railRight) ToolRail(session)
                Box(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize()) {
                        TopBar(
                            session,
                            onOpen = ::openBrowser,
                            onSave = { flows.save(it) },
                            onClose = flows::requestClose,
                            onReload = { flows.reloadAsk = it },
                            onReconnect = ::reconnect,
                            onOpenWorkspace = flows::requestWorkspace,
                        )
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            AndroidView(factory = { PresenterCanvas(it, session).also { c -> canvasView = c } }, modifier = Modifier.fillMaxSize())
                            // Code, terminal or browser tab: covers the page (which stays ready underneath).
                            session.activeWork?.let { tab ->
                                // One tab, one composition: views (WebView) and state do not pass from one tab to
                                // another.
                                key(tab.id) { WorkTabContent(session, tab, remoteAuth) }
                            }
                            SelectionBar(session)
                            TextEditOverlay(session)
                            // What the audience sees (live with the window to share, frozen, pause, private start):
                            // movable.
                            PreviewArea(session, railRight, Modifier.matchParentSize()) {
                                FrozenPreview(session, Modifier)
                                SharePreview(session, Modifier)
                                PrivateStartPreview(session, Modifier)
                                PausePreview(session, Modifier)
                            }
                            // PDF panels (notes, navigation, overview) only with the PDF in front: a work tab hides
                            // them.
                            val doc = session.current?.takeIf { session.activeWork == null }
                            if (session.current == null && flows.loading == 0 && session.activeWork == null) EmptyState()
                            if (doc != null && doc.notesVisible && doc.notesAvailable) {
                                NotesPanel(session, doc, onSaveNote = { page, text -> flows.setNote(doc, page, text) }) { doc.notesVisible = false }
                            }
                            if (doc != null && session.navigationOpen) {
                                Box(Modifier.align(if (railRight) Alignment.CenterStart else Alignment.CenterEnd)) {
                                    NavigationPanel(session, doc, onBookmark = { page, on -> flows.setBookmark(doc, page, on) }) { session.navigationOpen = false }
                                }
                            }
                            if (doc != null && session.overviewOpen) {
                                Overview(
                                    session, doc, busy = doc in flows.editingPages,
                                    onEdit = { flows.editPages(doc, it) },
                                    onHide = { page, hidden -> flows.setHidden(doc, page, hidden) },
                                ) { session.overviewOpen = false }
                            }
                            if (flows.loading > 0) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                        }
                    }
                    // A tool editor is open: a tap on the work area closes it (and does not draw).
                    if (session.toolEditor != null) EditorScrim { session.toolEditor = null }
                }
                if (railRight) ToolRail(session)
            }
            if (session.settingsOpen) SettingsScreen(session) { session.settingsOpen = false }
            if (session.shortcutsOpen) HelpDialog(session) { session.shortcutsOpen = false }
            // First-use hint, above the messages (tablet only).
            session.hints.current?.let { hint ->
                HintCard(hint, onClose = session.hints::dismiss, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp, start = 16.dp, end = 16.dp))
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { data ->
                AppSnackbar(data) { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            }
        }

        // Session turned off in the settings: the saved one is no longer needed. Turned back on: it must be
        // written at once, even if nothing changed meanwhile.
        val sessionEnabled = session.settings.sessionEnabled
        LaunchedEffect(sessionEnabled) {
            if (!sessionEnabled) {
                flows.clearSavedSession()
            } else if (app.sessionRestored) {
                flows.saveSession(force = true)
            }
        }

        if (showBrowser) {
            FileBrowserDialog(
                startFolder = session.prefs.lastFolder,
                hasAccess = allFilesAccess,
                onRequestAccess = ::requestAllFilesAccess,
                onSystemPicker = {
                    showBrowser = false
                    launchSystemPicker()
                },
                onOpen = { file ->
                    showBrowser = false
                    session.prefs.lastFolder = file.parent
                    flows.open(DocSource.Local(file))
                },
                onDismiss = { showBrowser = false },
            )
        }
        if (showTreeBrowser) {
            TreeBrowserDialog(
                startFolder = session.prefs.lastTreeFolder,
                onSystemPicker = {
                    showTreeBrowser = false
                    launchSystemPicker()
                },
                onOpen = { path, uri, name ->
                    showTreeBrowser = false
                    flows.open(DocSource.Tree(path, uri, name))
                },
                onFolder = { session.prefs.lastTreeFolder = it },
                onDismiss = { showTreeBrowser = false },
            )
        }
        // First-use hints: when changing tool (button tool, shapes) and with a PDF that has Beamer overlays.
        LaunchedEffect(Unit) {
            // The first value is the starting tool, not a change by the user.
            snapshotFlow { session.tools.selected }.drop(1).collect { ref ->
                session.hints.offer(Hint.DUAL)
                if (session.tools.preset(ref)?.kind == ToolKind.SHAPE) session.hints.offer(Hint.SHAPES)
            }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { session.current to session.settings.beamerOverlays }.collect { (doc, on) ->
                if (doc != null && on && doc.slides.slideCount < doc.pageCount) session.hints.offer(Hint.OVERLAYS)
            }
        }
        // Private start: the message only with the external display connected, at launch or as soon as it is
        // connected (without a display it has no visible effect; user's request).
        LaunchedEffect(Unit) {
            snapshotFlow {
                session.projectorInfo != null && session.projectorMode == PresenterSession.ProjectorMode.PRIVATE
            }.collect { shown -> if (shown) message = getString(R.string.msg_private_start) }
        }
        // Screen always on (a setting, on by default): tablet and projector window.
        LaunchedEffect(Unit) {
            snapshotFlow { session.settings.keepScreenOn }.collect { on ->
                keepScreenOn(window, on)
                projector.keepScreenOn(on)
            }
        }
        // The files of the open PDFs to watch: they change when documents are opened and closed.
        LaunchedEffect(Unit) {
            snapshotFlow {
                session.documents.mapNotNull { (it.source as? DocSource.Local)?.file }.toSet()
            }.collect { diskWatcher.update(it) }
        }
        Questions()
    }

    /** Confirmations asked by the activity and by the session (at most one at a time, in practice). */
    @Composable
    private fun Questions() {
        flows.closing?.let { doc ->
            UnsavedDialog(
                stringResource(R.string.unsaved_title),
                stringResource(R.string.unsaved_text, doc.title),
                onSave = { flows.save(doc, closeAfter = true) },
                onDiscard = { flows.closeDocument(doc) },
                onDismiss = { flows.closing = null },
            )
        }
        flows.reloadAsk?.let { doc ->
            val remote = doc.source is DocSource.Remote
            ConfirmDialog(
                stringResource(if (remote) R.string.reload_title_server else R.string.reload_title),
                stringResource(if (remote) R.string.reload_text_server else R.string.reload_text, doc.title),
                stringResource(R.string.action_reload),
                onConfirm = { flows.reload(doc) },
                onDismiss = { flows.reloadAsk = null },
                danger = true,
            )
        }
        flows.switchingTo?.let { ws ->
            UnsavedDialog(
                stringResource(R.string.workspace_unsaved_title),
                stringResource(R.string.workspace_unsaved_text, ws.name),
                onSave = { flows.saveAndOpenWorkspace(ws) },
                onDiscard = { flows.discardAndOpenWorkspace(ws) },
                onDismiss = { flows.switchingTo = null },
            )
        }
        flows.replacingWith?.let { ws ->
            ConfirmDialog(
                stringResource(R.string.workspace_replace_title),
                stringResource(R.string.workspace_replace_text, ws.name),
                stringResource(R.string.action_confirm),
                onConfirm = { flows.openWorkspace(ws) },
                onDismiss = { flows.replacingWith = null },
            )
        }
        when (val tab = session.closingWork) {
            // It closes only if saving succeeds.
            is EditorTab -> UnsavedDialog(
                stringResource(R.string.code_unsaved_title),
                stringResource(R.string.code_unsaved_close, tab.title),
                onSave = {
                    tab.saveAll { ok -> if (ok) session.closeWork(tab, discard = true) else message = getString(R.string.code_save_failed) }
                },
                onDiscard = { session.closeWork(tab, discard = true) },
                onDismiss = { session.closingWork = null },
            )
            // Closing a running terminal terminates the shell and the programs in it (no tmux).
            is TerminalTab -> ConfirmDialog(
                stringResource(R.string.terminal_close_title, tab.title),
                stringResource(R.string.terminal_close_text),
                stringResource(R.string.action_close),
                onConfirm = { session.closeWork(tab, discard = true) },
                onDismiss = { session.closingWork = null },
            )
            else -> Unit
        }
        // External link of the PDF: opens only after confirmation (you may leave the app).
        session.pendingUri?.let { uri ->
            ConfirmDialog(
                stringResource(R.string.link_open_title),
                uri,
                stringResource(R.string.link_open),
                onConfirm = { openLink(uri) },
                onDismiss = { session.pendingUri = null },
            )
        }
    }

    /** Web pages in the in-app browser tab (a setting), the rest (mail, …) outside. */
    private fun openLink(uri: String) {
        if (session.settings.linksInApp && (uri.startsWith("http://") || uri.startsWith("https://"))) {
            session.openWork(BrowserTab(uri))
        } else {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
                .onFailure { message = getString(R.string.link_open_failed) }
        }
    }

    /** Transparent veil over the top bar and the work area: the first tap closes the editor. */
    @Composable
    private fun EditorScrim(onClose: () -> Unit) {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    onClose()
                }
            },
        )
    }

    /**
     * No tab open: the button to open a PDF, the PDFs opened lately (a tap opens them again; local files that
     * are gone are left out) and the workspaces.
     */
    @Composable
    private fun EmptyState() {
        val recents = remember { RecentDocs.read(session.prefs).filter { (it as? DocSource.Local)?.file?.isFile != false } }
        val workspaces = session.workspaces
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            ) {
                WithTooltip(stringResource(R.string.tab_pdf_local)) {
                    FilledTonalIconButton(onClick = ::openBrowser, modifier = Modifier.size(96.dp)) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = stringResource(R.string.tab_pdf_local), modifier = Modifier.size(44.dp))
                    }
                }
                if (recents.isNotEmpty()) {
                    StartHeading(Icons.Rounded.History, stringResource(R.string.start_recent))
                    for (source in recents) {
                        StartItem(Icons.Rounded.PictureAsPdf, source.displayName, startDetail(source)) { flows.open(source) }
                    }
                }
                if (workspaces.isNotEmpty()) {
                    StartHeading(Icons.Rounded.Workspaces, stringResource(R.string.start_workspaces))
                    for (ws in workspaces) StartItem(Icons.Rounded.Workspaces, ws.name, null) { flows.requestWorkspace(ws) }
                }
            }
        }
    }

    /** Where a recent PDF is: its folder (the app's whiteboards by name), or the server. */
    private fun startDetail(source: DocSource): String? = when (source) {
        is DocSource.Local -> if (source.file.parentFile == File(filesDir, "boards")) getString(R.string.start_boards) else source.file.parent
        is DocSource.Remote -> source.server?.label?.let { "$it: ${source.path.substringBeforeLast('/')}" }
        is DocSource.Tree -> TreeFiles.displayPath(this, source.path.substringBeforeLast('/'))
        is DocSource.Content -> null
    }

    @Composable
    private fun StartHeading(icon: ImageVector, title: String) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
        }
    }

    @Composable
    private fun StartItem(icon: ImageVector, title: String, detail: String?, onClick: () -> Unit) {
        ListItem(
            headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = detail?.let { { Text(it, maxLines = 1, overflow = TextOverflow.StartEllipsis) } },
            leadingContent = { Icon(icon, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        )
    }

    // ------------------------------------------------------------------ PDFs changed on disk

    /** Watcher (inotify) of the files of the open PDFs. */
    private val diskWatcher = DocWatcher { file -> flows.checkDisk(file) }

    /** Periodic check while the app is in front: for what the watcher does not see. */
    private val diskPoll = object : Runnable {
        override fun run() {
            flows.checkDisk()
            handler.postDelayed(this, DISK_POLL_MS)
        }
    }

    private val openPdfHook: (DocSource, Any?) -> Unit = { source, after -> flows.open(source, after) }
    private val newBoardHook: () -> Unit = { flows.newBoard() }
    private val unlockKeyHook: (() -> Unit) -> Unit = { then -> unlockSshKey(then) }
    private val unlockPasswordHook: (ServerProfile, (String) -> Unit) -> Unit =
        { server, then -> unlockSavedPassword(server, then, gone = {}) }

    /**
     * Tap on the "not connected" icon of a remote PDF: unlocks the key or the saved password if that is what
     * is missing, then retries the server at once.
     */
    private fun reconnect(doc: PresenterDocument) {
        val src = doc.source as? DocSource.Remote ?: return
        fun retry() {
            src.retrySoon()
            flows.checkDisk()
        }
        val server = src.server
        when (src.offline) {
            DocSource.Offline.KEY_LOCKED -> unlockSshKey(::retry)
            DocSource.Offline.PASSWORD_LOCKED -> if (server != null) {
                unlockSavedPassword(server, then = { pw ->
                    RemoteDocLinks.files(src.serverId)?.link?.usePassword(pw, fromStore = true)
                    retry()
                }, gone = ::retry)
            }
            DocSource.Offline.NEEDS_PASSWORD -> {
                message = getString(R.string.remote_doc_password_hint, server?.label ?: "?")
                retry()
            }
            else -> retry()
        }
    }

    override fun onStart() {
        super.onStart()
        keepProjectorWhileAway = false
        // Before starting the projector: the audience must not see even one frame of the page.
        checkPrivateStart()
        projector.start()
        session.addListener(shareWindowListener)
        // Also when the recording started while the app was away.
        if (Build.VERSION.SDK_INT >= 35) recordingCallback!!.accept(windowManager.addScreenRecordingCallback(mainExecutor, recordingCallback))
        handler.postDelayed(periodicSave, SESSION_SAVE_INTERVAL_MS)
        // Coming back to the app: the PDFs may have changed meanwhile.
        diskPoll.run()
    }

    override fun onResume() {
        super.onResume()
        allFilesAccess = hasAllFilesAccess()
        updateTranslucent()
        // Coming back with the window to share open: it must be behind Cockpit again (after a moment: when
        // returning from the window itself it starts by itself).
        handler.postDelayed({
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                AudienceActivity.restackIfHidden(this, session)
            }
        }, RESTACK_DELAY_MS)
    }

    /**
     * Private start (a setting): at launch or when coming back after more than 2 hours away
     * ([PrivateStart]), the projector shows only the logo until its button (or B)
     * is pressed.
     */
    private fun checkPrivateStart() {
        val firstStart = !app.privateStartChecked
        app.privateStartChecked = true
        val enabled = session.settings.privateStart
        if (!PrivateStart.shouldStart(enabled, firstStart, System.currentTimeMillis(), session.prefs.lastAwayAt)) return
        if (session.projectorMode == PresenterSession.ProjectorMode.PRIVATE) return
        session.projectorMode = PresenterSession.ProjectorMode.PRIVATE
        // The message comes with the external display (see setContent): without it nothing would show.
    }

    override fun onStop() {
        // Outside the app the projector goes back to mirroring the tablet (so it is clear the presentation is
        // paused); it resumes by itself on return.
        if (!keepProjectorWhileAway && !AudienceActivity.inFront) projector.stop()
        session.removeListener(shareWindowListener)
        if (Build.VERSION.SDK_INT >= 35) windowManager.removeScreenRecordingCallback(recordingCallback!!)
        session.prefs.lastAwayAt = System.currentTimeMillis()
        handler.removeCallbacks(periodicSave)
        handler.removeCallbacks(diskPoll)
        // An open text box is closed: the text goes into the session and the document.
        session.endTextEdit()
        // The app leaves the foreground: from here the system may kill it without notice.
        flows.saveSession(force = true)
        super.onStop()
    }

    override fun onDestroy() {
        projector.stop()
        AudienceActivity.onStopped = null
        diskWatcher.stop()
        flows.dispose()
        // The session outlives the activity: no references to this one (unless a new activity already replaced
        // them with its own).
        if (session.onOpenPdf === openPdfHook) session.onOpenPdf = null
        if (session.onNewBoard === newBoardHook) session.onNewBoard = null
        if (session.unlockKey === unlockKeyHook) session.unlockKey = null
        if (session.unlockPassword === unlockPasswordHook) session.unlockPassword = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------------------------------------------------------------- S Pen button on the work tabs

    private var penButtonDown = false
    private var lastPenButtonRelease = 0L

    /**
     * S Pen side button over code and browser tabs, from the raw events (in Compose the pen button does not
     * arrive): same rules as on the pages, where PresenterCanvas follows it. Held = dual tool, double click
     * = eraser ([ToolState.buttonHeld], `buttonDoubleClick`).
     */
    private fun trackPenButton(event: MotionEvent) {
        if (session.activeWork == null || event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return
        var pressed = event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY) || event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)
        // Pen out of range: the digitizer will no longer say if the button is released.
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT && event.buttonState == 0) pressed = false
        if (pressed == penButtonDown) return
        penButtonDown = pressed
        val tools = session.tools
        if (pressed) {
            tools.buttonDoubleClick = event.eventTime - lastPenButtonRelease < ToolState.DOUBLE_CLICK_MS
        } else {
            lastPenButtonRelease = event.eventTime
            tools.buttonDoubleClick = false
        }
        tools.buttonHeld = pressed
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        trackPenButton(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) session.onWindowTouch?.invoke()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        trackPenButton(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    /**
     * Presentation remotes and keyboards: arrows, page up/down, space; Ctrl+S saves. They are intercepted
     * before the UI: otherwise PgUp/PgDn would scroll the tool rail (which is scrollable) instead of
     * changing slide.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Keys from the single shortcut registry (input/Shortcuts), as in PDFPC.
        val action = Shortcuts.find(event) ?: return super.dispatchKeyEvent(event)
        lastKeyCode = event.keyCode
        // With dialogs or panels open (including the navigation panel, where you type) the keys stay with them.
        if (showBrowser || flows.closing != null || session.settingsOpen || session.pendingUri != null || session.navigationOpen ||
            session.textEdit != null || session.shortcutsOpen || session.activeWork != null || session.notesEditing
        ) {
            return super.dispatchKeyEvent(event)
        }
        // Holding a key repeats only next and previous.
        val repeatable = action == ShortcutAction.NEXT || action == ShortcutAction.PREVIOUS
        if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || repeatable)) perform(action)
        return true
    }

    /** Key that produced the current action (Backspace does different things with a selection). */
    private var lastKeyCode = 0

    private fun perform(action: ShortcutAction) {
        val doc = session.current
        when (action) {
            // With the overview open the audience must not see the slide change behind the grid.
            ShortcutAction.NEXT -> if (!session.overviewOpen) session.nextPage()
            ShortcutAction.PREVIOUS -> if (!session.overviewOpen) {
                // Backspace with a selection deletes it, instead of going back.
                if (session.selection != null && lastKeyCode == KeyEvent.KEYCODE_DEL) session.deleteSelection() else session.previousPage()
            }
            ShortcutAction.FIRST -> doc?.let { session.jumpTo(it, 0, record = false) }
            ShortcutAction.LAST -> doc?.let { session.jumpTo(it, session.navSlides(it).let { s -> s.firstPageOf(it.pageCount - 1) }, record = false) }
            ShortcutAction.BLACK -> if (session.projectorInfo != null || session.projectorMode == PresenterSession.ProjectorMode.PRIVATE) session.toggleBlack()
            ShortcutAction.FREEZE -> session.toggleFreeze()
            ShortcutAction.GOTO -> if (doc != null) {
                session.overviewOpen = false
                session.navigationOpen = true
            }
            ShortcutAction.OVERVIEW -> if (doc != null) session.overviewOpen = !session.overviewOpen
            ShortcutAction.NOTES -> if (doc?.notesAvailable == true) doc.notesVisible = !doc.notesVisible
            ShortcutAction.BOOKMARK -> doc?.let { flows.setBookmark(it, it.pageIndex, !it.isBookmarked(it.pageIndex)) }
            ShortcutAction.UNDO -> session.undo()
            ShortcutAction.REDO -> session.redo()
            ShortcutAction.SAVE -> doc?.takeIf { it.isDirty }?.let { flows.save(it) }
            ShortcutAction.COPY -> session.copySelection()
            ShortcutAction.CUT -> {
                session.copySelection()
                session.deleteSelection()
            }
            ShortcutAction.PASTE -> session.paste()
            ShortcutAction.DUPLICATE -> session.duplicateSelection()
            ShortcutAction.DELETE -> session.deleteSelection()
            ShortcutAction.ESCAPE -> {
                session.overviewOpen = false
                session.selection = null
                session.toolEditor = null
                session.spotlight = null
            }
            ShortcutAction.HELP -> session.shortcutsOpen = true
        }
    }

    /**
     * Fingerprint (or PIN) for the SSH key: unlocks the Keystore for 30 minutes, then [then] retries the
     * connection. The unlock applies to the key, not to the tab: if it is already unlocked (by another tab)
     * it is not asked again, and after the fingerprint the other tabs and remote PDFs waiting on the key
     * restart too.
     */
    private fun unlockSshKey(then: () -> Unit) {
        if (SshKeys.isUnlocked()) {
            then()
            retryKeyLocked()
            return
        }
        Biometrics.unlockKey(this) {
            then()
            retryKeyLocked()
        }
    }

    /** Key unlocked: the tabs and remote PDFs waiting for the fingerprint retry. */
    private fun retryKeyLocked() {
        for (tab in session.workTabs) when (tab) {
            is TerminalTab -> if (tab.status == LinkStatus.Locked) tab.start()
            is EditorTab -> if (tab.status == LinkStatus.Locked) tab.refresh()
            else -> Unit
        }
        var docs = false
        for (doc in session.documents) {
            val src = doc.source as? DocSource.Remote ?: continue
            if (src.offline == DocSource.Offline.KEY_LOCKED) { src.retrySoon(); docs = true }
        }
        if (docs) flows.checkDisk()
    }

    /** Fingerprint unlocks for the remote tabs (SSH key and saved passwords). */
    private val remoteAuth by lazy {
        RemoteAuth(
            unlockKey = ::unlockSshKey,
            canSavePasswords = Biometrics.canUnlockCiphers(this),
            savePassword = ::savePassword,
            unlockPassword = ::unlockSavedPassword,
        )
    }

    private fun savePassword(password: String, then: (String) -> Unit) {
        val cipher = runCatching { SavedPasswords.encryptCipher() }.getOrElse {
            message = getString(R.string.remote_password_save_failed)
            return
        }
        Biometrics.unlockCipher(this, cipher, getString(R.string.remote_password_prompt_save)) { c ->
            runCatching { SavedPasswords.encrypt(c, password) }
                .onSuccess(then)
                .onFailure { message = getString(R.string.remote_password_save_failed) }
        }
    }

    private fun unlockSavedPassword(server: ServerProfile, then: (String) -> Unit, gone: () -> Unit) {
        val cipher = SavedPasswords.decryptCipher(server.id)
        if (cipher == null) {
            // Fingerprints changed (or unreadable data): the password is forgotten, it is asked again.
            message = getString(R.string.remote_password_gone)
            gone()
            return
        }
        Biometrics.unlockCipher(this, cipher, server.label) { c ->
            val password = SavedPasswords.decrypt(c, server.id)
            if (password != null) then(password) else {
                message = getString(R.string.remote_password_gone)
                gone()
            }
        }
    }

    // ---------------------------------------------------------------- workspaces

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------------------------------------------------------------- session

    private val handler = Handler(Looper.getMainLooper())
    /** Periodic save, only if something changed: it also protects against a crash. */
    private val periodicSave = object : Runnable {
        override fun run() {
            flows.saveSession(force = false)
            handler.postDelayed(this, SESSION_SAVE_INTERVAL_MS)
        }
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        intent.data = null
        flows.open(DocSource.fromUri(this, uri))
    }

    // ---------------------------------------------------------------- files

    private fun openBrowser() {
        // "store" distribution: no access to all files, the granted folders instead.
        if (!BuildConfig.ALL_FILES_ACCESS) {
            showTreeBrowser = true
            return
        }
        allFilesAccess = hasAllFilesAccess()
        showBrowser = true
    }

    private fun requestAllFilesAccess() {
        // Android 10 has no "all files access" permission (nor its screens): the system picker remains.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    /** The system picker starts from the folder of the last opened PDF. */
    private fun launchSystemPicker() {
        keepProjectorWhileAway = true
        val initial = session.prefs.lastPickerUri?.let { DocSource.parentDocumentUri(Uri.parse(it)) }
            ?: session.prefs.lastFolder?.let { folderUri(File(it)) }
        systemPicker.launch(initial)
    }

    private fun folderUri(dir: File): Uri? {
        val base = Environment.getExternalStorageDirectory().path
        if (!dir.path.startsWith(base)) return null
        val rel = dir.path.removePrefix(base).trim('/')
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$rel")
    }

    /** System picker for PDFs, with the initial folder ([DocumentsContract.EXTRA_INITIAL_URI]). */
    private class PickPdf : ActivityResultContract<Uri?, Uri?>() {
        override fun createIntent(context: Context, input: Uri?) = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
            if (input != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data?.takeIf { resultCode == RESULT_OK }
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val SESSION_SAVE_INTERVAL_MS = 30_000L
        /** A message equal to the one just shown, arrived meanwhile, is not shown again. */
        const val REPEAT_GAP_MS = 500L
        const val DISK_POLL_MS = 5_000L
        /** Wait before checking that the window to share is visible behind Cockpit. */
        const val RESTACK_DELAY_MS = 400L
    }
}
