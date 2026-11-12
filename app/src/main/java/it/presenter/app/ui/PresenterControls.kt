package it.presenter.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ConnectedTv
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.InvertColors
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.TvOff
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.PopupProperties
import it.presenter.app.R
import it.presenter.app.projector.AudienceActivity
import it.presenter.app.projector.AudienceDisplays
import it.presenter.app.projector.PauseScreen
import it.presenter.app.projector.PrivateStartScreen
import it.presenter.app.projector.audienceView
import it.presenter.app.session.PresenterSession
import it.presenter.app.session.PresenterSession.ProjectorMode
import it.presenter.app.session.TimerPhase
import java.util.Calendar
import java.util.Date
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * Audience outputs: status icon (none, external displays with their number, mirroring, private start) that
 * opens the menu of the outputs: each external display found with its switch (trusted ones on by default,
 * those of other apps off: [AudienceDisplays]), the window to share
 * ([AudienceActivity]) and, with external displays, mirroring of the tablet (with
 * confirmation) or the way back to the presentation.
 */
@Composable
fun OutputsButton(session: PresenterSession) {
    val info = session.projectorInfo
    val mode = session.projectorMode
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var closedAt by remember { mutableLongStateOf(0L) }
    var confirm by remember { mutableStateOf(false) }
    val connected = info != null
    val count = session.externalDisplays.count { it.enabled } + if (session.shareWindow) 1 else 0
    // Proportions of the window to share (full screen): read here, inside the menu the window is the menu's.
    val screen = LocalWindowInfo.current.containerSize
    val screenRatio = if (screen.height > 0) screen.width.toFloat() / screen.height else 16f / 9f
    val label = when {
        mode == ProjectorMode.PRIVATE -> stringResource(if (connected) R.string.projector_private else R.string.projector_private_none)
        !connected -> stringResource(R.string.projector_none)
        mode == ProjectorMode.MIRROR -> stringResource(R.string.projector_mirroring)
        else -> info
    }
    val (icon, tint, background) = when {
        // Private start: a well visible solid color, even without a connected display.
        mode == ProjectorMode.PRIVATE -> Triple(if (connected) Icons.Rounded.VisibilityOff else Icons.Rounded.TvOff, colors.onTertiaryContainer, colors.tertiaryContainer)
        !connected -> Triple(Icons.Rounded.TvOff, colors.onSurfaceVariant, Color.Transparent)
        mode == ProjectorMode.MIRROR -> Triple(Icons.Rounded.Devices, colors.onErrorContainer, colors.errorContainer)
        else -> Triple(Icons.Rounded.ConnectedTv, colors.primary, Color.Transparent)
    }
    Box {
        WithTooltip(label, stringResource(R.string.outputs_usage)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(BAR_BUTTON)
                    .clickable(role = Role.Button, onClickLabel = label) {
                        // A tap on the icon with the menu open closes it (the outside touch already did).
                        if (SystemClock.uptimeMillis() - closedAt > REOPEN_GUARD_MS) open = true
                    }
                    .semantics { contentDescription = label }
                    .padding(4.dp)
                    .background(background, CircleShape),
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(BAR_ICON))
                // More than one output: how many.
                if (count > 1 && mode != ProjectorMode.PRIVATE) {
                    Text(
                        "$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onPrimary,
                        modifier = Modifier.align(Alignment.TopEnd).background(colors.primary, CircleShape).padding(horizontal = 4.dp),
                    )
                }
            }
        }
        // Not focusable: a tap outside closes the menu and still reaches what is under it (pause, freeze…, user's
        // request); Back closes it here.
        BackHandler(enabled = open) { open = false }
        // Closed at any touch on Cockpit's window, whether the popup reports the outside tap or not.
        DisposableEffect(open) {
            val close = {
                open = false
                closedAt = SystemClock.uptimeMillis()
            }
            if (open) session.onWindowTouch = close
            onDispose { if (session.onWindowTouch === close) session.onWindowTouch = null }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = {
                open = false
                closedAt = SystemClock.uptimeMillis()
            },
            properties = PopupProperties(focusable = false),
        ) {
            // Live preview of what the audience sees (not when mirroring: they see the tablet).
            if (connected && mode != ProjectorMode.MIRROR) AudiencePreview(session, screenRatio)
            MenuHeader(stringResource(R.string.outputs_displays))
            if (session.externalDisplays.isEmpty()) {
                MenuRow(Icons.Rounded.TvOff, stringResource(R.string.projector_none), null, checked = null) {}
            }
            for (d in session.externalDisplays) {
                val tooltip = if (d.trusted) null else stringResource(R.string.outputs_virtual)
                MenuRow(if (d.trusted) Icons.Rounded.Tv else Icons.Rounded.Devices, d.name, "${d.width}×${d.height}", checked = d.enabled, tooltip = tooltip) {
                    session.setDisplayEnabled(d.name, !d.enabled)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            MenuRow(Icons.AutoMirrored.Rounded.ScreenShare, stringResource(R.string.share_window_name), null, checked = session.shareWindow, tooltip = stringResource(R.string.share_window_hint)) {
                if (!session.shareWindow) {
                    open = false
                    session.notice = R.string.share_window_opened
                }
                AudienceActivity.toggle(context, session)
            }
            // Mirroring only makes sense on real displays (the window to share shows the pause meanwhile).
            if ((session.externalDisplays.any { it.enabled } && mode != ProjectorMode.PRIVATE) || mode == ProjectorMode.MIRROR) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                if (mode == ProjectorMode.MIRROR) {
                    MenuRow(Icons.Rounded.ConnectedTv, stringResource(R.string.outputs_present), null, checked = null) {
                        open = false
                        session.projectorMode = ProjectorMode.PRESENT
                    }
                } else {
                    MenuRow(Icons.Rounded.Devices, stringResource(R.string.outputs_mirror), null, checked = null) {
                        open = false
                        confirm = true
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Rounded.Devices, contentDescription = null) },
            title = { Text(stringResource(R.string.projector_mirror_title)) },
            text = { Text(stringResource(R.string.projector_mirror_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    session.projectorMode = ProjectorMode.MIRROR
                }) { Text(stringResource(R.string.projector_mirror_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * What the audience sees, live, in the outputs menu: the same view as the outputs
 * ([audienceView]), with the proportions of the first external display, or of
 * the tablet ([screenRatio]) for the window to share (each output has its own: this is a representation).
 */
@Composable
private fun AudiencePreview(session: PresenterSession, screenRatio: Float) {
    val first = session.externalDisplays.firstOrNull { it.enabled }
    val ratio = if (first != null && first.height > 0) first.width.toFloat() / first.height else screenRatio
    val label = stringResource(R.string.outputs_preview)
    Box(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).width(320.dp).aspectRatio(ratio)
            .clip(RoundedCornerShape(8.dp)).background(Color.Black)
            .semantics { contentDescription = label },
    ) {
        AndroidView(
            factory = { ctx -> audienceView(ctx, session, compose = true, shareWindow = false) },
            modifier = Modifier.matchParentSize(),
        )
    }
}

/**
 * Light outline of the previews of what the audience sees: the shadow alone does not show over dark content
 * (terminal, inverted colors), user's request.
 */
private val PREVIEW_HALO = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.35f))

/** A tap on the status icon this soon after the menu closed (by the same touch) does not open it again. */
private const val REOPEN_GUARD_MS = 400L

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/**
 * Row of the outputs menu, minimal (user's request): icon, name, details below (the resolution) and, for an
 * output, its switch ([checked]); explanations only in the [tooltip].
 */
@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    details: String?,
    checked: Boolean?,
    tooltip: String? = null,
    onClick: () -> Unit,
) {
    if (tooltip != null) WithTooltip(title, tooltip) { MenuItem(icon, title, details, checked, onClick) } else MenuItem(icon, title, details, checked, onClick)
}

@Composable
private fun MenuItem(icon: ImageVector, title: String, details: String?, checked: Boolean?, onClick: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = { Icon(icon, contentDescription = null) },
        text = {
            Column(Modifier.widthIn(max = 360.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                details?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        trailingIcon = checked?.let { c -> { Switch(checked = c, onCheckedChange = { onClick() }) } },
        onClick = onClick,
    )
}

/**
 * Pause and play for the audience (or B), on all outputs, like a media player (user's choice): while
 * presenting ⏸ pauses; while paused, or in private start, ▶ starts again, highlighted. Grey without outputs. The
 * state is told by the outputs icon (e.g. the crossed eye of the private start).
 */
@Composable
fun PauseButton(session: PresenterSession) {
    val mode = session.projectorMode
    val connected = session.projectorInfo != null
    // The private start without outputs shows on the outputs icon and waits for them (B turns it off anyway).
    if (!connected && mode == ProjectorMode.PRIVATE) return
    if (!connected) return NeedsOutput(session, Icons.Rounded.Pause, stringResource(R.string.projector_pause))
    if (mode == ProjectorMode.MIRROR) return
    if (mode == ProjectorMode.PRIVATE) {
        BarToggle(Icons.Rounded.PlayArrow, stringResource(R.string.projector_private), true, stringResource(R.string.projector_hint_private)) {
            session.projectorMode = ProjectorMode.PRESENT
        }
        return
    }
    if (mode == ProjectorMode.BLACK) {
        BarToggle(Icons.Rounded.PlayArrow, stringResource(R.string.projector_black_on), true, stringResource(R.string.projector_hint_resume)) { session.toggleBlack() }
    } else {
        BarToggle(Icons.Rounded.Pause, stringResource(R.string.projector_pause), false, stringResource(R.string.usage_keyboard, "B")) { session.toggleBlack() }
    }
}

/** Freeze: the audience keeps seeing the current screen; on the tablet you move freely. */
@Composable
fun FreezeButton(session: PresenterSession) {
    if (session.projectorInfo == null) return NeedsOutput(session, Icons.Rounded.AcUnit, stringResource(R.string.projector_freeze))
    if (session.projectorMode != ProjectorMode.PRESENT) return
    val label = stringResource(if (session.frozen != null) R.string.projector_unfreeze else R.string.projector_freeze)
    BarToggle(Icons.Rounded.AcUnit, label, session.frozen != null, stringResource(R.string.usage_keyboard, "F")) { session.toggleFreeze() }
}

/** Inverted colors on the projector for the tab in front (each remembers its own state). */
@Composable
fun InvertButton(session: PresenterSession) {
    if (session.activeWork == null && session.current == null) return
    if (session.projectorInfo == null) return NeedsOutput(session, Icons.Rounded.InvertColors, stringResource(R.string.projector_invert))
    if (session.projectorMode != ProjectorMode.PRESENT) return
    val label = stringResource(R.string.projector_invert)
    BarToggle(Icons.Rounded.InvertColors, label, session.inverted) { session.toggleInverted() }
}

/**
 * The icon of an audience command while no output is connected. It is grey but still shows its tooltip and,
 * when tapped, says what to connect, so the command can be found before the projector is there (user's
 * request; it used to be hidden).
 */
@Composable
private fun NeedsOutput(session: PresenterSession, icon: ImageVector, label: String) {
    ActionIcon(icon, stringResource(R.string.projector_needed_label, label), compact = true, unavailable = true) {
        session.notice = R.string.projector_needed
    }
}

/** Preview of what the audience sees while the projector is frozen (tablet only). */
@Composable
fun FrozenPreview(session: PresenterSession, modifier: Modifier) {
    val frozen = session.frozen ?: return
    // Pause or private start on top: the audience sees those, and their previews take this place.
    if (session.projectorMode != ProjectorMode.PRESENT) return
    val image = remember(frozen) { frozen.asImageBitmap() }
    PreviewCard(Icons.Rounded.AcUnit, label = null, modifier) {
        Image(
            image, contentDescription = stringResource(R.string.projector_frozen_preview),
            contentScale = ContentScale.Fit,
            modifier = Modifier.width(240.dp).clip(RoundedCornerShape(12.dp)),
        )
    }
}

/**
 * Card of a preview of what the audience sees (frozen, live, pause, private start): outlined (see
 * [PREVIEW_HALO]), with the [badge] icon of its state at the top left. [label]: its description, if the
 * content does not have one.
 */
@Composable
private fun PreviewCard(badge: ImageVector, label: String?, modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = PREVIEW_HALO,
        modifier = modifier.padding(12.dp).then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
    ) {
        Box {
            content()
            Icon(
                badge, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(28.dp)
                    .background(Color(0x99000000), CircleShape).padding(4.dp),
            )
        }
    }
}

/**
 * Place of the previews of what the audience sees (live, frozen, pause, private start: one at a time), in
 * the page area: dragged anywhere (user's request), remembered in the workspace
 * ([PresenterSession.previewPosition], fractions of the free space, so it stays inside when sizes change);
 * by default the bottom corner on the side of the tool rail ([railRight]).
 */
@Composable
fun PreviewArea(session: PresenterSession, railRight: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        var size by remember { mutableStateOf(IntSize.Zero) }
        // While dragging: the position in pixels; at the end it is saved as fractions.
        var drag by remember { mutableStateOf<Offset?>(null) }
        val freeW = (constraints.maxWidth - size.width).coerceAtLeast(0).toFloat()
        val freeH = (constraints.maxHeight - size.height).coerceAtLeast(0).toFloat()
        val (fx, fy) = session.previewPosition ?: ((if (railRight) 1f else 0f) to 1f)
        val pos = drag ?: Offset(freeW * fx, freeH * fy)
        val current by rememberUpdatedState(Triple(pos, freeW, freeH))
        Box(
            Modifier
                .offset { IntOffset(pos.x.toInt(), pos.y.toInt()) }
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { drag = current.first },
                        onDrag = { change, amount ->
                            change.consume()
                            val (p, w, h) = current
                            drag = Offset((p.x + amount.x).coerceIn(0f, w), (p.y + amount.y).coerceIn(0f, h))
                        },
                        onDragEnd = {
                            val (p, w, h) = current
                            session.setPreviewPosition((if (w > 0f) p.x / w else 0f) to (if (h > 0f) p.y / h else 0f))
                            drag = null
                        },
                        onDragCancel = { drag = null },
                    )
                },
        ) { content() }
    }
}

/**
 * Window to share open (user's request): in a corner, always, what the audience sees live, like the
 * previews of freeze and pause (which take this place when on). With the proportions of the window (the
 * tablet's).
 */
@Composable
fun SharePreview(session: PresenterSession, modifier: Modifier) {
    if (!session.shareWindow || session.projectorMode != ProjectorMode.PRESENT || session.frozen != null) return
    val screen = LocalWindowInfo.current.containerSize
    val ratio = if (screen.height > 0) screen.width.toFloat() / screen.height else 16f / 9f
    PreviewCard(Icons.AutoMirrored.Rounded.ScreenShare, stringResource(R.string.outputs_preview), modifier) {
        Box(Modifier.width(240.dp).aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
            AndroidView(
                factory = { ctx -> audienceView(ctx, session, compose = true, shareWindow = true) },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * Private start with the external display connected: in a corner the preview of what the audience sees
 * (only the logo), like the "freeze" one. A tap does nothing: you leave from the button.
 */
@Composable
fun PrivateStartPreview(session: PresenterSession, modifier: Modifier) {
    if (session.projectorMode != ProjectorMode.PRIVATE || session.projectorInfo == null) return
    PreviewCard(Icons.Rounded.VisibilityOff, stringResource(R.string.projector_private), modifier) {
        Box(Modifier.width(240.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) { PrivateStartScreen() }
    }
}

/**
 * Pause with the external display connected: in a corner the preview of what the audience sees (the pause
 * screen), like the "freeze" one. Taps do nothing: pause and freeze have their buttons (user's request).
 */
@Composable
fun PausePreview(session: PresenterSession, modifier: Modifier) {
    if (session.projectorMode != ProjectorMode.BLACK || session.projectorInfo == null) return
    PreviewCard(Icons.Rounded.Pause, stringResource(R.string.projector_black_on), modifier) {
        Box(Modifier.width(240.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) { PauseScreen() }
    }
}

/**
 * Current time and battery in the top bar, when turned on in the settings (off by default: the bar has
 * little room; the system bars are hidden). The time changes every minute; the battery icon follows the
 * level and is red when low and not charging. Tablet only.
 */
@Composable
fun ClockAndBattery(session: PresenterSession) {
    if (!session.settings.showClock) return
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)
        }
    }
    var level by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        fun read(i: Intent) {
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).let { if (it < 0) -1 else it * 100 / scale }
            charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = read(i)
        }
        // A sticky broadcast: the current state comes back at once.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::read)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val time = remember(now) { DateFormat.getTimeFormat(context).format(Date(now)) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        val clockLabel = stringResource(R.string.clock_now)
        WithTooltip(clockLabel) {
            Text(time, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { contentDescription = clockLabel })
        }
        if (level >= 0) {
            val label = pluralStringResource(if (charging) R.plurals.battery_charging else R.plurals.battery_level, level, level)
            val low = level <= LOW_BATTERY && !charging
            WithTooltip(label) {
                BatteryGlyph(
                    level, charging,
                    if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    Modifier.padding(start = 6.dp).semantics { contentDescription = label },
                )
            }
        }
    }
}

/**
 * Battery drawn as in Android's status bar (user's choice): upright body with slightly rounded corners and
 * the cap on top, filled from the bottom as much as the charge; a bolt in the middle while charging.
 */
@Composable
private fun BatteryGlyph(level: Int, charging: Boolean, color: Color, modifier: Modifier) {
    Box(modifier.size(width = 12.dp, height = 20.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 1.5.dp.toPx()
            val cap = 2.dp.toPx()
            val bodyTop = cap
            val bodyH = size.height - cap - stroke / 2
            val bodyW = size.width - stroke
            drawRoundRect(
                color, topLeft = Offset(size.width * 0.3f, 0f), size = Size(size.width * 0.4f, cap + stroke),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
            drawRoundRect(
                color, topLeft = Offset(stroke / 2, bodyTop), size = Size(bodyW, bodyH),
                cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(stroke),
            )
            val gap = stroke * 1.4f
            val innerH = (bodyH - stroke - 2 * gap) * level.coerceIn(0, 100) / 100f
            if (innerH > 0f) {
                drawRoundRect(
                    color, topLeft = Offset(stroke / 2 + gap, bodyTop + bodyH - stroke / 2 - gap - innerH),
                    size = Size(bodyW - 2 * gap, innerH), cornerRadius = CornerRadius(1.dp.toPx()),
                )
            }
        }
        if (charging) Icon(Icons.Rounded.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(11.dp))
    }
}

/** Battery level (%) shown in red when not charging. */
private const val LOW_BATTERY = 15

/**
 * End of the presentation: without a time set, a clock icon; with the time, the time left (yellow and red
 * by the thresholds in the settings, negative after the end), and the time elapsed in its tooltip; with the
 * stopwatch alone, the time elapsed. A tap opens the picker, which also offers quick durations and the
 * stopwatch. Tablet only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresentationEndTimer(session: PresenterSession) {
    var picking by remember { mutableStateOf(false) }
    val end = session.presentationEnd
    val start = session.presentationStart
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(end, start) {
        while (end > 0L || start > 0L) {
            now = System.currentTimeMillis()
            session.expirePresentationEnd(now)
            delay(1000L - now % 1000L)
        }
    }
    val elapsed = if (start > 0L) formatElapsed(now - start) else null
    if (end <= 0L && elapsed != null) {
        // Stopwatch alone: the time elapsed, in neutral colors.
        val description = stringResource(R.string.timer_elapsed)
        WithTooltip(description) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { picking = true }
                    .semantics { contentDescription = description }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(elapsed, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp))
            }
        }
    } else if (end <= 0L) {
        ActionIcon(Icons.Rounded.Schedule, stringResource(R.string.timer_set), compact = true) { picking = true }
    } else {
        val left = end - now
        val settings = session.settings
        val colors = MaterialTheme.colorScheme
        // Yellow, orange, then red once the time is up (as the background around the slide on the tablet).
        val (fg, bg) = when (TimerPhase.of(left, settings.timerYellowMinutes, settings.timerOrangeMinutes)) {
            TimerPhase.RED -> colors.onError to colors.error
            TimerPhase.ORANGE -> Color(0xFF3E1C00) to Color(0xFFFFA040)
            TimerPhase.YELLOW -> Color(0xFF3E2D00) to Color(0xFFFFD54F)
            TimerPhase.NONE -> colors.onSurface to colors.surfaceContainerHighest
        }
        val description = stringResource(R.string.timer_left)
        WithTooltip(description, elapsed?.let { stringResource(R.string.timer_elapsed_usage, it) }) {
            Text(
                formatLeft(left),
                style = MaterialTheme.typography.titleSmall,
                color = fg,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .clickable { picking = true }
                    .semantics { contentDescription = description }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
    if (picking) {
        val start = Calendar.getInstance().apply {
            val usual = session.currentWorkspace?.presentationEndMinutes ?: -1
            if (end > 0L) {
                timeInMillis = end
            } else if (usual >= 0) {
                // The workspace remembers when this presentation usually ends.
                set(Calendar.HOUR_OF_DAY, usual / 60)
                set(Calendar.MINUTE, usual % 60)
            } else {
                // Proposal: the next full hour.
                add(Calendar.HOUR_OF_DAY, 1)
                set(Calendar.MINUTE, 0)
            }
        }
        val state = rememberTimePickerState(start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            icon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
            title = { Text(stringResource(R.string.timer_title)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Quick choices: the end so many minutes from now, or only the time elapsed.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (minutes in QUICK_DURATIONS) {
                            AssistChip(
                                onClick = {
                                    picking = false
                                    session.updatePresentationEnd(System.currentTimeMillis() + minutes * 60_000L)
                                },
                                label = { Text(pluralStringResource(R.plurals.timer_duration, minutes, minutes)) },
                            )
                        }
                        AssistChip(
                            onClick = {
                                picking = false
                                session.startStopwatch()
                            },
                            label = { Text(stringResource(R.string.timer_stopwatch)) },
                            leadingIcon = { Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    TimePicker(state)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    session.updatePresentationEnd(nextOccurrence(state.hour, state.minute))
                }) { Text(stringResource(R.string.timer_confirm)) }
            },
            dismissButton = {
                Row {
                    if (end > 0L || session.presentationStart > 0L) {
                        TextButton(onClick = {
                            picking = false
                            session.updatePresentationEnd(0L)
                        }) { Text(stringResource(R.string.timer_remove)) }
                    }
                    TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
}

/** Time [hour]:[minute] today, or tomorrow if it passed more than an hour ago (an evening talk). */
private fun nextOccurrence(hour: Int, minute: Int): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, hour)
    c.set(Calendar.MINUTE, minute)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    if (c.timeInMillis < System.currentTimeMillis() - 60 * 60 * 1000L) c.add(Calendar.DAY_OF_YEAR, 1)
    return c.timeInMillis
}

/** Time elapsed [ms]: "12:34" (minutes and seconds) or "1:05:00". */
internal fun formatElapsed(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Quick durations of the timer, in minutes (user's choice). */
private val QUICK_DURATIONS = listOf(45, 60, 90)

/** "12:34" (minutes and seconds) or "1:05:00"; after the end "+3:20". */
internal fun formatLeft(ms: Long): String {
    val over = ms < 0
    val total = (abs(ms) + if (over) 0 else 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val body = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    return if (over) "+$body" else body
}

