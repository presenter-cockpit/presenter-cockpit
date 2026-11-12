package it.presenter.app.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.SpeakerNotes
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.PostAdd
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import it.presenter.app.R
import it.presenter.app.doc.PageEdit
import it.presenter.app.doc.PresenterDocument
import it.presenter.app.ink.ElementPainter
import it.presenter.app.ink.PAGE_WIDTH
import it.presenter.app.ink.PageElement
import it.presenter.app.session.PresenterSession
import kotlin.math.roundToInt

/**
 * Grid overview, tablet only: one thumbnail per slide (the last overlay, the complete slide; with overlays
 * off one per page), with the current slide highlighted and marks for annotations and notes. A tap jumps
 * to the slide (it enters the jump history).
 *
 * **Page editing** (icon at the top right, user's choice: outside it the overview changes nothing): hold a
 * slide and drag it to another place; under each thumbnail the icons to hide or show the slide (in the
 * .pdfpc, through [onHide], when the PDF's folder can be reached), to insert a blank page after it and to
 * delete the slide (with confirmation). Every page edit goes through [onEdit]; while one is being applied
 * ([busy]) no others are made.
 */
@Composable
fun Overview(
    session: PresenterSession,
    doc: PresenterDocument,
    busy: Boolean,
    onEdit: (PageEdit) -> Unit,
    onHide: (page: Int, hidden: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val slides = session.navSlides(doc)
    // First page of each slide; the thumbnail shows the last one.
    val firsts = remember(slides) { (0 until doc.pageCount).filter { slides.firstPageOf(it) == it } }
    val current = slides.slideOf(doc.pageIndex)
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
    var editing by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Int?>(null) }
    // Drag: slide taken (position in the grid), finger (grid coordinates), point of the thumbnail under the
    // finger and place where it would go if released.
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var dragWidth by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current

    fun move(from: Int, to: Int) {
        val pages = firsts[from]..slides.lastPageOf(firsts[from])
        val before = if (to < from) firsts[to] else firsts.getOrNull(to + 1) ?: doc.pageCount
        onEdit(PageEdit.Move(pages, before))
    }

    // Near the edges the grid scrolls by itself, so a slide can be carried far.
    LaunchedEffect(dragFrom != null) {
        val edge = with(density) { AUTOSCROLL_EDGE.toPx() }
        while (dragFrom != null) {
            withFrameNanos { }
            val height = grid.layoutInfo.viewportSize.height
            val dy = when {
                dragPos.y < edge -> -(edge - dragPos.y) / edge
                dragPos.y > height - edge -> (dragPos.y - (height - edge)) / edge
                else -> 0f
            }
            if (dy != 0f) {
                grid.scrollBy(dy.coerceIn(-1f, 1f) * with(density) { AUTOSCROLL_SPEED.toPx() })
                itemAt(grid, dragPos)?.let { target = it.index }
            }
        }
    }

    // Explicit content color: with a transparent background Material does not derive it, and the icons
    // (close, edit, blank page, delete) stayed dark on the dark background.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.97f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(220.dp),
                state = grid,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = GRID_PADDING_H, vertical = GRID_PADDING_V)
                    .pointerInput(editing, busy, doc, slides) {
                        if (!editing || busy) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // A movement before the long press is a scroll of the grid.
                            val long = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                            val item = itemAt(grid, long.position) ?: return@awaitEachGesture
                            dragFrom = item.index
                            target = item.index
                            grab = long.position - item.offset.toOffset()
                            dragWidth = item.size.width
                            dragPos = long.position
                            var released = false
                            try {
                                // Before the grid (initial pass): while dragging it does not scroll.
                                while (true) {
                                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == long.id } ?: break
                                    change.consume()
                                    if (!change.pressed) {
                                        released = true
                                        break
                                    }
                                    dragPos = change.position
                                    itemAt(grid, dragPos)?.let { target = it.index }
                                }
                                val from = dragFrom
                                val to = target
                                if (released && from != null && to != null && to != from && from in firsts.indices && to in firsts.indices) move(from, to)
                            } finally {
                                // Even if the gesture is interrupted (document reloaded, mode closed): otherwise the
                                // dragged thumbnail and the automatic scrolling would stay.
                                dragFrom = null
                                target = null
                            }
                        }
                    },
            ) {
                itemsIndexed(firsts) { i, first ->
                    val last = slides.lastPageOf(first)
                    val hasInk = doc.inkIfPresent(first)?.isEmpty == false
                    val hasNotes = (first..last).any { doc.notes?.noteFor(it) != null }
                    Thumbnail(
                        doc, last, number = i + 1, current = i == current, hasInk = hasInk, hasNotes = hasNotes, hidden = doc.isHidden(first),
                        bookmarked = doc.isBookmarked(first),
                        target = dragFrom != null && i == target && i != dragFrom,
                        modifier = if (i == dragFrom) Modifier.alpha(0.3f) else Modifier,
                        onClick = if (editing) {
                            null
                        } else {
                            {
                                session.jumpTo(doc, first)
                                onClose()
                            }
                        },
                    ) {
                        if (editing) {
                            Spacer(Modifier.weight(1f))
                            if (doc.source.canWritePdfpc) {
                                val hidden = doc.isHidden(first)
                                SmallIconButton(
                                    if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                    stringResource(if (hidden) R.string.overview_show else R.string.overview_hide),
                                    enabled = !busy,
                                ) { onHide(first, !hidden) }
                            }
                            SmallIconButton(Icons.Rounded.PostAdd, stringResource(R.string.overview_insert_blank), enabled = !busy) {
                                onEdit(PageEdit.InsertBlank(last))
                            }
                            SmallIconButton(Icons.Rounded.Delete, stringResource(R.string.overview_delete), enabled = !busy && firsts.size > 1) {
                                deleting = i
                            }
                        }
                    }
                }
            }
            // The dragged slide follows the finger.
            dragFrom?.let { from ->
                val first = firsts.getOrNull(from) ?: return@let
                Thumbnail(
                    doc, slides.lastPageOf(first), number = from + 1, current = false, hasInk = false, hasNotes = false,
                    target = false,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (GRID_PADDING_H.toPx() + dragPos.x - grab.x).roundToInt(),
                                (GRID_PADDING_V.toPx() + dragPos.y - grab.y).roundToInt(),
                            )
                        }
                        .width(with(density) { dragWidth.toDp() })
                        .shadow(8.dp, RoundedCornerShape(6.dp)),
                    onClick = null,
                )
            }
            ActionIcon(Icons.Rounded.Close, stringResource(R.string.action_close), onClick = onClose)
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                BarToggle(Icons.Rounded.EditNote, stringResource(R.string.overview_edit), editing, stringResource(R.string.overview_edit_usage)) { editing = it }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    deleting?.let { i ->
        val first = firsts.getOrNull(i)
        if (first == null) {
            deleting = null
            return@let
        }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.overview_delete_title, i + 1)) },
            text = { Text(stringResource(R.string.overview_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onEdit(PageEdit.Delete(first..slides.lastPageOf(first)))
                }) { Text(stringResource(R.string.selection_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Annotations [elements] (page coordinates, [PAGE_WIDTH] wide) over a thumbnail. */
@Composable
private fun InkOverlay(elements: List<PageElement>) {
    val painter = remember { ElementPainter() }
    Canvas(Modifier.fillMaxSize()) {
        val scale = size.width / PAGE_WIDTH
        val pageToScreen = Matrix().apply { setScale(scale, scale) }
        drawIntoCanvas { c ->
            val canvas = c.nativeCanvas
            canvas.save()
            canvas.concat(pageToScreen)
            painter.draw(canvas, elements, pageToScreen)
            canvas.restore()
        }
    }
}

/** Thumbnail under point [pos] (grid coordinates), or null between two. */
private fun itemAt(grid: LazyGridState, pos: Offset): LazyGridItemInfo? =
    grid.layoutInfo.visibleItemsInfo.firstOrNull { Rect(it.offset.toOffset(), it.size.toSize()).contains(pos) }

private val GRID_PADDING_H = 20.dp
private val GRID_PADDING_V = 16.dp
private val AUTOSCROLL_EDGE = 72.dp
private val AUTOSCROLL_SPEED = 18.dp

/** Opacity of the thumbnail of a hidden slide. */
private const val HIDDEN_ALPHA = 0.35f

@Composable
private fun Thumbnail(
    doc: PresenterDocument,
    page: Int,
    number: Int,
    current: Boolean,
    hasInk: Boolean,
    hasNotes: Boolean,
    target: Boolean,
    /** Hidden in the .pdfpc: going forward and back skips it, so it is shown faded. */
    hidden: Boolean = false,
    bookmarked: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
) {
    var bitmap by remember(doc, page) { mutableStateOf(doc.pages.cachedThumbnail(page)) }
    DisposableEffect(doc, page) {
        var active = true
        val onReady: (Bitmap) -> Unit = { if (active) bitmap = it }
        if (bitmap == null) doc.pages.thumbnail(page, onReady)
        onDispose {
            active = false
            doc.pages.cancelThumbnail(page, onReady)
        }
    }
    val colors = MaterialTheme.colorScheme
    val borderColor = when {
        target -> colors.tertiary
        current -> colors.primary
        else -> colors.outlineVariant
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / doc.aspect(page))
                .clip(RoundedCornerShape(6.dp))
                .border(if (current || target) 4.dp else 1.dp, borderColor, RoundedCornerShape(6.dp))
                .background(Color.White)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        ) {
            bitmap?.let { b ->
                val image = remember(b) { b.asImageBitmap() }
                Image(
                    image, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().then(if (hidden) Modifier.alpha(HIDDEN_ALPHA) else Modifier),
                )
            }
            // The image comes from the PDF without Presenter's annotations: they are drawn on top, as on the page (ink
            // shared by the overlays of the slide). [editCount] is read so the thumbnail follows the changes.
            val elements = remember(doc, page, doc.editCount) { doc.inkIfPresent(page)?.elements?.toList().orEmpty() }
            if (elements.isNotEmpty()) InkOverlay(elements)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp).then(if (onClick == null) Modifier.fillMaxWidth() else Modifier),
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (current) colors.primary else colors.onSurface,
            )
            if (hidden) {
                val label = stringResource(R.string.overview_hidden)
                WithTooltip(label) { Icon(Icons.Rounded.VisibilityOff, contentDescription = label, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
            }
            if (bookmarked) Icon(Icons.Rounded.Bookmark, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
            if (hasInk) Icon(Icons.Rounded.Draw, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(16.dp))
            if (hasNotes) Icon(Icons.AutoMirrored.Rounded.SpeakerNotes, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(16.dp))
            actions()
        }
    }
}
