package it.presenter.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.presenter.app.R
import it.presenter.app.doc.OutlineItem
import it.presenter.app.doc.PdfTextIndex
import it.presenter.app.doc.PresenterDocument
import it.presenter.app.session.PresenterSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Navigation panel, tablet only: "go to page" (number or Beamer label), PDF outline, text search and
 * bookmarks (put on the current slide with [onBookmark], saved in the .pdfpc). Every jump enters the history
 * ("back" icon at the top); further jumps made from the same open panel count as one.
 */
@Composable
fun NavigationPanel(session: PresenterSession, doc: PresenterDocument, onBookmark: (page: Int, on: Boolean) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var tab by rememberSaveable { mutableStateOf(Tab.OUTLINE) }
    // First jump from this panel: it goes into the history; the following ones do not.
    var jumped by remember(doc) { mutableStateOf(false) }
    fun jump(page: Int) {
        session.jumpTo(doc, page, record = !jumped)
        jumped = true
    }
    DisposableEffect(Unit) { onDispose { session.searchHighlight = null } }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.width(380.dp).fillMaxHeight().padding(8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoToPage(doc, onGo = { page ->
                    jump(page)
                    onClose()
                }, modifier = Modifier.weight(1f))
                ActionIcon(Icons.Rounded.Close, stringResource(R.string.action_close), onClick = onClose)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Tab.entries.forEachIndexed { i, t ->
                    val label = stringResource(t.label)
                    SegmentedButton(
                        selected = tab == t,
                        onClick = { tab = t },
                        shape = SegmentedButtonDefaults.itemShape(i, Tab.entries.size),
                        icon = {},
                    ) { Icon(t.icon(), contentDescription = label) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.OUTLINE -> OutlineList(doc, onJump = { page ->
                        jump(page)
                        onClose()
                    })
                    Tab.SEARCH -> SearchList(session, doc, onJump = ::jump)
                    Tab.BOOKMARKS -> BookmarkList(session, doc, onBookmark, onJump = { page ->
                        jump(page)
                        onClose()
                    })
                }
            }
        }
    }
}

private enum class Tab(val label: Int) {
    OUTLINE(R.string.nav_outline),
    SEARCH(R.string.nav_search),
    BOOKMARKS(R.string.nav_bookmarks);

    fun icon() = when (this) {
        OUTLINE -> Icons.AutoMirrored.Rounded.List
        SEARCH -> Icons.Rounded.Search
        BOOKMARKS -> Icons.Rounded.Bookmarks
    }
}

/**
 * Bookmarks: the icon puts one on the current slide or takes it off (if the .pdfpc can be written); below, the
 * slides with one, with their note's first line, to jump there.
 */
@Composable
private fun BookmarkList(session: PresenterSession, doc: PresenterDocument, onBookmark: (Int, Boolean) -> Unit, onJump: (Int) -> Unit) {
    val slides = session.navSlides(doc)
    Column {
        if (doc.source.canWritePdfpc) {
            val on = doc.isBookmarked(doc.pageIndex)
            ListItem(
                headlineContent = { Text(stringResource(if (on) R.string.nav_bookmark_remove else R.string.nav_bookmark_add)) },
                leadingContent = { Icon(if (on) Icons.Rounded.BookmarkRemove else Icons.Rounded.BookmarkAdd, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.clickable { onBookmark(doc.pageIndex, !on) },
            )
            HorizontalDivider()
        }
        val marks = doc.bookmarks
        if (marks.isEmpty()) {
            Message(R.string.nav_no_bookmarks)
        } else {
            LazyColumn {
                items(marks) { page ->
                    ListItem(
                        headlineContent = {
                            Text(doc.noteFor(page)?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = { Text((slides.slideOf(page) + 1).toString(), style = MaterialTheme.typography.titleSmall) },
                        trailingContent = { Icon(Icons.Rounded.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.clickable { onJump(page) },
                    )
                }
            }
        }
    }
}

/** "Go to page" field: page number or Beamer label (the label is tried first). */
@Composable
private fun GoToPage(doc: PresenterDocument, onGo: (Int) -> Unit, modifier: Modifier) {
    var input by remember { mutableStateOf("") }
    val target = remember(input) {
        doc.pageWithLabel(input) ?: input.trim().toIntOrNull()?.minus(1)?.takeIf { it in 0 until doc.pageCount }
    }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.nav_go_to_hint, doc.pageCount)) },
        isError = input.isNotBlank() && target == null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { target?.let(onGo) }),
        trailingIcon = {
            ActionIcon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.nav_go_to), enabled = target != null) {
                target?.let(onGo)
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun OutlineList(doc: PresenterDocument, onJump: (Int) -> Unit) {
    val structure = doc.structure
    when {
        structure == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        structure.outline.isEmpty() -> Message(R.string.nav_no_outline)
        else -> {
            val rows = remember(structure) { flatten(structure.outline, 0) }
            LazyColumn {
                items(rows) { (item, depth) ->
                    val page = item.page
                    ListItem(
                        headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingContent = page?.let { { Text((it + 1).toString(), style = MaterialTheme.typography.labelMedium) } },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier
                            .padding(start = (16 * depth).dp)
                            .then(if (page != null) Modifier.clickable { onJump(page) } else Modifier),
                    )
                }
            }
        }
    }
}

private fun flatten(items: List<OutlineItem>, depth: Int): List<Pair<OutlineItem, Int>> =
    items.flatMap { listOf(it to depth) + flatten(it.children, depth + 1) }

@Composable
private fun SearchList(session: PresenterSession, doc: PresenterDocument, onJump: (Int) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var index by remember(doc) { mutableStateOf<PdfTextIndex?>(null) }
    var hits by remember(doc) { mutableStateOf<List<PdfTextIndex.Hit>>(emptyList()) }
    // The text index is built at the first search in the document (a few seconds for a long PDF).
    LaunchedEffect(doc) { index = withContext(Dispatchers.IO) { doc.textIndex() } }
    LaunchedEffect(index, query) {
        val idx = index ?: return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        hits = if (query.isBlank()) emptyList() else withContext(Dispatchers.Default) { idx.search(query) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.nav_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        )
        val idx = index
        when {
            idx == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            query.isBlank() -> Unit
            hits.isEmpty() -> Message(R.string.nav_no_results)
            else -> {
                Text(
                    pluralStringResource(R.plurals.nav_results, hits.size, hits.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                LazyColumn {
                    items(hits) { hit ->
                        ListItem(
                            overlineContent = { Text(stringResource(R.string.nav_page, (hit.page + 1).toString())) },
                            headlineContent = { Text(hit.snippet, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.clickable {
                                session.searchHighlight = PresenterSession.SearchHighlight(doc, hit.page, idx.pages[hit.page].rects(hit.range))
                                onJump(hit.page)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: Int) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        Text(stringResource(text), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val SEARCH_DEBOUNCE_MS = 250L
