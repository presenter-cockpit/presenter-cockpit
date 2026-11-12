package it.presenter.app.projector

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.vectorResource
import it.presenter.app.R

/**
 * What the audience sees with the "black" screen: an almost black background (it does not dazzle a dark
 * room) and, in the center, the pause symbol: two rounded bars inside a thin circle with a soft
 * glow. The opacity slowly "breathes", so it is clear the app is alive, not frozen.
 */
@Composable
fun PauseScreen(modifier: Modifier = Modifier) = BreathingScreen(modifier) { c, r, breath ->
    val barW = r * 0.22f
    val barH = r * 0.9f
    val gap = r * 0.18f
    for (x in floatArrayOf(c.x - gap - barW, c.x + gap)) {
        drawRoundRect(
            SYMBOL.copy(alpha = breath),
            topLeft = Offset(x, c.y - barH / 2f),
            size = Size(barW, barH),
            cornerRadius = CornerRadius(barW / 2f),
        )
    }
}

/**
 * Private start: like the pause, but with the app logo in color in the center (user's request): a disc
 * in the icon's background color with the drawing on top, breathing with the rest. The audience sees
 * that the screen is connected and nothing else.
 */
@Composable
fun PrivateStartScreen(modifier: Modifier = Modifier) {
    val logo = rememberVectorPainter(
        ImageVector.vectorResource(R.drawable.ic_launcher_foreground),
    )
    val background = colorResource(R.color.ic_launcher_background)
    BreathingScreen(modifier) { c, r, breath ->
        // Disc just inside the thin circle, which stays visible around it.
        val disc = r * DISC_FRACTION
        drawCircle(background.copy(alpha = breath), radius = disc, center = c)
        // The adaptive icon layer is 108 dp, of which the central 72 are always visible: the disc matches that
        // area, as in Android's round icons.
        val side = 2f * disc * 108f / 72f
        translate(c.x - side / 2f, c.y - side / 2f) {
            with(logo) { draw(Size(side, side), alpha = breath) }
        }
    }
}

/**
 * Breathing background, glow and circle of the pause; [symbol] draws at the center [c], inside the
 * circle of radius r, with the current opacity.
 */
@Composable
private fun BreathingScreen(modifier: Modifier, symbol: DrawScope.(c: Offset, r: Float, breath: Float) -> Unit) {
    val breath by rememberInfiniteTransition(label = "pause").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS / 2, easing = LinearOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    Canvas(modifier.fillMaxSize()) {
        drawRect(BACKGROUND)
        val c = center
        val r = size.height * SYMBOL_FRACTION / 2f
        // Glow: a blurred disc just lighter than the background.
        drawCircle(
            Brush.radialGradient(listOf(SYMBOL.copy(alpha = 0.10f * breath), Color.Transparent), c, r * 2.2f),
            radius = r * 2.2f, center = c,
        )
        drawCircle(SYMBOL.copy(alpha = 0.75f * breath), radius = r, center = c, style = Stroke(width = r * 0.06f))
        symbol(c, r, breath)
    }
}

private val BACKGROUND = Color(0xFF060607)
private val SYMBOL = Color(0xFFB8BCC4)

/** Circle diameter relative to the screen height. */
private const val SYMBOL_FRACTION = 0.15f

/** Radius of the logo disc relative to the pause circle (the thin circle stays around it). */
private const val DISC_FRACTION = 0.94f

/** A full "breathing" cycle. */
private const val BREATH_MS = 4000
