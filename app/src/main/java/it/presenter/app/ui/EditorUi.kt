package it.presenter.app.ui

import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.ImmutableStrokeInputBatch
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import it.presenter.app.R
import it.presenter.app.code.Highlighter
import it.presenter.app.code.TextFind
import it.presenter.app.describe
import it.presenter.app.files.TreeFiles
import it.presenter.app.ink.BrushFamilies
import it.presenter.app.ink.ElementPainter
import it.presenter.app.ink.InkStroke
import it.presenter.app.session.PresenterSession
import it.presenter.app.tabs.CodeInk
import it.presenter.app.tabs.EditorTab
import it.presenter.app.tabs.LocalShell
import it.presenter.app.tabs.OpenFile
import it.presenter.app.tools.ToolKind
import it.presenter.app.tools.ToolRef
import kotlin.math.abs
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.terminal.Terminal

/** IDE tab on the tablet: bar (tree, hidden files, terminal, path, save), file tabs, tree and code. */
@Composable
internal fun EditorContent(session: PresenterSession, tab: EditorTab, colors: CodeColors, readOnly: Boolean, shield: Boolean) {
    // Back on the IDE: the folders of the terminals are reread once (icon in the tree).
    LaunchedEffect(tab) { session.refreshTerminalDirs(tab) }
    // Files and folders changed by others (terminal, other apps), while the IDE is in front and the app visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(tab, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                tab.checkDisk()
                delay(EditorTab.DISK_CHECK_MS)
            }
        }
    }
    // The file in the editor changed elsewhere while it has unsaved changes: told once.
    val changed = tab.active?.takeIf { it.changedOnDisk }
    LaunchedEffect(changed) { changed?.let { session.noticePath = R.string.code_changed_on_disk to it.name } }
    var reloadAsk by remember(tab) { mutableStateOf<OpenFile?>(null) }
    // Go to line: dialog asked with the icon of the search bar or Ctrl+G.
    var gotoAsk by remember(tab) { mutableStateOf(false) }
    // Tree open at the start (the last folder is in view); the state stays with the tab.
    var showTree by tab::showTree
    // File tab to close, waiting for the answer about unsaved changes.
    var closing by remember(tab) { mutableStateOf<OpenFile?>(null) }
    fun requestClose(f: OpenFile) {
        if (f.isDirty) closing = f else tab.closeFile(f)
    }
    // On-screen keyboard closed by the system (back gesture): the icon goes back to "show" and it does not reopen by
    // itself.
    @OptIn(ExperimentalLayoutApi::class)
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            if (tab.softKeyboard) tab.imeShown = true
        } else if (tab.imeShown) {
            tab.imeShown = false
            tab.softKeyboard = false
        }
    }
    // Tapping a file tab gives the focus to the tab: shortcuts work at once, even without touching the code.
    val focus = remember { FocusRequester() }
    Column(
        Modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@onPreviewKeyEvent false
            when (e.key) {
                Key.S -> tab.save()
                Key.F -> tab.findOpen = true
                Key.G -> if (tab.active != null) gotoAsk = true
                Key.Tab -> tab.cycle(if (e.isShiftPressed) -1 else 1)
                Key.W -> tab.active?.let(::requestClose)
                else -> return@onPreviewKeyEvent false
            }
            true
        }.focusRequester(focus).focusable(),
    ) {
        FontRow(tab, beforeFont = { KeyboardToggle(tab) }) {
            WithTooltip(stringResource(R.string.code_files), stringResource(R.string.code_tree_usage)) {
                FilledTonalIconToggleButton(checked = showTree, onCheckedChange = { showTree = it }) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = stringResource(R.string.code_files))
                }
            }
            // With the tree open: shows or hides hidden files and folders.
            if (showTree) {
                WithTooltip(stringResource(R.string.code_show_hidden)) {
                    FilledTonalIconToggleButton(checked = tab.showHidden, onCheckedChange = { tab.showHidden = it }) {
                        Icon(if (tab.showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, contentDescription = stringResource(R.string.code_show_hidden))
                    }
                }
                // Terminal in the root folder of the tree (on the server or, except in the store version, on the
                // tablet).
                if ((tab.server != null || LocalShell.canOpenIn(tab.root)) && tab.root.isNotEmpty()) {
                    ActionIcon(Icons.Rounded.Terminal, stringResource(R.string.code_terminal_root)) { session.terminalAt(tab, tab.root) }
                }
            }
            if (tab.active != null) {
                ActionIcon(Icons.Rounded.Search, stringResource(R.string.code_find), usage = stringResource(R.string.usage_keyboard, "Ctrl+F")) {
                    tab.findOpen = !tab.findOpen
                }
            }
            val shownPath = (tab.openPath ?: tab.root).let { p ->
                if (TreeFiles.isTreePath(p)) {
                    val ctx = LocalContext.current
                    remember(p) { TreeFiles.displayPath(ctx, p) }
                } else p
            }
            Text(
                shownPath,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.padding(horizontal = 8.dp).weight(1f, fill = false),
            )
            // Changed elsewhere with unsaved changes here: reload (with confirmation), or save to overwrite.
            changed?.let { f ->
                val label = stringResource(if (tab.server != null) R.string.action_reload_from_server else R.string.action_reload_from_disk)
                WithTooltip(label) {
                    FilledTonalIconButton(
                        onClick = { reloadAsk = f },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) { Icon(Icons.Rounded.SyncProblem, contentDescription = label) }
                }
            }
            if (tab.saving) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
            } else {
                ActionIcon(Icons.Rounded.Save, stringResource(R.string.code_save), enabled = tab.isDirty, usage = stringResource(R.string.usage_keyboard, "Ctrl+S")) { tab.save() }
            }
        }
        val active = tab.active
        if (tab.findOpen && active != null) FindBar(tab, active, onGoto = { gotoAsk = true })
        tab.error?.let { Text(it.describe(LocalContext.current), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp)) }
        if (tab.files.isNotEmpty()) FileTabs(tab, onClose = ::requestClose, onSelect = { runCatching { focus.requestFocus() } })
        HorizontalDivider()
        var rowWidth by remember { mutableIntStateOf(0) }
        Row(Modifier.weight(1f).onSizeChanged { rowWidth = it.width }) {
            if (showTree) {
                // A tap opens the file as a preview and the tree stays (to browse the files); a double tap keeps it
                // open and closes the tree.
                FileTree(
                    session, tab,
                    onOpen = { path, pin ->
                        tab.open(path, pin)
                        if (pin) showTree = false
                    },
                    modifier = Modifier.width(tab.treeWidth.dp).fillMaxHeight(),
                )
                TreeSplitter(tab, maxWidth = with(LocalDensity.current) { rowWidth.toDp().value } - MIN_EDITOR_WIDTH)
            }
            val file = tab.active
            if (file != null) {
                Box(Modifier.weight(1f).fillMaxHeight().pinchFontSize(session, tab).clipToBounds()) {
                    if (file.loaded) {
                        // Each file has its own composition: cursor and scrolling do not pass from one to another.
                        androidx.compose.runtime.key(file) { CodeEditor(session, tab, file, colors, readOnly, Modifier.fillMaxSize()) }
                    } else {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    PenShield(shield)
                }
            }
        }
    }
    if (gotoAsk) {
        tab.active?.let { f -> GotoLineDialog(f) { gotoAsk = false } } ?: run { gotoAsk = false }
    }
    reloadAsk?.let { f ->
        ConfirmDialog(
            stringResource(if (tab.server != null) R.string.reload_title_server else R.string.reload_title),
            stringResource(R.string.code_reload_text, f.name),
            stringResource(R.string.action_reload),
            onConfirm = { f.reloadFromDisk() },
            onDismiss = { reloadAsk = null },
            danger = true,
        )
    }
    closing?.let { f ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text(stringResource(R.string.code_unsaved_title)) },
            text = { Text(stringResource(R.string.code_unsaved_file_close, f.name)) },
            confirmButton = {
                TextButton(onClick = {
                    closing = null
                    // It closes only if saving succeeded.
                    tab.save(f) { ok -> if (ok) tab.closeFile(f) }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        tab.closeFile(f)
                        closing = null
                    }) { Text(stringResource(R.string.action_dont_save)) }
                    TextButton(onClick = { closing = null }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
}

/**
 * Tabs of the open files, in a scrolling row; the active one stays in view. Italic name for the preview,
 * dot for unsaved changes, ✕ to close. Tap: switch to the file; double tap: keep the preview open.
 */
@Composable
private fun FileTabs(tab: EditorTab, onClose: (OpenFile) -> Unit, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val state = rememberLazyListState()
    val activeIndex = tab.files.indexOf(tab.active)
    LaunchedEffect(activeIndex, tab.files.size) {
        if (activeIndex < 0) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()
        val last = visible.lastOrNull()
        val inView = visible.any { it.index == activeIndex } &&
            !(activeIndex == first?.index && first.offset < 0) &&
            !(activeIndex == last?.index && last.offset + last.size > state.layoutInfo.viewportEndOffset)
        if (!inView) state.animateScrollToItem(activeIndex)
    }
    val doubleTap = remember { DoubleTap() }
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(tab.files, key = { it.path }) { f ->
            val selected = f === tab.active
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) colors.secondaryContainer else Color.Transparent)
                    .clickable {
                        tab.activate(f)
                        if (doubleTap.hit(f.path)) f.preview = false
                        onSelect()
                    }
                    .padding(start = 10.dp, end = 0.dp),
            ) {
                Text(
                    f.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontStyle = if (f.preview) FontStyle.Italic else FontStyle.Normal,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                if (f.isDirty) Box(Modifier.padding(start = 6.dp).size(7.dp).background(colors.primary, CircleShape))
                SmallIconButton(Icons.Rounded.Close, stringResource(R.string.code_close_file), usage = stringResource(R.string.usage_keyboard, "Ctrl+W")) { onClose(f) }
            }
        }
    }
}

/**
 * Border between tree and files: dragged with the finger or the pen it changes the width of the tree
 * ([EditorTab.treeWidth]), between [MIN_TREE_WIDTH] and [maxWidth] (dp). The grab zone is wider than the
 * line, which lights up while dragging.
 */
@Composable
private fun TreeSplitter(tab: EditorTab, maxWidth: Float) {
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    val upper = maxWidth.coerceAtLeast(MIN_TREE_WIDTH)
    val state = rememberDraggableState { delta ->
        tab.treeWidth = (tab.treeWidth + with(density) { delta.toDp().value }).coerceIn(MIN_TREE_WIDTH, upper)
    }
    Box(
        Modifier
            .width(SPLITTER_GRIP)
            .fillMaxHeight()
            .draggable(
                state, Orientation.Horizontal,
                onDragStarted = { dragging = true },
                onDragStopped = { dragging = false },
            ),
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .width(if (dragging) 3.dp else 1.dp)
                .fillMaxHeight()
                .background(if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

private const val MIN_TREE_WIDTH = 140f
private const val MIN_EDITOR_WIDTH = 200f
private val SPLITTER_GRIP = 16.dp

/** Highlighting as a visual transformation: the text stays the file's. */
/** Syntax colors, and the search result [mark] (if any) on a colored background. */
private class HighlightTransformation(
    private val lang: Highlighter.Language,
    private val colors: CodeColors,
    private val mark: IntRange? = null,
) : VisualTransformation {
    private var lastText: String? = null
    private var last: AnnotatedString? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        var styled = if (s == lastText) last!! else annotate(s, lang, colors).also { lastText = s; last = it }
        val m = mark
        if (m != null && m.first >= 0 && m.last < s.length && !m.isEmpty()) {
            styled = AnnotatedString.Builder(styled).apply { addStyle(SpanStyle(background = SEARCH_MARK), m.first, m.last + 1) }.toAnnotatedString()
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    // Two transformations are the same when they draw the same: the field then keeps its state.
    override fun equals(other: Any?) = other is HighlightTransformation && other.lang == lang && other.colors == colors && other.mark == mark
    override fun hashCode() = (lang.hashCode() * 31 + colors.hashCode()) * 31 + (mark?.hashCode() ?: 0)
}

/** Background of the search result in the editor (yellow, see-through: readable on light and dark themes). */
private val SEARCH_MARK = Color(0x80FFC107)

/** Line pitch, averaged over the whole text (the first line has its own margin). */
internal fun averageLineHeight(r: TextLayoutResult): Float {
    val last = r.lineCount - 1
    return (r.getLineTop(last) - r.getLineTop(1)) / (last - 1)
}

internal fun annotate(text: String, lang: Highlighter.Language, colors: CodeColors): AnnotatedString {
    val b = AnnotatedString.Builder(text)
    for (span in Highlighter.highlight(text, lang)) {
        val c = colors.spans[span.kind] ?: continue
        val style = when (span.kind) {
            Highlighter.Kind.COMMENT -> SpanStyle(color = c, fontStyle = FontStyle.Italic)
            Highlighter.Kind.KEYWORD, Highlighter.Kind.HEADING -> SpanStyle(color = c, fontWeight = FontWeight.SemiBold)
            else -> SpanStyle(color = c)
        }
        b.addStyle(style, span.start, span.end)
    }
    return b.toAnnotatedString()
}

/** Code of [file]: line numbers, highlighting, pen strokes over the text; the projector follows its first line. */
@Composable
private fun CodeEditor(session: PresenterSession, tab: EditorTab, file: OpenFile, colors: CodeColors, readOnly: Boolean, modifier: Modifier) {
    val path = file.path
    val lang = remember(path) { Highlighter.forFile(file.name) }
    val transformation = remember(lang, colors, file.mark) { HighlightTransformation(lang, colors, file.mark) }
    var value by remember(file) { mutableStateOf(TextFieldValue(file.text, TextRange(file.cursor.coerceIn(0, file.text.length)))) }
    // The text may arrive later (file read): the field is brought in line.
    if (value.text != file.text) value = TextFieldValue(file.text, TextRange(file.cursor.coerceIn(0, file.text.length)))
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = tab.fontSize.sp, lineHeight = (tab.fontSize * LINE_HEIGHT).sp, color = colors.foreground)
    // Scrolling per tab and file: start from the saved line (0 for a file just opened).
    val scroll = remember(file) { ScrollState(0) }
    val density = LocalDensity.current
    // Real line height, measured on the text: the one computed from sp may differ (~10% less on the Tab S9+)
    // and would get wrong the first visible line and the projector's scale. The ratio stays when changing the
    // font, so the value is right even before the new measurement.
    var lineHeightRatio by remember { mutableFloatStateOf(1f) }
    val computedLineHeight = with(density) { (tab.fontSize * LINE_HEIGHT).sp.toPx() }
    val lineHeightPx = computedLineHeight * lineHeightRatio
    val lineHeight by rememberUpdatedState(lineHeightPx)
    // First visible line: the projector shows from there.
    LaunchedEffect(scroll) {
        val target = (file.firstVisibleLine * lineHeight).toInt()
        if (target > 0) {
            withTimeoutOrNull(2_000) { snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE && it >= target } }
            scroll.scrollTo(target)
        }
        snapshotFlow { scroll.value }.collect {
            file.firstVisibleLine = (it / lineHeight).toInt()
            tab.codeInk.scrollPx = it
        }
    }
    // Font changed (pinch, A−/A+): the same line stays at the top.
    var shownLineHeight by remember(scroll) { mutableFloatStateOf(lineHeightPx) }
    LaunchedEffect(lineHeightPx) {
        if (lineHeightPx != shownLineHeight) {
            shownLineHeight = lineHeightPx
            scroll.scrollTo((file.firstVisibleLine * lineHeightPx).toInt())
        }
    }
    val lines = remember(value.text) { value.text.count { it == '\n' } + 1 }
    // Pen strokes over the code: tied to the screen, they disappear when scrolling (vertically or
    // horizontally) and when changing file or font size.
    val hscroll = remember(file) { ScrollState(0) }
    val codeInk = tab.codeInk
    val fontPx = with(density) { tab.fontSize.sp.toPx() }
    SideEffect {
        codeInk.lineHeightPx = lineHeightPx
        codeInk.fontPx = fontPx
    }
    LaunchedEffect(file, lineHeightPx) {
        codeInk.clear()
        // Only a real scroll (half a line): a few pixels do not erase (the strokes follow the text).
        var base = scroll.value to hscroll.value
        snapshotFlow { scroll.value to hscroll.value }.collect { (v, h) ->
            codeInk.hscrollPx = h
            if (abs(v - base.first) > lineHeight / 2 || abs(h - base.second) > lineHeight / 2) {
                base = v to h
                codeInk.clear()
            }
        }
    }
    // Start of the text in the editor box (it moves with the vertical scroll).
    var boxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var fieldCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    fun textOrigin(): Offset {
        val b = boxCoords ?: return Offset.Zero
        val f = fieldCoords?.takeIf { it.isAttached } ?: return Offset.Zero
        return b.localPositionOf(f, Offset.Zero) - Offset(hscroll.value.toFloat(), 0f)
    }
    // The tab's laser anchors to the same start of the text.
    DisposableEffect(codeInk) {
        val origin: (LayoutCoordinates) -> Offset? = { ref ->
            fieldCoords?.takeIf { it.isAttached && ref.isAttached }?.let { f -> ref.localPositionOf(f, Offset.Zero) - Offset(hscroll.value.toFloat(), 0f) }
        }
        codeInk.originIn = origin
        onDispose { if (codeInk.originIn === origin) codeInk.originIn = null }
    }
    Box(
        modifier
            .onGloballyPositioned { c ->
                boxCoords = c
                codeInk.viewportHeightPx = c.size.height.toFloat()
                codeInk.viewportWidthPx = c.size.width.toFloat()
            }
            .rejectPalm(session)
            .codeInkInput(session, codeInk, ::textOrigin)
            .codeInkDrawing(codeInk, ::textOrigin),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            Text(
                (1..lines).joinToString("\n"),
                style = style.copy(color = colors.gutter, textAlign = TextAlign.End),
                modifier = Modifier.width(with(density) { (tab.fontSize * 0.62f * (lines.toString().length + 1)).sp.toDp() }).padding(end = 6.dp),
            )
            // The on-screen keyboard connects to the field only if requested with the icon: otherwise the field's
            // request to the system stays pending (taps and cursor work, the physical keyboard types). The icon opens
            // and closes the keyboard session on the same field, which keeps the focus.
            val fieldFocus = remember { FocusRequester() }
            // Search and go to line: the text is scrolled into view, a few lines from the top. Going to a line also
            // puts the cursor there; a search result is only marked (the focus stays in the search field, where Enter
            // goes to the next one: with the result selected, Enter would replace it).
            LaunchedEffect(file.reveal) {
                val r = file.reveal ?: return@LaunchedEffect
                file.reveal = null
                val text = value.text
                val start = r.first.coerceIn(0, text.length)
                if (r.isEmpty()) {
                    value = value.copy(selection = TextRange(start))
                    file.cursor = start
                    runCatching { fieldFocus.requestFocus() }
                }
                val line = TextFind.lineOf(text, start)
                scroll.animateScrollTo(((line - REVEAL_LINES_ABOVE).coerceAtLeast(0) * lineHeight).toInt())
                val column = start - TextFind.lineStart(text, line + 1)
                val x = (column * fontPx * CHAR_WIDTH_RATIO - codeInk.viewportWidthPx / 3f).toInt()
                hscroll.animateScrollTo(x.coerceIn(0, hscroll.maxValue))
            }
            val keyboardController = LocalSoftwareKeyboardController.current
            LaunchedEffect(tab.softKeyboard) {
                if (tab.softKeyboard) {
                    withFrameNanos { }
                    runCatching { fieldFocus.requestFocus() }
                    keyboardController?.show()
                }
            }
            @OptIn(ExperimentalComposeUiApi::class)
            InterceptPlatformTextInput(
                interceptor = { request, next ->
                    while (true) {
                        snapshotFlow { tab.softKeyboard }.first { it }
                        coroutineScope<Unit> {
                            // The field presents itself to the keyboard as "visible password, no suggestions", like the
                            // terminal (termlib): requested with the pen (icon tapped with the S Pen) Gboard showed
                            // only its handwriting bar ("IT, enter, delete") instead of the keyboard, and it does not
                            // do that on a password field. For code it is fine anyway: no autocorrection nor
                            // suggestions.
                            val noHandwriting = PlatformTextInputMethodRequest { info ->
                                request.createInputConnection(info).also {
                                    info.inputType = InputType.TYPE_CLASS_TEXT or
                                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                    if (Build.VERSION.SDK_INT >= 35) info.setStylusHandwritingEnabled(false)
                                }
                            }
                            val session = this.launch { next.startInputMethod(noHandwriting) }
                            snapshotFlow { tab.softKeyboard }.first { !it }
                            session.cancel()
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    awaitCancellation()
                },
            ) {
                BasicTextField(
                    onTextLayout = { r ->
                        if (r.lineCount > 2) {
                            val measured = averageLineHeight(r)
                            // Small differences too: lines are rounded to pixels (39 instead of 38.6) and the error
                            // adds up going down (half a pixel per line is several lines at line 300).
                            if (abs(measured - lineHeightPx) > 0.001f) lineHeightRatio = measured / computedLineHeight
                        }
                    },
                    value = value,
                    onValueChange = {
                        value = it
                        file.edit(it.text)
                        file.cursor = it.selection.start
                    },
                    textStyle = style,
                    readOnly = readOnly,
                    visualTransformation = transformation,
                    cursorBrush = SolidColor(colors.foreground),
                    modifier = Modifier.onGloballyPositioned { c -> fieldCoords = c }.focusRequester(fieldFocus).horizontalScroll(hscroll).padding(end = 24.dp),
                )
            }
        }
    }
}

/**
 * Palm rejection over the code, as on the pages: with the pen nearby the fingers (the palm resting while
 * writing) do not scroll the text, which would erase the strokes, and do not zoom.
 */
private fun Modifier.rejectPalm(session: PresenterSession): Modifier = pointerInput(session) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (!session.workStylusNear) continue
            event.changes.forEach { if (it.type == PointerType.Touch) it.consume() }
        }
    }
}

/**
 * Pen over the code (pen, marker, highlighter with their brush, and eraser): the S Pen is taken before the
 * editor (Initial pass). Widths as on the pages: the width of the box counts as 1000 page units. Laser,
 * shapes, text and lasso do not write here.
 */
private fun Modifier.codeInkInput(session: PresenterSession, ci: CodeInk, origin: () -> Offset): Modifier = pointerInput(ci) {
    val batch = MutableStrokeInputBatch()
    var erasing = false
    var lastX = 0f
    var lastY = 0f
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: continue
            val eraserTip = change.type == PointerType.Eraser
            if (change.type != PointerType.Stylus && !eraserTip) continue
            if (!change.pressed && !change.previousPressed) continue
            val tools = session.tools
            // Side button: MainActivity follows it from the raw events (it does not always arrive here).
            val buttons = session.tools.buttonHeld || event.buttons.isSecondaryPressed
            val ref = tools.effective(buttonPressed = buttons, eraserTip = eraserTip)
            val preset = tools.preset(ref)
            val inkTool = preset != null && preset.kind in CODE_INK_KINDS
            val k = size.width / 1000f
            val o = origin()
            val x = change.position.x - o.x
            val y = change.position.y - o.y
            val starting = change.pressed && !change.previousPressed
            if (starting) {
                erasing = ref == ToolRef.Eraser
                if (!erasing && !inkTool) continue
                if (erasing) {
                    ci.ink.beginChange()
                    lastX = x
                    lastY = y
                } else {
                    val brush = BrushFamilies.brushFor(preset!!.copy(size = preset.size * k)) ?: continue
                    ci.live = InProgressStroke().apply { start(brush) }
                    ci.liveDown = change.uptimeMillis
                }
            } else if (!erasing && ci.live == null) {
                continue
            }
            if (erasing) {
                if (ci.ink.eraseAlong(lastX, lastY, x, y, tools.eraserSize * k, tools.eraseMode)) ci.changed()
                lastX = x
                lastY = y
                if (!change.pressed) {
                    ci.ink.endChange()
                    erasing = false
                    ci.changed()
                }
            } else {
                val live = ci.live!!
                batch.clear()
                val unitCm = 2.54f / (160f * density)
                val points = if (starting) emptyList() else change.historical.map { it.position to it.uptimeMillis }
                for ((p, t) in points + (change.position to change.uptimeMillis)) {
                    runCatching {
                        batch.add(
                            InputToolType.STYLUS, p.x - o.x, p.y - o.y, t - ci.liveDown, unitCm,
                            change.pressure.coerceIn(0f, 1f), StrokeInput.NO_TILT, StrokeInput.NO_ORIENTATION,
                        )
                    }
                }
                runCatching { live.enqueueInputs(batch, ImmutableStrokeInputBatch.EMPTY) }
                if (!change.pressed) {
                    runCatching {
                        live.finishInput()
                        live.updateShape(change.uptimeMillis - ci.liveDown)
                        ci.ink.add(listOf(InkStroke(live.toImmutable())))
                    }
                    ci.live = null
                }
                ci.changed()
            }
            change.consume()
        }
    }
}

/** Draws the strokes over the code, in the same frame as the text. */
@Composable
private fun Modifier.codeInkDrawing(ci: CodeInk, origin: () -> Offset): Modifier {
    val painter = remember { ElementPainter() }
    val liveRenderer = remember { CanvasStrokeRenderer.create() }
    return drawWithContent {
        drawContent()
        ci.version
        val o = origin()
        val m = Matrix().apply { setTranslate(o.x, o.y) }
        drawIntoCanvas { c -> drawCodeInk(c.nativeCanvas, ci, m, painter, liveRenderer) }
    }
}

/** Strokes of [ci] with [toScreen] (from the editor content to the screen); tablet and projector. */
internal fun drawCodeInk(
    canvas: Canvas,
    ci: CodeInk,
    toScreen: Matrix,
    painter: ElementPainter,
    liveRenderer: CanvasStrokeRenderer,
) {
    val live = ci.live
    if (ci.ink.isEmpty && live == null) return
    canvas.save()
    canvas.concat(toScreen)
    painter.draw(canvas, ci.ink.elements, toScreen)
    if (live != null) {
        runCatching { live.updateShape(SystemClock.uptimeMillis() - ci.liveDown) }
        liveRenderer.draw(canvas, live, toScreen)
    }
    canvas.restore()
}

private val CODE_INK_KINDS = setOf(ToolKind.PEN, ToolKind.MARKER, ToolKind.HIGHLIGHTER)

internal const val LINE_HEIGHT = 1.35f

/** Lines left visible above the text shown by search and go to line. */
private const val REVEAL_LINES_ABOVE = 3

/** Width of a monospace character, in font sizes (to scroll to a column). */
private const val CHAR_WIDTH_RATIO = 0.6f

/**
 * Search in the file in the editor (Ctrl+F): results as you type, from the cursor on; Enter or the arrows go
 * to the next and previous one, wrapping around. The icon on the right asks for a line to go to (Ctrl+G).
 */
@Composable
private fun FindBar(tab: EditorTab, file: OpenFile, onGoto: () -> Unit) {
    val matches = remember(file.text, tab.findQuery) { TextFind.matches(file.text, tab.findQuery) }
    var index by remember(file, tab.findQuery) { mutableIntStateOf(-1) }
    val length = tab.findQuery.length
    fun show(i: Int) {
        if (matches.isEmpty()) return
        index = Math.floorMod(i, matches.size)
        file.mark = matches[index] until matches[index] + length
        file.reveal = file.mark
    }
    // Closing the search (or changing it to nothing found) takes the mark away.
    DisposableEffect(file) { onDispose { file.mark = null } }
    LaunchedEffect(matches) { if (matches.isEmpty()) file.mark = null }
    // A new search starts from the first result after the cursor.
    LaunchedEffect(matches) {
        if (matches.isNotEmpty()) show(matches.indexOfFirst { it >= file.cursor }.takeIf { it >= 0 } ?: 0)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        OutlinedTextField(
            value = tab.findQuery,
            onValueChange = { tab.findQuery = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.code_find)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { show(index + 1) }),
            modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { e ->
                when {
                    e.type != KeyEventType.KeyDown -> false
                    e.key == Key.Enter -> { show(if (e.isShiftPressed) index - 1 else index + 1); true }
                    e.key == Key.Escape -> { tab.findOpen = false; true }
                    else -> false
                }
            },
        )
        Text(
            if (matches.isEmpty()) "0" else "${index + 1}/${matches.size}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        SmallIconButton(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.code_find_previous), enabled = matches.isNotEmpty()) { show(index - 1) }
        SmallIconButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.code_find_next), enabled = matches.isNotEmpty()) { show(index + 1) }
        SmallIconButton(Icons.Rounded.FormatListNumbered, stringResource(R.string.code_goto), usage = stringResource(R.string.usage_keyboard, "Ctrl+G"), onClick = onGoto)
        SmallIconButton(Icons.Rounded.Close, stringResource(R.string.code_find_close)) { tab.findOpen = false }
    }
}

/** Asks for a line of [file] and shows it (Ctrl+G). */
@Composable
private fun GotoLineDialog(file: OpenFile, onDismiss: () -> Unit) {
    var line by remember { mutableStateOf("") }
    val lines = remember(file.text) { file.text.count { it == '\n' } + 1 }
    val number = line.trim().toIntOrNull()
    fun go() {
        val n = number ?: return
        val start = TextFind.lineStart(file.text, n.coerceIn(1, lines))
        file.reveal = start until start
        onDismiss()
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.code_goto)) },
        text = {
            OutlinedTextField(
                value = line,
                onValueChange = { line = it.filter(Char::isDigit).take(7) },
                singleLine = true,
                label = { Text(pluralStringResource(R.plurals.code_goto_range, lines, lines)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = ::go, enabled = number != null) { Text(stringResource(R.string.code_goto_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
