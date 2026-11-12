package it.presenter.app.doc

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import it.presenter.app.R
import it.presenter.app.UserFacingException
import it.presenter.app.files.TreeFiles
import it.presenter.app.remote.FileEntry
import it.presenter.app.remote.RemoteDocLinks
import it.presenter.app.remote.RemoteFiles
import it.presenter.app.remote.ServerProfile
import it.presenter.app.remote.SshLink
import it.presenter.app.tabs.MainState
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Where a PDF comes from and where it is written back when saved. */
sealed interface DocSource {
    val displayName: String

    /** Copies the current content of the file into [dest]. */
    fun copyTo(context: Context, dest: File)

    /** Overwrites the original file with [src]. */
    fun overwriteWith(context: Context, src: File)

    /** Content of the .pdfpc file next to the PDF, if it exists and is readable. */
    fun readPdfpc(context: Context): String?

    /**
     * Writes the .pdfpc file next to the PDF with [text] (pages moved or added, slides hidden), creating it if
     * there is none. Only where the folder can be reached: see [canWritePdfpc].
     */
    fun writePdfpc(context: Context, text: String)

    /** The folder of the PDF can be reached, so the .pdfpc can be written. */
    val canWritePdfpc: Boolean get() = true

    fun sameAs(other: DocSource): Boolean

    /**
     * Size and modification time of the file now, to notice if someone else changes it; null if unknown
     * (file gone, provider that does not tell).
     */
    fun stamp(context: Context): DocStamp?

    class Local(val file: File) : DocSource {
        override val displayName: String get() = file.name

        override fun copyTo(context: Context, dest: File) {
            file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        }

        /**
         * Atomic write: temporary file in the same folder, then rename, so an error halfway never leaves a
         * damaged PDF. The temporary file is synced to disk before the rename: otherwise after a sudden power-off
         * the PDF could be left empty.
         */
        override fun overwriteWith(context: Context, src: File) {
            replaceAtomically(file) { o -> src.inputStream().use { it.copyTo(o) } }
            // Android's file index does not always follow the rename: update it, so other apps (and the system
            // picker) see the saved version at once.
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("application/pdf"), null)
        }

        override fun writePdfpc(context: Context, text: String) {
            val pdfpc = File(pdfpcPath(file.path))
            replaceAtomically(pdfpc) { o -> o.write(text.toByteArray(Charsets.UTF_8)) }
            MediaScannerConnection.scanFile(context, arrayOf(pdfpc.path), null, null)
        }

        private fun replaceAtomically(target: File, write: (FileOutputStream) -> Unit) {
            val tmp = File(target.parentFile, ".${target.name}.presenter-tmp")
            try {
                FileOutputStream(tmp).use { o ->
                    write(o)
                    o.fd.sync()
                }
            } catch (e: Exception) {
                // Storage full or similar: no hidden files left in the folder.
                tmp.delete()
                throw e
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw UserFacingException(R.string.error_replace_file, target.name)
            }
        }

        override fun readPdfpc(context: Context): String? {
            val pdfpc = File(pdfpcPath(file.path))
            return pdfpc.takeIf { it.isFile && it.canRead() }?.readText()
        }

        override fun sameAs(other: DocSource) = other is Local && other.file.absolutePath == file.absolutePath

        override fun stamp(context: Context): DocStamp? =
            if (file.isFile) DocStamp(file.length(), file.lastModified()) else null
    }

    /** Document received from another app or from the system picker, without direct access to the file. */
    class Content(val uri: Uri, override val displayName: String) : DocSource {
        override fun copyTo(context: Context, dest: File) {
            val input = context.contentResolver.openInputStream(uri) ?: throw UserFacingException(R.string.error_unreadable)
            input.use { i -> dest.outputStream().use { i.copyTo(it) } }
        }

        override fun overwriteWith(context: Context, src: File) {
            val resolver = context.contentResolver
            // "wt" truncates the file. Where it is not supported, write in "rw" and truncate by hand: "w" does not
            // truncate on many providers, and a shorter file would be left damaged at the end.
            val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            if (out != null) {
                out.use { o -> src.inputStream().use { it.copyTo(o) } }
                return
            }
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw UserFacingException(R.string.error_not_writable)
            pfd.use {
                FileOutputStream(it.fileDescriptor).use { o ->
                    val written = src.inputStream().use { i -> i.copyTo(o) }
                    o.channel.truncate(written)
                }
            }
        }

        // Without access to the folder the neighboring files are not visible.
        override fun readPdfpc(context: Context): String? = null

        override fun writePdfpc(context: Context, text: String) = Unit

        // A file passed by another app: its folder cannot be reached.
        override val canWritePdfpc: Boolean get() = false

        override fun sameAs(other: DocSource) = other is Content && other.uri == uri

        override fun stamp(context: Context): DocStamp? = runCatching {
            val columns = arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val size = c.getColumnIndex(columns[0]).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
                val modified = c.getColumnIndex(columns[1]).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
                if (size == null && modified == null) null else DocStamp(size ?: -1, modified ?: -1)
            }
        }.getOrNull()
    }

    /**
     * PDF inside a **granted folder** ("store" distribution, [TreeFiles]): [path] is the path in the folder
     * (`<tree>/…/talk.pdf`). Reading, writing and change checks as for [Content]; in addition the
     * neighboring files are visible, hence the `.pdfpc` notes.
     */
    class Tree(val path: String, val uri: Uri, override val displayName: String) : DocSource {
        private val content = Content(uri, displayName)

        override fun copyTo(context: Context, dest: File) = content.copyTo(context, dest)

        override fun overwriteWith(context: Context, src: File) = content.overwriteWith(context, src)

        override fun readPdfpc(context: Context): String? = runCatching {
            val files = TreeFiles(context, TreeFiles.treeOf(path))
            pdfpcEntry(files)?.let { e -> files.readBytesOrNull(e.path)?.decodeToString() }
        }.getOrNull()

        /** Rewritten in place (not atomic, like the PDF in granted folders), or created next to the PDF. */
        override fun writePdfpc(context: Context, text: String) {
            val files = TreeFiles(context, TreeFiles.treeOf(path))
            val target = pdfpcEntry(files)?.path
                ?: (path.substringBeforeLast('/') + "/" + pdfpcPath(path).substringAfterLast('/')).also(files::create)
            files.write(target, text)
        }

        private fun pdfpcEntry(files: TreeFiles): FileEntry? {
            val wanted = pdfpcPath(path).substringAfterLast('/')
            return files.list(path.substringBeforeLast('/')).firstOrNull { e -> !e.isDir && e.name.equals(wanted, ignoreCase = true) }
        }

        override fun sameAs(other: DocSource) = other is Tree && other.uri == uri

        override fun stamp(context: Context): DocStamp? = content.stamp(context)
    }

    /**
     * PDF on a saved server ([serverId]), read and written through SFTP. A **copy** stays on the tablet
     * (`files/remote-docs/`, not in the cache the system may clear) with the `.pdfpc` next to it and the date
     * and size read from the server: without the server you work on it, and the tab shows it ([offline]).
     * Saving goes only to the server (user's choice): without it, nothing is saved. [fromSession]: reopened
     * from the session or a workspace, it starts from the copy without waiting for the network; the server
     * check comes later (`MainActivity.checkDisk`).
     */
    class Remote(val serverId: String, val path: String, fromSession: Boolean = false) : DocSource {
        override val displayName: String get() = path.substringAfterLast('/')

        // Created at first use (on the device): so the source can be created in JVM tests too.
        private val offlineState by lazy { MainState<Offline?>(null) }

        /** Why you are working on the tablet copy (server unreachable, key to unlock…), or null. */
        val offline: Offline? get() = offlineState.value

        @Volatile
        private var copyFirst = fromSession

        /** Without the server: next attempt not before this time (ms). */
        @Volatile
        private var nextTry = 0L

        /** Retry at the next check at once (network back, return to the app, tap on the icon). */
        fun retrySoon() {
            nextTry = 0L
        }

        val server: ServerProfile? get() = RemoteDocLinks.server(serverId)

        private fun online() {
            nextTry = 0L
            offlineState.post(null)
        }

        private fun offline(reason: Offline) {
            nextTry = System.currentTimeMillis() + RETRY_MS
            offlineState.post(reason)
        }

        /**
         * Runs [block] on the server; connection failures (login, network, session dropped) become
         * [Unreachable] and the [offline] state; file errors (missing, permissions) pass through as they are.
         */
        private fun <T> remote(block: (RemoteFiles) -> T): T =
            RemoteDocLinks.use(serverId) { f ->
                try {
                    if (f == null) throw Unreachable(Offline.NO_SERVER)
                    block(f).also { online() }
                } catch (e: Unreachable) {
                    offline(e.reason)
                    throw e
                } catch (e: Exception) {
                    Log.w("DocSource", "remote $path: $e", e)
                    // A file error only if the connection is still up: otherwise it is the network.
                    if (!RemoteFiles.isConnectionError(e) && f?.link?.isConnected != false) throw e
                    val reason = if (e is SshLink.Failure) Offline.of(e) else Offline.NETWORK
                    offline(reason)
                    throw Unreachable(reason)
                }
            }

        override fun copyTo(context: Context, dest: File) = copyTo(context, dest, fallback = true)

        /**
         * Downloads the PDF (and the `.pdfpc`, if any) into the tablet copy and copies it into [dest]. Without
         * the server: with [fallback] the tablet copy is used if there is one; without it (reload: the server's
         * version is wanted) error.
         */
        fun copyTo(context: Context, dest: File, fallback: Boolean) {
            val copy = RemoteCopy(context, serverId, path)
            if (copyFirst && fallback && copy.pdf.isFile) {
                copyFirst = false
                copy.pdf.copyTo(dest, overwrite = true)
                return
            }
            copyFirst = false
            try {
                remote { f ->
                    // Date read first: a change during the download will show at the next check.
                    val (size, modified) = f.stat(path)
                    val notesPath = pdfpcPath(path)
                    val tmp = File(copy.dir, copy.pdf.name + ".part")
                    val notesTmp = File(copy.dir, copy.pdfpc.name + ".part")
                    try {
                        f.download(path, tmp)
                        // Notes that do not exist: no attempt (the common case); a network error instead stops
                        // everything, and the notes copied before stay.
                        val notes = f.exists(notesPath)
                        if (notes) f.download(notesPath, notesTmp)
                        if (!tmp.renameTo(copy.pdf)) error("rename ${copy.pdf}")
                        if (notes) notesTmp.renameTo(copy.pdfpc) else copy.pdfpc.delete()
                        copy.writeStamp(DocStamp(size, modified))
                    } finally {
                        tmp.delete()
                        notesTmp.delete()
                    }
                }
            } catch (e: Unreachable) {
                val label = server?.label ?: "?"
                if (!fallback) throw UserFacingException(R.string.remote_doc_unreachable_why, label, e.reason.describeShort(context))
                if (!copy.pdf.isFile) {
                    if (e.reason == Offline.NO_SERVER) throw UserFacingException(R.string.remote_doc_no_server)
                    throw UserFacingException(R.string.remote_doc_unreachable, label)
                }
            }
            copy.pdf.copyTo(dest, overwrite = true)
        }

        override fun overwriteWith(context: Context, src: File) {
            val copy = RemoteCopy(context, serverId, path)
            try {
                remote { f -> f.upload(src, path) }
            } catch (e: Unreachable) {
                throw UserFacingException(R.string.remote_doc_not_saved, server?.label ?: "?", e.reason.describeShort(context))
            }
            // Uploaded: the save is done. The tablet copy is the last version written to the server; if the date can
            // no longer be read (connection dropped right after), the copy stays without it and gets it at the first
            // successful check (`MainActivity.checkDisk`).
            src.copyTo(copy.pdf, overwrite = true)
            val stamp = runCatching { remote { f -> f.stat(path) } }.getOrNull()
            if (stamp != null) copy.writeStamp(DocStamp(stamp.first, stamp.second)) else copy.deleteStamp()
        }

        override fun readPdfpc(context: Context): String? =
            RemoteCopy(context, serverId, path).pdfpc.takeIf { it.isFile }?.readText()

        /** On the server (like the PDF, only there); then the tablet copy. */
        override fun writePdfpc(context: Context, text: String) {
            val copy = RemoteCopy(context, serverId, path)
            try {
                remote { f -> f.write(pdfpcPath(path), text) }
            } catch (e: Unreachable) {
                throw UserFacingException(R.string.remote_doc_not_saved, server?.label ?: "?", e.reason.describeShort(context))
            }
            copy.pdfpc.writeText(text)
        }

        override fun sameAs(other: DocSource): Boolean {
            if (other !is Remote || other.path != path) return false
            if (other.serverId == serverId) return true
            val a = server
            val b = other.server
            return a != null && b != null && a.sameAccount(b)
        }

        /** Date and size on the server (`stat`); without the server null, retried only every [RETRY_MS]. */
        override fun stamp(context: Context): DocStamp? {
            if (offline != null && System.currentTimeMillis() < nextTry) return null
            return runCatching { remote { f -> f.stat(path) }.let { (size, modified) -> DocStamp(size, modified) } }.getOrNull()
        }

        /** Date and size on the server of the version in the tablet copy. */
        fun copyStamp(context: Context): DocStamp? = RemoteCopy(context, serverId, path).readStamp()

        /** Removes the tablet copy (tab closed by the user). */
        fun deleteCopy(context: Context) = RemoteCopy(context, serverId, path).delete()

        /** Server unreachable for reason [reason]. */
        class Unreachable(val reason: Offline) : IOException(reason.name)

        private companion object {
            const val RETRY_MS = 30_000L
        }
    }

    /** Why a remote PDF cannot be read from the server; [message]: error on opening without a copy. */
    enum class Offline(val message: Int, private val short: Int) {
        NETWORK(R.string.remote_doc_unreachable, R.string.remote_doc_why_network),
        KEY_LOCKED(R.string.remote_doc_unreachable, R.string.remote_doc_why_key),
        PASSWORD_LOCKED(R.string.remote_doc_unreachable, R.string.remote_doc_why_saved_password),
        NEEDS_PASSWORD(R.string.remote_doc_unreachable, R.string.remote_doc_why_password),
        KEY_INVALID(R.string.remote_doc_unreachable, R.string.remote_doc_why_key_invalid),
        HOST_KEY_CHANGED(R.string.remote_doc_unreachable, R.string.remote_doc_why_host_key),
        NO_SERVER(R.string.remote_doc_no_server, R.string.remote_doc_why_no_server),
        ;

        fun describeShort(context: Context): String = context.getString(short)

        companion object {
            fun of(e: SshLink.Failure) = when (e) {
                is SshLink.Failure.NeedsUnlock -> KEY_LOCKED
                is SshLink.Failure.PasswordLocked -> PASSWORD_LOCKED
                is SshLink.Failure.NeedsPassword -> NEEDS_PASSWORD
                is SshLink.Failure.KeyInvalid -> KEY_INVALID
                is SshLink.Failure.HostKeyChanged -> HOST_KEY_CHANGED
                is SshLink.Failure.Network -> NETWORK
            }
        }
    }

    companion object {
        /** `.pdfpc` notes next to the PDF [path]: same name without the extension. */
        fun pdfpcPath(path: String): String {
            val name = path.substringAfterLast('/')
            return path.dropLast(name.length) + name.substringBeforeLast('.', name) + ".pdfpc"
        }

        /**
         * Chooses the best source for [uri]: if it matches a readable local file (needs the "all files access"
         * permission) the file is used, so notes and saving work.
         */
        fun fromUri(context: Context, uri: Uri): DocSource {
            localFileOf(uri)?.takeIf { it.isFile && it.canRead() }?.let { return Local(it) }
            return Content(uri, displayNameOf(context.contentResolver, uri) ?: context.getString(R.string.document_untitled))
        }

        private fun localFileOf(uri: Uri): File? = when {
            uri.scheme == ContentResolver.SCHEME_FILE -> uri.path?.let(::File)
            uri.authority == EXTERNAL_STORAGE_AUTHORITY -> runCatching {
                val docId = DocumentsContract.getDocumentId(uri)
                val volume = docId.substringBefore(':')
                val path = docId.substringAfter(':', "")
                val root = if (volume.equals("primary", ignoreCase = true)) {
                    Environment.getExternalStorageDirectory()
                } else {
                    File("/storage/$volume")
                }
                File(root, path)
            }.getOrNull()
            else -> null
        }

        private fun displayNameOf(resolver: ContentResolver, uri: Uri): String? = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()

        /** Folder of the document, to start the system picker from there. */
        fun parentDocumentUri(uri: Uri): Uri? = runCatching {
            if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
            val docId = DocumentsContract.getDocumentId(uri)
            val parent = docId.substringBeforeLast('/', docId.substringBefore(':') + ":")
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, parent)
        }.getOrNull()

        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}

/** Size and modification time of a file: if they change, the file changed. */
data class DocStamp(val size: Long, val modified: Long)

/** Name without extension, for the tabs. */
val DocSource.title: String get() = displayName.substringBeforeLast('.', displayName)

/**
 * Tablet copy of a remote PDF: `<hash>.pdf`, `<hash>.pdfpc` and `<hash>.stamp` (size and date on the
 * server of the copied version) in `files/remote-docs/`. The name comes from server and path.
 */
class RemoteCopy(context: Context, serverId: String, path: String) {
    val dir = File(context.filesDir, "remote-docs").apply { mkdirs() }
    private val key = keyOf(serverId, path)
    val pdf = File(dir, "$key.pdf")
    val pdfpc = File(dir, "$key.pdfpc")
    private val stamp = File(dir, "$key.stamp")

    fun writeStamp(s: DocStamp) = stamp.writeText("${s.size} ${s.modified}")

    fun deleteStamp() = stamp.delete()

    fun readStamp(): DocStamp? = runCatching {
        val (size, modified) = stamp.readText().trim().split(' ')
        DocStamp(size.toLong(), modified.toLong())
    }.getOrNull()

    fun delete() {
        pdf.delete()
        pdfpc.delete()
        stamp.delete()
    }

    companion object {
        fun keyOf(serverId: String, path: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$serverId\n$path".toByteArray())
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}
