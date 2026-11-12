package it.presenter.app.session

import it.presenter.app.doc.DocSource
import it.presenter.app.settings.Prefs
import org.json.JSONArray

/** PDFs opened lately, the most recent first, for the start screen ([Prefs.recentDocs], as in the session). */
object RecentDocs {
    const val MAX = 8

    fun read(prefs: Prefs): List<DocSource> = runCatching {
        val a = JSONArray(prefs.recentDocs ?: return emptyList())
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(SessionStore::sourceFromJson) }
    }.getOrDefault(emptyList())

    /** [source] goes first; the same document further down leaves the list, and so do the oldest beyond [MAX]. */
    fun add(prefs: Prefs, source: DocSource) {
        val list = (listOf(source) + read(prefs).filterNot { it.sameAs(source) }).take(MAX)
        prefs.recentDocs = JSONArray().apply { list.forEach { put(SessionStore.sourceToJson(it)) } }.toString()
    }
}
