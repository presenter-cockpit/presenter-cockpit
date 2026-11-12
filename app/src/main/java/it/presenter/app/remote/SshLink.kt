package it.presenter.app.remote

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import it.presenter.app.tabs.MainState
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * SSH connection to a [ServerProfile] (JSch). Authentication with the app's key ([SshKeys]) and, if it
 * is missing or the server refuses it, with the password asked to the user (kept only in memory, for
 * reconnections). Server keys: accepted and remembered in [knownHosts] at the first connection; if they
 * change later, the connection is refused. Keepalive every 15 s.
 *
 * The calls block: run them on a worker thread.
 */
class SshLink(val server: ServerProfile, private val knownHosts: File) {
    /** Password for this server, if the user gave it (or unlocked a saved one). */
    @Volatile
    var password: String? = null
        private set

    /** The password comes from [SavedPasswords]: if the server refuses it, it is forgotten. */
    @Volatile
    private var passwordFromStore = false

    /** Encrypted password to save if the connection succeeds (the user asked to remember it). */
    @Volatile
    private var pendingSave: String? = null

    /** Password to use from now on; [saveBlob]: to remember if the login succeeds. */
    fun usePassword(p: String, fromStore: Boolean = false, saveBlob: String? = null) {
        password = p
        passwordFromStore = fromStore
        pendingSave = saveBlob
    }

    @Volatile
    private var session: Session? = null

    /** Tab closed: no more connections (one in progress is closed as soon as it succeeds). */
    @Volatile
    private var closed = false

    sealed class Failure(message: String, cause: Throwable? = null) : Exception(message, cause) {
        /** The app's key must be unlocked with the fingerprint. */
        class NeedsUnlock : Failure("unlock")

        /** There is a saved password for the server: it must be unlocked with the fingerprint. */
        class PasswordLocked : Failure("saved password")

        /** The app's key is no longer valid (screen lock changed): it must be regenerated. */
        class KeyInvalid : Failure("key invalid")

        /** The key is not enough (or missing): the password is needed. */
        class NeedsPassword(cause: Throwable?) : Failure("password", cause)

        /** The server's key changed from the remembered one. */
        class HostKeyChanged(cause: Throwable?) : Failure("host key", cause)

        class Network(cause: Throwable?) : Failure("network", cause)
    }

    val isConnected get() = session?.isConnected == true

    /** Why the server cannot be reached (shown while retrying). */
    enum class NetError { UNKNOWN_HOST, REFUSED, NO_RESPONSE }

    /** Reason of the last attempt that failed on the network, or null (connection dropped, unknown reason). */
    @Volatile
    var lastNetError: NetError? = null
        private set

    /** Connected session (reuses the open one). Throws [Failure] if it cannot connect. */
    @Synchronized
    fun connect(): Session {
        if (closed) throw Failure.Network(null)
        session?.takeIf { it.isConnected }?.let { return it }
        lastNetError = null
        // Password remembered for this server: use it (before the key, which the server evidently does not
        // accept), after the fingerprint.
        if (password == null && SavedPasswords.has(server.id)) throw Failure.PasswordLocked()
        val useKey = SshKeys.exists()
        if (useKey && password == null) {
            when (SshKeys.state()) {
                SshKeys.KeyState.UNLOCKED -> Unit
                SshKeys.KeyState.LOCKED -> throw Failure.NeedsUnlock()
                SshKeys.KeyState.INVALID -> throw Failure.KeyInvalid()
            }
        }
        val jsch = JSch()
        knownHosts.parentFile?.mkdirs()
        if (!knownHosts.exists()) knownHosts.createNewFile()
        jsch.setKnownHosts(knownHosts.path)
        if (useKey) jsch.addIdentity(SshKeys.identity, null)
        val s = jsch.getSession(server.user, server.host, server.port)
        s.setConfig("StrictHostKeyChecking", "ask")
        s.setConfig("PreferredAuthentications", if (password != null) "publickey,keyboard-interactive,password" else "publickey")
        s.userInfo = Prompts(password)
        s.setServerAliveInterval(KEEPALIVE_MS)
        s.serverAliveCountMax = KEEPALIVE_MAX
        try {
            s.connect(CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "connect ${server.user}@${server.host}:${server.port}: $e", e)
            val msg = e.message.orEmpty()
            // Network failure: no answer from the server, or connection dropped (retried automatically).
            // Everything else after the server answered is a failed login: ask for the password instead of
            // retrying forever.
            // Without an answer from the server JSch has no version and getServerVersion itself fails
            // (NullPointerException): it counts as "no answer".
            val serverVersion = runCatching { s.serverVersion }.getOrNull()
            val network = serverVersion == null || msg.contains("timeout", true) ||
                generateSequence(e as Throwable) { it.cause }.any { it is SocketException || it is SocketTimeoutException || it is UnknownHostException }
            throw when {
                msg.contains("HostKey has been changed", true) || msg.contains("reject HostKey", true) -> Failure.HostKeyChanged(e)
                SshKeys.needsAuth(e) -> Failure.NeedsUnlock()
                msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) || !network -> {
                    // Password refused: the saved one is no longer valid, the one to save is not saved.
                    if (passwordFromStore) SavedPasswords.remove(server.id)
                    passwordFromStore = false
                    pendingSave = null
                    // Only the app's key, and the server does not want it: after the login with the password, offer to
                    // install it.
                    if (useKey && password == null) keyRejected = true
                    Failure.NeedsPassword(e)
                }
                else -> {
                    val causes = generateSequence(e as Throwable) { it.cause }
                    lastNetError = when {
                        causes.any { it is UnknownHostException } -> NetError.UNKNOWN_HOST
                        causes.any { it is ConnectException } -> NetError.REFUSED
                        else -> NetError.NO_RESPONSE
                    }
                    Failure.Network(e)
                }
            }
        }
        if (closed) {
            // Closed while connecting: the session just opened must not be left orphaned.
            s.disconnect()
            throw Failure.Network(null)
        }
        pendingSave?.let { SavedPasswords.put(server.id, it) }
        pendingSave = null
        // Logged in with the password after the key was refused: offer to install the key.
        if (keyRejected && password != null && SshKeys.exists()) keyOffer.post(true)
        keyRejected = false
        session = s
        return s
    }

    /** The app's key was refused in the last attempt (the server does not know it). */
    @Volatile
    var keyRejected = false
        private set

    /** Offer to install the app's key on the server (after a login with the password). */
    internal val keyOffer = MainState(false)

    /**
     * Adds the app's public key to the server's `~/.ssh/authorized_keys` (if not already there), with the
     * permissions sshd wants. Blocks: call from a worker thread. True if it worked.
     */
    fun installKey(): Boolean = runCatching {
        val line = SshKeys.publicKeyLine() ?: return false
        val quoted = "'" + line.replace("'", "'\\''") + "'"
        val command = "umask 077; mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && " +
            "(grep -qxF $quoted ~/.ssh/authorized_keys || echo $quoted >> ~/.ssh/authorized_keys)"
        val ch = connect().openChannel("exec") as ChannelExec
        ch.setCommand(command)
        ch.connect(CONNECT_TIMEOUT_MS)
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (!ch.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val ok = ch.isClosed && ch.exitStatus == 0
        ch.disconnect()
        ok
    }.getOrDefault(false)

    /**
     * Runs [command] on the already open connection (it does not open one) and returns its output, or null
     * if there is no connection, the command fails or does not finish in time. Blocks.
     */
    fun runIfConnected(command: String, timeoutMs: Int = CONNECT_TIMEOUT_MS): String? = runCatching {
        val s = session?.takeIf { it.isConnected } ?: return null
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val input = ch.inputStream
        val out = ByteArrayOutputStream()
        var finished = false
        // The channel is closed even when leaving early (error, interrupted thread).
        try {
            ch.connect(timeoutMs)
            val buffer = ByteArray(1024)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                while (input.available() > 0) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                if (ch.isClosed && input.available() == 0) break
                Thread.sleep(30)
            }
            // Read before disconnect, which marks even a command still running as closed.
            finished = ch.isClosed
        } finally {
            ch.disconnect()
        }
        val ok = finished && ch.exitStatus == 0
        if (!ok) Log.w(TAG, "exec failed (closed=$finished, status=${ch.exitStatus}): $command")
        if (ok) out.toString(Charsets.UTF_8.name()) else null
    }.onFailure { Log.w(TAG, "exec: $it", it) }.getOrNull()

    fun disconnect() {
        session?.disconnect()
        session = null
    }

    /** Final close (tab closed): a connection in progress is also closed as soon as it succeeds. */
    fun close() {
        closed = true
        disconnect()
    }

    /**
     * JSch prompts: the key of a new server is accepted and remembered (first connection), a changed key
     * is refused; password from the user.
     */
    private class Prompts(private val password: String?) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = password
        override fun promptPassword(message: String?) = password != null
        override fun promptPassphrase(message: String?) = false

        // JSch asks to confirm a new key ("authenticity ... can't be established") and warns about a changed one
        // ("REMOTE HOST IDENTIFICATION HAS CHANGED"): yes only to the first.
        override fun promptYesNo(message: String?): Boolean = message?.contains("CHANGED", ignoreCase = true) != true

        override fun showMessage(message: String?) = Unit

        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?,
        ): Array<String>? = password?.let { p -> Array(prompt?.size ?: 0) { p } }
    }

    private companion object {
        const val TAG = "SshLink"
        const val KEEPALIVE_MS = 15_000
        const val KEEPALIVE_MAX = 3
        const val CONNECT_TIMEOUT_MS = 12_000
    }
}
