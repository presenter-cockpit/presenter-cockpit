package it.presenter.app.remote

import it.presenter.app.doc.DocSource
import java.io.File

/**
 * SSH connections of the PDFs on a server ([DocSource.Remote]): one per server,
 * shared by the PDFs of that server (work tabs each have their own). The session configures it with
 * the saved servers, the `known_hosts` and the way to find a password already given in a tab connected
 * to the same server.
 */
object RemoteDocLinks {
    @Volatile
    private var servers: () -> List<ServerProfile> = { emptyList() }

    @Volatile
    private var knownHosts: File? = null

    @Volatile
    private var passwordOf: (ServerProfile) -> String? = { null }

    private val files = HashMap<String, RemoteFiles>()

    fun configure(servers: () -> List<ServerProfile>, knownHosts: File, passwordOf: (ServerProfile) -> String?) {
        this.servers = servers
        this.knownHosts = knownHosts
        this.passwordOf = passwordOf
    }

    fun server(id: String): ServerProfile? = servers().firstOrNull { it.id == id }

    /**
     * Files through SFTP on server [id], or null if the server no longer exists. If the profile changed
     * (host, user…) the connection is recreated. Without its own password it takes the one of a tab already
     * connected to the same server, if any. Prefer [use], which keeps the connection open while needed.
     */
    @Synchronized
    fun files(id: String): RemoteFiles? {
        val server = server(id) ?: return null
        val known = knownHosts ?: return null
        val f = files[id]?.takeIf { it.link.server == server } ?: run {
            close(id)
            RemoteFiles(SshLink(server, known), "").also { files[id] = it }
        }
        // Used again: a pending close no longer applies.
        closeWhenIdle.remove(id)
        if (f.link.password == null) passwordOf(server)?.let { f.link.usePassword(it) }
        return f
    }

    /** Operations in progress per server, and servers to close as soon as they finish. */
    private val busy = HashMap<String, Int>()
    private val closeWhenIdle = HashSet<String>()

    /**
     * Runs [block] on the files of server [id] (null: the server no longer exists); while it runs the
     * connection is not closed, even if the last PDF of that server is closed meanwhile.
     */
    fun <T> use(id: String, block: (RemoteFiles?) -> T): T {
        val f = synchronized(this) { files(id)?.also { busy[id] = (busy[id] ?: 0) + 1 } }
        try {
            return block(f)
        } finally {
            if (f != null) synchronized(this) {
                val n = (busy[id] ?: 1) - 1
                if (n > 0) {
                    busy[id] = n
                } else {
                    busy.remove(id)
                    if (closeWhenIdle.remove(id)) close(id)
                }
            }
        }
    }

    /** Connection of server [id], if already created (to give it the password or unlock it). */
    @Synchronized
    fun linkIfAny(id: String): SshLink? = files[id]?.link

    /** No PDF uses server [id] any more: the connection is closed (as soon as ongoing work finishes). */
    @Synchronized
    fun release(id: String) {
        if (busy.containsKey(id)) closeWhenIdle.add(id) else close(id)
    }

    private fun close(id: String) {
        val f = files.remove(id) ?: return
        Thread {
            f.close()
            f.link.close()
        }.start()
    }
}
