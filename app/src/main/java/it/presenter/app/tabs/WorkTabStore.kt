package it.presenter.app.tabs

import it.presenter.app.remote.ServerProfile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Work tabs in the session (and in workspaces): kind, server, folder, open files with unsaved text and
 * position, font size, web address, terminal folder. Connections restart on reopening; the terminal
 * resumes its tmux session or opens a new shell in the last folder.
 */
object WorkTabStore {
    fun toJson(tab: WorkTab): JSONObject {
        val o = JSONObject().put("id", tab.id).put("font", tab.fontSize.toDouble()).put("scale", tab.projectorScale.toDouble()).put("inverted", tab.inverted)
        when (tab) {
            is TerminalTab -> {
                o.put("type", "terminal")
                // Local: marked as such (terminals saved without the mark always have a server).
                if (tab.server != null) o.put("server", tab.server.toJson()) else o.put("local", true)
                tab.cwd?.let { o.put("cwd", it) }
            }
            is EditorTab -> {
                o.put("type", "editor").put("root", tab.root).put("hidden", tab.showHidden).put("treeWidth", tab.treeWidth.toDouble())
                    .put("expanded", JSONArray(tab.expandedDirs))
                tab.lastDir?.let { o.put("lastDir", it) }
                tab.server?.let { o.put("server", it.toJson()) }
                o.put("files", filesToJson(tab.files)).put("active", tab.files.indexOf(tab.active))
            }
            is BrowserTab -> o.put("type", "browser").put("url", tab.url).put("scroll", tab.scrollFraction.toDouble())
        }
        return o
    }

    /**
     * Files open in an IDE: path, position, preview and, if any, the unsaved changes (never lost: they go
     * into the session). The disk text is reread on reopening.
     */
    fun filesToJson(files: List<OpenFile>): JSONArray = JSONArray().apply {
        for (f in files) {
            val o = JSONObject().put("path", f.path).put("cursor", f.cursor).put("line", f.firstVisibleLine).put("preview", f.preview)
            if (f.isDirty) o.put("unsaved", f.text)
            put(o)
        }
    }

    /** Open files of a saved IDE and the index of the active one; also from the older format (a single file). */
    fun editorFiles(o: JSONObject): Pair<List<OpenFile>, Int> {
        o.optJSONArray("files")?.let { a ->
            return (0 until a.length()).mapNotNull { i -> runCatching { fileFromJson(a.getJSONObject(i)) }.getOrNull() } to o.optInt("active")
        }
        val path = o.optString("open").ifEmpty { null } ?: return emptyList<OpenFile>() to 0
        val old = JSONObject().put("path", path).put("cursor", o.optInt("cursor")).put("line", o.optInt("line"))
        if (o.has("unsaved")) old.put("unsaved", o.getString("unsaved"))
        return listOf(fileFromJson(old)) to 0
    }

    private fun fileFromJson(o: JSONObject): OpenFile {
        val unsaved = if (o.has("unsaved")) o.getString("unsaved") else null
        // With changes: the text is that, the disk is read later. Without: everything is read later.
        return OpenFile(o.getString("path"), unsaved.orEmpty(), savedText = null, loaded = unsaved != null, preview = o.optBoolean("preview")).apply {
            cursor = o.optInt("cursor").coerceIn(0, unsaved?.length ?: Int.MAX_VALUE)
            firstVisibleLine = o.optInt("line")
        }
    }

    /** Tab from [o], not started yet (whoever opens it does that). */
    fun fromJson(o: JSONObject, knownHosts: File, servers: List<ServerProfile> = emptyList()): WorkTab? = runCatching {
        val id = o.optString("id").ifEmpty { null }
        // The server as configured now (if it still exists), not the copy saved with the tab.
        val saved = o.optJSONObject("server")?.let(ServerProfile::fromJson)
        val server = saved?.let { s -> servers.firstOrNull { it.id == s.id } ?: s }
        val tab: WorkTab = when (o.getString("type")) {
            "terminal" -> {
                // Local terminal in a distribution that does not have it (workspace brought from another one): skipped.
                if (o.optBoolean("local") && !LocalShell.available) return null
                TerminalTab(if (o.optBoolean("local")) null else server ?: return null, knownHosts, id)
                    .also { it.cwd = o.optString("cwd").ifEmpty { null } }
            }
            "editor" -> {
                val root = o.optString("root")
                // The tree starts again from the IDE's root (user's request), also remotely ("IDE here"); without one, from
                // the folder of the server configuration (computed on connecting).
                val t = if (server != null) EditorTab.remote(server, knownHosts, root, id) else EditorTab.local(root, id)
                t.showHidden = o.optBoolean("hidden", false)
                t.treeWidth = o.optDouble("treeWidth", EditorTab.DEFAULT_TREE_WIDTH.toDouble()).toFloat()
                o.optJSONArray("expanded")?.let { a -> t.restoreExpanded((0 until a.length()).map(a::getString)) }
                t.restoreLastDir(o.optString("lastDir").ifEmpty { null })
                val (files, active) = editorFiles(o)
                t.restoreFiles(files, active)
                t
            }
            "browser" -> BrowserTab(o.getString("url"), id).also { it.scrollFraction = o.optDouble("scroll", 0.0).toFloat() }
            else -> return null
        }
        tab.fontSize = o.optDouble("font", WorkTab.DEFAULT_FONT_SP.toDouble()).toFloat().coerceIn(WorkTab.FONT_RANGE)
        tab.inverted = o.optBoolean("inverted", false)
        tab.projectorScale = o.optDouble("scale", WorkTab.DEFAULT_PROJECTOR_SCALE.toDouble()).toFloat()
        tab
    }.getOrNull()
}
