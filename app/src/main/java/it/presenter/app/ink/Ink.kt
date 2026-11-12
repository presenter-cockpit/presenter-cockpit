package it.presenter.app.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ColorFunction
import androidx.ink.brush.EasingFunction
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import it.presenter.app.tools.ToolKind
import it.presenter.app.tools.ToolPreset

/**
 * Page coordinates: every page is [PAGE_WIDTH] units wide, whatever the PDF format. Strokes and laser
 * live in these coordinates, so they stay attached to the page when zooming and are redrawn identically
 * on screens of different resolution (tablet and projector).
 */
const val PAGE_WIDTH = 1000f

/**
 * Geometric fidelity of the strokes, in page units. Ink recommends ~0.1 physical px at zoom 1: on a
 * Tab S9+ the page takes ~2700 px, i.e. ~2.7 px per unit.
 */
const val EPSILON = 0.04f

/** Highlighter opacity: enough to be seen, not so much that it covers the text. */
const val HIGHLIGHTER_ALPHA = 0x66

fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

/**
 * Brush families, identified by a stable key: the key is saved in the PDF with the stroke, so it can be
 * rebuilt identically on reopening.
 */
@OptIn(ExperimentalInkCustomBrushApi::class)
object BrushFamilies {
    const val PRESSURE_PEN = "pressure-pen"
    /** Stock hard-edged marker: strokes saved before the soft edge existed, and the pen without pressure. */
    const val MARKER = "marker"
    const val SOFT_MARKER = "soft-marker"
    const val CHISEL_MARKER = "chisel-marker"
    const val HIGHLIGHTER = "highlighter"

    /**
     * Pressure-sensitive pen. Ink's stock one ([StockBrushes.pressurePen]) only reacts to the last 20% of
     * the pressure: here the stroke really thins with a light touch.
     * - pressure 0→1 ⇒ width 35%→100%, EASE_OUT curve (the change is felt in light touches);
     * - 12 ms time constant against sensor noise, with no perceptible delay;
     * - the tip narrows slightly at the end of the stroke, like a pen being lifted.
     */
    private val pressurePen: BrushFamily by lazy {
        BrushFamily(
            tip = BrushTip(
                behaviors = listOf(
                    BrushBehavior(
                        source = BrushBehavior.Source.NORMALIZED_PRESSURE,
                        target = BrushBehavior.Target.SIZE_MULTIPLIER,
                        sourceValueRangeStart = 0f,
                        sourceValueRangeEnd = 1f,
                        targetModifierRangeStart = 0.35f,
                        targetModifierRangeEnd = 1f,
                        sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
                        responseCurve = EasingFunction.Predefined.EASE_OUT,
                        responseTimeMillis = 12L,
                        enabledToolTypes = setOf(InputToolType.STYLUS),
                    ),
                    BrushBehavior(
                        source = BrushBehavior.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE,
                        target = BrushBehavior.Target.SIZE_MULTIPLIER,
                        sourceValueRangeStart = 3f,
                        sourceValueRangeEnd = 0f,
                        targetModifierRangeStart = 1f,
                        targetModifierRangeEnd = 0.8f,
                        sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
                    ),
                ),
            ),
            clientBrushFamilyId = "presenter:$PRESSURE_PEN",
        )
    }

    private val marker: BrushFamily by lazy { StockBrushes.marker() }

    /**
     * Soft-edged marker: a solid core and, below it, wider and more transparent halos ([SOFT_EDGE]), like
     * ink spreading into paper. Ink 1.0 has no "softness" parameter: each halo is a layer (coat) of the
     * stroke. The halos do not darken where the stroke passes over itself (DISCARD).
     */
    private fun softMarker(key: String, scaleY: Float, rotationDegrees: Float, cornerRounding: Float) = BrushFamily(
        coats = SOFT_EDGE.map { (scale, opacity) ->
            BrushCoat(
                BrushTip(scaleX = scale, scaleY = scaleY * scale, cornerRounding = cornerRounding, rotationDegrees = rotationDegrees),
                BrushPaint(
                    colorFunctions = if (opacity < 1f) listOf(ColorFunction.OpacityMultiplier(opacity)) else emptyList(),
                    selfOverlap = if (opacity < 1f) SelfOverlap.DISCARD else SelfOverlap.ANY,
                ),
            )
        },
        clientBrushFamilyId = "presenter:$key",
    )

    /** Pairs (tip scale, opacity) from the outermost halo to the core. */
    private val SOFT_EDGE = listOf(1f to 0.15f, 0.85f to 0.3f, 0.7f to 0.5f, 0.55f to 1f)

    private val softMarker: BrushFamily by lazy { softMarker(SOFT_MARKER, scaleY = 1f, rotationDegrees = 0f, cornerRounding = 1f) }

    /**
     * Chisel tip: flat (30%), with the long side tilted 45° like "/" (right-handed grip, as in calligraphy):
     * thin going up to the right, full going down; corners slightly rounded.
     */
    private val chiselMarker: BrushFamily by lazy { softMarker(CHISEL_MARKER, scaleY = 0.3f, rotationDegrees = -45f, cornerRounding = 0.3f) }

    /** DISCARD: where the stroke overlaps itself the color does not darken. */
    private val highlighter: BrushFamily by lazy { StockBrushes.highlighter(SelfOverlap.DISCARD) }

    fun byKey(key: String): BrushFamily? = when (key) {
        PRESSURE_PEN -> pressurePen
        MARKER -> marker
        SOFT_MARKER -> softMarker
        CHISEL_MARKER -> chiselMarker
        HIGHLIGHTER -> highlighter
        else -> null
    }

    /** Family key of an existing brush (to save it), or null if unknown. */
    fun keyOf(family: BrushFamily): String? = listOf(PRESSURE_PEN, MARKER, SOFT_MARKER, CHISEL_MARKER, HIGHLIGHTER).firstOrNull { byKey(it) == family }

    fun keyFor(preset: ToolPreset): String? = when (preset.kind) {
        ToolKind.PEN -> if (preset.pressure) PRESSURE_PEN else MARKER
        ToolKind.MARKER -> if (preset.chisel) CHISEL_MARKER else SOFT_MARKER
        ToolKind.HIGHLIGHTER -> HIGHLIGHTER
        // The stroke a shape is born from: a sharp line of constant width.
        ToolKind.SHAPE -> MARKER
        ToolKind.TEXT, ToolKind.LASER -> null
    }

    fun brushFor(preset: ToolPreset): Brush? {
        val key = keyFor(preset) ?: return null
        val color = if (preset.kind == ToolKind.HIGHLIGHTER) withAlpha(preset.color, HIGHLIGHTER_ALPHA) else preset.color
        return Brush.createWithColorIntArgb(byKey(key)!!, color, preset.size, EPSILON)
    }
}
