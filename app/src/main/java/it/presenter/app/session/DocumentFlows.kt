package it.presenter.app.session

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import it.presenter.app.PresenterApp
import it.presenter.app.R
import it.presenter.app.doc.Boards
import it.presenter.app.describe
import it.presenter.app.doc.DocSource
import it.presenter.app.doc.DocStamp
import it.presenter.app.doc.PageEdit
import it.presenter.app.doc.PageSlot
import it.presenter.app.doc.PresenterDocument
import it.presenter.app.tabs.EditorTab
import it.presenter.app.tabs.WorkTabStore
import it.presenter.app.workspace.Workspace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the app does with documents, sessions and workspaces, without any UI: open, save, reload from disk,
 * edit pages, restore and save the session, switch workspace. `MainActivity` shows its state (progress,
 * messages, questions) and calls it; tests drive it directly, with executors that run at once.
 *
 * [worker] reads and writes the tablet's documents, [remoteWorker] those on a server; [main] runs a block
 * on the main thread and [later] after a delay (milliseconds).
 */
class DocumentFlows(
    private val app: PresenterApp,
    private val worker: Executor,
    private val remoteWorker: Executor,
    private val main: (() -> Unit) -> Unit,
    private val later: (Long, () -> Unit) -> Unit,
) {
    private val context: Context get() = app
    private val session get() = app.session

    /** Openings and restores in progress (progress bar). */
    var loading by mutableIntStateOf(0)
        private set

    /** Message waiting for the snackbar (the UI shows it and clears it). */
    var message by mutableStateOf<String?>(null)

    /** The activity is gone: late results are dropped and nothing new is sent to the executors. */
    var disposed = false
        private set

    fun dispose() {
        disposed = true
        (worker as? ExecutorService)?.shutdown()
        (remoteWorker as? ExecutorService)?.shutdown()
    }

    /** Document being closed with unsaved annotations, waiting for the answer. */
    var closing by mutableStateOf<PresenterDocument?>(null)

    /** Close of a PDF tab asked by the user: asks first if there is something to save. */
    fun requestClose(doc: PresenterDocument) {
        session.endTextEdit()
        if (doc.isDirty) closing = doc else closeDocument(doc)
    }

    /** Session turned off in the settings: the saved one is no longer needed. */
    fun clearSavedSession() = worker.execute { SessionStore.clear(context) }

    /** "Save" when switching workspace: everything is saved, then [ws] opens once the saves are over. */
    fun saveAndOpenWorkspace(ws: Workspace) {
        session.documents.filter { it.isDirty }.forEach { save(it) }
        session.workTabs.filterIsInstance<EditorTab>().filter { it.hasUnsaved }.forEach { it.saveAll() }
        pendingWorkspace = ws
        openPendingWorkspaceWhenSaved()
    }

    /** "Don't save" when switching workspace: the changes are dropped, so the workspace left does not keep them. */
    fun discardAndOpenWorkspace(ws: Workspace) {
        session.workTabs.filterIsInstance<EditorTab>().forEach { it.discardChanges() }
        openWorkspace(ws)
    }

    var reloadAsk by mutableStateOf<PresenterDocument?>(null)

    private val reloading = HashSet<PresenterDocument>()

    /**
     * Compares size and date of the files of the open PDFs (all, or only [only]) with those read or written
     * by Presenter. Dates are read on the documents thread (for URIs it is a provider query).
     */
    fun checkDisk(only: File? = null) {
        val docs = session.documents.filter { d ->
            !d.isSaving && d !in reloading &&
                (only == null || (d.source as? DocSource.Local)?.file?.absolutePath == only.absolutePath)
        }
        if (docs.isEmpty()) return
        fun check(list: List<PresenterDocument>) {
            val changed = list.filter { d ->
                val now = d.source.stamp(context)
                // Missing date (remote save without the date afterwards): compare with this one from now on.
                if (now != null && d.diskStamp == null) d.diskStamp = now
                now != null && d.diskStamp != null && now != d.diskStamp && now != d.unreadableStamp
            }
            if (changed.isNotEmpty()) main { changed.forEach(::onDiskChanged) }
        }
        val (remote, local) = docs.partition { it.source is DocSource.Remote }
        if (local.isNotEmpty()) worker.execute { check(local) }
        // Remote PDFs on their own thread: an unresponsive server must not stop openings and saves; one check
        // at a time.
        if (remote.isNotEmpty() && remoteChecking.compareAndSet(false, true)) {
            remoteWorker.execute {
                try {
                    check(remote)
                } finally {
                    remoteChecking.set(false)
                }
            }
        }
    }

    /** Remote PDFs are read and saved on their own thread: a slow server does not stop the tablet's PDFs. */
    private fun executorFor(source: DocSource) = if (source is DocSource.Remote) remoteWorker else worker

    private val remoteChecking = AtomicBoolean(false)

    /**
     * PDF tab closed by the user: for a remote PDF also the copy on the tablet, which is no longer needed
     * (workspaces download it again when reopened).
     */
    fun closeDocument(doc: PresenterDocument) {
        session.close(doc)
        val src = doc.source as? DocSource.Remote ?: return
        if (session.documents.none { it.source.sameAs(src) }) {
            worker.execute { src.deleteCopy(context) }
        }
    }

    /**
     * [doc] changed on disk. Without changes in the app it reloads by itself, on the same page; with unsaved
     * changes (or an open text box) a message and the icon at the top let you reload it, discarding them.
     */
    private fun onDiskChanged(doc: PresenterDocument) {
        if (disposed || doc !in session.documents || doc.isSaving || doc in reloading) return
        if (!doc.isDirty && session.textEdit?.doc !== doc) {
            // Stroke or drag in progress: reload at the next check, once the gesture is over.
            if (session.isEditing(doc)) return
            reload(doc, auto = true)
        } else if (!doc.changedOnDisk) {
            doc.changedOnDisk = true
            message = context.getString(if (doc.source is DocSource.Remote) R.string.msg_changed_on_server else R.string.msg_changed_on_disk, doc.title)
        }
    }

    /** Rereads [doc] from disk and puts it in its place (same tab and page), discarding the changes. */
    fun reload(doc: PresenterDocument, auto: Boolean = false) {
        // Not during a save (afterwards the file is Presenter's again) nor during a page edit.
        if (disposed || doc.isSaving || doc in editingPages || doc !in session.documents || !reloading.add(doc)) return
        executorFor(doc.source).execute {
            val stamp = doc.source.stamp(context)
            val result = runCatching { PresenterDocument.open(context, doc.source, fresh = true) }
            main {
                reloading.remove(doc)
                result
                    .onSuccess { fresh ->
                        if (disposed || doc !in session.documents) {
                            fresh.close()
                            return@onSuccess
                        }
                        session.replace(doc, fresh)
                        // Dialogs and closes requested for the old document no longer apply.
                        if (closing === doc) closing = null
                        if (reloadAsk === doc) reloadAsk = null
                        closeAfterSave.remove(doc)
                        loadStructure(fresh)
                        message = context.getString(
                            when {
                                !auto && fresh.source is DocSource.Remote -> R.string.msg_reloaded_discarded_server
                                !auto -> R.string.msg_reloaded_discarded
                                fresh.source is DocSource.Remote -> R.string.msg_reloaded_server
                                else -> R.string.msg_reloaded
                            },
                            fresh.title,
                        )
                    }
                    .onFailure {
                        // Maybe the file is half written: retry when it changes again (or from the icon).
                        doc.unreadableStamp = stamp
                        doc.changedOnDisk = true
                        message = context.getString(R.string.msg_reload_failed, doc.title, it.describe(context))
                    }
            }
        }
    }

    /** Workspace to open, waiting for the answer about unsaved annotations. */
    var switchingTo by mutableStateOf<Workspace?>(null)

    fun requestWorkspace(ws: Workspace) {
        session.endTextEdit()
        val unsavedCode = session.workTabs.any { it is EditorTab && it.hasUnsaved }
        when {
            session.documents.any { it.isDirty } || unsavedCode -> switchingTo = ws
            // Tabs open outside any workspace: they would be closed without being remembered anywhere.
            session.currentWorkspace == null && (session.documents.isNotEmpty() || session.workTabs.isNotEmpty()) -> replacingWith = ws
            else -> openWorkspace(ws)
        }
    }

    /** Workspace to open, waiting for confirmation to close tabs that are in no workspace. */
    var replacingWith by mutableStateOf<Workspace?>(null)

    private var pendingWorkspace: Workspace? = null

    private fun openPendingWorkspaceWhenSaved() {
        val ws = pendingWorkspace ?: return
        if (disposed) return
        val busy = session.documents.any { it.isSaving } || session.workTabs.any { it is EditorTab && it.saving }
        if (busy) {
            later(200) { openPendingWorkspaceWhenSaved() }
        } else {
            pendingWorkspace = null
            // A save failed: stay here, annotations and code are not lost.
            val stillDirty = session.documents.any { it.isDirty } || session.workTabs.any { it is EditorTab && it.hasUnsaved }
            if (stillDirty) {
                message = context.getString(R.string.workspace_not_switched)
                return
            }
            openWorkspace(ws)
        }
    }

    /** Replaces the open tabs with those of the workspace (which becomes the one in use). */
    fun openWorkspace(ws: Workspace) {
        // The workspace being left remembers how it was left.
        session.syncCurrentWorkspace()
        // No workspace "in use" until the new one is open: a session save meanwhile would overwrite it with the
        // empty tabs.
        session.setCurrentWorkspace(null)
        session.closeEverything()
        loading++
        worker.execute {
            // Index of the active document among those actually opened (some may be missing).
            var active = 0
            val opened = ArrayList<PresenterDocument>()
            ws.docs.forEachIndexed { i, d ->
                runCatching { PresenterDocument.open(context, d.source) }.getOrNull()?.let { doc ->
                    doc.pageIndex = d.page.coerceIn(0, doc.pageCount - 1)
                    doc.inverted = d.inverted
                    if (i == ws.active) active = opened.size
                    opened.add(doc)
                }
            }
            val missing = ws.docs.size - opened.size
            main {
                loading--
                if (disposed) {
                    opened.forEach { it.close() }
                    return@main
                }
                session.restore(opened, active, null)
                opened.forEach(::loadStructure)
                session.restoreWork(ws.work, ws.activeWork)
                session.applyOrder(ws.order)
                // A new empty workspace enters the list only now.
                if (session.workspaces.none { it.id == ws.id }) session.updateWorkspace(ws)
                session.setCurrentWorkspace(ws.id)
                if (missing > 0) message = context.getString(R.string.workspace_missing, missing)
            }
        }
    }

    private var savedPersistVersion = -1L

    private var savedWork = ""

    fun saveSession(force: Boolean) {
        // After onDestroy (a save ending late) the worker no longer accepts tasks: onStop already saved.
        if (!app.sessionRestored || disposed) return
        val version = session.persistVersion
        // Work tabs (unsaved editor text, folders, open file) change without notifications: compare their state.
        val work = session.workTabs.joinToString("\n") { tab -> WorkTabStore.toJson(tab).toString() } +
            session.workTabs.indexOf(session.activeWork) + session.orderKeys()
        if (!force && version == savedPersistVersion && work == savedWork) return
        savedPersistVersion = version
        savedWork = work
        // The workspace in use follows the open tabs (even with the session turned off).
        session.syncCurrentWorkspace()
        if (!session.settings.sessionEnabled) return
        val snapshot = SessionStore.snapshot(session)
        worker.execute { runCatching { SessionStore.write(context, snapshot) } }
    }

    /** Reopens the documents of the previous session, with pages, framing and unsaved annotations. */
    fun restoreSession(then: () -> Unit) {
        loading++
        worker.execute {
            val saved = if (session.settings.sessionEnabled) SessionStore.read(context) else null
            val opened = ArrayList<PresenterDocument>()
            val missing = ArrayList<String>()
            // Kept aside (see SessionStore.pending): handed over on the main thread, where the list is read.
            val kept = ArrayList<SessionStore.SavedDocument>()
            // Reordered, unsaved pages of a file changed meanwhile: lost, and the user is told.
            val dropped = ArrayList<String>()
            // Index of the active document among those actually reopened (some may be missing).
            var active = 0
            saved?.documents?.forEachIndexed { i, s ->
                if (opened.any { it.source.sameAs(s.source) }) return@forEachIndexed
                runCatching { openWithLayout(context, s.source, s.layout, s.layoutStamp) }
                    .onSuccess { (doc, valid) ->
                        doc.pageIndex = if (valid) s.page.coerceIn(0, doc.pageCount - 1) else 0
                        doc.notesVisible = s.notesVisible && doc.notesAvailable
                        doc.inverted = s.inverted
                        if (valid) {
                            s.unsaved?.let(doc::restoreUnsaved)
                            s.pdfpc?.let(doc::restorePdfpc)
                        } else {
                            dropped.add(doc.title)
                        }
                        if (i == saved.active) active = opened.size
                        opened.add(doc)
                    }
                    .onFailure {
                        if (s.unsaved != null) {
                            kept.add(s)
                            missing.add(context.getString(R.string.msg_unavailable_kept, s.source.displayName))
                        } else {
                            missing.add(s.source.displayName)
                        }
                    }
            }
            main {
                loading--
                if (disposed) {
                    opened.forEach { it.close() }
                    return@main
                }
                SessionStore.pending.addAll(kept)
                session.restore(opened, active, saved?.viewport)
                saved?.let { session.restoreWork(it.work, it.activeWork) }
                session.applyOrder(saved?.order.orEmpty())
                opened.forEach(::loadStructure)
                app.sessionRestored = true
                savedPersistVersion = session.persistVersion
                // Both, if both happened: a second message would replace the first before it is shown.
                val notes = listOfNotNull(
                    context.getString(R.string.msg_unavailable, missing.joinToString()).takeIf { missing.isNotEmpty() },
                    context.getString(R.string.msg_layout_dropped, dropped.joinToString()).takeIf { dropped.isNotEmpty() },
                )
                if (notes.isNotEmpty()) message = notes.joinToString("\n")
                session.hints.offer(Hint.HELP)
                then()
            }
        }
    }

    /** Documents being opened: the same file opened twice would give two conflicting tabs. */
    private val opening = ArrayList<DocSource>()

    /** Documents to close as soon as the current save finishes. */
    private val closeAfterSave = HashSet<PresenterDocument>()

    fun open(source: DocSource, after: Any? = null) {
        session.documents.firstOrNull { it.source.sameAs(source) }?.let {
            session.switchTo(it)
            return
        }
        if (opening.any { it.sameAs(source) }) return
        opening.add(source)
        loading++
        // It had been kept aside with reordered, unsaved pages: the order is applied again.
        val pending = SessionStore.pending.firstOrNull { it.source.sameAs(source) }
        executorFor(source).execute {
            val result = runCatching { openWithLayout(context, source, pending?.layout, pending?.layoutStamp) }
            main {
                loading--
                opening.remove(source)
                result
                    .onSuccess { (doc, valid) ->
                        if (disposed) {
                            doc.close()
                            return@onSuccess
                        }
                        // It had been kept aside with unsaved annotations: they go back on the document.
                        SessionStore.pending.firstOrNull { it.source.sameAs(source) }?.let { p ->
                            SessionStore.pending.remove(p)
                            if (valid) {
                                p.unsaved?.let(doc::restoreUnsaved)
                                p.pdfpc?.let(doc::restorePdfpc)
                                message = context.getString(R.string.msg_restored_unsaved, doc.title)
                            } else {
                                message = context.getString(R.string.msg_layout_dropped, doc.title)
                            }
                        }
                        session.open(doc, after)
                        loadStructure(doc)
                        RecentDocs.add(session.prefs, source)
                    }
                    .onFailure { message = context.getString(R.string.msg_open_failed, source.displayName, it.describe(context)) }
            }
        }
    }

    /**
     * Opens [source] and applies again the session's unsaved page order [layout]. The second value tells
     * whether order and unsaved annotations still apply (false: the file changed).
     */
    private fun openWithLayout(
        context: Context,
        source: DocSource,
        layout: List<PageSlot>?,
        stamp: DocStamp?,
    ): Pair<PresenterDocument, Boolean> {
        val doc = PresenterDocument.open(context, source)
        if (layout == null) return doc to true
        return doc.withLayout(context, layout, stamp)?.let { it to true } ?: (doc to false)
    }

    /** Documents with a page edit in progress (one at a time). */
    val editingPages = mutableStateListOf<PresenterDocument>()

    /**
     * Page edit from the overview: the private copy is rewritten in the background and the new document
     * takes the place of [doc] (same tab; page and jumps follow the pages).
     */
    fun editPages(doc: PresenterDocument, edit: PageEdit) {
        if (doc.isSaving || doc in reloading || doc in editingPages || doc !in session.documents) return
        session.endTextEdit()
        session.selection = null
        editingPages.add(doc)
        val elements = doc.allElements()
        val saved = doc.savedElements()
        val editCount = doc.editCount
        executorFor(doc.source).execute {
            val result = runCatching { doc.edited(context, edit, elements, saved) }
            main {
                editingPages.remove(doc)
                result
                    .onSuccess { edited ->
                        if (disposed || doc !in session.documents || doc.isSaving) {
                            edited.doc.close()
                            return@onSuccess
                        }
                        // Annotations changed meanwhile: the result does not have them, start over.
                        if (doc.editCount != editCount) {
                            edited.doc.close()
                            editPages(doc, edit)
                            return@onSuccess
                        }
                        session.replace(doc, edited.doc, edited.newIndex)
                        if (closing === doc) closing = null
                        loadStructure(edited.doc)
                    }
                    .onFailure { message = context.getString(R.string.msg_pages_failed, it.describe(context)) }
            }
        }
    }

    /**
     * Hides or shows the slide of [page] from the overview. Like notes and bookmarks, the change stays in memory
     * and the document is to save: the .pdfpc is written together with the PDF (user's choice).
     */
    fun setHidden(doc: PresenterDocument, page: Int, hidden: Boolean) = editPdfpc(doc) { doc.setHidden(page, hidden) }

    /** Bookmark on the slide of [page] (navigation panel, M), kept like [setHidden]. */
    fun setBookmark(doc: PresenterDocument, page: Int, on: Boolean) = editPdfpc(doc) { doc.setBookmark(page, on) }

    /** Note of the slide of [page] edited in the notes panel, kept like [setHidden]. */
    fun setNote(doc: PresenterDocument, page: Int, text: String) {
        if (text.trim() == doc.noteFor(page).orEmpty().trim()) return
        editPdfpc(doc) { doc.setNote(page, text) }
    }

    /** Applies [change] to the .pdfpc of [doc], if it can be written next to the PDF. */
    private fun editPdfpc(doc: PresenterDocument, change: () -> Unit) {
        if (!doc.source.canWritePdfpc || doc !in session.documents) return
        change()
        // The unsaved mark appears and the session keeps the change.
        session.notify(PresenterSession.Change.INK)
    }

    /** A new whiteboard (one blank A4 page in landscape) in `files/boards/`, opened as a PDF tab. */
    fun newBoard() {
        val name = context.getString(R.string.board_name, SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT).format(Date()))
        worker.execute {
            val result = runCatching { Boards.create(File(context.filesDir, "boards"), name) }
            main {
                result
                    .onSuccess { open(DocSource.Local(it)) }
                    .onFailure { message = context.getString(R.string.msg_board_failed, it.describe(context)) }
            }
        }
    }

    /** Links and outline of the document, read in the background: until ready, they are simply missing. */
    private fun loadStructure(doc: PresenterDocument) {
        worker.execute {
            val structure = doc.loadStructure()
            main { if (doc in session.documents) doc.structure = structure }
        }
    }

    fun save(doc: PresenterDocument, closeAfter: Boolean = false) {
        session.endTextEdit()
        if (closeAfter) closeAfterSave.add(doc)
        // A save is already running: the close (if requested) happens when it ends.
        if (doc.isSaving) return
        // Rereading from disk (changes discarded): saving would write the old version back.
        // Pages being edited: the document is about to be replaced (the new one is saved).
        if (doc in reloading || doc in editingPages) {
            closeAfterSave.remove(doc)
            return
        }
        val snapshot = doc.snapshot()
        executorFor(doc.source).execute {
            val result = runCatching { doc.save(context, snapshot) }
            main {
                doc.onSaved(snapshot.takeIf { result.isSuccess })
                // At once: a session still holding the old page order would not match the file just written.
                if (result.isSuccess) saveSession(force = true)
                result
                    .onSuccess { notesError ->
                        message = if (notesError == null) {
                            context.getString(R.string.msg_saved, doc.source.displayName)
                        } else {
                            context.getString(R.string.msg_saved_notes_failed, doc.source.displayName, notesError.describe(context))
                        }
                        if (closeAfterSave.remove(doc)) {
                            // Annotations added during the save: ask again what to do.
                            if (doc.isDirty) closing = doc else closeDocument(doc)
                        }
                    }
                    .onFailure {
                        closeAfterSave.remove(doc)
                        message = context.getString(R.string.msg_save_failed, it.describe(context))
                    }
            }
        }
    }
}
