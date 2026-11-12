package it.presenter.app.tabs

import android.content.Context
import android.os.Environment
import it.presenter.app.files.TreeFiles
import it.presenter.app.settings.Distribution
import java.io.File

/**
 * Local terminal: the Android shell (`/system/bin/sh`, mksh with toybox commands) on the tablet, only in
 * the full and F-Droid distributions (user's decision: in the store one the shell could not see the granted folders,
 * which are URIs).
 */
object LocalShell {
    const val SHELL = "/system/bin/sh"

    /** The distribution has the local terminal. */
    val available: Boolean get() = !Distribution.isStore

    @Volatile
    private var tmpDir: String? = null

    fun init(context: Context) {
        tmpDir = context.cacheDir.path
    }

    /** Root of the tablet storage: the shell's home (`cd` without arguments) and the last resort. */
    val home: String get() = Environment.getExternalStorageDirectory().path

    /**
     * Folder of a new terminal from the "+" menu: the last one chosen for code (user's decision); if it is
     * gone, the closest existing folder above it; otherwise [home].
     */
    fun startDir(lastCodeFolder: String?): String {
        if (lastCodeFolder == null || TreeFiles.isTreePath(lastCodeFolder)) return home
        return generateSequence(File(lastCodeFolder)) { it.parentFile }.firstOrNull { it.isDirectory && it.canRead() }?.path ?: home
    }

    /** A local terminal can be opened in folder [dir] of a local IDE. */
    fun canOpenIn(dir: String): Boolean = available && dir.isNotBlank() && !TreeFiles.isTreePath(dir)

    /** The app's environment (needed by Android commands such as `am` and `pm`) plus a terminal's. */
    fun environment(): Map<String, String> = System.getenv().toMutableMap().apply {
        put("TERM", "xterm-256color")
        put("COLORTERM", "truecolor")
        put("HOME", home)
        tmpDir?.let { put("TMPDIR", it) }
        putIfAbsent("PATH", "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin")
    }
}
