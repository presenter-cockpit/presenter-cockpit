package it.presenter.app.tabs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Web page inside the app (PDF links, online material). The projector opens the same page in its own
 * WebView, at the size of the tablet's, and keeps it at the same scroll position (proportionally).
 */
class BrowserTab(startUrl: String, id: String? = null) : WorkTab(id ?: UUID.randomUUID().toString()) {
    var url by mutableStateOf(startUrl)
    var pageTitle by mutableStateOf("")

    /** Vertical scroll as a fraction of the page height (0 at the top, 1 at the bottom). */
    var scrollFraction by mutableFloatStateOf(0f)

    /**
     * Page area on the tablet (dp, 0 = not known yet): the projector lays the page out at the same size and
     * scales it to its screen, so it looks the same (not a page laid out again for a narrower or wider screen).
     */
    var viewWidthDp by mutableFloatStateOf(0f)
    var viewHeightDp by mutableFloatStateOf(0f)

    /** Top of the page area below the address bar, in the laser units (tab width = 1000). */
    var viewTop by mutableFloatStateOf(0f)

    /**
     * Grows when the tablet's page changes without a new address (reload, cookies set by a tap, e.g. a cookie
     * banner accepted): the audience's copies load it again. They are copies of the page: only address,
     * scroll and text size follow the tablet.
     */
    var reloads by mutableIntStateOf(0)

    /** Text size of the page (%, WebView `textZoom`) from the tab's font: 100 at the default size. */
    val textZoom: Int get() = (fontSize / DEFAULT_FONT_SP * 100f).roundToInt()

    override val title: String get() = pageTitle.ifBlank { url.substringAfter("://").substringBefore('/') }
}
