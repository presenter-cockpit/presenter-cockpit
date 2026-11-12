package it.presenter.app.tabs

import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Android shell in a pseudo-terminal (`src/main/jni/pty.c`), for the local terminal. The shell has its
 * own session: closing the tab hangs up the programs it launched too. Reading [input] ends (I/O error)
 * when no process keeps the terminal open any more.
 */
class LocalPty private constructor(val pid: Int, private val pfd: ParcelFileDescriptor) {
    val input: InputStream = FileInputStream(pfd.fileDescriptor)
    val output: OutputStream = FileOutputStream(pfd.fileDescriptor)

    fun resize(rows: Int, cols: Int) = nativeResize(pfd.fd, rows, cols)

    /** Waits for the shell to end (blocks): exit code, or -signal. */
    fun waitFor(): Int = nativeWaitFor(pid)

    /**
     * Closes the shell and every program of its session (background ones too): gently first (SIGHUP, like
     * closing a window), with [force] SIGKILL.
     */
    fun hangup(force: Boolean = false) = nativeSignal(pid, if (force) SIGKILL else SIGHUP, !exited)

    /** The shell ended and was reaped ([waitFor]): its pid is no longer its own. */
    @Volatile
    var exited = false

    /** Current folder of the shell (same user as the app: `/proc` shows it), or null if it ended. */
    fun cwd(): String? = runCatching { Os.readlink("/proc/$pid/cwd") }.getOrNull()

    fun close() {
        runCatching { pfd.close() }
    }

    companion object {
        private const val SIGHUP = 1
        private const val SIGKILL = 9

        init {
            System.loadLibrary("presenterpty")
        }

        /** Starts [path] (arguments [args], the first is the name) with environment [env] in [dir]. */
        fun start(path: String, args: Array<String>, env: Map<String, String>, dir: String, rows: Int, cols: Int): LocalPty {
            val vars = env.map { (k, v) -> "$k=$v" }.toTypedArray()
            val (fd, pid) = nativeStart(path, args, vars, dir, rows, cols).let { it[0] to it[1] }
            return LocalPty(pid, ParcelFileDescriptor.adoptFd(fd))
        }

        @JvmStatic
        private external fun nativeStart(path: String, args: Array<String>, env: Array<String>, dir: String, rows: Int, cols: Int): IntArray

        @JvmStatic
        private external fun nativeResize(fd: Int, rows: Int, cols: Int)

        @JvmStatic
        private external fun nativeWaitFor(pid: Int): Int

        @JvmStatic
        private external fun nativeSignal(pid: Int, signal: Int, leader: Boolean)
    }
}
