package it.presenter.app.tools

import it.presenter.app.ink.LineDash
import it.presenter.app.ink.TextFont
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Tool kinds, in the editor's order. [SHAPE]: recognized shapes; [TEXT]: text boxes. */
enum class ToolKind { PEN, MARKER, HIGHLIGHTER, SHAPE, TEXT, LASER }

enum class LaserStyle {
    /** Bright dot with a short tail that shortens while moving. */
    COMET,

    /** The path stays visible while drawing and fades shortly after lifting the pen. */
    FADING_INK,
}

/**
 * A tool configured by the user. [size] is in page units (page 1000 wide).
 * [pressure] applies to pen and laser: the width follows the S Pen pressure.
 * [chisel] applies to the marker: chisel tip instead of a round one.
 * [fadeAfterMs] > 0: vanishing ink (pen, marker, highlighter), strokes stay for this time and then
 * fade by themselves.
 * [snapToText] applies to the highlighter: over PDF text it becomes a straight highlight of the lines.
 * Shapes: [fill] (0 = none), [dash], [arrow]. Text: [size] is the font size, [font], [bold], [italic].
 */
data class ToolPreset(
    val id: String,
    val kind: ToolKind,
    val color: Int,
    val size: Float,
    val laserStyle: LaserStyle = LaserStyle.COMET,
    val pressure: Boolean = true,
    val chisel: Boolean = false,
    val fadeAfterMs: Long = 0L,
    val snapToText: Boolean = false,
    val fill: Int = 0,
    val dash: LineDash = LineDash.SOLID,
    val arrow: Boolean = false,
    val font: TextFont = TextFont.SANS,
    val bold: Boolean = false,
    val italic: Boolean = false,
) {
    val supportsPressure get() = kind == ToolKind.PEN || kind == ToolKind.LASER

    val supportsFade get() = kind == ToolKind.PEN || kind == ToolKind.MARKER || kind == ToolKind.HIGHLIGHTER

    /** Time after which strokes fade, or 0 if they stay (or the kind does not support it). */
    val fadeMs get() = if (supportsFade) fadeAfterMs else 0L

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind.name)
        .put("color", color)
        .put("size", size.toDouble())
        .put("laserStyle", laserStyle.name)
        .put("pressure", pressure)
        .put("chisel", chisel)
        .put("fadeAfterMs", fadeAfterMs)
        .put("snapToText", snapToText)
        .put("fill", fill)
        .put("dash", dash.name)
        .put("arrow", arrow)
        .put("font", font.name)
        .put("bold", bold)
        .put("italic", italic)

    /** Brings the width within the limits of the kind. The color is free (picker in the editor). */
    fun normalized(): ToolPreset {
        val range = sizeRange(kind)
        return copy(
            size = size.coerceIn(range.start, range.endInclusive),
            fadeAfterMs = if (fadeAfterMs <= 0L) 0L else fadeAfterMs.coerceIn(FADE_RANGE_MS),
        )
    }

    /**
     * Kind change: between kinds with the same palette (pen and marker) the color stays, even a custom
     * one; otherwise (e.g. from pen to laser) it switches to the first color of the new palette, because
     * a dark ink suits neither a laser nor a highlighter.
     */
    fun withKind(newKind: ToolKind): ToolPreset {
        val samePalette = palette(newKind) == palette(kind)
        val newColor = if (samePalette || color in palette(newKind)) color else palette(newKind).first()
        return copy(kind = newKind, color = newColor).normalized()
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        /** Vanishing ink: time before strokes fade, and the value proposed when turning it on. */
        val FADE_RANGE_MS = 1000L..30_000L
        const val DEFAULT_FADE_MS = 5000L

        /** Font size of text boxes: ~11 pt on a Beamer slide. */
        const val DEFAULT_TEXT_SIZE = 32f

        fun fromJson(o: JSONObject): ToolPreset? = runCatching {
            ToolPreset(
                id = o.getString("id"),
                kind = ToolKind.valueOf(o.getString("kind")),
                color = o.getInt("color"),
                size = o.getDouble("size").toFloat(),
                laserStyle = LaserStyle.valueOf(o.optString("laserStyle", LaserStyle.COMET.name)),
                pressure = o.optBoolean("pressure", true),
                chisel = o.optBoolean("chisel", false),
                fadeAfterMs = o.optLong("fadeAfterMs", 0L),
                snapToText = o.optBoolean("snapToText", false),
                fill = o.optInt("fill", 0),
                dash = runCatching { LineDash.valueOf(o.optString("dash")) }.getOrDefault(LineDash.SOLID),
                arrow = o.optBoolean("arrow", false),
                font = runCatching { TextFont.of(o.optString("font")) }.getOrDefault(TextFont.SANS),
                bold = o.optBoolean("bold", false),
                italic = o.optBoolean("italic", false),
            )
        }.getOrNull()

        fun listToJson(list: List<ToolPreset>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(json: String): List<ToolPreset> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        val defaults: List<ToolPreset>
            get() = listOf(
                ToolPreset(newId(), ToolKind.PEN, Palette.INK[0], 2.6f),
                ToolPreset(newId(), ToolKind.PEN, Palette.INK[1], 2.6f),
                ToolPreset(newId(), ToolKind.PEN, Palette.INK[2], 2.6f),
                ToolPreset(newId(), ToolKind.MARKER, Palette.INK[1], 5f),
                ToolPreset(newId(), ToolKind.HIGHLIGHTER, Palette.HIGHLIGHTER[0], 20f),
                ToolPreset(newId(), ToolKind.SHAPE, Palette.INK[1], 2.6f),
                ToolPreset(newId(), ToolKind.TEXT, Palette.INK[0], DEFAULT_TEXT_SIZE),
                ToolPreset(newId(), ToolKind.LASER, Palette.LASER[0], 5f, LaserStyle.COMET),
                ToolPreset(newId(), ToolKind.LASER, Palette.LASER[1], 4f, LaserStyle.FADING_INK),
            )

        fun sizeRange(kind: ToolKind): ClosedFloatingPointRange<Float> = when (kind) {
            ToolKind.PEN -> 1f..10f
            ToolKind.MARKER -> 2f..16f
            ToolKind.HIGHLIGHTER -> 8f..44f
            ToolKind.SHAPE -> 1f..12f
            ToolKind.TEXT -> 12f..120f
            ToolKind.LASER -> 2f..12f
        }

        fun palette(kind: ToolKind): List<Int> = when (kind) {
            ToolKind.PEN, ToolKind.MARKER, ToolKind.SHAPE, ToolKind.TEXT -> Palette.INK
            ToolKind.HIGHLIGHTER -> Palette.HIGHLIGHTER
            ToolKind.LASER -> Palette.LASER
        }
    }
}

object Palette {
    val INK = listOf(
        0xFF1A1A1A, 0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA,
        0xFF00897B, 0xFF6D4C41, 0xFF757575, 0xFFFFFFFF,
    ).map { it.toInt() }
    val HIGHLIGHTER = listOf(
        0xFFFFEB3B, 0xFF76FF03, 0xFF40C4FF, 0xFFFF4081, 0xFFFFAB40, 0xFFB388FF,
    ).map { it.toInt() }
    val LASER = listOf(
        0xFFFF1744, 0xFF00E676, 0xFF2979FF, 0xFFFFEA00, 0xFFD500F9, 0xFFFF9100,
    ).map { it.toInt() }
}
