package it.presenter.app.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import it.presenter.app.R
import it.presenter.app.UserFacingException
import it.presenter.app.doc.DocStamp
import it.presenter.app.remote.FileAccess
import it.presenter.app.remote.FileEntry
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Files of a folder **granted** by the user with the system picker (`ACTION_OPEN_DOCUMENT_TREE`): the
 * way to read and write the tablet's folders without access to all files ("store" distribution).
 * Android gives a URI for the folder ([tree]) and an id for each document inside; for the rest of the
 * app paths stay strings with "/": `<tree>/sub/folder/file.c`. The folder URI has its inner "/"
 * encoded (`%2F`), so name and parent are derived as for real files. Document ids are found by listing
 * the folders and kept in memory.
 */
class TreeFiles(context: Context, val tree: Uri) : FileAccess {
    private val resolver: ContentResolver = context.contentResolver
    override val root: String = tree.toString()
    private val ids = ConcurrentHashMap<String, String>().apply { put(root, DocumentsContract.getTreeDocumentId(tree)) }

    /** Readable name of the granted folder (for titles and breadcrumbs). */
    val rootName: String by lazy { readableName(resolver, tree) }

    fun uriOf(path: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, idOf(path))

    private fun idOf(path: String): String {
        ids[path]?.let { return it }
        if (!path.startsWith("$root/")) throw UserFacingException(R.string.error_unreadable)
        list(path.substringBeforeLast('/'))
        return ids[path] ?: throw UserFacingException(R.string.error_unreadable)
    }

    override fun list(dir: String): List<FileEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, idOf(dir))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        val out = ArrayList<FileEntry>()
        resolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val path = "$dir/$name"
                ids[path] = c.getString(0)
                out += FileEntry(path, name, c.getString(2) == Document.MIME_TYPE_DIR, if (c.isNull(3)) 0 else c.getLong(3))
            }
        } ?: throw UserFacingException(R.string.error_unreadable)
        return FileAccess.sorted(out)
    }

    override fun read(path: String): String {
        val bytes = resolver.openInputStream(uriOf(path))?.use { it.readBytes() } ?: throw UserFacingException(R.string.error_unreadable)
        return FileAccess.decodeUtf8(bytes, path)
    }

    fun readBytesOrNull(path: String): ByteArray? = runCatching {
        resolver.openInputStream(uriOf(path))?.use { it.readBytes() }
    }.getOrNull()

    override fun size(path: String): Long = runCatching {
        resolver.query(uriOf(path), arrayOf(Document.COLUMN_SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
    }.getOrNull() ?: 0L

    override fun stamp(path: String): DocStamp? = runCatching {
        resolver.query(uriOf(path), arrayOf(Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst()) DocStamp(if (c.isNull(0)) 0L else c.getLong(0), if (c.isNull(1)) 0L else c.getLong(1)) else null
        }
    }.getOrNull()

    /**
     * No atomic replacement with documents (renaming does not overwrite): the file is rewritten in one go,
     * with the text already in memory.
     */
    override fun write(path: String, text: String) = writeUri(resolver, uriOf(path), text.toByteArray(Charsets.UTF_8))

    /**
     * Creates the empty file. With a name already in use providers create "name (1)", and with a text type
     * they may append ".txt": hence the name check first and a generic type.
     */
    override fun create(path: String) {
        val dir = path.substringBeforeLast('/')
        val name = path.substringAfterLast('/')
        if (list(dir).any { it.name == name }) throw UserFacingException(R.string.code_file_exists, name)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, idOf(dir))
        val created = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
            ?: throw UserFacingException(R.string.error_not_writable)
        // Some providers change the name anyway: it is set back to the requested one.
        if (readableName(resolver, created) != name) runCatching { DocumentsContract.renameDocument(resolver, created, name) }
        list(dir)
    }

    companion object {
        /** Is it a path inside a granted folder (not a real file)? */
        fun isTreePath(path: String) = path.startsWith("content://")

        /** Granted folder [path] belongs to (the part before the first "/" after "/tree/"). */
        fun treeOf(path: String): Uri = Uri.parse(treeRootOf(path))

        /** Like [treeOf], as text (the folder id has its "/" encoded, so it ends at the first "/"). */
        internal fun treeRootOf(path: String): String {
            val start = path.indexOf("/tree/") + "/tree/".length
            val end = path.indexOf('/', start).let { if (it < 0) path.length else it }
            return path.substring(0, end)
        }

        /** Folders granted so far (persistent permissions). */
        fun granted(context: Context): List<Uri> =
            context.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
                .map { it.uri }

        /** Keeps the permission on folder [tree] after a restart too. */
        fun keep(context: Context, tree: Uri) {
            context.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        fun readableName(resolver: ContentResolver, uri: Uri): String {
            // URI of the granted folder alone ("tree/<id>", no "document/<id>"): ask for its document.
            val docUri = if (uri.pathSegments.size == 2 && uri.pathSegments[0] == "tree") {
                DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            } else uri
            return runCatching {
                resolver.query(docUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: uri.toString()
        }

        /**
         * Path to show: for the tablet storage the real one ("Documents/talk/main.c"), for other providers
         * the name of the granted folder followed by the rest.
         */
        fun displayPath(context: Context, path: String): String {
            val tree = treeOf(path)
            val rest = path.removePrefix(tree.toString())
            val base = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
                ?.takeIf { tree.authority == "com.android.externalstorage.documents" }
                ?.substringAfter(':')
                ?: readableName(context.contentResolver, tree)
            return base + rest
        }

        /**
         * Rewrites document [uri] with [bytes]. "wt" truncates the file; where it is not supported, write in
         * "rw" and truncate by hand ("w" does not truncate on many providers and the tail would remain).
         */
        fun writeUri(resolver: ContentResolver, uri: Uri, bytes: ByteArray) {
            val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            if (out != null) {
                out.use { it.write(bytes) }
                return
            }
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw UserFacingException(R.string.error_not_writable)
            pfd.use {
                FileOutputStream(it.fileDescriptor).use { o ->
                    o.write(bytes)
                    o.channel.truncate(bytes.size.toLong())
                }
            }
        }
    }
}
