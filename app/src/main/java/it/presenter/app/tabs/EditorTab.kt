package it.presenter.app.tabs

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import it.presenter.app.PresenterApp
import it.presenter.app.R
import it.presenter.app.UserFacingException
import it.presenter.app.doc.DocSource
import it.presenter.app.doc.DocStamp
import it.presenter.app.files.TreeFiles
import it.presenter.app.remote.FileAccess
import it.presenter.app.remote.FileEntry
import it.presenter.app.remote.LocalFiles
import it.presenter.app.remote.RemoteFiles
import it.presenter.app.remote.ServerProfile
import it.presenter.app.remote.SshLink
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * IDE on a folder of the tablet or of the server: file tree, several files open in tabs (one active,
 * with highlighted code), editing and saving. Unsaved changes are never lost: they stay here (and in
 * the session) even if the connection drops, and are saved when it comes back.
 */
class EditorTab private constructor(
    /** Server for remote files; null = tablet folder. */
    val server: ServerProfile?,
    private val fs: FileAccess,
    rootDir: String,
    id: String?,
) : WorkTab(id ?: UUID.randomUUID().toString()) {

    var root by mutableStateOf(rootDir)
        private set

    override val title: String
        get() = active?.name ?: server?.label
            // Granted folder: its readable name (the URI does not tell it); one of its subfolders: the name.
            ?: (fs as? TreeFiles)?.takeIf { it.root == root }?.rootName
            ?: root.substringAfterLast('/').ifEmpty { root }

    private val io = Executors.newSingleThreadExecutor { Thread(it, "editor-files") }
    private val statusState = MainState<LinkStatus>(if (server == null) LinkStatus.Connected else LinkStatus.Connecting)
    val status: LinkStatus get() = statusState.value

    /** Content of the folders already open in the tree (path → entries). */
    val children = mutableStateMapOf<String, List<FileEntry>>()
    val expanded = mutableStateMapOf<String, Boolean>()

    /** Hidden files and folders (name starting with a dot) in the tree: off by default. */
    var showHidden by mutableStateOf(false)

    /** Width of the tree (dp), adjusted by dragging its edge with the finger or the pen. */
    var treeWidth by mutableFloatStateOf(DEFAULT_TREE_WIDTH)

    /** Open files, in tab order, and the one in the editor. */
    val files = mutableStateListOf<OpenFile>()
    var active by mutableStateOf<OpenFile?>(null)
        private set

    /** On the same machine as [other] (null: the tablet), with the same user. */
    fun sameMachine(other: ServerProfile?): Boolean = if (other == null) server == null else server?.sameAccount(other) == true

    /** The PDF [path] of the tree as a document: tablet file, granted folder or file on the server. */
    fun pdfSource(path: String): DocSource = when {
        server != null -> DocSource.Remote(server.id, path)
        fs is TreeFiles -> DocSource.Tree(path, fs.uriOf(path), path.substringAfterLast('/'))
        else -> DocSource.Local(File(path))
    }

    /** The active file, as editor, projector and session see it. */
    val openPath: String? get() = active?.path
    val text: String get() = active?.text.orEmpty()
    val isDirty: Boolean get() = active?.isDirty == true
    val firstVisibleLine: Int get() = active?.firstVisibleLine ?: 0

    /** SSH connection of remote files (null for a tablet folder). */
    val link: SshLink? get() = (fs as? RemoteFiles)?.link

    /** Pen strokes over the code (they disappear when scrolling). */
    val codeInk = CodeInk()

    /** Saves in progress. */
    private var savingCount by mutableIntStateOf(0)
    val saving: Boolean get() = savingCount > 0

    /** Error of the last file operation (null if all went well); the UI turns it into text with `describe`. */
    var error by mutableStateOf<Throwable?>(null)
        private set

    private val backoff = Backoff(action = { refresh() }, onWait = { statusState.post(LinkStatus.Waiting(it, link?.lastNetError)) })

    /**
     * Restore from the session. Unsaved text is shown at once, even without a connection (so it cannot be
     * lost: it is in the editor, dirty, and is saved like any change); the text on disk is read on
     * connecting, as is the content of the files without changes.
     */
    fun restoreFiles(restored: List<OpenFile>, activeIndex: Int) {
        files.clear()
        files.addAll(restored)
        active = restored.getOrNull(activeIndex) ?: restored.firstOrNull()
    }

    /** Some file has unsaved changes. */
    val hasUnsaved: Boolean get() = files.any { it.isDirty }

    /** Drops the unsaved changes of every open file ("Don't save" when leaving the workspace). */
    fun discardChanges() = files.forEach { if (it.isDirty) it.discardChanges() }

    @Volatile
    private var closed = false

    /** File work on the tab's thread; nothing more is done after closing. */
    private fun exec(block: () -> Unit) {
        if (closed) return
        runCatching { io.execute { if (!closed) block() } }
    }

    /** Tree of the root folder (and reopening of the files, if any). */
    fun refresh() {
        if (closed) return
        statusState.post(LinkStatus.Connecting.takeIf { server != null } ?: LinkStatus.Connected)
        exec {
            runRemote {
                var dir = root
                if (server != null && dir.isEmpty()) {
                    val home = (fs as RemoteFiles).home()
                    dir = server.startDir(home)
                }
                val list = fs.list(dir.ifEmpty { "/" })
                post {
                    root = dir
                    children[dir] = list
                }
                // Folders open in the tree (also from the session): reread from the outermost. Those that no longer
                // exist are closed.
                val base = dir.trimEnd('/') + "/"
                // The last open folder is reopened together with all the folders above it.
                val ancestors = lastDirCopy?.let { last ->
                    generateSequence(last) { p -> p.substringBeforeLast('/', "").takeIf { it.length >= base.length } }.toList()
                }.orEmpty()
                val open = (expandedSnapshot() + ancestors).distinct().filter { it.startsWith(base) }.sortedBy { it.length }
                post { for (p in open) expanded[p] = true }
                for (p in open) {
                    val sub = runCatching { fs.list(p) }.getOrNull()
                    post {
                        if (sub != null) {
                            children[p] = sub
                        } else {
                            expanded.remove(p)
                            if (lastDir == p) lastDir = null
                        }
                    }
                }
                statusState.post(LinkStatus.Connected)
                backoff.reset()
                post { readPending() }
            }
        }
    }

    /** Search bar open, with its text (Ctrl+F). */
    var findOpen by mutableStateOf(false)
    var findQuery by mutableStateOf("")

    /** File tree visible next to the code (open at start: the last folder is in view). */
    var showTree by mutableStateOf(true)

    /** Last folder opened in the tree: on reopening it is open, highlighted and in view. */
    var lastDir by mutableStateOf<String?>(null)

    @Volatile
    private var lastDirCopy: String? = null

    fun restoreLastDir(dir: String?) {
        lastDir = dir
        lastDirCopy = dir
    }

    /** Copy of the open folders, readable from the files thread. */
    @Volatile
    private var expandedCopy: List<String> = emptyList()

    private fun expandedSnapshot(): List<String> = expandedCopy

    /** Folders open in the tree (for the session and workspaces). */
    val expandedDirs: List<String> get() = expanded.filterValues { it }.keys.toList()

    /** Reopens folders [dirs] (from the session): they are read on connecting. */
    fun restoreExpanded(dirs: List<String>) {
        for (d in dirs) expanded[d] = true
        expandedCopy = dirs
    }

    fun toggle(dir: String) {
        val open = expanded[dir] != true
        expanded[dir] = open
        expandedCopy = expandedDirs
        // Opening: it is the last folder; closing it (or a folder above it), its parent takes over if open.
        lastDir = when {
            open -> dir
            lastDir?.let { it == dir || it.startsWith("$dir/") } == true -> dir.substringBeforeLast('/', "").takeIf { expanded[it] == true }
            else -> lastDir
        }
        lastDirCopy = lastDir
        if (open && children[dir] == null) exec { runRemote { fs.list(dir).let { post { children[dir] = it } } } }
    }

    /**
     * Opens [path] in a tab, or goes back to its tab if already open. With [pin] false it is a preview: it
     * replaces the previous preview (a tap in the tree); with [pin] the file stays open.
     */
    fun open(path: String, pin: Boolean = false) {
        files.firstOrNull { it.path == path }?.let { f ->
            if (pin) f.preview = false
            active = f
            return
        }
        exec {
            runRemote {
                // Huge or binary files (logs, core dumps, images): not in the editor.
                if (fs.size(path) > FileAccess.MAX_EDIT_BYTES) throw UserFacingException(R.string.code_too_large, name(path))
                // Date before the text: a change while reading shows at the next check.
                val stamp = runCatching { fs.stamp(path) }.getOrNull()
                val content = fs.read(path)
                if (content.contains('\u0000')) throw UserFacingException(R.string.code_binary, name(path))
                post { show(OpenFile(path, content, preview = !pin).also { it.diskStamp = stamp }) }
            }
        }
    }

    /** Shows [f], just read: in place of the preview, or after the active file. */
    private fun show(f: OpenFile) {
        files.firstOrNull { it.path == f.path }?.let { active = it; return }
        val preview = files.indexOfFirst { it.preview && !it.isDirty }
        if (f.preview && preview >= 0) {
            files[preview] = f
        } else {
            val at = active?.let { files.indexOf(it) + 1 } ?: files.size
            files.add(at.coerceIn(0, files.size), f)
        }
        active = f
    }

    fun activate(f: OpenFile) {
        if (f in files) active = f
    }

    /** Next (or previous) active file among the tabs, wrapping around. */
    fun cycle(step: Int) {
        val cur = active ?: return
        if (files.size < 2) return
        active = files[Math.floorMod(files.indexOf(cur) + step, files.size)]
    }

    /** Closes the tab of [f] (changes must be saved first: the UI asks). */
    fun closeFile(f: OpenFile) {
        val i = files.indexOf(f)
        if (i < 0) return
        files.removeAt(i)
        if (active === f) active = files.getOrNull(i) ?: files.lastOrNull()
    }

    /**
     * Creates the empty file [name] in folder [dir] (never over an existing one: error), opens the folder in
     * the tree and the file in the editor.
     */
    fun createFile(dir: String, name: String) {
        val path = dir.trimEnd('/') + "/" + name
        exec {
            runRemote {
                fs.create(path)
                val list = fs.list(dir)
                post {
                    children[dir] = list
                    if (dir != root && expanded[dir] != true) toggle(dir)
                    open(path, pin = true)
                }
            }
        }
    }

    /**
     * Saves [f] (Ctrl+S or icon: the active file). If the connection is down, it is retried when it comes back.
     * If someone else changed the file since it was read, the first save does not overwrite it: the file is
     * marked [OpenFile.changedOnDisk] (reload or save again to overwrite).
     */
    fun save(f: OpenFile? = active, onDone: (Boolean) -> Unit = {}) {
        if (f == null || !f.isDirty) return onDone(true)
        val path = f.path
        val content = f.text
        val known = f.diskStamp
        val base = f.savedText
        // Already told that the file changed: saving again overwrites it.
        val overwrite = f.changedOnDisk
        savingCount++
        exec {
            var conflict: Pair<String, DocStamp?>? = null
            var stamp: DocStamp? = null
            val ok = runRemote {
                if (!overwrite && known != null) {
                    val now = fs.stamp(path)
                    if (now != null && now != known) {
                        val disk = fs.read(path)
                        if (disk != base) conflict = disk to now
                    }
                }
                if (conflict == null) {
                    fs.write(path, content)
                    stamp = runCatching { fs.stamp(path) }.getOrNull()
                }
            }
            post {
                savingCount--
                val c = conflict
                when {
                    !ok -> onDone(false)
                    c != null -> {
                        f.diskChanged(c.first, c.second)
                        error = UserFacingException(R.string.code_save_conflict, f.name)
                        onDone(false)
                    }
                    else -> {
                        f.saved(content, stamp)
                        onDone(true)
                    }
                }
            }
        }
    }

    /** A disk check is running ([checkDisk]); main thread only. */
    private var checking = false

    /**
     * Files and folders changed by others (terminal, other apps): open files without changes are reread,
     * those with changes become [OpenFile.changedOnDisk]; the open folders of the tree are listed again.
     * Called while the tab is in front, every [DISK_CHECK_MS].
     */
    fun checkDisk() {
        if (closed || checking || saving || status != LinkStatus.Connected) return
        // Changed elsewhere, and the changes here undone meanwhile: nothing to lose, the new text comes in.
        files.filter { it.changedOnDisk && !it.isDirty }.forEach { it.reloadFromDisk() }
        val known = files.filter { it.loaded && it.diskStamp != null }.map { it to it.diskStamp }
        val dirs = (listOf(root) + expandedDirs).filter { it.isNotEmpty() }.distinct()
        checking = true
        exec {
            try {
                for ((f, stamp) in known) {
                    // A connection error stops the check: the next one retries.
                    val now = runCatching { fs.stamp(f.path) }.getOrElse { return@exec } ?: continue
                    if (now == stamp) continue
                    val disk = runCatching { fs.read(f.path) }.getOrNull() ?: continue
                    post { if (f in files && f.diskStamp == stamp && savingCount == 0) f.diskChanged(disk, now) }
                }
                for (d in dirs) {
                    val list = runCatching { fs.list(d) }.getOrNull() ?: continue
                    post { if (children[d] != list) children[d] = list }
                }
            } finally {
                post { checking = false }
            }
        }
    }

    /** Saves all files with changes; [onDone] with true if all succeeded. */
    fun saveAll(onDone: (Boolean) -> Unit = {}) {
        val dirty = files.filter { it.isDirty }
        if (dirty.isEmpty()) return onDone(true)
        var left = dirty.size
        var allOk = true
        for (f in dirty) save(f) { ok ->
            allOk = allOk && ok
            if (--left == 0) onDone(allOk)
        }
    }

    /**
     * On connecting, the disk content of restored files is read. With unsaved text already in the editor
     * only the disk text is taken. If a file cannot be read it stays as it is, with a warning: retried at
     * the next connection (or closed); with changes, saving recreates it.
     */
    private fun readPending() {
        for (f in files.filter { it.savedText == null }) {
            val path = f.path
            exec {
                val stamp = runCatching { fs.stamp(path) }.getOrNull()
                val disk = runCatching { fs.read(path) }.getOrNull()
                post {
                    if (f !in files || f.savedText != null) return@post
                    if (disk != null) {
                        f.loadedFromDisk(disk)
                        f.diskStamp = stamp
                    } else if (!f.loaded) error = UserFacingException(R.string.code_read_failed, name(path))
                }
            }
        }
    }

    fun retryWithPassword(password: String, fromStore: Boolean = false, saveBlob: String? = null) {
        (fs as? RemoteFiles)?.link?.usePassword(password, fromStore, saveBlob)
        refresh()
    }

    override fun onNetworkAvailable() {
        if (status is LinkStatus.Waiting) backoff.now()
    }

    /** Runs [block]; for remote files turns connection failures into the tab's state. */
    private fun runRemote(block: () -> Unit): Boolean = try {
        block()
        post { error = null }
        true
    } catch (e: SshLink.Failure) {
        when (e) {
            is SshLink.Failure.NeedsUnlock -> statusState.post(LinkStatus.Locked)
            is SshLink.Failure.PasswordLocked -> statusState.post(LinkStatus.PasswordLocked)
            is SshLink.Failure.KeyInvalid -> statusState.post(LinkStatus.KeyInvalid)
            is SshLink.Failure.NeedsPassword -> statusState.post(LinkStatus.NeedsPassword(link?.password != null, link?.keyRejected == true))
            is SshLink.Failure.HostKeyChanged -> statusState.post(LinkStatus.HostKeyChanged)
            is SshLink.Failure.Network -> if (!closed) backoff.schedule()
        }
        false
    } catch (e: Exception) {
        if (!closed && server != null && (fs as RemoteFiles).link.isConnected.not()) backoff.schedule()
        post { error = e as? UserFacingException ?: UserFacingException(R.string.code_file_error, e.message ?: e.javaClass.simpleName) }
        false
    }

    private val main = Handler(Looper.getMainLooper())
    private fun post(block: () -> Unit) {
        main.post(block)
    }

    override fun close() {
        closed = true
        backoff.cancel()
        // On a separate thread: the tab's one may be stuck in a connection.
        Thread {
            fs.close()
            (fs as? RemoteFiles)?.link?.close()
        }.start()
        io.shutdown()
    }

    companion object {
        /** Initial width of the file tree (dp). */
        const val DEFAULT_TREE_WIDTH = 280f

        /** Check for files and folders changed by others, while the tab is in front. */
        const val DISK_CHECK_MS = 5_000L

        private fun name(path: String) = path.substringAfterLast('/')

        /** Tablet folder: a real file path, or a granted folder (URI, "store" distribution). */
        fun local(dir: String, id: String? = null): EditorTab {
            val fs = if (TreeFiles.isTreePath(dir)) TreeFiles(PresenterApp.context, TreeFiles.treeOf(dir)) else LocalFiles(dir)
            return EditorTab(null, fs, dir, id)
        }

        /** Empty [dir]: the profile's folder or the home, decided at the first connection; otherwise that folder. */
        fun remote(server: ServerProfile, knownHosts: File, dir: String = "", id: String? = null): EditorTab {
            val link = SshLink(server, knownHosts)
            return EditorTab(server, RemoteFiles(link, dir), dir, id)
        }
    }
}
