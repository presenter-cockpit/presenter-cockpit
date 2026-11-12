package it.presenter.app.ui

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Icon-only command: the name ([description]) serves accessibility and appears as a tooltip as soon as the
 * S Pen (or the mouse) hovers over the command, disappearing when it moves away (user's choice, as for
 * the rail tools); with the finger, on long press. Below the name, if needed, a second line with the usage
 * ([usage]: non-obvious gestures, shortcut), in the fixed form of the style guide (`docs/guida-testi.md`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithTooltip(description: String, usage: String? = null, content: @Composable () -> Unit) {
    val state = rememberTooltipState()
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) state.show() else state.dismiss() }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { TooltipText(description, usage) } },
        state = state,
    ) { Box(Modifier.hoverable(interactions)) { content() } }
}

/** Text of a tooltip: the name and, smaller and dimmed, the usage line. */
@Composable
fun TooltipText(name: String, usage: String?) {
    if (usage == null) {
        Text(name)
        return
    }
    Column {
        Text(name)
        Text(usage, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.8f))
    }
}

/**
 * Icon command. [compact] = top bar sizes ([BAR_BUTTON], [BAR_ICON]), the same as the vertical tool rail;
 * elsewhere the standard 48 dp `IconButton`. [unavailable] = missing in this distribution: grey, but the
 * tap works (it shows the notice).
 */
@Composable
fun ActionIcon(
    icon: ImageVector, description: String, enabled: Boolean = true, usage: String? = null, compact: Boolean = false,
    unavailable: Boolean = false, onClick: () -> Unit,
) {
    WithTooltip(description, usage) {
        val colors = IconButtonDefaults.iconButtonColors()
        IconButton(
            onClick = onClick, enabled = enabled, modifier = if (compact) Modifier.size(BAR_BUTTON) else Modifier,
            colors = if (unavailable) colors.copy(contentColor = colors.disabledContentColor) else colors,
        ) {
            Icon(icon, contentDescription = description, modifier = if (compact) Modifier.size(BAR_ICON) else Modifier)
        }
    }
}

/**
 * Icon toggle of the top bar (workspaces, panels, freeze, inverted colors…): it takes [BAR_BUTTON] like the
 * other commands, but the colored circle is [BAR_TOGGLE], like the rail tools (user's request: at full
 * size it looked bigger).
 */
@Composable
fun BarToggle(icon: ImageVector, description: String, checked: Boolean, usage: String? = null, onCheckedChange: (Boolean) -> Unit) {
    WithTooltip(description, usage) {
        Box(Modifier.size(BAR_BUTTON), contentAlignment = Alignment.Center) {
            FilledTonalIconToggleButton(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.size(BAR_TOGGLE)) {
                Icon(icon, contentDescription = description, modifier = Modifier.size(BAR_ICON))
            }
        }
    }
}

/** Top bar sizes: commands like the tools of the vertical rail (`ToolRail`). */
val BAR_HEIGHT = 52.dp
val BAR_BUTTON = 44.dp
val BAR_ICON = 20.dp

/** Circle of the top bar toggles: like the rail tools (44 − 2 × 5 dp). */
val BAR_TOGGLE = 34.dp

/** Small button (panels, editor, dialogs): same tooltip as the large commands. */
@Composable
fun SmallIconButton(icon: ImageVector, description: String, enabled: Boolean = true, usage: String? = null, onClick: () -> Unit) {
    WithTooltip(description, usage) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Hold to activate a feature (dual tool, screen mirroring): the same time everywhere (user's choice: 1.5 s).
 * The progress ring appears after [HOLD_RING_DELAY_MS], so a normal tap does not make it flash.
 */
const val HOLD_TO_ACTIVATE_MS = 1500L
const val HOLD_RING_DELAY_MS = 250L
