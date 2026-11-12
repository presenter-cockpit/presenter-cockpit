package it.presenter.app.ui

import android.graphics.RectF
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.presenter.app.R
import it.presenter.app.doc.PresenterDocument
import it.presenter.app.session.PresenterSession
import kotlinx.coroutines.launch

/**
 * Presenter notes (from the .pdfpc file), visible only on the tablet. The panel moves by dragging its top
 * bar and resizes from the bottom-right corner; the commands set the text size, the scrolling and the
 * background transparency (the text stays solid, the slide shows through). Position, size, text and
 * transparency persist across sessions. The pencil edits the note of the slide (Markdown, as in PDFPC):
 * [onSaveNote] writes it when confirmed or when the slide changes.
 */
@Composable
fun NotesPanel(session: PresenterSession, doc: PresenterDocument, onSaveNote: (page: Int, text: String) -> Unit, onHide: () -> Unit) {
    val notes = doc.notes
    val prefs = session.prefs
    val density = LocalDensity.current
    val saved = remember { prefs.notesBounds }
    var x by remember { mutableFloatStateOf(saved[0]) }
    var y by remember { mutableFloatStateOf(saved[1]) }
    var w by remember { mutableFloatStateOf(saved[2]) }
    var h by remember { mutableFloatStateOf(saved[3]) }
    var fontSp by remember { mutableFloatStateOf(prefs.notesFontSp) }
    var opacity by remember { mutableFloatStateOf(prefs.notesOpacity.coerceIn(MIN_OPACITY, 1f)) }
    var opacityMenu by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    val page = doc.pageIndex
    val note = remember(notes, page) { doc.noteFor(page) }
    val markdown = notes?.markdown ?: true
    val text = remember(note, markdown) { note?.let { Markdown.render(it, markdown) } }
    LaunchedEffect(doc, page) { scroll.scrollTo(0) }
    // Note being edited: its page and the text typed so far. Changing slide saves it, as the check icon does.
    var editingPage by remember(doc) { mutableStateOf<Int?>(null) }
    var draft by remember(doc) { mutableStateOf("") }
    fun finishEdit(save: Boolean) {
        val p = editingPage ?: return
        editingPage = null
        session.notesEditing = false
        if (save) onSaveNote(p, draft)
    }
    LaunchedEffect(page) { if (editingPage != null && editingPage != page) finishEdit(save = true) }
    DisposableEffect(doc) { onDispose { if (editingPage != null) finishEdit(save = true) } }

    DisposableEffect(Unit) {
        onDispose { session.notesBoundsInWindow = null }
    }

    fun persist() {
        prefs.notesBounds = floatArrayOf(x, y, w, h)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxW = maxWidth.value
        val maxH = maxHeight.value
        // Effective values, always inside the available area (the saved state may come from another window).
        val ew = w.coerceIn(MIN_W, maxW.coerceAtLeast(MIN_W))
        val eh = h.coerceIn(MIN_H, maxH.coerceAtLeast(MIN_H))
        // Default position: bottom right.
        val ex = (if (x < 0f) maxW - ew - 16f else x).coerceIn(0f, (maxW - ew).coerceAtLeast(0f))
        val ey = (if (y < 0f) maxH - eh - 16f else y).coerceIn(0f, (maxH - eh).coerceAtLeast(0f))
        // The gesture handlers stay those of the first composition: they read the current geometry from here (it
        // changes with the rotation or resizing of the window).
        val geometry by rememberUpdatedState(floatArrayOf(ex, ey, ew, eh, maxW, maxH))

        Surface(
            modifier = Modifier
                .offset { IntOffset(ex.dp.roundToPx(), ey.dp.roundToPx()) }
                .size(ew.dp, eh.dp)
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInWindow()
                    session.notesBoundsInWindow = RectF(b.left, b.top, b.right, b.bottom)
                },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = opacity),
            tonalElevation = 3.dp,
            // With a transparent background the shadow would show through the panel as a dark halo.
            shadowElevation = if (opacity < 1f) 0.dp else 8.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(onDragEnd = { persist() }) { change, drag ->
                                change.consume()
                                val (gx, gy, gw, gh) = geometry
                                x = (gx + drag.x / density.density).coerceIn(0f, (geometry[4] - gw).coerceAtLeast(0f))
                                y = (gy + drag.y / density.density).coerceIn(0f, (geometry[5] - gh).coerceAtLeast(0f))
                            }
                        }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.DragIndicator, contentDescription = stringResource(R.string.notes_move),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp),
                    )
                    Text(
                        "${page + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (doc.source.canWritePdfpc) {
                        if (editingPage == null) {
                            SmallIconButton(Icons.Rounded.Edit, stringResource(R.string.notes_edit)) {
                                draft = note.orEmpty()
                                editingPage = page
                                session.notesEditing = true
                            }
                        } else {
                            SmallIconButton(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.notes_edit_cancel)) { finishEdit(save = false) }
                            SmallIconButton(Icons.Rounded.Check, stringResource(R.string.notes_edit_done)) { finishEdit(save = true) }
                        }
                    }
                    SmallIconButton(Icons.Rounded.TextDecrease, stringResource(R.string.notes_text_smaller), enabled = fontSp > MIN_FONT) {
                        fontSp = (fontSp - 2f).coerceAtLeast(MIN_FONT)
                        prefs.notesFontSp = fontSp
                    }
                    SmallIconButton(Icons.Rounded.TextIncrease, stringResource(R.string.notes_text_larger), enabled = fontSp < MAX_FONT) {
                        fontSp = (fontSp + 2f).coerceAtMost(MAX_FONT)
                        prefs.notesFontSp = fontSp
                    }
                    // Only the switch between "can" and "cannot" recomposes the bar, not every pixel of scrolling.
                    val canScrollUp by remember { derivedStateOf { scroll.value > 0 } }
                    val canScrollDown by remember { derivedStateOf { scroll.value < scroll.maxValue } }
                    SmallIconButton(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.notes_scroll_up), enabled = canScrollUp) {
                        scope.launch { scroll.animateScrollBy(-scrollStep(eh, density.density)) }
                    }
                    SmallIconButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.notes_scroll_down), enabled = canScrollDown) {
                        scope.launch { scroll.animateScrollBy(scrollStep(eh, density.density)) }
                    }
                    Box {
                        SmallIconButton(Icons.Rounded.Opacity, stringResource(R.string.notes_transparency)) { opacityMenu = true }
                        DropdownMenu(expanded = opacityMenu, onDismissRequest = { opacityMenu = false }) {
                            Slider(
                                value = opacity,
                                onValueChange = { opacity = it },
                                onValueChangeFinished = { prefs.notesOpacity = opacity },
                                valueRange = MIN_OPACITY..1f,
                                modifier = Modifier.width(200.dp).padding(horizontal = 16.dp),
                            )
                        }
                    }
                    SmallIconButton(Icons.Rounded.Close, stringResource(R.string.notes_hide), onClick = onHide)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
                        if (editingPage != null) {
                            val focus = remember { FocusRequester() }
                            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                textStyle = LocalTextStyle.current.copy(fontSize = fontSp.sp, lineHeight = (fontSp * 1.35f).sp),
                                placeholder = { Text(stringResource(R.string.notes_edit_hint)) },
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        } else if (text != null) {
                            Text(
                                text,
                                fontSize = fontSp.sp,
                                lineHeight = (fontSp * 1.35f).sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                // Light text on a transparent background: the dark halo keeps it readable even over a
                                // white slide.
                                style = LocalTextStyle.current.copy(
                                    shadow = if (opacity < 0.9f) Shadow(Color.Black.copy(alpha = 0.9f), blurRadius = 6f) else null,
                                ),
                            )
                        } else {
                            Text(
                                "—",
                                fontSize = fontSp.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.alpha(0.6f),
                            )
                        }
                    }
                    // Corner to resize.
                    Icon(
                        ResizeCorner, contentDescription = stringResource(R.string.notes_resize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(28.dp)
                            .pointerInput(Unit) {
                                detectDragGestures(onDragEnd = { persist() }) { change, drag: Offset ->
                                    change.consume()
                                    val (gx, gy, gw, gh) = geometry
                                    x = gx
                                    y = gy
                                    w = (gw + drag.x / density.density).coerceIn(MIN_W, (geometry[4] - gx).coerceAtLeast(MIN_W))
                                    h = (gh + drag.y / density.density).coerceIn(MIN_H, (geometry[5] - gy).coerceAtLeast(MIN_H))
                                }
                            }
                            .padding(6.dp),
                    )
                }
            }
        }
    }
}

private fun scrollStep(heightDp: Float, density: Float) = (heightDp - 60f).coerceAtLeast(40f) * 0.8f * density

private const val MIN_W = 280f
private const val MIN_H = 140f
private const val MIN_FONT = 12f
private const val MAX_FONT = 44f
private const val MIN_OPACITY = 0.2f

private val ResizeCorner by lazy {
    ImageVector.Builder("ResizeCorner", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes("M20 10 L10 20 M20 15 L15 20"),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
        )
        .build()
}
