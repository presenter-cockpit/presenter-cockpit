package it.presenter.app.files

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.SpeakerNotes
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import it.presenter.app.R
import it.presenter.app.remote.FileEntry
import it.presenter.app.ui.ActionIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Picker of the "store" distribution: instead of the whole storage, the folders **granted** by the user
 * (once per folder, with the system picker; the permission persists). Inside a granted folder you
 * browse as in the internal picker, with PDFs that have notes marked.
 * With [onChooseFolder] it picks a folder (local IDE): only folders, and an icon to use the current
 * one, also a subfolder of the granted folder.
 * [startFolder]: last folder used ([TreeFiles] path), if it is still granted.
 */
@Composable
fun TreeBrowserDialog(
    startFolder: String?,
    onSystemPicker: () -> Unit,
    onOpen: (path: String, uri: Uri, name: String) -> Unit,
    onFolder: (String) -> Unit,
    onDismiss: () -> Unit,
    onChooseFolder: ((String) -> Unit)? = null,
) {
    val folderMode = onChooseFolder != null
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val granted = remember(version) { TreeFiles.granted(context) }
    var dir by remember {
        mutableStateOf(startFolder?.takeIf { TreeFiles.isTreePath(it) && TreeFiles.treeOf(it) in granted })
    }
    val files = remember(dir) { dir?.let { TreeFiles(context, TreeFiles.treeOf(it)) } }
    var entries by remember { mutableStateOf<List<Pair<FileEntry, Boolean>>?>(null) }
    LaunchedEffect(dir, version) {
        val d = dir ?: return@LaunchedEffect
        entries = null
        entries = withContext(Dispatchers.IO) { runCatching { listPdfs(files!!, d, foldersOnly = folderMode) }.getOrDefault(emptyList()) }
    }
    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            runCatching { TreeFiles.keep(context, tree) }
            version++
            dir = tree.toString()
            onFolder(tree.toString())
        }
    }
    val height = LocalConfiguration.current.screenHeightDp * 0.86f

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth(0.8f).height(height.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
                ) {
                    val current = dir
                    ActionIcon(Icons.Rounded.ArrowUpward, stringResource(R.string.browser_parent_folder), enabled = current != null) {
                        dir = if (current == null || current == TreeFiles.treeOf(current).toString()) null else current.substringBeforeLast('/')
                    }
                    Text(
                        current?.let { remember(it) { TreeFiles.displayPath(context, it).replace("/", " › ") } }
                            ?: stringResource(if (folderMode) R.string.tab_folder_title else R.string.browser_granted_folders),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    ActionIcon(Icons.Rounded.CreateNewFolder, stringResource(R.string.browser_add_folder)) { addFolder.launch(null) }
                    if (onChooseFolder != null) {
                        ActionIcon(Icons.Rounded.Check, stringResource(R.string.tab_folder_choose), enabled = current != null) {
                            current?.let(onChooseFolder)
                        }
                    } else {
                        ActionIcon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.browser_system_picker), onClick = onSystemPicker)
                    }
                    ActionIcon(Icons.Rounded.Close, stringResource(R.string.action_close), onClick = onDismiss)
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                val current = dir
                when {
                    current == null && granted.isEmpty() -> FolderRequest(folderMode) { addFolder.launch(null) }
                    current == null -> LazyColumn(Modifier.fillMaxHeight()) {
                        items(granted, key = { it.toString() }) { tree ->
                            val name = remember(tree) { TreeFiles.displayPath(context, tree.toString()) }
                            ListItem(
                                headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = {
                                    ActionIcon(Icons.Rounded.FolderOff, stringResource(R.string.browser_remove_folder)) {
                                        runCatching {
                                            context.contentResolver.releasePersistableUriPermission(
                                                tree,
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                            )
                                        }
                                        version++
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                modifier = Modifier.clickable { dir = tree.toString() },
                            )
                        }
                    }
                    // List read once here: the lazy list reads it later too, while entering another folder sets
                    // [entries] back to null (with `entries!!` inside the list the app would crash).
                    else -> when (val list = entries) {
                    null -> Box(Modifier.fillMaxSize())
                    emptyList<Pair<FileEntry, Boolean>>() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (!folderMode) {
                            Icon(
                                Icons.Rounded.PictureAsPdf, contentDescription = stringResource(R.string.browser_no_pdf),
                                tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(64.dp),
                            )
                        }
                    }
                    else -> LazyColumn(Modifier.fillMaxHeight()) {
                        items(list, key = { it.first.path }) { (e, notes) ->
                            ListItem(
                                headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = if (e.isDir) null else { { Text(Formatter.formatShortFileSize(context, e.size)) } },
                                leadingContent = {
                                    Icon(
                                        if (e.isDir) Icons.Rounded.Folder else Icons.Rounded.PictureAsPdf, contentDescription = null,
                                        tint = if (e.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                                    )
                                },
                                trailingContent = if (notes) {
                                    { Icon(Icons.AutoMirrored.Rounded.SpeakerNotes, contentDescription = stringResource(R.string.browser_has_notes), tint = MaterialTheme.colorScheme.secondary) }
                                } else null,
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                modifier = Modifier.clickable {
                                    if (e.isDir) {
                                        dir = e.path
                                    } else {
                                        onFolder(current)
                                        onOpen(e.path, files!!.uriOf(e.path), e.name)
                                    }
                                },
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

/** Folders and PDFs of [dir] (no hidden ones), marking the PDFs that have a `.pdfpc` next to them. */
private fun listPdfs(files: TreeFiles, dir: String, foldersOnly: Boolean = false): List<Pair<FileEntry, Boolean>> {
    val all = files.list(dir).filter { !it.name.startsWith(".") }
    val names = all.mapTo(HashSet()) { it.name.lowercase() }
    return all.filter { it.isDir || (!foldersOnly && it.name.endsWith(".pdf", ignoreCase = true)) }.map { e ->
        e to (!e.isDir && "${e.name.substringBeforeLast('.')}.pdfpc".lowercase() in names)
    }
}

@Composable
private fun FolderRequest(forCode: Boolean, onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(if (forCode) R.string.browser_tree_rationale_code else R.string.browser_tree_rationale, stringResource(R.string.app_name)),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        FilledTonalButton(onClick = onAdd) { Text(stringResource(R.string.browser_add_folder)) }
    }
}
