package it.presenter.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.presenter.app.session.PresenterSession
import it.presenter.app.tabs.LinkStatus
import it.presenter.app.tabs.TerminalTab
import kotlin.math.abs
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey

/**
 * Terminal tab on the tablet: font and keyboard row, the grid (termlib) and, with the on-screen keyboard, the
 * extra keys.
 */
@Composable
internal fun TerminalContent(session: PresenterSession, tab: TerminalTab, colors: CodeColors, keyboard: Boolean, shield: Boolean) {
    val config = LocalConfiguration.current
    // With a physical keyboard the extra keys row is not needed.
    val hardKeyboard = config.keyboard != Configuration.KEYBOARD_NOKEYS && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    Column {
        FontRow(tab, beforeFont = { KeyboardToggle(tab) })
        val fontPx = with(LocalDensity.current) { tab.fontSize.sp.toPx() }
        SideEffect { tab.fontPx = fontPx }
        // The laser anchors to the corner of the grid.
        var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        DisposableEffect(tab) {
            val origin: (LayoutCoordinates) -> Offset? = { ref ->
                gridCoords?.takeIf { it.isAttached && ref.isAttached }?.let { g -> ref.localPositionOf(g, Offset.Zero) }
            }
            tab.originIn = origin
            onDispose { if (tab.originIn === origin) tab.originIn = null }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { c ->
                    gridCoords = c
                    tab.viewWidthPx = c.size.width.toFloat()
                    tab.viewHeightPx = c.size.height.toFloat()
                }
                .pinchFontSize(session, tab)
                .then(if (tab.usingTmux) Modifier.tmuxWheel(session, tab) else Modifier)
                .clipToBounds(),
        ) {
            // termlib also selects with a double tap followed by a drag and after the system long press (~0.5 s):
            // scrolling in jerks or with a short pause before moving the finger selected text. Here: no double tap,
            // selection only with the finger held still longer ([TERMINAL_SELECT_MS]); dragging scrolls.
            // Keyboard opened from the icon: the terminal takes the focus as with a tap, otherwise the keyboard
            // appears but what is typed does not reach the shell.
            val focusRequester = remember(tab) { FocusRequester() }
            LaunchedEffect(tab.softKeyboard, keyboard) {
                if (tab.softKeyboard && keyboard) runCatching { focusRequester.requestFocus() }
            }
            val base = LocalViewConfiguration.current
            val viewConfiguration = remember(base) {
                object : ViewConfiguration by base {
                    override val longPressTimeoutMillis get() = TERMINAL_SELECT_MS
                    override val doubleTapTimeoutMillis get() = 0L
                }
            }
            key(tab.fontSize, colors) {
                CompositionLocalProvider(LocalViewConfiguration provides viewConfiguration) {
                    Terminal(
                        terminalEmulator = tab.emulator,
                        modifier = Modifier.fillMaxSize(),
                        initialFontSize = tab.fontSize.sp,
                        backgroundColor = colors.background,
                        foregroundColor = colors.foreground,
                        keyboardEnabled = keyboard,
                        showSoftKeyboard = keyboard && tab.softKeyboard,
                        focusRequester = focusRequester,
                        // Closed by the system (back gesture) or by the laser: the icon goes back to "show" and the
                        // keyboard does not reopen by itself.
                        onImeVisibilityChanged = { visible ->
                            if (visible) tab.imeShown = true
                            else if (tab.imeShown) {
                                tab.imeShown = false
                                tab.softKeyboard = false
                            }
                        },
                        modifierManager = tab.modifiers,
                        onTerminalTap = { if (tab.status == LinkStatus.Ended) tab.start() },
                    )
                }
            }
            PenShield(shield)
        }
        // The extra keys complete the on-screen keyboard: only while it is open.
        if (session.settings.terminalExtraKeys && !hardKeyboard && tab.softKeyboard) ExtraKeys(tab)
    }
}

/** Row of keys for the shell that the on-screen keyboard lacks. Ctrl and Alt apply to the next key. */
@Composable
private fun ExtraKeys(tab: TerminalTab) {
    val emu = tab.emulator
    val mods = tab.modifiers
    fun modBits() = (if (mods.ctrl) 4 else 0) or (if (mods.alt) 2 else 0)
    fun key(code: Int) {
        emu.dispatchKey(modBits(), code)
        mods.clearTransients()
    }
    fun char(c: Char) {
        emu.dispatchCharacter(modBits(), c.code)
        mods.clearTransients()
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(4.dp),
        ) {
            KeyCap("Esc") { key(VTermKey.ESCAPE) }
            KeyCap("Ctrl", active = mods.ctrl) { mods.ctrl = !mods.ctrl }
            KeyCap("Alt", active = mods.alt) { mods.alt = !mods.alt }
            KeyCap("Tab") { key(VTermKey.TAB) }
            KeyCap("←") { key(VTermKey.LEFT) }
            KeyCap("↑") { key(VTermKey.UP) }
            KeyCap("↓") { key(VTermKey.DOWN) }
            KeyCap("→") { key(VTermKey.RIGHT) }
            for (c in listOf('|', '~', '/', '-', '_', '*', '&', '$')) KeyCap(c.toString()) { char(c) }
            KeyCap("Home") { key(VTermKey.HOME) }
            KeyCap("End") { key(VTermKey.END) }
            KeyCap("PgUp") { key(VTermKey.PAGEUP) }
            KeyCap("PgDn") { key(VTermKey.PAGEDOWN) }
        }
    }
}

@Composable
private fun KeyCap(label: String, active: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (active) colors.onPrimary else colors.onSurface,
        modifier = Modifier
            .background(if (active) colors.primary else colors.surfaceContainerHighest, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/**
 * Inside tmux the history is tmux's (with the mouse on): a one-finger vertical swipe becomes the wheel, one
 * step every [TMUX_WHEEL_LINES] lines (as many as tmux scrolls), so the text follows the finger.
 */
private fun Modifier.tmuxWheel(session: PresenterSession, tab: TerminalTab): Modifier = pointerInput(tab) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.type != PointerType.Touch || session.workStylusNear) return@awaitEachGesture
        var dragging = false
        var acc = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            // A second finger: it is a pinch, not a scroll.
            if (event.changes.count { it.pressed } > 1) break
            val c = event.changes.firstOrNull { it.id == down.id }?.takeIf { it.pressed } ?: break
            if (!dragging && abs(c.position.y - down.position.y) > viewConfiguration.touchSlop) dragging = true
            if (!dragging) continue
            // Not consumed: termlib measures the movement with positionChange (zero if consumed) and, believing the
            // finger still, started selecting. Its own scrolling moves nothing here (inside tmux its history is empty:
            // tmux uses the alternate screen).
            val (rows, cols) = tab.size
            if (rows <= 0 || cols <= 0 || tab.viewHeightPx <= 0f) continue
            val cellH = tab.viewHeightPx / rows
            val step = cellH * TMUX_WHEEL_LINES
            acc += c.position.y - c.previousPosition.y
            val col = (c.position.x / (tab.viewWidthPx / cols)).toInt().coerceIn(0, cols - 1) + 1
            val row = (c.position.y / cellH).toInt().coerceIn(0, rows - 1) + 1
            while (abs(acc) >= step) {
                // Finger moving down: show what came before (wheel up).
                tab.wheel(up = acc > 0, col = col, row = row)
                acc -= if (acc > 0) step else -step
            }
        }
    }
}

private const val TMUX_WHEEL_LINES = 5

/** Finger held still on the terminal to start selecting text. */
private const val TERMINAL_SELECT_MS = 800L
