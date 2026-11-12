package it.presenter.app.ui

import android.annotation.SuppressLint
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.os.Build
import android.os.SystemClock
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import it.presenter.app.code.Highlighter
import it.presenter.app.ink.ElementPainter
import it.presenter.app.laser.LaserRenderer
import it.presenter.app.projector.MirrorView
import it.presenter.app.session.PresenterSession
import it.presenter.app.tabs.BrowserTab
import it.presenter.app.tabs.EditorTab
import it.presenter.app.tabs.TerminalTab
import it.presenter.app.tabs.WorkTab
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import org.connectbot.terminal.Terminal

/**
 * Name of the open file, at the top right on the projector: a label in the colors opposite to the code
 * theme, so it stands out from the lines; changing file it fades into the new name.
 */
@Composable
private fun FileNameLabel(name: String?, colors: CodeColors, fontSp: Float, modifier: Modifier) {
    // As large as the code text (user's request); 16 sp until known.
    val size = if (fontSp > 0f) fontSp else 16f
    AnimatedContent(
        targetState = name,
        transitionSpec = {
            fadeIn(tween(250)) togetherWith
                fadeOut(tween(250))
        },
        // Margin and line height follow the size: in a small view (the preview on the tablet) fixed ones made
        // the label much taller than the code lines.
        modifier = modifier.padding((size * 0.75f).coerceAtMost(12f).dp),
        label = "fileName",
    ) { shown ->
        if (shown == null) return@AnimatedContent
        Row(
            Modifier
                .fillMaxWidth(0.5f)
                .wrapContentWidth(Alignment.End)
                .background(colors.foreground.copy(alpha = 0.85f), RoundedCornerShape((size * 0.6f).dp))
                .padding(horizontal = (size * 0.75f).dp, vertical = (size * 0.35f).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((size * 0.4f).dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = null, tint = colors.background, modifier = Modifier.size((size * 1.1f).dp))
            Text(shown, color = colors.background, fontSize = size.sp, lineHeight = (size * 1.25f).sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * What the audience sees of a non-PDF tab: the terminal with the same grid (the font adapts to the
 * screen), the editor from the same line and larger, the same web page.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ProjectorWorkContent(session: PresenterSession, tab: WorkTab) {
    val colors = CodeColors.of(session)
    val laserRenderer = remember { LaserRenderer() }
    val tick = workTrailTicker(session)
    // Laser anchored to the editor text: the editor below draws it, over the same lines.
    var laserInEditor = false
    // Code font size here (sp), for the file name of the same size (0 = no file).
    var editorFontSp = 0f
    // Laser anchored to the terminal grid: scale from the tablet to here (0 = not known yet) and position of
    // the grid, centered.
    var terminalScale = 0f
    var terminalOffset = Offset.Zero
    // Laser over the web page: scale from the laser units to here (0 = not known yet) and offset of the page.
    var webScale = 0f
    var webOffset = Offset.Zero
    // When terminal or editor do not fill the screen they stay centered, with black at the sides (user's
    // request).
    // Laser over the editor: the editor computes the matrix while drawing, the laser is drawn on top of
    // everything, outside the inverted-colors layer (as on the pages).
    val editorLaserMatrix = remember { arrayOfNulls<Matrix>(1) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Inverted colors of the tab (hues kept, as for PDFs): dark code becomes light.
        Box(Modifier.fillMaxSize().invertedColors(tab.inverted)) {
            when (tab) {
                is TerminalTab -> {
                    // The tablet decides the grid: no resizing here (with forcedSize the projector also resized the
                    // emulator with its own pixels, racing with the tablet: the grid kept changing and termlib drew a
                    // green border). Same rows and columns with the tablet's font scaled to the projector's area.
                    var area by remember { mutableStateOf(IntSize.Zero) }
                    val density = LocalDensity.current
                    val fit = if (tab.viewWidthPx > 0f && tab.viewHeightPx > 0f && area.width > 0) {
                        minOf(area.width / tab.viewWidthPx, area.height / tab.viewHeightPx)
                    } else 0f
                    val font = if (fit > 0f && tab.fontPx > 0f) with(density) { (tab.fontPx * fit * 0.98f).toSp() } else (tab.fontSize * tab.projectorScale).sp
                    terminalScale = fit * 0.98f
                    val gridW = if (fit > 0f) tab.viewWidthPx * terminalScale else area.width.toFloat()
                    val gridH = if (fit > 0f) tab.viewHeightPx * terminalScale else area.height.toFloat()
                    terminalOffset = Offset((area.width - gridW) / 2f, (area.height - gridH) / 2f)
                    Box(Modifier.fillMaxSize().onSizeChanged { area = it }) {
                        key(font) {
                            Terminal(
                                terminalEmulator = tab.emulator,
                                modifier = if (fit > 0f) {
                                    Modifier.align(Alignment.Center).size(with(density) { gridW.toDp() }, with(density) { gridH.toDp() })
                                } else Modifier.fillMaxSize(),
                                initialFontSize = font,
                                minFontSize = 4.sp,
                                maxFontSize = 400.sp,
                                backgroundColor = colors.background,
                                foregroundColor = colors.foreground,
                                keyboardEnabled = false,
                                showSoftKeyboard = false,
                                resizeSuspended = true,
                            )
                        }
                    }
                }
                is EditorTab -> {
                    val path = tab.openPath
                    if (path != null) {
                        val lang = remember(path) { Highlighter.forFile(path.substringAfterLast('/')) }
                        val all = remember(tab.text, colors, lang) { annotate(tab.text, lang, colors) }
                        val starts = remember(tab.text) { lineStarts(tab.text) }
                        val first = tab.firstVisibleLine.coerceIn(0, starts.size - 1)
                        val shown = all.subSequence(starts[first], tab.text.length)
                        val ci = tab.codeInk
                        val density = LocalDensity.current
                        val pad = with(density) { 16.dp.toPx() }
                        // Same lines as the tablet, first to last (user's request): the scale is the ratio between the
                        // height available here and that of the editor on the tablet. Until the tablet has measured it,
                        // the fixed magnification of the tab ([WorkTab.projectorScale]).
                        var boxHeight by remember { mutableFloatStateOf(0f) }
                        var screenWidth by remember { mutableFloatStateOf(0f) }
                        // Line height in tablet pixels (its density, not the projector's).
                        val tabletLineHeight = ci.lineHeightPx
                        val fit = if (ci.viewportHeightPx > 0f && tabletLineHeight > 1f && boxHeight > 2 * pad) (boxHeight - 2 * pad) / ci.viewportHeightPx else 0f
                        val projLineHeight = if (fit > 0f) tabletLineHeight * fit else with(density) { (tab.fontSize * tab.projectorScale * LINE_HEIGHT).sp.toPx() }
                        // The font in sp on the external display does not give exactly the requested line height (seen
                        // with a 4K monitor: 57 px instead of 61): the real one is measured and corrected.
                        var correction by remember { mutableFloatStateOf(1f) }
                        var measured by remember { mutableFloatStateOf(0f) }
                        val size = with(density) { (projLineHeight * correction / LINE_HEIGHT).toSp() }.value
                        LaunchedEffect(measured) {
                            if (measured > 0f && abs(measured - projLineHeight) > 0.5f) correction *= projLineHeight / measured
                        }
                        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp, lineHeight = (size * LINE_HEIGHT).sp, color = colors.foreground)
                        editorFontSp = size
                        // Real line pitch here (measured, rounded to pixels) and separate scales: vertically the lines,
                        // horizontally the characters. With a single scale (lines) strokes slid left or right along the
                        // line.
                        val projPitch = if (measured > 0f) measured else projLineHeight
                        val scale = projPitch / tabletLineHeight.coerceAtLeast(1f)
                        val scaleX = if (ci.fontPx > 0f) with(density) { size.sp.toPx() } / ci.fontPx else scale
                        // The first line may be cut in half on the tablet: the same fraction here; and the same
                        // horizontal scroll.
                        val fraction = (ci.scrollPx / tabletLineHeight.coerceAtLeast(1f) - first).coerceIn(0f, 1f)
                        val shiftY = -(fraction * projPitch).roundToInt()
                        val shiftX = -(ci.hscrollPx * scaleX).roundToInt()
                        var boxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                        var textCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                        val painter = remember { ElementPainter() }
                        val liveRenderer = remember { CanvasStrokeRenderer.create() }
                        laserInEditor = true
                        // Width of the tablet's editor at the same scale, centered.
                        val regionWidth = if (fit > 0f && ci.viewportWidthPx > 0f && screenWidth > 0f) {
                            (ci.viewportWidthPx * scaleX + 2 * pad).coerceAtMost(screenWidth)
                        } else screenWidth
                        Box(Modifier.fillMaxSize().onSizeChanged { screenWidth = it.width.toFloat() }) {
                            Box(
                                Modifier
                                    .align(Alignment.Center)
                                    .fillMaxHeight()
                                    .then(if (regionWidth > 0f) Modifier.width(with(density) { regionWidth.toDp() }) else Modifier.fillMaxWidth())
                                    .background(colors.background)
                                    .onGloballyPositioned { c ->
                                        boxCoords = c
                                        boxHeight = c.size.height.toFloat()
                                    }
                                    .clipToBounds()
                                    // Pen strokes from the tablet over the same lines: same origin (start of the text),
                                    // scaled by the ratio of the line heights, from the first line shown.
                                    .drawWithContent {
                                        drawContent()
                                        ci.version
                                        val b = boxCoords ?: return@drawWithContent
                                        val t = textCoords?.takeIf { c -> c.isAttached } ?: return@drawWithContent
                                        val o = b.localPositionOf(t, Offset.Zero)
                                        val m = Matrix().apply {
                                            setTranslate(0f, -first * ci.lineHeightPx)
                                            postScale(scaleX, projPitch / ci.lineHeightPx)
                                            postTranslate(o.x, o.y)
                                        }
                                        drawIntoCanvas { c -> drawCodeInk(c.nativeCanvas, ci, m, painter, liveRenderer) }
                                        // Laser: from tablet units (1000 = width of the tab) to tablet pixels, then
                                        // like the strokes. From the editor box to the projector's full screen (where
                                        // the laser is).
                                        val shift = b.positionInRoot()
                                        editorLaserMatrix[0] = Matrix().apply {
                                            setScale(session.workWidth / 1000f, session.workWidth / 1000f)
                                            postConcat(m)
                                            postTranslate(shift.x, shift.y)
                                        }
                                    },
                            ) {
                                // The text is clipped at the margin: outside only the lines the tablet shows remain.
                                Box(Modifier.padding(16.dp).clipToBounds()) {
                                    Row(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).offset { IntOffset(0, shiftY) }) {
                                        Text(
                                            ((first + 1)..starts.size).joinToString("\n"),
                                            style = style.copy(color = colors.gutter, textAlign = TextAlign.End),
                                            modifier = Modifier.padding(end = 12.dp),
                                        )
                                        Box(Modifier.weight(1f).clipToBounds()) {
                                            Text(
                                                shown, style = style, softWrap = false,
                                                onTextLayout = { r -> if (r.lineCount > 2) measured = averageLineHeight(r) },
                                                modifier = Modifier
                                                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                                                    .offset { IntOffset(shiftX, 0) }
                                                    .onGloballyPositioned { c -> textCoords = c },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                is BrowserTab -> {
                    // The page is laid out at the size of the tablet's and scaled to fit, centered (laid out again at
                    // this size it would differ, e.g. the mobile version in the small previews). One AndroidView only:
                    // a new one would load the page again.
                    var area by remember { mutableStateOf(IntSize.Zero) }
                    val density = LocalDensity.current
                    val pageW = tab.viewWidthDp * density.density
                    val pageH = tab.viewHeightDp * density.density
                    val fit = if (pageW > 0f && pageH > 0f && area.width > 0) minOf(area.width / pageW, area.height / pageH) else 0f
                    if (fit > 0f) {
                        webScale = pageW * fit / 1000f
                        webOffset = Offset((area.width - pageW * fit) / 2f, (area.height - pageH * fit) / 2f - tab.viewTop * webScale)
                    }
                    // The WebView takes the scaled size and the page is told the tablet's width and the scale (viewport
                    // meta): scaled as a view instead, the WebView drew only a band at the top (the rest white).
                    // Grows when the page's renderer dies (see BrowserContent): a new WebView loads the page again.
                    var renderer by remember { mutableIntStateOf(0) }
                    Box(Modifier.fillMaxSize().onSizeChanged { area = it }, contentAlignment = Alignment.Center) {
                        key(renderer) {
                            AndroidView(
                                factory = { ctx ->
                                    AudienceWebView(ctx).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.textZoom = tab.textZoom
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        webViewClient = object : WebViewClient() {
                                            override fun onPageCommitVisible(view: WebView, url: String?) = applyViewport()

                                            // Once the page has loaded the scroll is restored (before that the height is
                                            // still 0).
                                            override fun onPageFinished(view: WebView, url: String?) {
                                                applyViewport()
                                                scrollToFraction(view, tab.scrollFraction)
                                            }

                                            // The viewport applied (the page height changes with the scale): same place
                                            // again.
                                            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) =
                                                scrollToFraction(view, tab.scrollFraction)

                                            // Unhandled, the death of the renderer would close the whole app.
                                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                                renderer++
                                                return true
                                            }
                                        }
                                        // New size (screen, tablet's page area): same place again.
                                        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> scrollToFraction(v as WebView, tab.scrollFraction) }
                                        // Last address requested (not wv.url: with a redirect it would differ and the page
                                        // would reload at every scroll of the tablet).
                                        tag = tab.url
                                        reloads = tab.reloads
                                        loadUrl(tab.url)
                                    }
                                },
                                update = { wv ->
                                    if (wv.tag != tab.url && tab.url.isNotEmpty()) {
                                        wv.tag = tab.url
                                        wv.reloads = tab.reloads
                                        wv.loadUrl(tab.url)
                                    } else if (wv.reloads != tab.reloads) {
                                        // Same address, page changed on the tablet (BrowserTab.reloads).
                                        wv.reloads = tab.reloads
                                        wv.reload()
                                    }
                                    wv.settings.textZoom = tab.textZoom
                                    wv.viewport = if (fit > 0f) tab.viewWidthDp.roundToInt() to fit else null
                                    scrollToFraction(wv, tab.scrollFraction)
                                },
                                onRelease = { it.destroy() },
                                modifier = if (fit > 0f) {
                                    with(density) { Modifier.size((pageW * fit).toDp(), (pageH * fit).toDp()) }
                                } else Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            // File name at the top right, in the inverted layer: it stays in contrast with the code.
            if (tab is EditorTab) FileNameLabel(tab.openPath?.substringAfterLast('/'), colors, editorFontSp, Modifier.align(Alignment.TopEnd))
        }
        val inEditor = laserInEditor
        val termScale = terminalScale
        val termOffset = terminalOffset
        val pageScale = webScale
        val pageOffset = webOffset
        // Laser over terminal and web (and the editor without a file): the same relative position as the tab on
        // the tablet; over the editor's text, anchored to its lines.
        Canvas(Modifier.fillMaxSize()) {
            tick.longValue
            if (inEditor && session.workLaserInText) {
                val lm = editorLaserMatrix[0] ?: return@Canvas
                drawIntoCanvas { laserRenderer.draw(it.nativeCanvas, session.workTrail, lm, SystemClock.uptimeMillis()) }
                return@Canvas
            }
            val m = Matrix().apply {
                if (session.workLaserInText && termScale > 0f) {
                    // Terminal grid centered, scaled like the font.
                    setScale(session.workWidth / 1000f * termScale, session.workWidth / 1000f * termScale)
                    postTranslate(termOffset.x, termOffset.y)
                } else if (tab is BrowserTab && pageScale > 0f) {
                    // Same units as the tab on the tablet, over the scaled page.
                    setScale(pageScale, pageScale)
                    postTranslate(pageOffset.x, pageOffset.y)
                } else {
                    setScale(size.width / 1000f, size.height / (1000f * session.workAspect))
                }
            }
            drawIntoCanvas { laserRenderer.draw(it.nativeCanvas, session.workTrail, m, SystemClock.uptimeMillis()) }
        }
    }
}

private fun lineStarts(text: String): IntArray {
    val list = ArrayList<Int>()
    list.add(0)
    for (i in text.indices) if (text[i] == '\n') list.add(i + 1)
    return list.toIntArray()
}

/**
 * Inverted colors with hues kept (the same matrix as the pages on the projector). Needs Android 12; before
 * that the tab stays as it is.
 */
private fun Modifier.invertedColors(on: Boolean): Modifier =
    if (!on || Build.VERSION.SDK_INT < 31) this
    else graphicsLayer {
        renderEffect = RenderEffect
            .createColorFilterEffect(ColorMatrixColorFilter(MirrorView.INVERT_KEEP_HUE))
            .asComposeRenderEffect()
    }
