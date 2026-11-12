package it.presenter.app.ui

import android.content.res.Configuration
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyOff
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import it.presenter.app.R
import it.presenter.app.code.Highlighter
import it.presenter.app.laser.LaserRenderer
import it.presenter.app.remote.SavedPasswords
import it.presenter.app.remote.ServerProfile
import it.presenter.app.remote.SshLink
import it.presenter.app.session.PresenterSession
import it.presenter.app.tabs.BrowserTab
import it.presenter.app.tabs.EditorTab
import it.presenter.app.tabs.LinkStatus
import it.presenter.app.tabs.TerminalTab
import it.presenter.app.tabs.WorkTab
import it.presenter.app.tools.ToolKind
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest

/** Colors of editor and terminal (dark theme by default, light from the settings). */
class CodeColors(val background: Color, val foreground: Color, val gutter: Color, val spans: Map<Highlighter.Kind, Color>) {
    companion object {
        val DARK = CodeColors(
            Color(0xFF16181C), Color(0xFFE6E6E6), Color(0xFF6B7280),
            mapOf(
                Highlighter.Kind.COMMENT to Color(0xFF7F8C98), Highlighter.Kind.STRING to Color(0xFFA5D6A7),
                Highlighter.Kind.NUMBER to Color(0xFFFFCC80), Highlighter.Kind.KEYWORD to Color(0xFF82AAFF),
                Highlighter.Kind.TYPE to Color(0xFF80CBC4), Highlighter.Kind.PREPROCESSOR to Color(0xFFF48FB1),
                Highlighter.Kind.HEADING to Color(0xFFFFD54F),
            ),
        )
        val LIGHT = CodeColors(
            Color(0xFFFFFFFF), Color(0xFF1F2328), Color(0xFF8C959F),
            mapOf(
                Highlighter.Kind.COMMENT to Color(0xFF6E7781), Highlighter.Kind.STRING to Color(0xFF0A7D32),
                Highlighter.Kind.NUMBER to Color(0xFFB35900), Highlighter.Kind.KEYWORD to Color(0xFF0550AE),
                Highlighter.Kind.TYPE to Color(0xFF116329), Highlighter.Kind.PREPROCESSOR to Color(0xFFA2195F),
                Highlighter.Kind.HEADING to Color(0xFF953800),
            ),
        )

        fun of(session: PresenterSession) = if (session.settings.codeLightTheme) LIGHT else DARK
    }
}

/**
 * After a password login to a server that does not know the app's key: offer to install it (in
 * `~/.ssh/authorized_keys`), so from then on the fingerprint is enough.
 */
@Composable
private fun KeyOfferBar(session: PresenterSession, link: SshLink) {
    if (!link.keyOffer.value) return
    var installing by remember(link) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Icon(Icons.Rounded.Key, contentDescription = null)
            Text(stringResource(R.string.remote_key_offer), modifier = Modifier.padding(start = 12.dp).weight(1f))
            if (installing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
            } else {
                ActionIcon(Icons.Rounded.Upload, stringResource(R.string.remote_key_install)) {
                    installing = true
                    Thread {
                        val ok = link.installKey()
                        Handler(Looper.getMainLooper()).post {
                            installing = false
                            link.keyOffer.post(false)
                            session.notice = if (ok) R.string.remote_key_installed else R.string.remote_key_install_failed
                        }
                    }.start()
                }
                ActionIcon(Icons.Rounded.Close, stringResource(R.string.remote_key_offer_dismiss)) { link.keyOffer.post(false) }
            }
        }
    }
}

/**
 * Fingerprint unlocks for the connections (MainActivity does them, it has the BiometricPrompt): the app's
 * SSH key; saved passwords (biometrics only, [canSavePasswords] if the tablet has them).
 */
class RemoteAuth(
    val unlockKey: (then: () -> Unit) -> Unit,
    val canSavePasswords: Boolean,
    /** Encrypts [password] after the fingerprint and passes on the ciphertext to save. */
    val savePassword: (password: String, then: (blob: String) -> Unit) -> Unit,
    /**
     * Decrypts the saved password after the fingerprint; [gone]: it no longer exists (fingerprints changed), retry
     * without.
     */
    val unlockPassword: (server: ServerProfile, then: (password: String) -> Unit, gone: () -> Unit) -> Unit,
)

/**
 * Content of a non-PDF tab on the tablet, in place of the page. [auth] unlocks the SSH key and the saved
 * passwords with the fingerprint.
 */
@Composable
fun WorkTabContent(session: PresenterSession, tab: WorkTab, auth: RemoteAuth) {
    val colors = CodeColors.of(session)
    // No Android handwriting over code and terminal (user's request: it messes up the code and takes the pen
    // strokes before the app, laser included). Two defenses: the window's automatic handwriting is off while
    // the tab is open, and with the pen nearby (any tool) editor and terminal do not accept text and lose the
    // focus. With the pen away the keyboard types as usual.
    val view = LocalView.current
    DisposableEffect(view) {
        val before = if (Build.VERSION.SDK_INT >= 33) view.isAutoHandwritingEnabled else false
        if (Build.VERSION.SDK_INT >= 33) view.isAutoHandwritingEnabled = false
        onDispose { if (Build.VERSION.SDK_INT >= 33) view.isAutoHandwritingEnabled = before }
    }
    val laserOn = session.workStylusNear
    val focus = LocalFocusManager.current
    // On-screen keyboard requested with the icon (terminal or editor, also tapping it with the pen): it stays,
    // pen or not. With a physical keyboard connected the same applies (user's request): bringing the pen close
    // the content lost the focus and, once the pen moved away, the physical keyboard no longer typed.
    val config = LocalConfiguration.current
    val hardKeyboard = config.keyboard != Configuration.KEYBOARD_NOKEYS && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    val keyboardAsked = tab.softKeyboard || hardKeyboard
    LaunchedEffect(laserOn) { if (laserOn && !keyboardAsked) focus.clearFocus(force = true) }
    // With the pen nearby and the laser (or on terminal and web, where the pen does not write) a transparent
    // layer takes the pen instead of the content ([PenShield]): the long press of terminal and editor does not
    // check whether the touch was already consumed and, with the tip still in a slow laser circle, started text
    // selection. The layer covers only the content, not the icons around it (keyboard, A−/A+, extra keys,
    // bars), which stay usable with the pen. The laser stays on the container ([workLaser]); with the pen away
    // the fingers reach the content.
    val tools = session.tools
    val laserTool = tools.preset(tools.effective(buttonPressed = tools.buttonHeld, eraserTip = false))?.kind == ToolKind.LASER
    val shield = laserOn && (laserTool || tab !is EditorTab)
    Surface(color = colors.background, contentColor = colors.foreground, modifier = Modifier.fillMaxSize().workLaser(session, tab)) {
        // The window does not resize with the keyboard (adjustNothing, for the page): here it does, otherwise the
        // keyboard covers the line being typed and the extra keys row.
        Box {
            Column(Modifier.imePadding()) {
                when (tab) {
                    is TerminalTab -> {
                        if (tab.server != null) {
                            LinkBanner(tab.status, tab.server, auth, retry = tab::start, usePassword = tab::retryWithPassword)
                        } else {
                            LocalShellBanner(tab.status, retry = tab::start)
                        }
                        tab.link?.let { link -> KeyOfferBar(session, link) }
                        TerminalContent(session, tab, colors, keyboard = !laserOn || keyboardAsked, shield = shield)
                    }
                    is EditorTab -> {
                        tab.server?.let { server -> LinkBanner(tab.status, server, auth, retry = tab::refresh, usePassword = tab::retryWithPassword) }
                        tab.link?.let { link -> KeyOfferBar(session, link) }
                        EditorContent(session, tab, colors, readOnly = laserOn && !keyboardAsked, shield = shield)
                    }
                    is BrowserTab -> BrowserContent(session, tab, shield)
                }
            }
        }
    }
}

/** Connection state at the top of the tab: waiting, fingerprint, password, server key changed. */
@Composable
private fun LinkBanner(
    status: LinkStatus,
    server: ServerProfile,
    auth: RemoteAuth,
    retry: () -> Unit,
    usePassword: (password: String, fromStore: Boolean, saveBlob: String?) -> Unit,
) {
    if (status == LinkStatus.Connected) return
    val onRetry = retry
    val onUnlock = { auth.unlockKey(retry) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            when (status) {
                LinkStatus.Connecting -> {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                    Text(stringResource(R.string.remote_connecting), modifier = Modifier.padding(start = 12.dp))
                }
                is LinkStatus.Waiting -> {
                    // The reason, if known: name that does not resolve, closed port, silent server.
                    val text = when (status.reason) {
                        SshLink.NetError.UNKNOWN_HOST -> stringResource(R.string.remote_waiting_unknown_host, server.host, status.seconds)
                        SshLink.NetError.REFUSED -> stringResource(R.string.remote_waiting_refused, server.host, server.port, status.seconds)
                        SshLink.NetError.NO_RESPONSE -> stringResource(R.string.remote_waiting_no_response, server.host, status.seconds)
                        null -> stringResource(R.string.remote_waiting, status.seconds)
                    }
                    Text(text, modifier = Modifier.weight(1f))
                    ActionIcon(Icons.Rounded.Refresh, stringResource(R.string.remote_retry), onClick = onRetry)
                }
                LinkStatus.Locked -> {
                    Text(stringResource(R.string.remote_locked), modifier = Modifier.weight(1f))
                    ActionIcon(Icons.Rounded.Fingerprint, stringResource(R.string.remote_unlock), onClick = onUnlock)
                }
                LinkStatus.PasswordLocked -> {
                    Text(stringResource(R.string.remote_password_saved), modifier = Modifier.weight(1f))
                    ActionIcon(Icons.Rounded.Fingerprint, stringResource(R.string.remote_password_unlock)) {
                        auth.unlockPassword(server, { p -> usePassword(p, true, null) }, retry)
                    }
                    // Another password (changed on the server): the saved one is forgotten.
                    ActionIcon(Icons.Rounded.KeyOff, stringResource(R.string.remote_password_forget)) {
                        SavedPasswords.remove(server.id)
                        retry()
                    }
                }
                is LinkStatus.NeedsPassword -> {
                    var password by remember { mutableStateOf("") }
                    // Remember the password: only with the fingerprint (if the tablet has one enrolled).
                    var keep by remember { mutableStateOf(false) }
                    val onPassword = { p: String ->
                        if (keep) auth.savePassword(p) { blob -> usePassword(p, false, blob) } else usePassword(p, false, null)
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        isError = status.wrong,
                        placeholder = {
                            Text(
                                stringResource(
                                    when {
                                        status.wrong -> R.string.remote_password_wrong
                                        status.keyRejected -> R.string.remote_password_key_rejected
                                        else -> R.string.remote_password
                                    },
                                ),
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (password.isNotEmpty()) onPassword(password) }),
                        modifier = Modifier.weight(1f),
                    )
                    if (auth.canSavePasswords) {
                        WithTooltip(stringResource(R.string.remote_password_remember)) {
                            FilledTonalIconToggleButton(checked = keep, onCheckedChange = { keep = it }) {
                                Icon(Icons.Rounded.Fingerprint, contentDescription = stringResource(R.string.remote_password_remember))
                            }
                        }
                    }
                    ActionIcon(Icons.AutoMirrored.Rounded.Login, stringResource(R.string.remote_login), enabled = password.isNotEmpty()) { onPassword(password) }
                }
                LinkStatus.HostKeyChanged -> {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.remote_host_key_changed), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    ActionIcon(Icons.Rounded.Refresh, stringResource(R.string.remote_retry), onClick = onRetry)
                }
                LinkStatus.KeyInvalid -> {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.remote_key_invalid), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    ActionIcon(Icons.Rounded.Refresh, stringResource(R.string.remote_retry), onClick = onRetry)
                }
                LinkStatus.Ended -> {
                    Text(stringResource(R.string.remote_ended), modifier = Modifier.weight(1f))
                    ActionIcon(Icons.Rounded.Refresh, stringResource(R.string.remote_new_shell), onClick = onRetry)
                }
                LinkStatus.Connected -> Unit
            }
        }
    }
    if (status == LinkStatus.Connecting) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
}

/** Local terminal: no connection; only the ended shell is shown, with the icon to open a new one. */
@Composable
private fun LocalShellBanner(status: LinkStatus, retry: () -> Unit) {
    if (status != LinkStatus.Ended) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(stringResource(R.string.remote_ended), modifier = Modifier.weight(1f))
            ActionIcon(Icons.Rounded.Refresh, stringResource(R.string.remote_new_shell), onClick = retry)
        }
    }
}

/**
 * On-screen keyboard icon of terminal and editor: the keyboard opens only from here, not by touching the
 * content (it reopened at every tap and after the laser; user's request). With a physical keyboard
 * connected Android does not show it (system setting): the icon disappears (user's request).
 */
@Composable
internal fun KeyboardToggle(tab: WorkTab) {
    val config = LocalConfiguration.current
    val hardKeyboard = config.keyboard != Configuration.KEYBOARD_NOKEYS && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    if (hardKeyboard) {
        LaunchedEffect(Unit) { tab.softKeyboard = false }
        return
    }
    val shown = tab.softKeyboard
    val view = LocalView.current
    SmallIconButton(
        if (shown) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard,
        stringResource(if (shown) R.string.terminal_keyboard_hide else R.string.terminal_keyboard_show),
    ) {
        tab.softKeyboard = !shown
        // Closed with the pen nearby, the content loses the input together with the keyboard and (in the terminal)
        // termlib does not manage to hide it: ask the system directly.
        if (shown) {
            view.context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
}

/** A− A+ of the tab (editor and terminal): font size remembered per tab. */
@Composable
internal fun FontRow(tab: WorkTab, beforeFont: @Composable () -> Unit = {}, extra: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        extra()
        Box(Modifier.weight(1f))
        beforeFont()
        SmallIconButton(Icons.Rounded.TextDecrease, stringResource(R.string.code_font_smaller), enabled = tab.fontSize > WorkTab.FONT_RANGE.start) {
            tab.fontSize = (tab.fontSize - 1f).coerceIn(WorkTab.FONT_RANGE)
        }
        SmallIconButton(Icons.Rounded.TextIncrease, stringResource(R.string.code_font_larger), enabled = tab.fontSize < WorkTab.FONT_RANGE.endInclusive) {
            tab.fontSize = (tab.fontSize + 1f).coerceIn(WorkTab.FONT_RANGE)
        }
    }
}

/**
 * Two fingers on work tabs (editor, terminal, web page) change the font size like a zoom. The gesture is
 * taken before the children (Initial pass), so neither the scrolling nor the terminal's internal zoom see it.
 * The font changes live, in steps of one point (no magnified preview: the user found it annoying); the
 * editor keeps the same line at the top.
 */
internal fun Modifier.pinchFontSize(session: PresenterSession, tab: WorkTab): Modifier = pointerInput(tab) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var startDistance = 0f
        var startFont = tab.fontSize
        var started = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            // Fingers only, and not with the pen nearby: the palm resting while writing is not a zoom.
            val pressed = event.changes.filter { it.pressed && it.type == PointerType.Touch }
            if (event.changes.none { it.pressed }) break
            if (pressed.size >= 2 && !session.workStylusNear) {
                val a = pressed[0].position
                val b = pressed[1].position
                val distance = (a - b).getDistance().coerceAtLeast(1f)
                if (startDistance == 0f) startDistance = distance
                if (!started && abs(distance - startDistance) >= PINCH_SLOP_DP.dp.toPx()) {
                    started = true
                    startDistance = distance
                    startFont = tab.fontSize
                }
                if (started) {
                    val size = (startFont * distance / startDistance).roundToInt().toFloat().coerceIn(WorkTab.FONT_RANGE)
                    if (size != tab.fontSize) tab.fontSize = size
                }
            }
            // Once there are two fingers the gesture belongs to the pinch, even after lifting one.
            if (startDistance != 0f) event.changes.forEach { it.consume() }
        }
    }
}

private const val PINCH_SLOP_DP = 12f

/** Transparent layer over the content that takes the pen (see `WorkTabContent`). */
@Composable
internal fun BoxScope.PenShield(on: Boolean) {
    if (on) Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
}

/**
 * Redraws the laser trail of the tabs at every frame while there is something to animate (dot, fading
 * trail); restarts at every pen event.
 */
@Composable
internal fun workTrailTicker(session: PresenterSession): MutableLongState {
    val now = remember { mutableLongStateOf(0L) }
    // Read in a flow and not in the composition: pen events do not recompose the tab.
    LaunchedEffect(session) {
        snapshotFlow { session.workTrailFrame }.collectLatest {
            while (true) {
                withFrameMillis { }
                now.longValue = SystemClock.uptimeMillis()
                if (!session.workTrail.prune(now.longValue)) break
            }
        }
    }
    return now
}

/**
 * Laser over code and browser tabs: with the laser preset selected (or as the dual) the S Pen, touching or
 * near the screen, moves the dot and leaves the trail as on the pages (style and duration of the preset and
 * the settings; same relative position on the projector). Fingers and keyboard go to the tab. It is a
 * modifier of the container (Initial pass): a layer above the tab would take all its touches.
 */
@Composable
fun Modifier.workLaser(session: PresenterSession, tab: WorkTab): Modifier {
    val renderer = remember { LaserRenderer() }
    val tick = workTrailTicker(session)
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // Over the editor the laser anchors to the start of the text (like the pen); elsewhere to the tab area.
    fun textOrigin(): Offset? {
        val ref = coords[0] ?: return null
        return when (tab) {
            is EditorTab -> tab.codeInk.originIn?.invoke(ref)
            is TerminalTab -> tab.originIn?.invoke(ref)
            else -> null
        }
    }
    return this
        .onGloballyPositioned { c -> coords[0] = c }
        .pointerInput(tab) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.type != PointerType.Stylus) continue
                    // Side button: MainActivity follows it from the raw events (it does not always arrive here).
                    val buttons = session.tools.buttonHeld || event.buttons.isSecondaryPressed
                    val tools = session.tools
                    val preset = tools.preset(tools.effective(buttonPressed = buttons, eraserTip = false))
                    val trail = session.workTrail
                    val t = change.uptimeMillis
                    val k = 1000f / size.width
                    session.workAspect = size.height / size.width.toFloat()
                    session.workWidth = size.width.toFloat()
                    val origin = textOrigin()
                    if ((origin != null) != session.workLaserInText) {
                        // The frame of reference changes: the previous trail would no longer make sense.
                        trail.clear()
                        session.workLaserInText = origin != null
                    }
                    val ox = origin?.x ?: 0f
                    val oy = origin?.y ?: 0f
                    val exit = event.type == PointerEventType.Exit
                    session.workStylusNear = !exit
                    if (preset?.kind != ToolKind.LASER || exit) {
                        if (trail.current != null || trail.hovering) {
                            if (trail.current != null) trail.end(t)
                            trail.hoverExit(t)
                            session.workTrailFrame++
                        }
                        continue
                    }
                    val x = (change.position.x - ox) * k
                    val y = (change.position.y - oy) * k
                    when {
                        change.pressed && (!change.previousPressed || trail.current == null) ->
                            trail.begin(preset.laserStyle, preset.color, preset.size, preset.pressure, x, y, t, change.pressure)
                        change.pressed -> {
                            for (h in change.historical) trail.add((h.position.x - ox) * k, (h.position.y - oy) * k, h.uptimeMillis, change.pressure)
                            trail.add(x, y, t, change.pressure)
                        }
                        change.previousPressed -> {
                            trail.end(t)
                            trail.hover(preset.color, preset.size * HOVER_DOT_SCALE, x, y)
                        }
                        else -> trail.hover(preset.color, preset.size * HOVER_DOT_SCALE, x, y)
                    }
                    session.workTrailFrame++
                    // Not consumed: the icons around the content (keyboard, A−/A+, bars) must receive the pen's click.
                    // On the content the pen is taken by [PenShield].
                }
            }
        }
        .drawWithContent {
            drawContent()
            tick.longValue
            val o = if (session.workLaserInText) textOrigin() ?: Offset.Zero else Offset.Zero
            val m = Matrix().apply {
                setScale(size.width / 1000f, size.width / 1000f)
                postTranslate(o.x, o.y)
            }
            drawIntoCanvas { renderer.draw(it.nativeCanvas, session.workTrail, m, SystemClock.uptimeMillis()) }
        }
}

/** Hover dot relative to the laser width (as on the pages). */
private const val HOVER_DOT_SCALE = 0.75f
