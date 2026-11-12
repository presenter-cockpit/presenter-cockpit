package it.presenter.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import it.presenter.app.tools.LaserStyle
import it.presenter.app.tools.ToolKind
import it.presenter.app.tools.ToolPreset
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tool icons (24×24, black fill: colored with `tint`). The Material set has no eraser, and its
 * pen/marker/highlighter look too much alike.
 */
object ToolIcons {
    val Pen: ImageVector by lazy {
        icon("Pen") {
            fill("M3.6 20.4 L4.8 15.9 L15.6 5.1 C16.4 4.3 17.6 4.3 18.4 5.1 L18.9 5.6 C19.7 6.4 19.7 7.6 18.9 8.4 L8.1 19.2 Z")
        }
    }

    val Marker: ImageVector by lazy {
        icon("Marker") {
            fill("M3 21 L4.2 16.2 L13.9 6.5 L17.5 10.1 L7.8 19.8 Z")
            fill("M15.1 5.3 L16.9 3.5 C17.6 2.8 18.7 2.8 19.4 3.5 L20.5 4.6 C21.2 5.3 21.2 6.4 20.5 7.1 L18.7 8.9 Z")
        }
    }

    val Highlighter: ImageVector by lazy {
        icon("Highlighter") {
            fill("M8.6 12.9 L15.4 6.1 C16.2 5.3 17.4 5.3 18.2 6.1 L19.9 7.8 C20.7 8.6 20.7 9.8 19.9 10.6 L13.1 17.4 Z")
            fill("M7.9 13.6 L12.4 18.1 L10.4 20.1 L5.9 20.1 L5.9 15.6 Z")
            fill("M3 21.5 H21 V23 H3 Z", alpha = 0.55f)
        }
    }

    val Laser: ImageVector by lazy {
        icon("Laser") {
            fill("M12 8.6 A3.4 3.4 0 1 1 12 15.4 A3.4 3.4 0 1 1 12 8.6 Z")
            stroke("M12 2.5 V5.3 M12 18.7 V21.5 M2.5 12 H5.3 M18.7 12 H21.5 M5.3 5.3 L7.2 7.2 M16.8 16.8 L18.7 18.7 M5.3 18.7 L7.2 16.8 M16.8 7.2 L18.7 5.3", 1.8f)
        }
    }

    val Eraser: ImageVector by lazy {
        icon("Eraser") {
            fill("M14.1 3.1 L20.9 9.9 C21.5 10.5 21.5 11.5 20.9 12.1 L16.4 16.6 L7.4 7.6 L11.9 3.1 C12.5 2.5 13.5 2.5 14.1 3.1 Z")
            stroke("M7.4 7.6 L16.4 16.6 L12.9 20.1 L7.9 20.1 L3.1 15.3 C2.5 14.7 2.5 13.7 3.1 13.1 Z", 1.7f)
            stroke("M13.5 20.6 H21", 1.7f)
        }
    }

    /** "Pointer" laser: dot with a short tail. */
    val Comet: ImageVector by lazy {
        icon("Pointer") {
            fill("M16.5 5.2 A3.6 3.6 0 1 1 16.5 12.4 A3.6 3.6 0 1 1 16.5 5.2 Z")
            fill("M13.6 7.4 L3 15.8 L14.6 11.1 Z", alpha = 0.6f)
        }
    }

    /** "Trail" laser: a fading path. */
    val Trail: ImageVector by lazy {
        icon("Trail") {
            stroke("M3 17.5 C6 10 9.5 20 13 12", 2.2f, alpha = 0.35f)
            stroke("M13 12 C15.5 6.5 18 7 20.5 6.5", 2.2f)
            fill("M20.5 4.4 A2.1 2.1 0 1 1 20.5 8.6 A2.1 2.1 0 1 1 20.5 4.4 Z")
        }
    }

    /** Width following the pressure: a tapered stroke. */
    val Pressure: ImageVector by lazy {
        icon("Pressure") {
            fill("M2.5 19.5 C7 13.5 12.5 7.5 21.5 4.5 C14.5 9.5 9 14.5 4.5 20.5 C3.9 21.1 2.9 20.4 2.5 19.5 Z")
        }
    }

    /** Partial eraser: a stroke with a piece removed in the middle. */
    val ErasePartial: ImageVector by lazy {
        icon("Partial eraser") {
            stroke("M3 16 C5 10 7.5 9 9.5 11.5", 2.2f)
            stroke("M9.5 11.5 C11 13.5 13 13.5 14.5 12", 2.2f, alpha = 0.25f)
            stroke("M14.5 12 C16.5 10 18.5 9 21 8", 2.2f)
        }
    }

    /** Whole-stroke eraser: the whole stroke faded. */
    val EraseStroke: ImageVector by lazy {
        icon("Whole-stroke eraser") {
            stroke("M3 16 C5 10 7.5 9 9.5 11.5 C11 13.5 13 13.5 14.5 12 C16.5 10 18.5 9 21 8", 2.2f, alpha = 0.3f)
            stroke("M5 19 L19 5", 1.8f)
        }
    }

    /** Round-tip marker: stroke of uniform width. */
    val TipRound: ImageVector by lazy {
        icon("Round tip") {
            stroke("M4 18 C8 9 13 17 20 6", 4f)
        }
    }

    /** Chisel-tip marker: stroke that widens and thins with the direction. */
    val TipChisel: ImageVector by lazy {
        icon("Chisel tip") {
            fill("M2.5 17.5 L5.5 14.5 C8 12 10 13.5 11.5 15 C12.8 16.3 14 16.5 15.5 15 L21.5 9 L21.5 12 L16 17.5 C14 19.5 12 19 10.3 17.3 C9 16 8 15.5 6.5 17 L3.5 20 Z")
            fill("M14 5 L18 3 L21 6 L19 10 Z", alpha = 0.5f)
        }
    }

    /** Shapes: a triangle and a circle over a square. */
    val Shape: ImageVector by lazy {
        icon("Shapes") {
            stroke("M3.5 10.5 H11.5 V20.5 H3.5 Z", 1.8f)
            stroke("M16.5 3.5 A4.5 4.5 0 1 1 16.5 12.5 A4.5 4.5 0 1 1 16.5 3.5 Z", 1.8f)
            stroke("M14 20.5 L17.5 14.5 L21 20.5 Z", 1.8f)
        }
    }

    /** Text box: a serif "T". */
    val Text: ImageVector by lazy {
        icon("Text") {
            fill("M4.5 4 H19.5 V8 H17.6 L17.1 6.2 H13.4 V18 L15.4 18.5 V20 H8.6 V18.5 L10.6 18 V6.2 H6.9 L6.4 8 H4.5 Z")
        }
    }

    /** Lasso: dashed loop with a tail. */
    val Lasso: ImageVector by lazy {
        icon("Lasso") {
            // Dashed ellipse: 12 short arcs.
            val dashes = StringBuilder()
            for (k in 0 until 12) {
                for ((j, t) in listOf(0f, 0.55f).withIndex()) {
                    val a = (k + t) / 12f * 2f * Math.PI.toFloat()
                    val x = 12f + 8.5f * cos(a)
                    val y = 9.5f + 5.5f * sin(a)
                    dashes.append(if (j == 0) " M$x $y" else " L$x $y")
                }
            }
            stroke(dashes.toString(), 1.8f)
            stroke("M8 14.5 C7 16.5 7.5 18.5 9.5 19 C11 19.4 11.5 20.5 10.5 21.5", 1.8f)
        }
    }

    val DashSolid: ImageVector by lazy { icon("Solid") { stroke("M3 12 H21", 2.4f) } }
    val DashDashed: ImageVector by lazy { icon("Dashed") { stroke("M3 12 H7 M10 12 H14 M17 12 H21", 2.4f) } }
    val DashDotted: ImageVector by lazy {
        icon("Dotted") {
            for (x in listOf(4f, 9.3f, 14.6f, 19.9f)) fill("M$x 10.6 A1.4 1.4 0 1 1 $x 13.4 A1.4 1.4 0 1 1 $x 10.6 Z")
        }
    }

    /** Arrow head at the end of the line. */
    val Arrow: ImageVector by lazy {
        icon("Arrow") {
            stroke("M3.5 20.5 L17 7", 2.2f)
            fill("M20.5 3.5 L19 12 L12 5 Z")
        }
    }

    /** Highlighter snapped to text: text lines with a straight highlight bar. */
    val SnapText: ImageVector by lazy {
        icon("Snapped to text") {
            fill("M3 9 H21 V14 H3 Z", alpha = 0.4f)
            stroke("M5 11.5 H19 M5 5.5 H15 M5 17.5 H17", 1.6f)
        }
    }

    fun forKind(kind: ToolKind): ImageVector = when (kind) {
        ToolKind.PEN -> Pen
        ToolKind.MARKER -> Marker
        ToolKind.HIGHLIGHTER -> Highlighter
        ToolKind.SHAPE -> Shape
        ToolKind.TEXT -> Text
        ToolKind.LASER -> Laser
    }

    fun forPreset(p: ToolPreset): ImageVector =
        if (p.kind == ToolKind.LASER) forLaser(p.laserStyle) else forKind(p.kind)

    fun forLaser(style: LaserStyle): ImageVector = if (style == LaserStyle.COMET) Comet else Trail

    private class Builder(val b: ImageVector.Builder) {
        fun fill(d: String, alpha: Float = 1f) {
            b.addPath(addPathNodes(d), fill = SolidColor(Color.Black), fillAlpha = alpha)
        }

        fun stroke(d: String, width: Float, alpha: Float = 1f) {
            b.addPath(
                addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeAlpha = alpha,
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }

    private fun icon(name: String, block: Builder.() -> Unit): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        Builder(b).block()
        return b.build()
    }
}
