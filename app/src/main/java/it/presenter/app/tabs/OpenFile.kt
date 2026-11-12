package it.presenter.app.tabs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import it.presenter.app.doc.DocStamp

/**
 * A file open in an IDE tab: text in the editor, text as on disk, cursor and first visible line. Each
 * file keeps its own changes, so you switch between them without saving.
 */
class OpenFile(
    val path: String,
    text: String = "",
    /** Text on disk; null = not read yet (restored without a connection). */
    savedText: String? = text,
    /** [text] is the real content: false for a file restored without changes and not read yet. */
    loaded: Boolean = true,
    /** Preview tab: the next file opened from the tree replaces it. */
    preview: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')

    /** Text in the editor; changing it ends the preview. */
    var text by mutableStateOf(text)
        private set

    var savedText by mutableStateOf(savedText)
    var loaded by mutableStateOf(loaded)
    var preview by mutableStateOf(preview)
    var cursor by mutableIntStateOf(0)

    /**
     * Text to bring into view in the editor (search result; an empty range: go to that line, with the cursor);
     * the editor clears it once shown.
     */
    var reveal by mutableStateOf<IntRange?>(null)

    /** Search result shown with a colored background, or null. */
    var mark by mutableStateOf<IntRange?>(null)
    var firstVisibleLine by mutableIntStateOf(0)

    val isDirty: Boolean get() = loaded && (savedText == null || text != savedText)

    /** Size and date of the file when [savedText] was read or written; null: unknown (not read yet). */
    @Volatile
    var diskStamp: DocStamp? = null

    /**
     * Someone else (terminal, another app) changed the file while it has unsaved changes here: [diskText] is
     * the new text on disk. Saving overwrites it; [reloadFromDisk] takes it instead.
     */
    var changedOnDisk by mutableStateOf(false)
        private set
    private var diskText: String? = null

    /** Edit from the editor. */
    fun edit(value: String) {
        if (value == text) return
        text = value
        preview = false
    }

    /**
     * Drops the unsaved changes: back to the text on disk, or, if it was not read yet, to a file still to
     * read (no longer counted as changed).
     */
    fun discardChanges() {
        val disk = savedText
        if (disk == null) {
            loaded = false
            return
        }
        text = disk
        cursor = cursor.coerceIn(0, disk.length)
    }

    /**
     * The file on disk now holds [disk] ([stamp]): without changes here it becomes the text, otherwise
     * [changedOnDisk]. A file rewritten with the same text changes nothing.
     */
    fun diskChanged(disk: String, stamp: DocStamp?) {
        diskStamp = stamp
        if (disk == savedText) return
        if (!isDirty) {
            savedText = disk
            text = disk
            cursor = cursor.coerceIn(0, disk.length)
            return
        }
        diskText = disk
        changedOnDisk = true
    }

    /** The text changed by someone else replaces the unsaved changes. */
    fun reloadFromDisk() {
        val disk = diskText ?: return
        savedText = disk
        text = disk
        cursor = cursor.coerceIn(0, disk.length)
        diskText = null
        changedOnDisk = false
    }

    /** Saved here: the file holds [content] ([stamp]), whatever someone else had written. */
    fun saved(content: String, stamp: DocStamp?) {
        savedText = content
        diskStamp = stamp
        diskText = null
        changedOnDisk = false
    }

    /** Content read from disk: becomes the text if there was none yet. */
    fun loadedFromDisk(disk: String) {
        savedText = disk
        if (!loaded) {
            text = disk
            loaded = true
            cursor = cursor.coerceIn(0, disk.length)
        }
    }
}
