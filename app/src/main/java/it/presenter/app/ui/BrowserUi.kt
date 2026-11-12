package it.presenter.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import it.presenter.app.R
import it.presenter.app.doc.DocSource
import it.presenter.app.session.PresenterSession
import it.presenter.app.tabs.BrowserTab
import it.presenter.app.tabs.WebPdf
import it.presenter.app.tabs.WorkTab
import java.util.Locale
import kotlin.math.roundToInt

/** Web page tab on the tablet: address bar, back, reload, text size and the page. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BrowserContent(session: PresenterSession, tab: BrowserTab, shield: Boolean) {
    var address by remember(tab) { mutableStateOf(tab.url) }
    var webView by remember(tab) { mutableStateOf<WebView?>(null) }
    var canGoBack by remember(tab) { mutableStateOf(false) }
    // Grows when the page's renderer dies: a new WebView takes the place of the dead one.
    var renderer by remember(tab) { mutableIntStateOf(0) }
    // Loading progress of the page, 0-100.
    var progress by remember(tab) { mutableIntStateOf(100) }
    // System back goes to the previous page (it does not send the app to the background: the projector
    // would switch to mirroring the tablet).
    BackHandler(enabled = canGoBack) { webView?.goBack() }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            ActionIcon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.browser_back), enabled = canGoBack) { webView?.goBack() }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { webView?.loadUrl(normalizeUrl(address)) }),
                modifier = Modifier.weight(1f),
            )
            ActionIcon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.browser_go)) { webView?.loadUrl(normalizeUrl(address)) }
            ActionIcon(Icons.Rounded.Refresh, stringResource(R.string.browser_reload)) {
                webView?.reload()
                tab.reloads++
            }
            // Text size of the page (the tab's font, as in editor and terminal; also with two fingers).
            ActionIcon(Icons.Rounded.TextDecrease, stringResource(R.string.code_font_smaller), enabled = tab.fontSize > WorkTab.FONT_RANGE.start) {
                tab.fontSize = (tab.fontSize - 1f).coerceIn(WorkTab.FONT_RANGE)
            }
            ActionIcon(Icons.Rounded.TextIncrease, stringResource(R.string.code_font_larger), enabled = tab.fontSize < WorkTab.FONT_RANGE.endInclusive) {
                tab.fontSize = (tab.fontSize + 1f).coerceIn(WorkTab.FONT_RANGE)
            }
        }
        val density = LocalDensity.current
        Box(
            Modifier.fillMaxSize().pinchFontSize(session, tab).onGloballyPositioned { c ->
                if (c.size.width <= 0) return@onGloballyPositioned
                tab.viewWidthDp = c.size.width / density.density
                tab.viewHeightDp = c.size.height / density.density
                tab.viewTop = c.positionInParent().y * 1000f / c.size.width
            },
        ) {
            key(renderer) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    url?.let { tab.url = it; address = it }
                                    tab.pageTitle = view.title.orEmpty()
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                    canGoBack = view.canGoBack()
                                }

                                // Renderer killed by the system (memory) or crashed: unhandled, Android would close the whole
                                // app. This WebView can no longer be used: a new one loads the page again.
                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    if (webView === view) webView = null
                                    renderer++
                                    session.notice = R.string.browser_renderer_gone
                                    return true
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            // The WebView cannot show a PDF: it is downloaded and opened in a PDF tab after this one.
                            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                                if (WebPdf.isPdf(url, contentDisposition, mimeType)) openWebPdf(session, tab, this, url, userAgent, contentDisposition, mimeType)
                            }
                            setOnScrollChangeListener { v, _, y, _, _ ->
                                val wv = v as WebView
                                val max = maxScroll(wv).coerceAtLeast(1f)
                                tab.scrollFraction = (y / max).coerceIn(0f, 1f)
                            }
                            reloadAudienceOnCookies(tab)
                            settings.textZoom = tab.textZoom
                            loadUrl(tab.url)
                            webView = this
                        }
                    },
                    update = { wv -> wv.settings.textZoom = tab.textZoom },
                    onRelease = { it.destroy() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Thin bar over the page while it loads: the page area keeps its size (the projector copies it).
            if (progress < 100) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            PenShield(shield)
        }
    }
}

/**
 * Downloads the PDF at [url] (once: a PDF downloaded before opens as it is) and opens it in a PDF tab after
 * [tab]. The messages go through the session, since the download outlives the composition.
 */
private fun openWebPdf(
    session: PresenterSession,
    tab: BrowserTab,
    view: WebView,
    url: String,
    userAgent: String,
    contentDisposition: String?,
    mimeType: String?,
) {
    val file = WebPdf.fileFor(view.context, url, contentDisposition, mimeType)
    val open: () -> Unit = { session.onOpenPdf?.invoke(DocSource.Local(file), tab) }
    if (file.isFile) return open()
    session.noticePath = R.string.browser_pdf_downloading to file.name
    val main = Handler(Looper.getMainLooper())
    Thread {
        val result = runCatching { WebPdf.download(url, userAgent, file) }
        main.post {
            if (result.isSuccess) open() else session.noticePath = R.string.browser_pdf_failed to file.name
        }
    }.start()
}

/**
 * A tap that adds cookies to the site without leaving the page (a cookie banner accepted, a login…): the
 * audience's copies, loaded before, would keep the old page, so they load it again ([BrowserTab.reloads]).
 * The cookies are shared by all the app's WebViews; checked a little after the tap, when the page has set
 * them. Only the names count: analytics cookies change their values at every click.
 */
private fun WebView.reloadAudienceOnCookies(tab: BrowserTab) {
    var url: String? = null
    var names: Set<String> = emptySet()
    setOnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                url = this.url
                names = cookieNames(url)
            }
            MotionEvent.ACTION_UP -> {
                val at = url
                var seen = names
                val check = Runnable {
                    // A new page arrives to the audience with its address.
                    if (at == null || this.url != at) return@Runnable
                    val now = cookieNames(at)
                    if (now != seen) {
                        seen = now
                        tab.reloads++
                    }
                }
                for (delay in COOKIE_CHECKS_MS) v.postDelayed(check, delay)
            }
        }
        false
    }
}

private fun cookieNames(url: String?): Set<String> =
    url?.let { CookieManager.getInstance().getCookie(it) }
        ?.split(';')?.map { it.substringBefore('=').trim() }?.toSet().orEmpty()

private val COOKIE_CHECKS_MS = longArrayOf(800L, 2500L)

internal fun normalizeUrl(s: String): String {
    val t = s.trim()
    return if (t.contains("://")) t else "https://$t"
}

/**
 * WebView of the audience: the page laid out at [viewport] width (CSS px, the tablet's) and shown at its scale,
 * fixed, with the viewport meta (set again at every page, replacing the page's own).
 */
internal class AudienceWebView(context: Context) : WebView(context) {
    /** [BrowserTab.reloads] already followed. */
    var reloads = 0

    var viewport: Pair<Int, Float>? = null
        set(value) {
            if (value == field) return
            field = value
            applyViewport()
        }

    fun applyViewport() {
        val (width, scale) = viewport ?: return
        val s = String.format(Locale.ROOT, "%.4f", scale)
        evaluateJavascript(
            "(function(){document.querySelectorAll('meta[name=viewport]').forEach(function(e){e.remove();});" +
                "var m=document.createElement('meta');m.name='viewport';" +
                "m.content='width=$width, initial-scale=$s, minimum-scale=$s, maximum-scale=$s, user-scalable=no';" +
                "(document.head||document.documentElement).appendChild(m);})()",
            null,
        )
    }
}

internal fun scrollToFraction(wv: WebView, fraction: Float) {
    val max = maxScroll(wv).coerceAtLeast(0f)
    wv.scrollTo(0, (fraction * max).roundToInt())
}

/**
 * Vertical scroll range of [wv] (px): the page height at the current zoom minus the view's. `scale` is
 * deprecated, but its replacement (`WebViewClient.onScaleChanged`) would have to be tracked per view.
 */
@Suppress("DEPRECATION")
private fun maxScroll(wv: WebView): Float = wv.contentHeight * wv.scale - wv.height
