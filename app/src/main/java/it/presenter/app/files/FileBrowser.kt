package it.presenter.app.files

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.SpeakerNotes
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import it.presenter.app.BuildConfig
import it.presenter.app.R
import it.presenter.app.ui.ActionIcon
import java.io.File
import java.text.DateFormat
import java.util.Date

/** A storage of the tablet (internal or SD card). */
data class StorageRoot(val dir: File, val label: String, val removable: Boolean)

fun storageRoots(context: Context): List<StorageRoot> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val sm = context.getSystemService(StorageManager::class.java)
        val roots = sm.storageVolumes.mapNotNull { v ->
            v.directory?.let { StorageRoot(it, v.getDescription(context), v.isRemovable) }
        }
        if (roots.isNotEmpty()) return roots
    }
    return listOf(StorageRoot(Environment.getExternalStorageDirectory(), context.getString(R.string.storage_internal), false))
}

fun hasAllFilesAccess(): Boolean =
    BuildConfig.ALL_FILES_ACCESS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

/**
 * PDF picker inside the app: starts from the last folder used and marks the PDFs that have PDFPC notes
 * next to them. Needs the "all files access" permission; otherwise there is the system picker (without
 * notes, and without saving to the original file if the provider does not allow it).
 */
@Composable
fun FileBrowserDialog(
    startFolder: String?,
    hasAccess: Boolean,
    onRequestAccess: () -> Unit,
    onSystemPicker: () -> Unit,
    onOpen: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val roots = remember { storageRoots(context) }
    var dir by remember {
        mutableStateOf(startFolder?.let(::File)?.takeIf { it.isDirectory } ?: roots.first().dir)
    }
    val root = roots.firstOrNull { dir.path.startsWith(it.dir.path) } ?: roots.first()
    val entries = remember(dir, hasAccess) { if (hasAccess) listEntries(dir) else emptyList() }
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
                    ActionIcon(Icons.Rounded.ArrowUpward, stringResource(R.string.browser_parent_folder), enabled = hasAccess && dir != root.dir) {
                        dir.parentFile?.let { dir = it }
                    }
                    Text(
                        breadcrumb(dir, root),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        for (r in roots) {
                            ActionIcon(if (r.removable) Icons.Rounded.SdCard else Icons.Rounded.TabletAndroid, r.label, enabled = hasAccess) {
                                dir = r.dir
                            }
                        }
                    }
                    ActionIcon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.browser_system_picker), onClick = onSystemPicker)
                    ActionIcon(Icons.Rounded.Close, stringResource(R.string.action_close), onClick = onDismiss)
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                when {
                    !hasAccess -> AccessRequest(onRequestAccess)
                    entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.PictureAsPdf, contentDescription = stringResource(R.string.browser_no_pdf),
                            tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(64.dp),
                        )
                    }
                    else -> LazyColumn(Modifier.fillMaxHeight()) {
                        items(entries, key = { it.file.path }) { e ->
                            EntryRow(e) {
                                if (e.file.isDirectory) dir = e.file else onOpen(e.file)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessRequest(onRequestAccess: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.browser_access_rationale, stringResource(R.string.app_name)),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        FilledTonalButton(onClick = onRequestAccess) { Text(stringResource(R.string.browser_allow_access)) }
    }
}

private class Entry(val file: File, val hasNotes: Boolean)

private fun listEntries(dir: File): List<Entry> {
    val files = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: return emptyList()
    val names = files.mapTo(HashSet()) { it.name.lowercase() }
    val dirs = files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
    val pdfs = files.filter { it.isFile && it.name.endsWith(".pdf", ignoreCase = true) }.sortedBy { it.name.lowercase() }
    return dirs.map { Entry(it, false) } + pdfs.map { f ->
        Entry(f, "${f.name.substringBeforeLast('.')}.pdfpc".lowercase() in names)
    }
}

@Composable
private fun EntryRow(e: Entry, onClick: () -> Unit) {
    val context = LocalContext.current
    val dirEntry = e.file.isDirectory
    ListItem(
        headlineContent = { Text(e.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (dirEntry) null else {
            {
                Text(
                    Formatter.formatShortFileSize(context, e.file.length()) + " · " +
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.file.lastModified())),
                )
            }
        },
        leadingContent = {
            Icon(
                if (dirEntry) Icons.Rounded.Folder else Icons.Rounded.PictureAsPdf, contentDescription = null,
                tint = if (dirEntry) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            )
        },
        trailingContent = if (e.hasNotes) {
            { Icon(Icons.AutoMirrored.Rounded.SpeakerNotes, contentDescription = stringResource(R.string.browser_has_notes), tint = MaterialTheme.colorScheme.secondary) }
        } else null,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun breadcrumb(dir: File, root: StorageRoot): String {
    val rel = dir.path.removePrefix(root.dir.path).trim('/')
    return if (rel.isEmpty()) root.label else root.label + " › " + rel.replace("/", " › ")
}
