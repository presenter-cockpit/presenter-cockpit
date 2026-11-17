package it.presenter.app.ui

import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.ImmutableStrokeInputBatch
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import it.presenter.app.ink.BrushFamilies
import it.presenter.app.ink.ElementPainter
import it.presenter.app.ink.InkStroke
import it.presenter.app.session.PresenterSession
import it.presenter.app.tabs.CodeInk
import it.presenter.app.tools.ToolPlace
import it.presenter.app.tools.ToolRef

/**
 * Pen over code and terminal (pen, marker, highlighter with their brush, and eraser): the S Pen is taken
 * before the content (Initial pass). Widths as on the pages: the width of the box counts as 1000 page units.
 * What the pen does here is decided by [ToolPlace.CODE]: the laser, shapes, text and lasso do not write.
 * [origin]: start of the text (editor) or corner of the grid (terminal), in the box's coordinates.
 */
internal fun Modifier.codeInkInput(session: PresenterSession, ci: CodeInk, origin: () -> Offset): Modifier = pointerInput(ci) {
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
            val inkTool = preset != null && ToolPlace.CODE.draws(preset.kind)
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
                    ci.liveElapsed = 0L
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
                        live.updateShape(ci.elapsed(change.uptimeMillis))
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

/** Draws the strokes over the code or the terminal, in the same frame as the content. */
@Composable
internal fun Modifier.codeInkDrawing(ci: CodeInk, origin: () -> Offset): Modifier {
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
        runCatching { live.updateShape(ci.elapsed(SystemClock.uptimeMillis())) }
        liveRenderer.draw(canvas, live, toScreen)
    }
    canvas.restore()
}
