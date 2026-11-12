package it.presenter.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyOff
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.presenter.app.R
import it.presenter.app.remote.HostKeys
import it.presenter.app.remote.SavedPasswords
import it.presenter.app.remote.ServerProfile
import it.presenter.app.remote.SshKeys
import it.presenter.app.session.PresenterSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Servers and keys: the app's SSH key (generated in the Keystore, unlocked with the fingerprint;
 * the public part is copied for the server) and the saved servers.
 */
@Composable
fun ServersContent(session: PresenterSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyLine by remember { mutableStateOf(SshKeys.publicKeyLine()) }
    var editing by remember { mutableStateOf<ServerProfile?>(null) }
    var copied by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf(false) }
    // Actions that cannot be undone: they ask for confirmation.
    var confirmRegenerate by remember { mutableStateOf(false) }
    var forgettingHost by remember { mutableStateOf<ServerProfile?>(null) }
    var deleting by remember { mutableStateOf<ServerProfile?>(null) }
    var forgettingPassword by remember { mutableStateOf<ServerProfile?>(null) }
    // Grows when a password or a server key is forgotten: the icon disappears.
    var storedVersion by remember { mutableIntStateOf(0) }
    // Coming from the bar's "+": the new server dialog opens at once.
    LaunchedEffect(session.addServerRequested) {
        if (session.addServerRequested) {
            session.addServerRequested = false
            editing = ServerProfile(name = "", host = "", user = "")
        }
    }
    fun generate() {
        scope.launch {
            // Without a screen lock (PIN, fingerprint) the Keystore does not create protected keys.
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    SshKeys.delete()
                    SshKeys.generate()
                    SshKeys.publicKeyLine()
                }
            }
            keyError = result.isFailure
            keyLine = result.getOrNull()
            copied = false
    }
    }
    val rowColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

    ListItem(
        leadingContent = { Icon(Icons.Rounded.Key, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.settings_ssh_key)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_ssh_key_summary))
                Text(
                    keyLine ?: stringResource(R.string.ssh_key_none),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (copied) Text(stringResource(R.string.ssh_key_copied), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                if (keyError) Text(stringResource(R.string.ssh_key_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = {
            Row {
                if (keyLine != null) {
                    ActionIcon(Icons.Rounded.ContentCopy, stringResource(R.string.ssh_key_copy)) {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("ssh", keyLine))
                        copied = true
                    }
                }
                ActionIcon(
                    if (keyLine == null) Icons.Rounded.Add else Icons.Rounded.Refresh,
                    stringResource(if (keyLine == null) R.string.ssh_key_generate else R.string.ssh_key_regenerate),
                ) {
                    if (keyLine == null) generate() else confirmRegenerate = true
                }
            }
        },
        colors = rowColors,
    )
    HorizontalDivider()
    for (server in session.servers) {
        ListItem(
            leadingContent = { Icon(Icons.Rounded.Dns, contentDescription = null) },
            headlineContent = { Text(server.label) },
            supportingContent = { Text("${server.user}@${server.host}:${server.port}${if (server.dir.isNotBlank()) "  ${server.dir}" else ""}") },
            trailingContent = {
                Row {
                    storedVersion
                    if (SavedPasswords.has(server.id)) {
                        ActionIcon(Icons.Rounded.KeyOff, stringResource(R.string.servers_forget_password)) { forgettingPassword = server }
                    }
                    // After a server reinstall its key changes: it is forgotten.
                    if (HostKeys.has(session.knownHosts, server)) {
                        ActionIcon(Icons.Rounded.LockReset, stringResource(R.string.servers_forget_host)) { forgettingHost = server }
                    }
                    ActionIcon(Icons.Rounded.Edit, stringResource(R.string.servers_edit)) { editing = server }
                    ActionIcon(Icons.Rounded.DeleteOutline, stringResource(R.string.servers_delete)) { deleting = server }
                }
            },
            colors = rowColors,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
        ActionIcon(Icons.Rounded.Add, stringResource(R.string.servers_new)) { editing = ServerProfile(name = "", host = "", user = "") }
    }
    if (confirmRegenerate) {
        ConfirmDialog(stringResource(R.string.ssh_key_regenerate), stringResource(R.string.ssh_key_regenerate_confirm), onDismiss = { confirmRegenerate = false }) {
            confirmRegenerate = false
            generate()
        }
    }
    forgettingHost?.let { s ->
        ConfirmDialog(stringResource(R.string.servers_forget_host), stringResource(R.string.servers_forget_confirm, s.label), onDismiss = { forgettingHost = null }) {
            forgettingHost = null
            HostKeys.forget(session.knownHosts, s)
            storedVersion++
        }
    }
    forgettingPassword?.let { s ->
        ConfirmDialog(stringResource(R.string.servers_forget_password), stringResource(R.string.servers_forget_password_confirm, s.label), onDismiss = { forgettingPassword = null }) {
            forgettingPassword = null
            SavedPasswords.remove(s.id)
            storedVersion++
        }
    }
    deleting?.let { s ->
        ConfirmDialog(stringResource(R.string.servers_delete), stringResource(R.string.servers_delete_confirm, s.label), onDismiss = { deleting = null }) {
            deleting = null
            session.deleteServer(s.id)
        }
    }
    editing?.let { s -> ServerDialog(s, onDismiss = { editing = null }) { session.saveServer(it); editing = null } }
}

@Composable
private fun ServerDialog(initial: ServerProfile, onDismiss: () -> Unit, onSave: (ServerProfile) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.user) }
    var dir by remember { mutableStateOf(initial.dir) }
    var tmux by remember { mutableStateOf(initial.tmux) }
    // Spaces at the ends (the keyboard with suggestions adds one at the end) do not count.
    val valid = host.isNotBlank() && user.isNotBlank() && port.trim().toIntOrNull() in 1..65535
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Dns, contentDescription = null) },
        title = { Text(stringResource(if (initial.host.isEmpty()) R.string.servers_new else R.string.servers_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.servers_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        host, { host = it.trim() }, label = { Text(stringResource(R.string.servers_host)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.servers_port)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.4f),
                    )
                }
                OutlinedTextField(user, { user = it.trim() }, label = { Text(stringResource(R.string.servers_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dir, { dir = it.trim() }, label = { Text(stringResource(R.string.servers_dir)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.servers_tmux))
                        Text(stringResource(R.string.servers_tmux_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = tmux, onCheckedChange = { tmux = it })
                }
                Text(stringResource(R.string.servers_auth_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(initial.copy(name = name.trim(), host = host.trim(), port = port.trim().toInt(), user = user.trim(), dir = dir.trim(), tmux = tmux))
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
