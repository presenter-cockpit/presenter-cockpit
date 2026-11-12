package it.presenter.app.session

import android.content.Context
import android.net.Uri
import it.presenter.app.doc.DocSource
import it.presenter.app.doc.DocStamp
import it.presenter.app.doc.ElementCodec
import it.presenter.app.doc.PageSlot
import it.presenter.app.ink.PageElement
import it.presenter.app.tabs.WorkTabStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Session saved to file, to resume exactly where you left off even if the system kills the app: open
 * documents (in order, with the active one), page and notes of each, zoom and position of the sheet,
 * annotations not yet saved in the PDF, unsaved page order, work tabs.
 *
 * In the app's private storage: `session/session.json` plus one `.ink` file per document with
 * annotations to save. Everything is written into a temporary folder that then replaces the previous
 * one, so an interruption halfway leaves the previously saved session intact.
 */
object SessionStore {
    private const val VERSION = 1

    class SavedDocument(
        val source: DocSource,
        val page: Int,
        val notesVisible: Boolean,
        /** Annotations not saved in the PDF; null if the document was already saved. */
        val unsaved: Map<Int, List<PageElement>>?,
        /** Inverted colors on the projector. */
        val inverted: Boolean = false,
        /** Page order changed from the overview and not saved ([PresenterDocument.layout]). */
        val layout: List<PageSlot>? = null,
        /** Size and date of the file when [layout] applied: if they change, the order is not applied again. */
        val layoutStamp: DocStamp? = null,
        /** The .pdfpc edited here (notes, hidden slides, bookmarks) and not saved yet, as JSON; null if none. */
        val pdfpc: String? = null,
    )

    class Saved(
        val documents: List<SavedDocument>,
        val active: Int,
        val viewport: FloatArray?,
        /** Code, terminal and browser tabs ([WorkTabStore]) and the active one (-1: a PDF). */
        val work: List<JSONObject> = emptyList(),
        val activeWork: Int = -1,
        /** Order of the tabs in the bar; empty in older sessions (PDFs first). */
        val order: List<String> = emptyList(),
    )

    /**
     * Session documents that could not be reopened at launch (file moved, SD card removed, permission
     * expired) but have unsaved annotations: they stay in the session, so that work is not lost and it is
     * retried at the next launch.
     */
    val pending = ArrayList<SavedDocument>()

    /** State to write, taken on the main thread (annotations are immutable). */
    class Snapshot internal constructor(internal val json: JSONObject, internal val inks: Map<String, Map<Int, List<PageElement>>>)

    fun snapshot(session: PresenterSession): Snapshot {
        val docs = session.documents.map { doc ->
            SavedDocument(
                doc.source, doc.pageIndex, doc.notesVisible, doc.allElements().takeIf { doc.isDirty }, doc.inverted,
                doc.layout, doc.diskStamp.takeIf { doc.layout != null }, doc.unsavedPdfpc,
            )
        }
        // Unavailable documents with unsaved annotations go at the end (they never become active).
        return snapshotOf(
            docs + pending.filter { it.unsaved != null },
            session.documents.indexOf(session.current),
            session.viewport.saveState(),
            session.workTabs.map(WorkTabStore::toJson),
            session.workTabs.indexOf(session.activeWork),
            session.orderKeys(),
        )
    }

    /** State to write from its parts: what [read] gives back. */
    internal fun snapshotOf(
        documents: List<SavedDocument>,
        active: Int,
        viewport: FloatArray,
        work: List<JSONObject>,
        activeWork: Int,
        order: List<String>,
    ): Snapshot {
        val docs = JSONArray()
        val inks = HashMap<String, Map<Int, List<PageElement>>>()
        documents.forEachIndexed { i, s ->
            val o = sourceToJson(s.source).put("page", s.page).put("notes", s.notesVisible).put("inverted", s.inverted)
            s.layout?.let { o.put("layout", layoutToJson(it)) }
            s.layoutStamp?.let { st -> o.put("layoutStamp", JSONArray().put(st.size).put(st.modified)) }
            s.pdfpc?.let { o.put("pdfpc", it) }
            s.unsaved?.let {
                val name = "doc-$i.ink"
                o.put("ink", name)
                inks[name] = it
            }
            docs.put(o)
        }
        val json = JSONObject()
            .put("version", VERSION)
            .put("active", active)
            .put("viewport", JSONArray().apply { viewport.forEach { put(it.toDouble()) } })
            .put("documents", docs)
            .put("work", JSONArray(work))
            .put("activeWork", activeWork)
            .put("order", JSONArray(order))
        return Snapshot(json, inks)
    }

    /**
     * Writes the session (call from a worker thread). Synchronized: a recreated activity has its own
     * documents thread, and two writes at once would clash in the temporary folder.
     */
    @Synchronized
    fun write(context: Context, snapshot: Snapshot) {
        val dir = dir(context)
        val tmp = File(context.filesDir, "session.tmp").apply { deleteRecursively(); mkdirs() }
        for ((name, pages) in snapshot.inks) writeInk(File(tmp, name), pages)
        writeSynced(File(tmp, "session.json"), snapshot.json.toString().toByteArray())
        val old = File(context.filesDir, "session.old").apply { deleteRecursively() }
        if (dir.exists() && !dir.renameTo(old)) dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw IOException("Cannot replace the saved session")
        old.deleteRecursively()
    }

    /** Deletes the saved session (setting turned off). Call from a worker thread. */
    @Synchronized
    fun clear(context: Context) {
        dir(context).deleteRecursively()
        File(context.filesDir, "session.tmp").deleteRecursively()
        File(context.filesDir, "session.old").deleteRecursively()
    }

    /** Saved session, or null if there is none (or it is unreadable). Call from a worker thread. */
    fun read(context: Context): Saved? = runCatching {
        // App killed between the two renames of [write]: the complete session is still in "session.old".
        val dir = dir(context).takeIf { File(it, "session.json").isFile }
            ?: File(context.filesDir, "session.old")
        val file = File(dir, "session.json").takeIf { it.isFile } ?: return null
        val json = JSONObject(file.readText())
        if (json.optInt("version") != VERSION) return null
        val docs = json.optJSONArray("documents") ?: JSONArray()
        val documents = (0 until docs.length()).mapNotNull { i ->
            val o = docs.getJSONObject(i)
            val source = sourceFromJson(o) ?: return@mapNotNull null
            // A corrupted annotations file loses only that document's annotations, not the session.
            val unsaved = o.optString("ink", "").takeIf { it.isNotEmpty() }?.let { runCatching { readInk(File(dir, it)) }.getOrNull() }
            val layout = o.optJSONArray("layout")?.let { runCatching { layoutFromJson(it) }.getOrNull() }
            val stamp = o.optJSONArray("layoutStamp")?.let { a -> runCatching { DocStamp(a.getLong(0), a.getLong(1)) }.getOrNull() }
            val pdfpc = o.optString("pdfpc", "").takeIf { it.isNotEmpty() }
            SavedDocument(source, o.optInt("page", 0), o.optBoolean("notes", false), unsaved, o.optBoolean("inverted", false), layout, stamp, pdfpc)
        }
        val viewport = json.optJSONArray("viewport")?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
        val work = json.optJSONArray("work")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        val order = json.optJSONArray("order")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
        Saved(documents, json.optInt("active", 0), viewport, work, json.optInt("activeWork", -1), order)
    }.getOrNull()

    private fun dir(context: Context) = File(context.filesDir, "session")

    internal fun sourceToJson(source: DocSource): JSONObject = when (source) {
        is DocSource.Local -> JSONObject().put("type", "file").put("path", source.file.absolutePath)
        is DocSource.Content -> JSONObject().put("type", "uri").put("uri", source.uri.toString()).put("name", source.displayName)
        is DocSource.Tree -> JSONObject().put("type", "tree").put("path", source.path).put("uri", source.uri.toString()).put("name", source.displayName)
        is DocSource.Remote -> JSONObject().put("type", "sftp").put("server", source.serverId).put("path", source.path)
    }

    internal fun sourceFromJson(o: JSONObject): DocSource? = when (o.optString("type")) {
        "file" -> DocSource.Local(File(o.getString("path")))
        "uri" -> DocSource.Content(Uri.parse(o.getString("uri")), o.optString("name", ""))
        "tree" -> DocSource.Tree(o.getString("path"), Uri.parse(o.getString("uri")), o.optString("name", ""))
        // Reopened from the session or a workspace: start from the tablet copy.
        "sftp" -> DocSource.Remote(o.getString("server"), o.getString("path"), fromSession = true)
        else -> null
    }

    /** A page of the file: its index; a blank page: size in points and label. */
    internal fun layoutToJson(layout: List<PageSlot>): JSONArray = JSONArray().apply {
        for (slot in layout) when (slot) {
            is PageSlot.Page -> put(slot.index)
            is PageSlot.Blank -> put(
                JSONObject().put("w", slot.width.toDouble()).put("h", slot.height.toDouble()).apply { slot.label?.let { put("label", it) } },
            )
        }
    }

    internal fun layoutFromJson(a: JSONArray): List<PageSlot> = (0 until a.length()).map { i ->
        val o = a.optJSONObject(i)
        if (o == null) {
            PageSlot.Page(a.getInt(i))
        } else {
            PageSlot.Blank(-1, if (o.has("label")) o.getString("label") else null, o.getDouble("w").toFloat(), o.getDouble("h").toFloat())
        }
    }

    /**
     * Writes [bytes] and waits until they are on storage: renamed without it, after a sudden power-off the file
     * could be there but empty (and the session lost).
     */
    internal fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { o ->
            o.write(bytes)
            o.fd.sync()
        }
    }

    private fun writeInk(file: File, pages: Map<Int, List<PageElement>>) {
        val out = FileOutputStream(file)
        DataOutputStream(out.buffered()).use { d ->
            d.writeInt(pages.size)
            for ((page, strokes) in pages) {
                val encoded = strokes.mapNotNull(ElementCodec::encode)
                d.writeInt(page)
                d.writeInt(encoded.size)
                for (bytes in encoded) {
                    d.writeInt(bytes.size)
                    d.write(bytes)
                }
            }
            d.flush()
            out.fd.sync()
        }
    }

    /** Unsaved annotations of a document; null if the file is missing (those in the PDF stay). */
    private fun readInk(file: File): Map<Int, List<PageElement>>? {
        if (!file.isFile) return null
        DataInputStream(file.inputStream().buffered()).use { d ->
            val result = HashMap<Int, List<PageElement>>()
            repeat(d.readInt()) {
                val page = d.readInt()
                result[page] = (0 until d.readInt()).mapNotNull {
                    // A damaged size must not ask for gigabytes of memory: never more than the file.
                    val size = d.readInt()
                    if (size !in 0..file.length()) throw IOException("Damaged annotations file")
                    ElementCodec.decode(ByteArray(size).also(d::readFully))
                }
            }
            return result
        }
    }
}
