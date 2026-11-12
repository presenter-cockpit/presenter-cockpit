package it.presenter.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import it.presenter.app.R
import it.presenter.app.session.PresenterSession
import it.presenter.app.workspace.Workspace

/**
 * Workspaces (PDFs, code, terminals, web pages): opening one replaces the open tabs; the one in use follows
 * the tabs by itself; "save as new" creates a workspace with the current tabs.
 */
@Composable
fun WorkspaceMenu(session: PresenterSession, onOpen: (Workspace) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Name to ask for: to save the open tabs (false) or for a new empty workspace (true).
    var naming by remember { mutableStateOf<Boolean?>(null) }
    var deleting by remember { mutableStateOf<Workspace?>(null) }
    val current = session.currentWorkspace
    Box {
        val label = current?.name ?: stringResource(R.string.workspaces)
        BarToggle(Icons.Rounded.Workspaces, label, current != null) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (ws in session.workspaces) {
                DropdownMenuItem(
                    text = { Text(ws.name) },
                    leadingIcon = { if (ws.id == current?.id) Icon(Icons.Rounded.Check, contentDescription = null) },
                    trailingIcon = {
                        SmallIconButton(Icons.Rounded.DeleteOutline, stringResource(R.string.workspace_delete)) {
                            open = false
                            deleting = ws
                        }
                    },
                    onClick = {
                        open = false
                        if (ws.id != current?.id) onOpen(ws)
                    },
                )
            }
            if (session.workspaces.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_save_new)) },
                leadingIcon = { Icon(Icons.Rounded.LibraryAdd, contentDescription = null) },
                onClick = {
                    open = false
                    naming = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_new_empty)) },
                leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
                onClick = {
                    open = false
                    naming = true
                },
            )
        }
    }
    naming?.let { empty ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = null },
            icon = { Icon(Icons.Rounded.Workspaces, contentDescription = null) },
            title = { Text(stringResource(if (empty) R.string.workspace_new_empty else R.string.workspace_save_new)) },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text(stringResource(R.string.workspace_name_hint)) })
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    naming = null
                    // The empty workspace opens like the others (asks about unsaved annotations and closes the tabs);
                    // it enters the list only once open. It starts without an end time.
                    if (empty) onOpen(Workspace(name = name.trim())) else session.saveAsWorkspace(name.trim())
                }) { Text(stringResource(if (empty) R.string.action_confirm else R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    deleting?.let { ws ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.workspace_delete_title, ws.name)) },
            text = { Text(stringResource(R.string.workspace_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    session.deleteWorkspace(ws.id)
                    deleting = null
                }) { Text(stringResource(R.string.workspace_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
