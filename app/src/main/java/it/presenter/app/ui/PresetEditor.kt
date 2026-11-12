package it.presenter.app.ui

import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.ink.brush.Brush as InkBrush
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke as InkStroke
import androidx.ink.strokes.StrokeInput
import it.presenter.app.R
import it.presenter.app.ink.BrushFamilies
import it.presenter.app.ink.ElementPainter
import it.presenter.app.ink.LineDash
import it.presenter.app.ink.PageInk
import it.presenter.app.ink.Shape
import it.presenter.app.ink.ShapeType
import it.presenter.app.ink.TextBox
import it.presenter.app.ink.TextFont
import it.presenter.app.ink.TextLayouts
import it.presenter.app.ink.withAlpha
import it.presenter.app.session.PresenterSession
import it.presenter.app.settings.RailSide
import it.presenter.app.tools.LaserStyle
import it.presenter.app.tools.ToolEditorRequest
import it.presenter.app.tools.ToolKind
import it.presenter.app.tools.ToolPreset
import it.presenter.app.tools.ToolRef
import it.presenter.app.tools.ToolState
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Editor of a preset (or of the eraser), next to the side rail: kind, color, width and, for pen and laser,
 * pressure; for the laser also the style. Only icons and samples, no text.
 */
@Composable
fun ToolEditorPopup(session: PresenterSession, request: ToolEditorRequest, onDismiss: () -> Unit) {
    val tools = session.tools
    Popup(
        popupPositionProvider = remember(request.anchorY, session.settings.railSide) {
            RailAnchor(request.anchorY, session.settings.railSide)
        },
        onDismissRequest = onDismiss,
        // Not "focusable": taps outside do not close it by themselves, so a tap on the rail selects another tool
        // and the editor switches to it. It is closed by the veil over the work area and the Back key (in
        // MainActivity).
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.width(332.dp),
        ) {
            // Scrollable: with the color picker open the editor may exceed the screen height.
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (val tool = request.tool) {
                    is ToolRef.Preset -> {
                        val preset = tools.preset(tool) ?: return@Column onDismiss()
                        PresetEditorContent(tools, preset, onDismiss)
                    }
                    ToolRef.Eraser -> EraserEditorContent(tools)
                    // Lasso and spotlight have no options: the actions are in the selection bar.
                    ToolRef.Lasso, ToolRef.Spotlight -> return@Column onDismiss()
                }
            }
        }
    }
}

@Composable
private fun PresetEditorContent(tools: ToolState, preset: ToolPreset, onDismiss: () -> Unit) {
    fun update(p: ToolPreset) = tools.update(p)

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (kind in ToolKind.entries) {
            val label = stringResource(kindLabel(kind))
            WithTooltip(label) {
                FilledTonalIconToggleButton(
                    checked = preset.kind == kind,
                    onCheckedChange = { update(preset.withKind(kind)) },
                    modifier = Modifier.size(44.dp),
                ) { Icon(ToolIcons.forKind(kind), contentDescription = label) }
            }
        }
    }
    HorizontalDivider()
    val palette = ToolPreset.palette(preset.kind)
    // Free picker open; it closes when a palette or recent color is chosen.
    var picking by remember(preset.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in palette.chunked(6)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in row) ColorSwatch(Color(c), selected = !picking && preset.color == c) {
                    picking = false
                    update(preset.copy(color = c))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in tools.recentColors.filter { it !in palette }) {
                ColorSwatch(Color(c), selected = !picking && preset.color == c) {
                    picking = false
                    update(preset.copy(color = c))
                }
            }
            val custom = preset.color !in palette && preset.color !in tools.recentColors
            CustomColorSwatch(selected = picking || custom) { picking = !picking }
        }
        if (picking) {
            ColorPicker(
                preset.color,
                onChange = { update(preset.copy(color = it)) },
                onChangeFinished = { tools.addRecentColor(it) },
            )
        }
    }
    HorizontalDivider()
    SizePreview(preset)
    val range = ToolPreset.sizeRange(preset.kind)
    Slider(value = preset.size.coerceIn(range), onValueChange = { update(preset.copy(size = it)) }, valueRange = range)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (preset.supportsPressure) {
            WithTooltip(stringResource(R.string.tool_pressure_tooltip)) {
                FilledTonalIconToggleButton(
                    checked = preset.pressure,
                    onCheckedChange = { update(preset.copy(pressure = it)) },
                ) { Icon(ToolIcons.Pressure, contentDescription = stringResource(R.string.tool_pressure)) }
            }
        }
        if (preset.supportsFade) {
            WithTooltip(stringResource(R.string.tool_fade_tooltip)) {
                FilledTonalIconToggleButton(
                    checked = preset.fadeAfterMs > 0,
                    onCheckedChange = { on -> update(preset.copy(fadeAfterMs = if (on) ToolPreset.DEFAULT_FADE_MS else 0L)) },
                ) { Icon(Icons.Rounded.HourglassBottom, contentDescription = stringResource(R.string.tool_fade)) }
            }
        }
        if (preset.kind == ToolKind.MARKER) {
            for ((chisel, icon, label) in listOf(
                Triple(false, ToolIcons.TipRound, stringResource(R.string.tool_tip_round)),
                Triple(true, ToolIcons.TipChisel, stringResource(R.string.tool_tip_chisel)),
            )) {
                WithTooltip(label) {
                    FilledTonalIconToggleButton(
                        checked = preset.chisel == chisel,
                        onCheckedChange = { update(preset.copy(chisel = chisel)) },
                    ) { Icon(icon, contentDescription = label) }
                }
            }
        }
        if (preset.kind == ToolKind.HIGHLIGHTER) {
            WithTooltip(stringResource(R.string.highlighter_snap_tooltip)) {
                FilledTonalIconToggleButton(
                    checked = preset.snapToText,
                    onCheckedChange = { update(preset.copy(snapToText = it)) },
                ) { Icon(ToolIcons.SnapText, contentDescription = stringResource(R.string.highlighter_snap)) }
            }
        }
        if (preset.kind == ToolKind.SHAPE) {
            ToggleIcon(ToolIcons.Arrow, stringResource(R.string.shape_arrow), preset.arrow) { update(preset.copy(arrow = it)) }
            ToggleIcon(Icons.Rounded.FormatColorFill, stringResource(R.string.shape_fill), preset.fill != 0) { on ->
                update(preset.copy(fill = if (on) withAlpha(preset.color, DEFAULT_FILL_ALPHA) else 0))
            }
        }
        if (preset.kind == ToolKind.TEXT) {
            ToggleIcon(Icons.Rounded.FormatBold, stringResource(R.string.text_bold), preset.bold) { update(preset.copy(bold = it)) }
            ToggleIcon(Icons.Rounded.FormatItalic, stringResource(R.string.text_italic), preset.italic) { update(preset.copy(italic = it)) }
        }
        if (preset.kind == ToolKind.LASER) {
            for (style in LaserStyle.entries) {
                val label = stringResource(if (style == LaserStyle.COMET) R.string.laser_pointer else R.string.laser_trail)
                WithTooltip(label) {
                    FilledTonalIconToggleButton(
                        checked = preset.laserStyle == style,
                        onCheckedChange = { update(preset.copy(laserStyle = style)) },
                    ) { Icon(ToolIcons.forLaser(style), contentDescription = label) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val index = tools.presets.indexOfFirst { it.id == preset.id }
        SmallIconButton(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.action_move_up), enabled = index > 0) { tools.move(preset.id, -1) }
        SmallIconButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.action_move_down), enabled = index < tools.presets.size - 1) { tools.move(preset.id, 1) }
        SmallIconButton(Icons.Rounded.DeleteOutline, stringResource(R.string.tool_delete), enabled = tools.presets.size > 1) {
            tools.remove(preset.id)
            onDismiss()
        }
    }
    if (preset.kind == ToolKind.SHAPE) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((dash, icon, label) in listOf(
                Triple(LineDash.SOLID, ToolIcons.DashSolid, R.string.dash_solid),
                Triple(LineDash.DASHED, ToolIcons.DashDashed, R.string.dash_dashed),
                Triple(LineDash.DOTTED, ToolIcons.DashDotted, R.string.dash_dotted),
            )) {
                ToggleIcon(icon, stringResource(label), preset.dash == dash) { update(preset.copy(dash = dash)) }
            }
        }
    }
    if (preset.kind == ToolKind.TEXT) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((font, label) in listOf(
                TextFont.SANS to R.string.text_font_sans,
                TextFont.SERIF to R.string.text_font_serif,
                TextFont.MONO to R.string.text_font_mono,
                TextFont.HAND to R.string.text_font_hand,
            )) {
                val description = stringResource(label)
                WithTooltip(description) {
                    FilledTonalIconToggleButton(
                        checked = preset.font == font,
                        onCheckedChange = { update(preset.copy(font = font)) },
                        modifier = Modifier.size(52.dp).semantics { contentDescription = description },
                    ) {
                        // Font sample: it is content (like a color), not a label.
                        Text("Aa", fontFamily = FontFamily(TextLayouts.typeface(font, bold = false, italic = false)), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
    if (preset.supportsFade && preset.fadeAfterMs > 0) {
        // Vanishing pen: seconds before the strokes fade.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.HourglassBottom, contentDescription = null, modifier = Modifier.size(20.dp))
            Slider(
                value = preset.fadeAfterMs / 1000f,
                onValueChange = { update(preset.copy(fadeAfterMs = it.roundToLong() * 1000L)) },
                valueRange = ToolPreset.FADE_RANGE_MS.first / 1000f..ToolPreset.FADE_RANGE_MS.last / 1000f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(stringResource(R.string.settings_seconds, (preset.fadeAfterMs / 1000).toString()), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ToggleIcon(icon: ImageVector, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    WithTooltip(label) {
        FilledTonalIconToggleButton(checked = checked, onCheckedChange = onChange) { Icon(icon, contentDescription = label) }
    }
}

@Composable
private fun EraserEditorContent(tools: ToolState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((mode, icon, label) in listOf(
            Triple(PageInk.EraseMode.PARTIAL, ToolIcons.ErasePartial, stringResource(R.string.eraser_partial)),
            Triple(PageInk.EraseMode.STROKE, ToolIcons.EraseStroke, stringResource(R.string.eraser_stroke)),
        )) {
            WithTooltip(label) {
                FilledTonalIconToggleButton(
                    checked = tools.eraseMode == mode,
                    onCheckedChange = { tools.updateEraseMode(mode) },
                    modifier = Modifier.size(52.dp),
                ) { Icon(icon, contentDescription = label) }
            }
        }
    }
    HorizontalDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolState.ERASER_SIZES.forEachIndexed { i, s ->
            val selected = tools.eraserSize == s
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                    .clickable { tools.updateEraserSize(s) },
            ) {
                Box(Modifier.size((12 + 12 * i).dp).border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape))
            }
        }
    }
}

@Composable
internal fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .border(3.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
            .padding(5.dp)
            .background(color, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            .clickable(onClick = onClick),
    )
}

/** Opens the free color picker: a disc with all the hues. */
@Composable
internal fun CustomColorSwatch(selected: Boolean, onClick: () -> Unit) {
    val hues = (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 0.85f, 1f))) }
    val description = stringResource(R.string.color_custom)
    Box(
        modifier = Modifier
            .size(40.dp)
            .border(3.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
            .padding(5.dp)
            .background(Brush.sweepGradient(hues), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
    )
}

/**
 * Preview of the stroke at the real size it will have on the tablet with the whole page (~2.7 px per
 * unit). Pen, marker and highlighter are drawn by Ink with their real brush (rising and falling pressure,
 * soft edge, chisel tip).
 */
@Composable
private fun SizePreview(preset: ToolPreset) {
    val pxPerUnit = 2.7f
    val color = Color(preset.color)
    val renderer = remember { CanvasStrokeRenderer.create() }
    val painter = remember { ElementPainter() }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color.White, RoundedCornerShape(12.dp)),
    ) {
        val w = preset.size * pxPerUnit
        val y = size.height / 2f
        when (preset.kind) {
            ToolKind.LASER -> {
                val c = Offset(size.width / 2f, y)
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.6f), Color.Transparent), c, w * 4.2f), w * 4.2f, c)
                drawCircle(color, w * 0.95f, c)
                drawCircle(Color.White.copy(alpha = 0.8f), w * 0.5f, c)
            }
            ToolKind.TEXT -> drawIntoCanvas { c ->
                val box = TextBox("Aa Bb", 0f, 0f, 1000f, 0f, preset.size, preset.color, preset.font, preset.bold, preset.italic)
                val paint = TextLayouts.paint(box).apply { textSize = preset.size * pxPerUnit }
                val fm = paint.fontMetrics
                c.nativeCanvas.drawText(box.text, 16.dp.toPx(), y - (fm.ascent + fm.descent) / 2f, paint)
            }
            ToolKind.SHAPE -> drawIntoCanvas { c ->
                val h = size.height / pxPerUnit
                val w = size.width / pxPerUnit
                val closed = if (preset.fill != 0) preset.fill else 0
                val shapes = listOf(
                    Shape(ShapeType.POLYLINE, floatArrayOf(10f, h * 0.75f, w * 0.25f, h * 0.25f, w * 0.5f - 12f, h * 0.7f), preset.color, preset.size, 0, preset.dash, preset.arrow),
                    Shape(ShapeType.POLYGON, floatArrayOf(w * 0.58f, h * 0.22f, w - 12f, h * 0.22f, w - 12f, h * 0.78f, w * 0.58f, h * 0.78f), preset.color, preset.size, closed, preset.dash),
                )
                val matrix = Matrix().apply { setScale(pxPerUnit, pxPerUnit) }
                val canvas = c.nativeCanvas
                canvas.save()
                canvas.concat(matrix)
                painter.draw(canvas, shapes, matrix)
                canvas.restore()
            }
            else -> {
                val brush = BrushFamilies.brushFor(preset) ?: return@Canvas
                val stroke = previewStroke(brush, size.width / pxPerUnit, size.height / pxPerUnit)
                val matrix = Matrix().apply { setScale(pxPerUnit, pxPerUnit) }
                drawIntoCanvas { c ->
                    val canvas = c.nativeCanvas
                    canvas.save()
                    // Ink's renderer does not apply the matrix to the canvas (it uses it only for antialiasing).
                    canvas.concat(matrix)
                    renderer.draw(canvas, stroke, matrix)
                    canvas.restore()
                }
            }
        }
    }
}

/** S-curve [w] wide and [h] high (page units), with the pressure rising and then falling. */
private fun previewStroke(brush: InkBrush, w: Float, h: Float): InkStroke {
    val batch = MutableStrokeInputBatch()
    val input = StrokeInput()
    val n = 48
    for (i in 0..n) {
        val t = i.toFloat() / n
        val x = 10f + (w - 20f) * t
        val y = h / 2f + sin(t * 2f * PI.toFloat()) * h * 0.22f
        input.update(
            x = x, y = y, elapsedTimeMillis = (t * 400f).toLong(), toolType = InputToolType.STYLUS,
            pressure = 0.15f + 0.85f * sin(t * PI.toFloat()),
        )
        batch.add(input)
    }
    return InkStroke(brush, batch)
}

/**
 * Popup next to the side rail, towards the center of the screen (right of the rail if it is on the left,
 * and vice versa), aligned with the touched button and always inside the screen.
 */
private class RailAnchor(private val anchorY: Float, private val side: RailSide) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = if (side == RailSide.LEFT) anchorBounds.right + 12 else anchorBounds.left - popupContentSize.width - 12
        val y = anchorY.toInt().coerceIn(8, (windowSize.height - popupContentSize.height - 8).coerceAtLeast(8))
        return IntOffset(x, y)
    }
}
