package it.presenter.app.remote

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved server: a terminal or a remote IDE opens in two taps. [dir] is the starting folder (empty =
 * home); [tmux]: terminals live in tmux sessions, resumed after a disconnection.
 */
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val dir: String = "",
    val tmux: Boolean = false,
) {
    val label get() = name.ifBlank { "$user@$host" }

    /** Starting folder on the server: the profile's (absolute, inside the home or with "~"), or [home]. */
    fun startDir(home: String): String {
        val d = dir.trim()
        val h = home.trimEnd('/')
        return when {
            d.isEmpty() || d == "~" -> home
            d.startsWith("~/") -> "$h/" + d.removePrefix("~/")
            d.startsWith("/") -> d
            else -> "$h/$d"
        }
    }

    /** Same user on the same server (even with two different profiles). */
    fun sameAccount(other: ServerProfile) = host.equals(other.host, ignoreCase = true) && port == other.port && user == other.user

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port).put("user", user).put("dir", dir).put("tmux", tmux)

    companion object {
        fun fromJson(o: JSONObject): ServerProfile? = runCatching {
            ServerProfile(
                id = o.getString("id"), name = o.optString("name"), host = o.getString("host"),
                port = o.optInt("port", 22), user = o.getString("user"), dir = o.optString("dir"),
                tmux = o.optBoolean("tmux", false),
            )
        }.getOrNull()

        fun listToJson(list: List<ServerProfile>) = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(json: String?): List<ServerProfile> = runCatching {
            val a = JSONArray(json ?: return emptyList())
            (0 until a.length()).mapNotNull { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
