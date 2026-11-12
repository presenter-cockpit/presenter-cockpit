package it.presenter.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.presenter.app.R
import it.presenter.app.doc.PresenterDocument
import it.presenter.app.files.TreeFiles
import it.presenter.app.remote.FileEntry
import it.presenter.app.session.PresenterSession
import it.presenter.app.tabs.EditorTab
import it.presenter.app.tabs.LocalShell
import kotlinx.coroutines.flow.first
import org.connectbot.terminal.Terminal

/** Second tap on the same entry within [DOUBLE_TAP_MS]: a double tap, without delaying the first. */
internal class DoubleTap {
    private var lastKey: Any? = null
    private var lastAt = 0L

    fun hit(key: Any): Boolean {
        val now = SystemClock.uptimeMillis()
        val double = key == lastKey && now - lastAt < DOUBLE_TAP_MS
        lastKey = if (double) null else key
        lastAt = now
        return double
    }
}

private const val DOUBLE_TAP_MS = 400L

/** File tree: folders that open on tap (loaded then), files that open in the editor. */
@Composable
internal fun FileTree(session: PresenterSession, tab: EditorTab, onOpen: (path: String, pin: Boolean) -> Unit, modifier: Modifier) {
    val rows = ArrayList<Pair<FileEntry, Int>>()
    fun add(dir: String, depth: Int) {
        for (e in tab.children[dir].orEmpty()) {
            if (!tab.showHidden && e.name.startsWith(".")) continue
            rows.add(e to depth)
            if (e.isDir && tab.expanded[e.path] == true) add(e.path, depth + 1)
        }
    }
    add(tab.root, 0)
    // The last open folder stays in view: as soon as it exists (also after reconnecting) and if it is off
    // screen, the tree scrolls to it.
    val listState = rememberLazyListState()
    val lastIndex = rows.indexOfFirst { it.first.path == tab.lastDir }
    LaunchedEffect(tab.lastDir, lastIndex >= 0) {
        if (lastIndex < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val shown = visible.any { it.index == lastIndex } && visible.lastOrNull()?.index != lastIndex
        if (!shown) listState.scrollToItem((lastIndex - 2).coerceAtLeast(0))
    }
    // Showing or hiding hidden files the tree stays on the same folder: take the first visible row present in
    // both views (not hidden) and, once the tree is rebuilt, go back there. Compose keeps the position by
    // itself only if few rows are added above.
    val anchor = remember(tab) { TreeAnchor(tab.showHidden) }
    if (anchor.hidden != tab.showHidden) {
        anchor.hidden = tab.showHidden
        val old = anchor.rows
        anchor.pending = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { v -> old.getOrNull(v.index)?.let { !isHiddenPath(it.first.path, tab.root) } == true }
            ?.let { v -> old[v.index].first.path to v.offset }
    }
    SideEffect { anchor.rows = rows }
    LaunchedEffect(tab.showHidden) {
        val (path, offset) = anchor.pending ?: return@LaunchedEffect
        anchor.pending = null
        val index = rows.indexOfFirst { it.first.path == path }
        if (index >= 0) listState.scrollToItem(index, (-offset).coerceAtLeast(0))
    }
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val doubleTap = remember { DoubleTap() }
    // Folder with the menu open (long press) and folder in which to create a file.
    var menuFor by remember(tab) { mutableStateOf<String?>(null) }
    var newFileIn by remember(tab) { mutableStateOf<String?>(null) }
    // Background just different from the code's, in light and dark theme.
    LazyColumn(modifier.background(Color.Gray.copy(alpha = 0.10f)), state = listState) {
        items(rows, key = { it.first.path }) { (entry, depth) ->
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (entry.path == tab.lastDir) highlight else Color.Transparent)
                        .combinedClickable(
                            onClick = {
                                when {
                                    entry.isDir -> tab.toggle(entry.path)
                                    isPdf(entry.name) -> openPdfFromTree(session, tab, entry.path)
                                    else -> onOpen(entry.path, doubleTap.hit(entry.path))
                                }
                            },
                            onLongClick = if (entry.isDir) ({ menuFor = entry.path }) else null,
                        )
                        .padding(start = (8 + 16 * depth).dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    val pdf = !entry.isDir && isPdf(entry.name)
                    Icon(
                        when {
                            pdf -> Icons.Rounded.PictureAsPdf
                            !entry.isDir -> Icons.AutoMirrored.Rounded.InsertDriveFile
                            tab.expanded[entry.path] == true -> Icons.Rounded.FolderOpen
                            else -> Icons.Rounded.Folder
                        },
                        contentDescription = null,
                        tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        entry.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (entry.path == tab.openPath) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
                    )
                    // Folder with an open terminal (same machine): the icon leads to that terminal.
                    if (entry.isDir) {
                        session.terminalIn(tab, entry.path)?.let { term ->
                            val label = stringResource(R.string.code_terminal_open_here)
                            WithTooltip(label) {
                                IconButton(onClick = { session.showWork(term) }, modifier = Modifier.padding(start = 4.dp).size(28.dp)) {
                                    Icon(Icons.Rounded.Terminal, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    // PDF already open in a tab: a tap goes there.
                    if (pdf && openPdfOf(session, tab, entry.path) != null) {
                        val label = stringResource(R.string.code_pdf_open_tab)
                        Icon(
                            Icons.Rounded.Tab, contentDescription = label, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp).size(16.dp),
                        )
                    }
                }
                if (menuFor == entry.path) FolderMenu(session, tab, entry.path, onDismiss = { menuFor = null }, onNewFile = { newFileIn = entry.path })
            }
        }
    }
    newFileIn?.let { dir -> NewFileDialog(dir, onDismiss = { newFileIn = null }) { name -> newFileIn = null; tab.createFile(dir, name) } }
}

private fun isPdf(name: String) = name.endsWith(".pdf", ignoreCase = true)

/** Rows of the tree and first visible row to find again when the hidden files choice changes. */
private class TreeAnchor(var hidden: Boolean) {
    var rows: List<Pair<FileEntry, Int>> = emptyList()
    var pending: Pair<String, Int>? = null
}

/** [path] is hidden, or inside a hidden folder, relative to the tree root [root]. */
internal fun isHiddenPath(path: String, root: String): Boolean =
    path.removePrefix(root).split('/').any { it.startsWith(".") }

/** Tab where the PDF [path] of [tab]'s tree is already open, or null. */
private fun openPdfOf(session: PresenterSession, tab: EditorTab, path: String): PresenterDocument? {
    val src = tab.pdfSource(path)
    return session.documents.firstOrNull { it.source.sameAs(src) }
}

/**
 * Tap on a PDF in the tree: it opens at once as a PDF tab (no preview, user's choice), right after the IDE;
 * if it is already open, switch to its tab.
 */
private fun openPdfFromTree(session: PresenterSession, tab: EditorTab, path: String) {
    val open = openPdfOf(session, tab, path)
    if (open != null) session.switchTo(open) else session.onOpenPdf?.invoke(tab.pdfSource(path), tab)
}

/**
 * Long-press menu on a folder of the tree, with icons: terminal there (IDE on a server or, except in the
 * store version, on the tablet), IDE there (not on the root), copy the path, new file.
 */
@Composable
private fun FolderMenu(session: PresenterSession, tab: EditorTab, dir: String, onDismiss: () -> Unit, onNewFile: () -> Unit) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Row(Modifier.padding(horizontal = 8.dp)) {
            if (tab.server != null || LocalShell.canOpenIn(dir)) {
                ActionIcon(Icons.Rounded.Terminal, stringResource(R.string.code_terminal_here)) {
                    onDismiss()
                    session.terminalAt(tab, dir)
                }
            }
            if (dir.trimEnd('/') != tab.root.trimEnd('/')) {
                ActionIcon(TabKind.Code.icon, stringResource(R.string.code_ide_here)) {
                    onDismiss()
                    session.ideAt(tab, dir)
                }
            }
            val context = LocalContext.current
            ActionIcon(Icons.Rounded.ContentCopy, stringResource(R.string.code_copy_path)) {
                onDismiss()
                // In a granted folder the "path" is a URI: copy the readable one.
                val shown = if (TreeFiles.isTreePath(dir)) TreeFiles.displayPath(context, dir) else dir
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("path", shown))
                session.noticePath = R.string.code_path_copied to shown
            }
            ActionIcon(Icons.AutoMirrored.Rounded.NoteAdd, stringResource(R.string.code_new_file)) {
                onDismiss()
                onNewFile()
            }
        }
    }
}

/** Name of the new file in folder [dir]: no slashes, nor "." or "..". */
@Composable
private fun NewFileDialog(dir: String, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && '/' !in name && name.trim() != "." && name.trim() != ".."
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.NoteAdd, contentDescription = null) },
        title = { Text(stringResource(R.string.code_new_file_title, dir.substringAfterLast('/').ifEmpty { dir })) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.code_new_file_name)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onCreate(name.trim()) }),
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onCreate(name.trim()) }) { Text(stringResource(R.string.code_new_file_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
