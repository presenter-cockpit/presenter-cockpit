package it.presenter.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import it.presenter.app.R
import it.presenter.app.ink.TextLayouts
import it.presenter.app.session.PresenterSession
import it.presenter.app.tools.ToolKind
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Text box being edited, tablet only: a text field over the page, with the box's position, rotation and
 * scale (it follows zoom and pan of the sheet). Meanwhile the projector shows the real box, updated at
 * every key. Handles to move it and to change its width; changing the text preset in the rail changes the
 * open box too.
 */
@Composable
fun TextEditOverlay(session: PresenterSession) {
    val edit = session.textEdit ?: return
    session.screenTick
    val box = edit.box
    val density = LocalDensity.current
    val m = session.viewport.pageToView
    val k = m.mapRadius(1f).coerceAtLeast(1e-3f)
    val origin = floatArrayOf(box.x, box.y).also(m::mapPoints)
    val currentBox by rememberUpdatedState(box)

    var value by remember(edit.serial) { mutableStateOf(TextFieldValue(box.text, TextRange(edit.cursor.takeIf { it in 0..box.text.length } ?: box.text.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(edit.serial) { focus.requestFocus() }
    BackHandler { session.endTextEdit() }

    // The text preset changed while the box is open applies to it too.
    val preset = session.tools.preset(session.tools.selected)?.takeIf { it.kind == ToolKind.TEXT }
    var applied by remember(edit.serial) { mutableStateOf(preset) }
    LaunchedEffect(preset) {
        if (preset != null && preset != applied) {
            applied = preset
            session.textEdit?.let {
                session.updateTextEdit(it.box.copy(size = preset.size, color = preset.color, font = preset.font, bold = preset.bold, italic = preset.italic))
            }
        }
    }

    // The on-screen keyboard must not cover the box: if needed the sheet moves up.
    val ime = WindowInsets.ime.getBottom(density)
    var containerHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(ime, containerHeight) {
        if (ime <= 0 || containerHeight <= 0) return@LaunchedEffect
        val b = session.textEdit?.box ?: return@LaunchedEffect
        val corners = b.corners().also(m::mapPoints)
        val bottom = corners.filterIndexed { i, _ -> i % 2 == 1 }.max()
        val limit = containerHeight - ime - 24 * density.density
        if (bottom > limit) {
            session.viewport.beginGesture()
            session.viewport.applyGesture(1f, 0f, 0f, 0f, limit - bottom)
            session.notify(PresenterSession.Change.VIEWPORT)
        }
    }

    val textStyle = TextStyle(
        color = Color(box.color),
        fontSize = with(density) { (box.size * k).toSp() },
        fontFamily = FontFamily(TextLayouts.typeface(box.font, bold = false, italic = false)),
        fontWeight = if (box.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (box.italic) FontStyle.Italic else FontStyle.Normal,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    val primary = MaterialTheme.colorScheme.primary
    val handle = 28.dp
    val handlePx = with(density) { handle.toPx() }
    // Handles outside the rotated field, in screen coordinates: dragging them they do not move under the
    // finger together with the box (movements stay measured in a fixed frame).
    val topRight = floatArrayOf(box.x + box.width * cos(box.rotation), box.y + box.width * sin(box.rotation)).also(m::mapPoints)
    Box(Modifier.onSizeChanged { containerHeight = it.height }.fillMaxSize()) {
        Box(
            Modifier
                .offset { IntOffset(origin[0].roundToInt(), origin[1].roundToInt()) }
                .graphicsLayer {
                    rotationZ = Math.toDegrees(box.rotation.toDouble()).toFloat()
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .requiredWidth(with(density) { (box.width * k).toDp() }),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { v ->
                    // The cursor alone or the keyboard's composition also call here: the box changes only if the text
                    // changes.
                    val changed = v.text != value.text
                    value = v
                    if (changed) session.textEdit?.let { session.updateTextEdit(it.box.copy(text = v.text)) }
                },
                textStyle = textStyle,
                cursorBrush = SolidColor(Color(box.color)),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .border(1.dp, primary.copy(alpha = 0.7f), RoundedCornerShape(2.dp)),
            )
        }
        // Move: left of the top corner.
        HandleIcon(
            Icons.Rounded.OpenWith, stringResource(R.string.text_move),
            Modifier.offset { IntOffset((origin[0] - handlePx * 1.2f).roundToInt(), (origin[1] - handlePx * 0.6f).roundToInt()) }.size(handle),
        ) { dx, dy ->
            val b = currentBox
            session.updateTextEdit(b.copy(x = b.x + dx / k, y = b.y + dy / k))
        }
        // Width: beyond the top-right corner; the movement along the line counts.
        HandleIcon(
            Icons.Rounded.SwapHoriz, stringResource(R.string.text_width),
            Modifier.offset { IntOffset((topRight[0] + handlePx * 0.2f).roundToInt(), (topRight[1] - handlePx * 0.6f).roundToInt()) }.size(handle),
        ) { dx, dy ->
            val b = currentBox
            val along = dx * cos(b.rotation) + dy * sin(b.rotation)
            session.updateTextEdit(b.copy(width = (b.width + along / k).coerceAtLeast(MIN_WIDTH)))
        }
        // Done: closes the box (like a tap on the page).
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset { IntOffset((topRight[0] + handlePx * 1.5f).roundToInt(), (topRight[1] - handlePx * 0.6f).roundToInt()) }
                .size(handle)
                .background(primary, CircleShape)
                .pointerInput(Unit) { detectTapGestures(onTap = { session.endTextEdit() }) },
        ) { Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.text_done), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp)) }
    }
}

/**
 * Draggable handle that moves together with what it drags: movements are measured in window coordinates,
 * otherwise the handle sliding under the finger would distort them.
 */
@Composable
private fun HandleIcon(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    onDrag: (Float, Float) -> Unit,
) {
    val drag by rememberUpdatedState(onDrag)
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .onGloballyPositioned { coords = it }
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var last = coords?.localToRoot(down.position) ?: return@awaitEachGesture
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val now = coords?.localToRoot(change.position) ?: break
                        change.consume()
                        drag(now.x - last.x, now.y - last.y)
                        last = now
                    }
                }
            },
    ) { Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
}

private const val MIN_WIDTH = 60f
