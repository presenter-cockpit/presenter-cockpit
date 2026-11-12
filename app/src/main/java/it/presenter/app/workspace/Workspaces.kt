package it.presenter.app.workspace

import android.content.Context
import it.presenter.app.doc.DocSource
import it.presenter.app.session.PresenterSession
import it.presenter.app.session.SessionStore
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** A PDF of a workspace, with the page it was on. */
class WorkspaceDoc(val source: DocSource, val page: Int, val inverted: Boolean = false)

/**
 * Workspace: PDFs (with their page), code, terminal and browser tabs, and the presentation end time
 * proposed by the timer ([presentationEndMinutes], minutes since midnight, or -1).
 */
class Workspace(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val docs: List<WorkspaceDoc> = emptyList(),
    val active: Int = 0,
    val work: List<JSONObject> = emptyList(),
    val activeWork: Int = -1,
    val presentationEndMinutes: Int = -1,
    /** Order of the tabs in the bar ([PresenterSession.orderKeys]); empty = PDFs first. */
    val order: List<String> = emptyList(),
    /**
     * Inverted colors of the last toggled tab, per kind ("pdf", "terminal", "editor", "browser"): new tabs
     * of that kind start that way (user's request).
     */
    val invertedByKind: Map<String, Boolean> = emptyMap(),
    /** Font size (sp) of the last resized terminal or editor: new tabs of that kind start with it. */
    val fontByKind: Map<String, Float> = emptyMap(),
    /**
     * Where the previews of what the audience sees are (moved by hand): fractions 0..1 of the free space,
     * horizontal and vertical; null = bottom corner on the side of the tool rail.
     */
    val previewPosition: Pair<Float, Float>? = null,
) {
    fun copy(
        name: String = this.name,
        docs: List<WorkspaceDoc> = this.docs,
        active: Int = this.active,
        work: List<JSONObject> = this.work,
        activeWork: Int = this.activeWork,
        presentationEndMinutes: Int = this.presentationEndMinutes,
        order: List<String> = this.order,
        invertedByKind: Map<String, Boolean> = this.invertedByKind,
        fontByKind: Map<String, Float> = this.fontByKind,
        previewPosition: Pair<Float, Float>? = this.previewPosition,
    ) = Workspace(id, name, docs, active, work, activeWork, presentationEndMinutes, order, invertedByKind, fontByKind, previewPosition)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("active", active).put("activeWork", activeWork).put("lessonEnd", presentationEndMinutes)
        .put("docs", JSONArray().apply { docs.forEach { put(SessionStore.sourceToJson(it.source).put("page", it.page).put("inverted", it.inverted)) } })
        .put("work", JSONArray().apply { work.forEach { put(it) } })
        .put("order", JSONArray(order))
        .put("invertedByKind", JSONObject().apply { invertedByKind.forEach { (k, v) -> put(k, v) } })
        .put("fontByKind", JSONObject().apply { fontByKind.forEach { (k, v) -> put(k, v.toDouble()) } })
        .apply { previewPosition?.let { (x, y) -> put("previewX", x.toDouble()).put("previewY", y.toDouble()) } }

    companion object {
        fun fromJson(o: JSONObject): Workspace? = runCatching {
            val docs = o.optJSONArray("docs") ?: JSONArray()
            val work = o.optJSONArray("work") ?: JSONArray()
            Workspace(
                id = o.getString("id"),
                name = o.getString("name"),
                docs = (0 until docs.length()).mapNotNull { i ->
                    val d = docs.getJSONObject(i)
                    SessionStore.sourceFromJson(d)?.let { WorkspaceDoc(it, d.optInt("page"), d.optBoolean("inverted")) }
                },
                active = o.optInt("active"),
                work = (0 until work.length()).map { work.getJSONObject(it) },
                activeWork = o.optInt("activeWork", -1),
                presentationEndMinutes = o.optInt("lessonEnd", -1),
                order = o.optJSONArray("order")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
                invertedByKind = o.optJSONObject("invertedByKind")?.let { j -> j.keys().asSequence().associateWith { j.optBoolean(it) } }.orEmpty(),
                fontByKind = o.optJSONObject("fontByKind")?.let { j -> j.keys().asSequence().associateWith { j.optDouble(it).toFloat() } }.orEmpty(),
                previewPosition = if (o.has("previewX") && o.has("previewY")) {
                    o.optDouble("previewX").toFloat().coerceIn(0f, 1f) to o.optDouble("previewY").toFloat().coerceIn(0f, 1f)
                } else null,
            )
        }.getOrNull()
    }
}

/** Workspaces saved in `files/workspaces.json` (atomic write). */
object WorkspaceStore {
    private fun file(context: Context) = File(context.filesDir, "workspaces.json")

    fun read(context: Context): List<Workspace> = runCatching {
        val a = JSONArray(file(context).readText())
        (0 until a.length()).mapNotNull { Workspace.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun write(context: Context, list: List<Workspace>) {
        val f = file(context)
        val tmp = File(f.parentFile, "workspaces.json.tmp")
        // Synced before the rename: after a sudden power-off the list could otherwise be left empty.
        SessionStore.writeSynced(tmp, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString().toByteArray())
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
