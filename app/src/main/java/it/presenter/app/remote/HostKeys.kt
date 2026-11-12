package it.presenter.app.remote

import com.jcraft.jsch.JSch
import java.io.File

/**
 * Server keys remembered in `known_hosts` (OpenSSH format, handled by JSch): servers can be forgotten
 * one at a time, e.g. after a reinstall.
 */
object HostKeys {
    /** Name under which JSch records the server: `host` on port 22, `[host]:port` otherwise. */
    private fun name(server: ServerProfile) = if (server.port == 22) server.host else "[${server.host}]:${server.port}"

    private fun repository(file: File) = JSch().apply { setKnownHosts(file.path) }.hostKeyRepository

    /** The tablet remembers a key for [server]. */
    fun has(file: File, server: ServerProfile): Boolean {
        if (!file.exists()) return false
        val repo = runCatching { repository(file) }.getOrNull() ?: return false
        return !repo.getHostKey(name(server), null).isNullOrEmpty()
    }

    /**
     * Forgets the keys of [server] (and of any other profile with the same address and port): at the next
     * connection its key is accepted again.
     */
    fun forget(file: File, server: ServerProfile) {
        if (!file.exists()) return
        repository(file).remove(name(server), null)
    }
}
