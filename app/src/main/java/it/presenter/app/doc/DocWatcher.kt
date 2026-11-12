package it.presenter.app.doc

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Reports when the file of an open PDF changes on disk (inotify, through [FileObserver]). The folder is
 * watched, not the file: whoever saves with a rename (like Presenter) replaces the file, and a watcher on
 * the old file would hear nothing more. While a program writes, events come in bursts: the report (on the
 * main thread) comes after [QUIET_MS] of silence.
 *
 * It does not see everything (e.g. writes that do not go through the shared file system, like MTP from a
 * PC, nor documents opened as URIs): for those there are the check on return and the periodic one.
 */
class DocWatcher(private val onChanged: (File) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val observers = HashMap<File, DirObserver>()
    private val timers = HashMap<String, Runnable>()

    /** Watches exactly [files] (call on the main thread when the open documents change). */
    fun update(files: Set<File>) {
        val byDir = files.groupBy({ it.absoluteFile.parentFile ?: File("/") }, { it.name })
        for (dir in observers.keys - byDir.keys) observers.remove(dir)?.stopWatching()
        for ((dir, names) in byDir) {
            val observer = observers.getOrPut(dir) { DirObserver(dir).also { it.startWatching() } }
            observer.names = names.toSet()
        }
    }

    fun stop() {
        observers.values.forEach { it.stopWatching() }
        observers.clear()
        timers.values.forEach(handler::removeCallbacks)
        timers.clear()
    }

    private fun schedule(file: File) {
        val key = file.path
        timers.remove(key)?.let(handler::removeCallbacks)
        val run = Runnable {
            timers.remove(key)
            onChanged(file)
        }
        timers[key] = run
        handler.postDelayed(run, QUIET_MS)
    }

    private inner class DirObserver(private val dir: File) : FileObserver(dir, MASK) {
        @Volatile
        var names = emptySet<String>()

        override fun onEvent(event: Int, path: String?) {
            if (path == null || path !in names) return
            val file = File(dir, path)
            // Queued events of a folder no longer watched (or of a stopped watcher) are dropped.
            handler.post { if (observers[dir] === this) schedule(file) }
        }
    }

    companion object {
        private const val MASK = FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
            FileObserver.CREATE or FileObserver.DELETE
        const val QUIET_MS = 700L
    }
}
