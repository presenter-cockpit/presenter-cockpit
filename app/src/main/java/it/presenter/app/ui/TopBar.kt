package it.presenter.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.SpeakerNotes
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.SpeakerNotes
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import it.presenter.app.R
import it.presenter.app.doc.DocSource
import it.presenter.app.doc.PresenterDocument
import it.presenter.app.session.PageRef
import it.presenter.app.session.PresenterSession
import it.presenter.app.tabs.EditorTab
import it.presenter.app.tabs.LinkStatus
import it.presenter.app.tabs.TerminalTab
import it.presenter.app.tabs.WorkTab
import it.presenter.app.workspace.Workspace

@Composable
fun TopBar(
    session: PresenterSession,
    onOpen: () -> Unit,
    onSave: (PresenterDocument) -> Unit,
    onClose: (PresenterDocument) -> Unit,
    onReload: (PresenterDocument) -> Unit = {},
    onReconnect: (PresenterDocument) -> Unit = {},
    onOpenWorkspace: (Workspace) -> Unit = {},
) {
    val doc = session.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Workspaces and new tab stay fixed on the left: with many tabs they do not end up at the end of the
            // scrolling list.
            WorkspaceMenu(session, onOpenWorkspace)
            NewTabMenu(session, onOpenPdf = onOpen)
            BarDivider()
            val scroll = rememberScrollState()
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(TAB_SPACING),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // PDFs and work tabs in the order chosen by the user (long press and drag).
                val drag = rememberTabDrag(session, scroll)
                val tabs = session.orderedTabs
                // Code and terminals on more than one machine (the tablet counts as one): their names say where.
                val showMachine = session.workTabs.mapNotNull { it.machineKey() }.distinct().size > 1
                tabs.forEachIndexed { i, tab ->
                    key(tab) {
                        // The tab that becomes active (keyboard, link, new tab) scrolls into view by itself.
                        val active = if (tab is PresenterDocument) tab === doc && session.activeWork == null else tab === session.activeWork
                        val inView = remember { BringIntoViewRequester() }
                        LaunchedEffect(active) { if (active) inView.bringIntoView() }
                        Box(drag.modifier(tab, i, tabs).bringIntoViewRequester(inView)) {
                            when (tab) {
                                is PresenterDocument -> DocTab(tab, selected = tab === doc && session.activeWork == null, onClick = { session.switchTo(tab) }, onClose = { onClose(tab) })
                                is WorkTab -> WorkTabChip(
                                    tab, selected = tab === session.activeWork, showMachine = showMachine, onClick = { session.showWork(tab) },
                                    // Unsaved code or running shell: ask first (dialog in MainActivity).
                                    onClose = {
                                        val ask = (tab is EditorTab && tab.hasUnsaved) ||
                                            (tab is TerminalTab && tab.status == LinkStatus.Connected)
                                        if (ask) session.closingWork = tab else session.closeWork(tab, discard = true)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (doc != null && session.activeWork == null) {
                BarDivider()
                // After a jump (link, outline, search): back to the starting page.
                if (session.jumpHistory.isNotEmpty()) {
                    ActionIcon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_jump_back), compact = true) { session.jumpBack() }
                }
                val slides = session.navSlides(doc)
                ActionIcon(Icons.Rounded.ChevronLeft, stringResource(R.string.action_previous_page), enabled = session.slideTarget(PageRef(doc, doc.pageIndex), -1) != null, compact = true) { session.previousPage() }
                // Slide number (with Beamer overlays it counts slides, not PDF pages): tapping it opens the navigation
                // panel (go to, outline, search).
                Text(
                    "${slides.slideOf(doc.pageIndex) + 1} / ${slides.slideCount}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { session.navigationOpen = !session.navigationOpen }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                )
                ActionIcon(Icons.Rounded.ChevronRight, stringResource(R.string.action_next_page), enabled = session.slideTarget(PageRef(doc, doc.pageIndex), 1) != null, compact = true) { session.nextPage() }
                BarToggle(Icons.Rounded.TravelExplore, stringResource(R.string.nav_open), session.navigationOpen, stringResource(R.string.usage_keyboard, "G")) {
                    session.navigationOpen = it
                }
                BarToggle(Icons.Rounded.GridView, stringResource(R.string.overview), session.overviewOpen, stringResource(R.string.usage_keyboard, "Tab")) {
                    session.overviewOpen = it
                }
                BarDivider()
                // Notes icon: filled if the PDF has notes (.pdfpc), outlined if they can be added (the .pdfpc can be
                // written); none otherwise. It opens and closes the panel, where the notes are also edited.
                if (doc.notesAvailable) {
                    val hasNotes = doc.notes != null
                    BarToggle(
                        if (hasNotes) Icons.AutoMirrored.Rounded.SpeakerNotes else Icons.AutoMirrored.Outlined.SpeakerNotes,
                        stringResource(if (doc.notesVisible) R.string.notes_hide else if (hasNotes) R.string.notes_show else R.string.notes_add),
                        doc.notesVisible, stringResource(R.string.usage_keyboard, "N"),
                    ) { doc.notesVisible = it }
                }
                // PDF on an unreachable server: you work on the tablet copy; a tap retries.
                (doc.source as? DocSource.Remote)?.let { src ->
                    src.offline?.let { reason ->
                        val label = stringResource(R.string.remote_doc_offline, src.server?.label ?: "?", reason.describeShort(LocalContext.current))
                        WithTooltip(label, stringResource(R.string.remote_doc_offline_usage)) {
                            IconButton(onClick = { onReconnect(doc) }, modifier = Modifier.size(BAR_BUTTON)) {
                                Icon(Icons.Rounded.CloudOff, contentDescription = label, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(BAR_ICON))
                            }
                        }
                    }
                }
                // The file changed on disk while there were changes: reloading discards them (it asks).
                if (doc.changedOnDisk) {
                    val reloadLabel = stringResource(if (doc.source is DocSource.Remote) R.string.action_reload_from_server else R.string.action_reload_from_disk)
                    WithTooltip(reloadLabel) {
                        FilledTonalIconButton(
                            onClick = { onReload(doc) },
                            modifier = Modifier.size(BAR_BUTTON),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) { Icon(Icons.Rounded.SyncProblem, contentDescription = reloadLabel, modifier = Modifier.size(BAR_ICON)) }
                    }
                }
                if (doc.isSaving) {
                    Box(Modifier.size(BAR_BUTTON), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                    }
                } else {
                    ActionIcon(Icons.Rounded.Save, stringResource(R.string.action_save_strokes), enabled = doc.isDirty, usage = stringResource(R.string.usage_keyboard, "Ctrl+S"), compact = true) { onSave(doc) }
                }
            }
            BarDivider()
            // Presenter side: presentation time; then what the audience sees.
            PresentationEndTimer(session)
            InvertButton(session)
            FreezeButton(session)
            PauseButton(session)
            OutputsButton(session)
            ActionIcon(Icons.AutoMirrored.Rounded.HelpOutline, stringResource(R.string.help_title), usage = stringResource(R.string.help_usage), compact = true) {
                session.toolEditor = null
                session.shortcutsOpen = true
            }
            ActionIcon(Icons.Rounded.Settings, stringResource(R.string.settings), compact = true) {
                session.toolEditor = null
                session.settingsOpen = true
            }
            // Time and battery (if turned on) at the far right, where the system shows them (user's choice).
            ClockAndBattery(session)
        }
    }
}

/**
 * Tab of an open document: PDF icon (with the tablet or the server), name, dot if there are annotations to
 * save, close. A remote PDF without its server has the crossed-out cloud instead of the server.
 */
@Composable
private fun DocTab(doc: PresenterDocument, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val remote = doc.source as? DocSource.Remote
    TabChip(
        title = doc.title, selected = selected, dirty = doc.isDirty,
        closeDescription = stringResource(R.string.action_close_document, doc.title),
        onClick = onClick, onClose = onClose, hue = TabKind.Pdf.hue,
    ) { tint, background ->
        if (remote?.offline != null) {
            BadgedIcon(Icons.Rounded.PictureAsPdf, Icons.Rounded.CloudOff, TAB_ICON, tint, background)
        } else {
            TabIcon(TabKind.Pdf, remote = remote != null, TAB_ICON, tint, background)
        }
    }
}

/**
 * Tab of the top bar (PDFs and work tabs): [icon] receives the icon color and the background color (for the
 * border of [BadgedIcon]'s badge); then the name, the [dirty] dot and the close. [hue] tints the background
 * slightly (more when selected), to tell the kinds of tab apart ([TabKind.hue]); without it, the theme
 * colors.
 */
@Composable
fun TabChip(
    title: String,
    selected: Boolean,
    dirty: Boolean,
    closeDescription: String,
    onClick: () -> Unit,
    onClose: () -> Unit,
    hue: Color? = null,
    icon: @Composable (tint: Color, background: Color) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background = if (hue == null) {
        if (selected) colors.secondaryContainer else colors.surfaceContainerHighest
    } else {
        lerp(colors.surfaceContainerHighest, hue, if (selected) TAB_HUE_SELECTED else TAB_HUE_IDLE)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(TAB_HEIGHT)
            .clip(RoundedCornerShape(TAB_HEIGHT / 2))
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 2.dp),
    ) {
        icon(if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant, background)
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp).widthIn(max = 160.dp),
        )
        if (dirty) Box(Modifier.padding(start = 5.dp).size(7.dp).background(colors.primary, CircleShape))
        WithTooltip(closeDescription) {
            IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = closeDescription, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Vertical separator between the command groups of the top bar. */
@Composable
private fun BarDivider() = VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))

private val TAB_HEIGHT = 36.dp
val TAB_ICON = 18.dp

/** How much [TabKind.hue] tints the tab background, unselected and selected. */
private const val TAB_HUE_IDLE = 0.24f
private const val TAB_HUE_SELECTED = 0.52f
private val TAB_SPACING = 4.dp

/**
 * Reordering tabs by dragging: after a long press (with a vibration) the tab lifts and follows the finger or
 * the pen, the others move to make room, near the edges the bar scrolls by itself; released, it stays there
 * ([PresenterSession.moveTab]).
 */
private class TabDrag(private val session: PresenterSession, private val scroll: ScrollState, private val haptic: HapticFeedback) {
    /** Position (x in the row content) and width of each tab. */
    val bounds = HashMap<Any, Pair<Float, Float>>()
    var dragged by mutableStateOf<Any?>(null)
    var offset by mutableFloatStateOf(0f)
    private var from = -1
    var spacingPx = 0f

    /** Position the dragged tab would go to, in [tabs]. */
    fun target(tabs: List<Any>): Int {
        val d = dragged ?: return -1
        val (x, w) = bounds[d] ?: return from
        val center = x + w / 2 + offset
        var t = 0
        for ((i, other) in tabs.withIndex()) {
            if (other === d) continue
            val (ox, ow) = bounds[other] ?: continue
            if (center > ox + ow / 2) t = if (i < from) i + 1 else i
        }
        // Before the first tab: position 0.
        val first = tabs.firstOrNull { it !== d }?.let { bounds[it] }
        if (first != null && center < first.first + first.second / 2) t = 0
        return t.coerceIn(0, tabs.size - 1)
    }

    /** Shift (px) of a non-dragged tab to make room for the dragged one. */
    fun shift(index: Int, tabs: List<Any>): Float {
        val d = dragged ?: return 0f
        val w = (bounds[d]?.second ?: return 0f) + spacingPx
        val t = target(tabs)
        return when {
            index in (from + 1)..t -> -w
            index in t until from -> w
            else -> 0f
        }
    }

    fun start(tab: Any, index: Int) {
        dragged = tab
        from = index
        offset = 0f
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun end(tabs: List<Any>) {
        val t = target(tabs)
        if (dragged != null && t >= 0 && t != from) session.moveTab(from, t)
        dragged = null
        offset = 0f
    }

    /** Scrolling of the row while the dragged tab is near an edge (one step per frame). */
    suspend fun autoScroll() {
        while (true) {
            withFrameNanos { }
            val d = dragged ?: continue
            val (x, w) = bounds[d] ?: continue
            val left = x + offset - scroll.value
            val right = left + w
            val viewport = scroll.viewportSize.toFloat()
            val step = when {
                left < EDGE_PX -> -SCROLL_STEP_PX
                right > viewport - EDGE_PX -> SCROLL_STEP_PX
                else -> 0f
            }
            if (step != 0f) {
                val moved = scroll.scrollBy(step)
                offset += moved
            }
        }
    }

    @Composable
    fun modifier(tab: Any, index: Int, tabs: List<Any>): Modifier {
        val isDragged = dragged === tab
        val shift by animateFloatAsState(if (isDragged) 0f else shift(index, tabs), label = "tab-shift")
        return Modifier
            .onGloballyPositioned { c -> bounds[tab] = c.positionInParent().x to c.size.width.toFloat() }
            .zIndex(if (isDragged) 1f else 0f)
            .graphicsLayer {
                translationX = if (isDragged) offset else shift
                if (isDragged) {
                    scaleX = 1.06f
                    scaleY = 1.06f
                    shadowElevation = 8.dp.toPx()
                    shape = RoundedCornerShape(TAB_HEIGHT / 2)
                    clip = false
                }
            }
            .pointerInput(tab) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start(tab, session.orderedTabs.indexOf(tab)) },
                    onDrag = { change, amount ->
                        change.consume()
                        offset += amount.x
                    },
                    onDragEnd = { end(session.orderedTabs) },
                    onDragCancel = {
                        dragged = null
                        offset = 0f
                    },
                )
            }
    }

    private companion object {
        const val EDGE_PX = 80f
        const val SCROLL_STEP_PX = 14f
    }
}

@Composable
private fun rememberTabDrag(session: PresenterSession, scroll: ScrollState): TabDrag {
    val haptic = LocalHapticFeedback.current
    val drag = remember(session, scroll) { TabDrag(session, scroll, haptic) }
    drag.spacingPx = with(LocalDensity.current) { TAB_SPACING.toPx() }
    LaunchedEffect(drag.dragged != null) { if (drag.dragged != null) drag.autoScroll() }
    return drag
}

