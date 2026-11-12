package it.presenter.app.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import it.presenter.app.R
import it.presenter.app.session.PresenterSession
import it.presenter.app.tools.ToolEditorRequest
import it.presenter.app.tools.ToolKind
import it.presenter.app.tools.ToolPreset
import it.presenter.app.tools.ToolRef
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Side rail (left or right, from the settings): the user's presets, the fixed tools, undo/redo/clear page
 * (the settings are in the top bar).
 *
 * Tap on a tool: selects it; tap on the tool already selected: editor. With the editor open, a tap on
 * another tool selects it and the editor switches to it. Holding a tool for [HOLD_TO_ACTIVATE_MS] makes it
 * the dual of the selected one (the S Pen button's tool); the dual of the selected tool is marked by a dot.
 */
@Composable
fun ToolRail(session: PresenterSession) {
    val tools = session.tools
    val doc = session.current
    // Read to recompose undo/redo at every ink change.
    doc?.editCount
    val ink = doc?.let { it.inkOf(it.pageIndex) }
    // Height (window px) of each button: its editor opens there.
    val anchors = remember { HashMap<ToolRef, Float>() }

    fun onToolTap(ref: ToolRef) {
        // Changing tool ends the selection and the open text box.
        if (ref != tools.selected) {
            session.selection = null
            session.endTextEdit()
        }
        val editor = session.toolEditor
        // Changing tool removes the spotlight.
        if (ref != ToolRef.Spotlight) session.spotlight = null
        if (ref == ToolRef.Lasso || ref == ToolRef.Spotlight) {
            // Lasso and spotlight have no editor.
            session.toolEditor = null
            tools.select(ref)
            return
        }
        val anchor = anchors[ref] ?: 0f
        when {
            editor?.tool == ref -> session.toolEditor = null
            editor != null -> {
                tools.select(ref)
                session.toolEditor = ToolEditorRequest(ref, anchor)
            }
            tools.selected == ref -> session.toolEditor = ToolEditorRequest(ref, anchor)
            else -> tools.select(ref)
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxHeight().width(RAIL_WIDTH)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 8.dp)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(TOOL_GAP),
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            ) {
                for (preset in tools.presets) {
                    val ref = ToolRef.Preset(preset.id)
                    RailTool(
                        label = stringResource(kindLabel(preset.kind)),
                        selected = tools.inUse == ref,
                        dual = tools.dual == ref && tools.inUse != ref,
                        onAnchor = { anchors[ref] = it },
                        onTap = { onToolTap(ref) },
                        onHold = { tools.setDualOfSelected(ref) },
                    ) { PresetDisc(preset) }
                }
                var addAnchor = 0f
                WithTooltip(stringResource(R.string.tool_new)) {
                    IconButton(
                        onClick = {
                            val p = tools.duplicateActive()
                            session.toolEditor = ToolEditorRequest(ToolRef.Preset(p.id), addAnchor)
                        },
                        modifier = Modifier.size(TOOL_SIZE).onGloballyPositioned { addAnchor = it.boundsInWindow().top },
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.tool_new), modifier = Modifier.size(ICON_SIZE))
                    }
                }
            }
            HorizontalDivider(Modifier.width(36.dp).padding(vertical = 4.dp))
            RailTool(
                label = stringResource(R.string.tool_eraser),
                selected = tools.inUse == ToolRef.Eraser,
                dual = tools.dual == ToolRef.Eraser && tools.inUse != ToolRef.Eraser,
                onAnchor = { anchors[ToolRef.Eraser] = it },
                onTap = { onToolTap(ToolRef.Eraser) },
                onHold = { tools.setDualOfSelected(ToolRef.Eraser) },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                ) {
                    Icon(
                        ToolIcons.Eraser, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(ICON_SIZE),
                    )
                }
            }
            RailTool(
                label = stringResource(R.string.tool_lasso),
                selected = tools.inUse == ToolRef.Lasso,
                dual = tools.dual == ToolRef.Lasso && tools.inUse != ToolRef.Lasso,
                onAnchor = { anchors[ToolRef.Lasso] = it },
                onTap = { onToolTap(ToolRef.Lasso) },
                onHold = { tools.setDualOfSelected(ToolRef.Lasso) },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                ) {
                    Icon(
                        ToolIcons.Lasso, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(ICON_SIZE),
                    )
                }
            }
            RailTool(
                label = stringResource(R.string.tool_spotlight),
                selected = tools.inUse == ToolRef.Spotlight,
                dual = tools.dual == ToolRef.Spotlight && tools.inUse != ToolRef.Spotlight,
                onAnchor = { anchors[ToolRef.Spotlight] = it },
                onTap = { onToolTap(ToolRef.Spotlight) },
                onHold = { tools.setDualOfSelected(ToolRef.Spotlight) },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                ) {
                    Icon(
                        Icons.Rounded.CenterFocusStrong, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(ICON_SIZE),
                    )
                }
            }
            // Lasso clipboard: pasted on the current page (also in another document).
            if (session.clipboard != null && doc != null) {
                RailAction(Icons.Rounded.ContentPaste, stringResource(R.string.action_paste)) { session.paste() }
            }
            Spacer(Modifier.height(2.dp))
            RailAction(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.action_undo), enabled = ink?.canUndo == true, usage = stringResource(R.string.usage_undo)) { session.undo() }
            RailAction(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.action_redo), enabled = ink?.canRedo == true, usage = stringResource(R.string.usage_redo)) { session.redo() }
            RailAction(Icons.Rounded.DeleteSweep, stringResource(R.string.action_clear_page), enabled = ink?.isEmpty == false) {
                session.clearPage()
            }
        }
        // Composed here: the popup is positioned relative to the rail.
        session.toolEditor?.let { ToolEditorPopup(session, it) { session.toolEditor = null } }
    }
}

/**
 * Button of a tool: ring if selected, dot if it is the dual of the selected one. Holding it, a ring fills in
 * [HOLD_TO_ACTIVATE_MS] and at the end it becomes that dual; released earlier it counts as a tap (if short)
 * or does nothing.
 *
 * The tooltip appears on hover (S Pen nearby), not on long press, which has another use here. For TalkBack
 * the same two actions are exposed as tap and long action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RailTool(
    label: String,
    selected: Boolean,
    dual: Boolean,
    onAnchor: (Float) -> Unit,
    onTap: () -> Unit,
    onHold: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentTap by rememberUpdatedState(onTap)
    val currentHold by rememberUpdatedState(onHold)
    val dualLabel = stringResource(R.string.tool_dual)
    val holdHint = stringResource(R.string.tool_hold_hint)
    val tooltip = rememberTooltipState()
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) tooltip.show() else tooltip.dismiss() }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { TooltipText(label, if (dual) dualLabel else holdHint) } },
        state = tooltip,
        enableUserInput = false,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(TOOL_SIZE)
                .onGloballyPositioned { onAnchor(it.boundsInWindow().top) }
                .hoverable(interactions)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                    if (dual) stateDescription = dualLabel
                    onClick { currentTap(); true }
                    onLongClick(holdHint) { currentHold(); true }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        var held = false
                        val job = scope.launch {
                            // A short wait: normal taps do not make the ring flash.
                            delay(HOLD_RING_DELAY_MS)
                            progress.animateTo(1f, tween((HOLD_TO_ACTIVATE_MS - HOLD_RING_DELAY_MS).toInt(), easing = LinearEasing))
                            held = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentHold()
                        }
                        // null: the finger left or the rail is scrolling.
                        val up = waitForUpOrCancellation()
                        job.cancel()
                        scope.launch { progress.snapTo(0f) }
                        if (up != null && !held) currentTap()
                    }
                }
                .drawWithContent {
                    drawContent()
                    val p = progress.value
                    if (p > 0f) {
                        val w = 3.dp.toPx()
                        drawArc(
                            colors.tertiary, -90f, 360f * p, useCenter = false,
                            topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                            style = Stroke(w, cap = StrokeCap.Round),
                        )
                    }
                }
                .border(2.5.dp, if (selected) colors.primary else Color.Transparent, CircleShape)
                .padding(5.dp),
        ) {
            content()
            if (dual) {
                // Dot at the bottom right: the S Pen button's tool.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(10.dp)
                        .background(colors.tertiary, CircleShape)
                        .border(2.dp, colors.surfaceContainer, CircleShape),
                )
            }
        }
    }
}

/** Disc in the preset's color with the icon of its kind. */
@Composable
private fun PresetDisc(preset: ToolPreset) {
    val color = Color(preset.color)
    val iconTint = if (color.luminance() > 0.45f) Color.Black.copy(alpha = 0.8f) else Color.White
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(if (preset.kind == ToolKind.HIGHLIGHTER) color.copy(alpha = 0.85f) else color, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
    ) {
        Icon(ToolIcons.forPreset(preset), contentDescription = null, tint = iconTint, modifier = Modifier.size(ICON_SIZE))
    }
}

/** Rail command (undo, redo, ...): the same size as the tools. */
@Composable
private fun RailAction(icon: ImageVector, description: String, enabled: Boolean = true, usage: String? = null, onClick: () -> Unit) {
    WithTooltip(description, usage) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(TOOL_SIZE)) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(ICON_SIZE))
        }
    }
}

/** Rail sizes (compact: with larger sizes a few presets filled it). */
private val RAIL_WIDTH = 60.dp
private val TOOL_SIZE = 44.dp
private val ICON_SIZE = 20.dp
private val TOOL_GAP = 4.dp

/** Name (string resource) of a tool kind. */
@StringRes
fun kindLabel(kind: ToolKind): Int = when (kind) {
    ToolKind.PEN -> R.string.tool_pen
    ToolKind.MARKER -> R.string.tool_marker
    ToolKind.HIGHLIGHTER -> R.string.tool_highlighter
    ToolKind.SHAPE -> R.string.tool_shape
    ToolKind.TEXT -> R.string.tool_text
    ToolKind.LASER -> R.string.tool_laser
}
