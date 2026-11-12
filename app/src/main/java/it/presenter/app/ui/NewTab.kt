package it.presenter.app.ui

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.presenter.app.BuildConfig
import it.presenter.app.R
import it.presenter.app.describe
import it.presenter.app.doc.DocSource
import it.presenter.app.files.TreeBrowserDialog
import it.presenter.app.remote.FileEntry
import it.presenter.app.remote.RemoteDocLinks
import it.presenter.app.remote.ServerProfile
import it.presenter.app.remote.SshLink
import it.presenter.app.session.PresenterSession
import it.presenter.app.settings.Limit
import it.presenter.app.tabs.BrowserTab
import it.presenter.app.tabs.EditorTab
import it.presenter.app.tabs.LocalShell
import it.presenter.app.tabs.TerminalTab
import it.presenter.app.tabs.WorkTab
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Non-PDF tab in the top bar: the same icon as the "+" menu entry that opened it, name, close. With
 * [showMachine] (code and terminal tabs open on more than one machine) the name says where: "name @ server",
 * or "@ local" for the tablet (user's choice).
 */
@Composable
fun WorkTabChip(tab: WorkTab, selected: Boolean, showMachine: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val machine = when {
        !showMachine || tab.machineKey() == null -> null
        else -> tab.serverOf()?.label ?: stringResource(R.string.tab_machine_local)
    }
    TabChip(
        title = if (machine == null || machine == tab.title) tab.title else "${tab.title} @ $machine",
        selected = selected, dirty = tab is EditorTab && tab.hasUnsaved,
        closeDescription = stringResource(R.string.tab_close),
        onClick = onClick, onClose = onClose, hue = tab.kind.hue,
    ) { tint, background ->
        val remote = (tab is TerminalTab && !tab.isLocal) || (tab is EditorTab && tab.link != null)
        TabIcon(tab.kind, remote, TAB_ICON, tint, background)
    }
}

/** Server of a code or terminal tab; null for the tablet and for web pages. */
private fun WorkTab.serverOf(): ServerProfile? = when (this) {
    is TerminalTab -> server
    is EditorTab -> server
    is BrowserTab -> null
}

/**
 * Machine of a code or terminal tab, to tell whether tabs are open on more than one: the server's account
 * (user, host, port), "" for the tablet; null for web pages, which are on no machine.
 */
internal fun WorkTab.machineKey(): String? = when (this) {
    is BrowserTab -> null
    else -> serverOf()?.let { "${it.user}@${it.host.lowercase()}:${it.port}" } ?: ""
}

/**
 * Kind of tab: its icon ([TabIcon]) and the tint of the tab ([TabChip]), soft and different for PDF, IDE and
 * terminal (the web page keeps the theme colors).
 */
enum class TabKind(val icon: ImageVector, val hue: Color? = null) {
    Pdf(Icons.Rounded.PictureAsPdf, Color(0xFFF0645A)),
    Code(Icons.Rounded.AccountTree, Color(0xFF4A95FF)),
    Terminal(Icons.Rounded.Terminal, Color(0xFF3FC060)),
    Browser(Icons.Rounded.Language),
}

/** Kind of a work tab. */
val WorkTab.kind: TabKind
    get() = when (this) {
        is TerminalTab -> TabKind.Terminal
        is EditorTab -> TabKind.Code
        is BrowserTab -> TabKind.Browser
    }

/**
 * Icon of a tab, the same in the "+" menu and on the tab: the kind's symbol with the small server
 * ([remote]) or the small tablet (local); the web page has no badge.
 */
@Composable
fun TabIcon(kind: TabKind, remote: Boolean, size: Dp, tint: Color, background: Color) {
    if (kind == TabKind.Browser) {
        Icon(kind.icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    } else {
        BadgedIcon(kind.icon, if (remote) Icons.Rounded.Dns else Icons.Rounded.TabletAndroid, size, tint, background)
    }
}

/**
 * Icon entry of the "+" menu, with tooltip; [unavailable] = missing in this distribution: grey but the tap
 * works (it shows the notice).
 */
@Composable
private fun MenuIcon(description: String, unavailable: Boolean = false, onClick: () -> Unit, icon: @Composable () -> Unit) {
    WithTooltip(description) {
        val colors = IconButtonDefaults.iconButtonColors()
        IconButton(
            onClick = onClick,
            colors = if (unavailable) colors.copy(contentColor = colors.disabledContentColor) else colors,
            // The icons are drawn without a description: the button gives it (screen readers).
            modifier = Modifier.size(52.dp).semantics { contentDescription = description },
        ) { icon() }
    }
}

/**
 * Icon [base] with a small [badge] at the bottom right (e.g. the server: "on a server"). The badge has a
 * border in the background color ([background]), so it stands out from the icon.
 */
@Composable
fun BadgedIcon(base: ImageVector, badge: ImageVector, size: Dp, tint: Color, background: Color) {
    Box(Modifier.size(size)) {
        Icon(
            base, contentDescription = null, tint = tint,
            modifier = Modifier.align(Alignment.TopStart).offset(x = -size * 0.08f, y = -size * 0.08f).fillMaxSize(),
        )
        Icon(
            badge, contentDescription = null, tint = tint,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = size * 0.18f, y = size * 0.18f)
                .size(size * 0.56f)
                .background(background, RoundedCornerShape(size * 0.12f))
                .padding(size * 0.03f),
        )
    }
}

/**
 * "+" in the top bar. Icons only (with tooltips): at the top the local tabs, with the small tablet (PDF,
 * code, terminal) and the web page; then one row per saved server, with the name (just a label) and the
 * terminal, code and PDF icons with the small server; without servers, "add a server". In the store
 * distribution the local terminal is grey (tap = explanation). The menu is as wide as needed (the longest
 * server name).
 */
@Composable
fun NewTabMenu(session: PresenterSession, onOpenPdf: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var askUrl by remember { mutableStateOf(false) }
    var pickFolder by remember { mutableStateOf(false) }
    // "store" distribution: work folder among the granted ones (or one of their subfolders).
    var pickTree by remember { mutableStateOf(false) }
    var pickRemotePdf by remember { mutableStateOf<ServerProfile?>(null) }
    val menuBackground = MaterialTheme.colorScheme.surfaceContainer
    @Composable
    fun local(kind: TabKind) = TabIcon(kind, remote = false, 24.dp, LocalContentColor.current, menuBackground)
    @Composable
    fun remote(kind: TabKind) = TabIcon(kind, remote = true, 24.dp, LocalContentColor.current, menuBackground)
    Box {
        ActionIcon(Icons.Rounded.Add, stringResource(R.string.tab_new), compact = true) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Local.
            Row(Modifier.padding(horizontal = 8.dp)) {
                MenuIcon(stringResource(R.string.tab_pdf_local), onClick = { open = false; onOpenPdf() }) { local(TabKind.Pdf) }
                MenuIcon(stringResource(R.string.tab_board), onClick = {
                    open = false
                    session.onNewBoard?.invoke()
                }) { Icon(Icons.Rounded.Draw, contentDescription = null, modifier = Modifier.size(24.dp)) }
                // "full" and "fdroid": internal picker on the storage; "store": granted folder with the system picker
                // (the permission persists, the tab finds it again on reopening).
                MenuIcon(stringResource(R.string.tab_code_local), onClick = {
                    open = false
                    if (BuildConfig.ALL_FILES_ACCESS) pickFolder = true else pickTree = true
                }) { local(TabKind.Code) }
                // Store: missing (the shell could not see the folders), the tap explains why. Full and F-Droid: Android
                // shell in the last folder chosen for code (user's decision).
                if (!LocalShell.available) {
                    val limit = Limit.STORE_TERMINAL
                    MenuIcon(stringResource(limit.tooltip!!), unavailable = true, onClick = { open = false; session.limitNotice = limit }) { local(TabKind.Terminal) }
                } else {
                    MenuIcon(stringResource(R.string.tab_terminal_local), onClick = {
                        open = false
                        val dir = LocalShell.startDir(session.prefs.lastCodeFolder)
                        session.openWork(TerminalTab(null, session.knownHosts).also { t -> t.cwd = dir })
                    }) { local(TabKind.Terminal) }
                }
                MenuIcon(stringResource(R.string.tab_browser), onClick = { open = false; askUrl = true }) { local(TabKind.Browser) }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            // On the saved servers: the name (just a label) and the actions, aligned to the right.
            for (server in session.servers) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text(
                        server.label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).widthIn(max = 220.dp).padding(start = 8.dp, end = 12.dp),
                    )
                    // Same order as the local row: PDF, code, terminal.
                    MenuIcon(stringResource(R.string.tab_pdf_remote, server.label), onClick = {
                        open = false
                        pickRemotePdf = server
                    }) { remote(TabKind.Pdf) }
                    MenuIcon(stringResource(R.string.tab_code_remote, server.label), onClick = {
                        open = false
                        session.openWork(EditorTab.remote(server, session.knownHosts))
                    }) { remote(TabKind.Code) }
                    MenuIcon(stringResource(R.string.tab_terminal, server.label), onClick = {
                        open = false
                        session.openWork(TerminalTab(server, session.knownHosts))
                    }) { remote(TabKind.Terminal) }
                }
            }
            // Without servers: how to add one (then they are added from the settings).
            if (session.servers.isEmpty()) {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    MenuIcon(stringResource(R.string.tab_add_server), onClick = {
                        open = false
                        session.addServerRequested = true
                        session.settingsOpen = true
                    }) { BadgedIcon(Icons.Rounded.Dns, Icons.Rounded.Add, 24.dp, LocalContentColor.current, menuBackground) }
                }
            }
        }
    }
    if (askUrl) {
        var url by remember { mutableStateOf("") }
        val go = {
            askUrl = false
            session.openWork(BrowserTab(normalizeUrl(url)))
        }
        AlertDialog(
            onDismissRequest = { askUrl = false },
            icon = { Icon(Icons.Rounded.Language, contentDescription = null) },
            title = { Text(stringResource(R.string.tab_url_title)) },
            text = {
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, singleLine = true,
                    placeholder = { Text("https://…") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank()) go() }),
                )
            },
            confirmButton = {
                TextButton(enabled = url.isNotBlank(), onClick = go) { Text(stringResource(R.string.link_open)) }
            },
            dismissButton = { TextButton(onClick = { askUrl = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    pickRemotePdf?.let { server ->
        RemotePdfPicker(
            session, server,
            onDismiss = {
                pickRemotePdf = null
                session.releaseRemoteLink(server.id)
            },
        ) { path ->
            pickRemotePdf = null
            session.onOpenPdf?.invoke(DocSource.Remote(server.id, path), null)
        }
    }
    if (pickTree) {
        TreeBrowserDialog(
            startFolder = session.prefs.lastCodeFolder,
            onSystemPicker = {},
            onOpen = { _, _, _ -> },
            onFolder = {},
            onDismiss = { pickTree = false },
            onChooseFolder = { dir ->
                pickTree = false
                session.prefs.lastCodeFolder = dir
                session.openWork(EditorTab.local(dir))
                // First opening of the local IDE in the store flavor: once only, the notice about the file limits.
                if (!session.prefs.storeFilesNoticeShown) {
                    session.prefs.storeFilesNoticeShown = true
                    session.limitNotice = Limit.STORE_FILES
                }
            },
        )
    }
    if (pickFolder) {
        FolderPicker(session.prefs.lastCodeFolder, onDismiss = { pickFolder = false }) { dir ->
            pickFolder = false
            session.prefs.lastCodeFolder = dir.path
            session.openWork(EditorTab.local(dir.path))
        }
    }
}

/** Listing of the remote PDF picker: folders and PDFs of a folder. */
private class RemoteListing(val dir: String, val entries: List<FileEntry>)

/**
 * Picker of a PDF on server [server] (SFTP, on the remote PDFs' connection): folders and PDFs, starting from
 * the profile's folder (or the home). If the key or the saved password must be unlocked, "retry" asks for
 * the fingerprint first.
 */
@Composable
private fun RemotePdfPicker(session: PresenterSession, server: ServerProfile, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    var dir by remember { mutableStateOf<String?>(null) }
    var listing by remember { mutableStateOf<RemoteListing?>(null) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    LaunchedEffect(dir, attempt) {
        failure = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                RemoteDocLinks.use(server.id) { f ->
                    if (f == null) throw DocSource.Remote.Unreachable(DocSource.Offline.NO_SERVER)
                    val d = dir ?: server.startDir(f.home())
                    RemoteListing(d, f.list(d).filter { e -> !e.name.startsWith(".") && (e.isDir || e.name.endsWith(".pdf", ignoreCase = true)) })
                }
            }
        }
        result.onSuccess { listing = it }.onFailure { failure = it }
    }
    val reason = when (val e = failure) {
        is SshLink.Failure -> DocSource.Offline.of(e)
        is DocSource.Remote.Unreachable -> e.reason
        else -> null
    }
    fun retry() {
        when (reason) {
            DocSource.Offline.KEY_LOCKED -> session.unlockKey?.invoke { attempt++ }
            DocSource.Offline.PASSWORD_LOCKED -> session.unlockPassword?.invoke(server) { pw ->
                RemoteDocLinks.linkIfAny(server.id)?.usePassword(pw, fromStore = true)
                attempt++
            }
            else -> attempt++
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.PictureAsPdf, contentDescription = null) },
        title = { Text(stringResource(R.string.remote_pdf_title, server.label)) },
        text = {
            Column {
                val current = listing
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val parent = current?.dir?.substringBeforeLast('/', "")?.ifEmpty { "/" }
                    ActionIcon(Icons.Rounded.ArrowUpward, stringResource(R.string.browser_parent_folder), enabled = current != null && current.dir != "/") {
                        parent?.let { dir = it }
                    }
                    Text(current?.dir.orEmpty(), maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
                }
                val error = failure
                when {
                    error != null -> {
                        Text(
                            if (reason != null) stringResource(R.string.msg_open_failed, server.label, reason.describeShort(context)) else error.describe(context),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        if (reason == DocSource.Offline.NEEDS_PASSWORD) {
                            Text(stringResource(R.string.remote_doc_password_hint, server.label), style = MaterialTheme.typography.bodySmall)
                        }
                        ActionIcon(Icons.Rounded.Refresh, stringResource(R.string.remote_retry)) { retry() }
                    }
                    current == null -> CircularProgressIndicator(Modifier.padding(16.dp).size(28.dp))
                    current.entries.isEmpty() -> Text(stringResource(R.string.remote_pdf_empty), modifier = Modifier.padding(vertical = 12.dp))
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp).fillMaxWidth()) {
                        items(current.entries, key = { it.path }) { e ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { if (e.isDir) dir = e.path else onChoose(e.path) }.padding(vertical = 10.dp),
                            ) {
                                Icon(
                                    if (e.isDir) Icons.Rounded.Folder else Icons.Rounded.PictureAsPdf, contentDescription = null,
                                    tint = if (e.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(e.name, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Choice of a tablet folder for the local IDE (needs all files access, already asked for the PDFs). */
@Composable
private fun FolderPicker(lastFolder: String?, onDismiss: () -> Unit, onChoose: (File) -> Unit) {
    // Starts from the last folder chosen (or from the first of its parents that still exists).
    var dir by remember {
        val last = generateSequence(lastFolder?.let(::File)) { it.parentFile }.firstOrNull { it.isDirectory && it.canRead() }
        mutableStateOf(last ?: Environment.getExternalStorageDirectory())
    }
    val subdirs = remember(dir) { dir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
        title = { Text(stringResource(R.string.tab_folder_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(Icons.Rounded.ArrowUpward, stringResource(R.string.browser_parent_folder), enabled = dir.parentFile?.canRead() == true) {
                        dir.parentFile?.let { dir = it }
                    }
                    Text(dir.path, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
                }
                LazyColumn(Modifier.heightIn(max = 360.dp).fillMaxWidth()) {
                    items(subdirs) { d ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { dir = d }.padding(vertical = 10.dp),
                        ) {
                            Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(d.name, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(dir) }) {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Text(stringResource(R.string.tab_folder_choose), modifier = Modifier.padding(start = 6.dp))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
