package it.presenter.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.presenter.app.R
import it.presenter.app.input.Shortcuts
import it.presenter.app.session.PresenterSession
import it.presenter.app.tabs.BrowserTab
import it.presenter.app.tabs.EditorTab
import it.presenter.app.tabs.TerminalTab
import it.presenter.app.tools.ToolKind
import it.presenter.app.tools.ToolRef

/** List of the shortcuts, generated from the registry: keys on the left, action on the right. */
@Composable
fun ShortcutsList(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in Shortcuts.all) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(220.dp)) {
                    for (key in s.keys) {
                        Text(
                            key.label,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Text(stringResource(s.action.label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * "What you can do here" ("?" icon next to the gear, "?" key): the pen, finger and rail gestures for the
 * active tab and tool, then all the keyboard shortcuts. Lines follow the "Gesture: effect" form of the
 * style guide (`docs/guida-testi.md`).
 */
@Composable
fun HelpDialog(session: PresenterSession, onDismiss: () -> Unit) {
    val sections = helpSections(session)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null) },
        title = { Text(stringResource(R.string.help_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for ((title, lines) in sections) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        for (line in lines) Text("•  " + stringResource(line), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.shortcuts), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    ShortcutsList(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Help sections for what is in front: title and lines (text resources). */
private fun helpSections(session: PresenterSession): List<Pair<Int, List<Int>>> {
    val work = session.activeWork
    if (work != null) {
        return when (work) {
            is EditorTab -> listOf(
                R.string.help_code to listOf(R.string.help_code_pen, R.string.help_code_pinch, R.string.help_code_keys, R.string.help_code_tree, R.string.help_code_tree_hold),
            )
            is TerminalTab -> listOf(R.string.help_terminal to listOf(R.string.help_terminal_pinch, R.string.help_terminal_keys, R.string.help_terminal_tmux))
            is BrowserTab -> listOf(R.string.help_web to listOf(R.string.help_web_back, R.string.help_web_projector))
        }
    }
    val tools = session.tools
    val pen = buildList {
        val selected = tools.selected
        val kind = tools.preset(selected)?.kind
        when {
            selected == ToolRef.Eraser -> add(R.string.help_eraser)
            selected == ToolRef.Lasso -> add(R.string.help_lasso)
            selected == ToolRef.Spotlight -> add(R.string.help_spotlight)
            kind == ToolKind.PEN || kind == ToolKind.MARKER -> {
                add(R.string.help_scribble)
                add(R.string.help_circle_lasso)
            }
            kind == ToolKind.HIGHLIGHTER -> add(R.string.help_highlighter)
            kind == ToolKind.SHAPE -> {
                add(R.string.help_shape)
                add(R.string.help_shape_edit)
                add(R.string.help_shape_snap)
                add(R.string.help_circle_lasso)
            }
            kind == ToolKind.TEXT -> add(R.string.help_text)
            kind == ToolKind.LASER -> add(R.string.help_laser)
        }
        // Hold to select: where the pen can do it (not with the tools one writes with, nor where pausing is normal).
        if (selected == ToolRef.Lasso || kind == ToolKind.SHAPE || kind == ToolKind.TEXT) {
            add(R.string.help_hold_select)
        }
        // The pen's double tap on a text box: not with the tools one writes with, nor with the eraser.
        if (selected != ToolRef.Eraser && kind != ToolKind.PEN && kind != ToolKind.MARKER && kind != ToolKind.HIGHLIGHTER) add(R.string.help_text_edit)
        add(R.string.help_pen_button)
    }
    val fingers = buildList {
        if (tools.fingerDraws) add(R.string.help_finger_draws) else add(R.string.help_swipe)
        add(R.string.help_pinch)
        add(R.string.help_double_tap)
        if (!tools.fingerDraws) add(R.string.help_text_edit)
        if (session.settings.beamerOverlays && !tools.fingerDraws) add(R.string.help_overlay_tap)
        add(R.string.help_link_tap)
        if (!tools.fingerDraws) add(R.string.help_finger_hold)
        add(R.string.help_finger_objects)
        if (session.settings.fingerUndo) add(R.string.help_finger_undo)
    }
    return listOf(
        R.string.help_pen to pen,
        R.string.help_fingers to fingers,
        R.string.help_rail to listOf(R.string.help_rail_tap, R.string.help_rail_hold),
    )
}
