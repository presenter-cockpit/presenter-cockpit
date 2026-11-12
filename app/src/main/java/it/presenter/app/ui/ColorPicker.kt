package it.presenter.app.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Free color picker: a saturation/brightness square and a hue bar. [onChange] at every move (live
 * preview), [onChangeFinished] with the final color when the finger is lifted.
 */
@Composable
fun ColorPicker(color: Int, onChange: (Int) -> Unit, onChangeFinished: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { AndroidColor.colorToHSV(color, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onChangeFinished)
    fun current() = AndroidColor.HSVToColor(floatArrayOf(hue, sat, value))
    fun emit() = change(current())

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Saturation horizontally, brightness vertically.
        Canvas(
            Modifier.fillMaxWidth().height(132.dp).pointerInput(Unit) {
                trackDrag(onEnd = { finished(current()) }) { p ->
                    sat = (p.x / size.width).coerceIn(0f, 1f)
                    value = 1f - (p.y / size.height).coerceIn(0f, 1f)
                    emit()
                }
            },
        ) {
            val radius = CornerRadius(12.dp.toPx())
            val pure = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            drawRoundRect(Brush.horizontalGradient(listOf(Color.White, pure)), cornerRadius = radius)
            drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), cornerRadius = radius)
            val c = Offset(sat * size.width, (1f - value) * size.height)
            drawCircle(Color.White, 9.dp.toPx(), c, style = Stroke(3.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.4f), 10.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        }
        Canvas(
            Modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) {
                trackDrag(onEnd = { finished(current()) }) { p ->
                    hue = (p.x / size.width).coerceIn(0f, 1f) * 360f
                    emit()
                }
            },
        ) {
            val hues = (0..6).map { Color(AndroidColor.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }
            drawRoundRect(Brush.horizontalGradient(hues), cornerRadius = CornerRadius(size.height / 2))
            val c = Offset(hue / 360f * size.width, size.height / 2)
            drawCircle(Color.White, size.height / 2 - 2.dp.toPx(), c, style = Stroke(3.dp.toPx()))
        }
    }
}

/** Tap or drag: [onMove] with the position at every step, [onEnd] when lifted. */
private suspend fun PointerInputScope.trackDrag(onEnd: () -> Unit, onMove: (Offset) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        onMove(down.position)
        drag(down.id) { change ->
            change.consume()
            onMove(change.position)
        }
        onEnd()
    }
}
