package it.presenter.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.FormatColorReset
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LineStyle
import androidx.compose.material.icons.rounded.LineWeight
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import it.presenter.app.R
import it.presenter.app.canvas.OverlayView
import it.presenter.app.ink.Handwriting
import it.presenter.app.ink.InkStroke
import it.presenter.app.ink.LineDash
import it.presenter.app.ink.SelectionGeometry
import it.presenter.app.ink.Shape
import it.presenter.app.ink.withAlpha
import it.presenter.app.session.PresenterSession
import it.presenter.app.settings.Limit
import it.presenter.app.tools.Palette
import kotlin.math.roundToInt

/**
 * Floating bar of the selection (shapes and lasso), tablet only: above the selected box (below if there is
 * no room above). Color, width, shape style (dashes, fill, arrow), duplicate, copy to paste elsewhere,
 * delete.
 */
@Composable
fun SelectionBar(session: PresenterSession) {
    val sel = session.selection ?: return
    val ref = session.currentRef ?: return
    if (ref.doc.inkOf(ref.index) !== sel.ink || session.transition != null) return
    // Recomputes the position when the page moves or is zoomed.
    session.screenTick
    val density = LocalDensity.current
    val m = session.viewport.pageToView
    val k = m.mapRadius(1f).coerceAtLeast(1e-3f)
    val pad = sel.padDp * density.density / k
    val corners = SelectionGeometry.corners(sel.box(pad), sel.angle).also(m::mapPoints)
    val xs = corners.filterIndexed { i, _ -> i % 2 == 0 }
    val ys = corners.filterIndexed { i, _ -> i % 2 == 1 }
    val centerX = xs.average().toFloat()
    // Above the box there is also the rotation handle: the bar sits higher.
    val top = ys.min() - (OverlayView.ROTATE_OFFSET_DP + 20f) * density.density
    val bottom = ys.max() + 16f * density.density

    // Panel and color picker start over with a new selection (not with its edits).
    var panel by remember(sel.origin) { mutableStateOf(Panel.NONE) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.layout { measurable, constraints ->
            val p = measurable.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
            val x = (centerX - p.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
            val above = top - p.height
            val y = (if (above >= 0f) above else bottom).roundToInt().coerceIn(0, (constraints.maxHeight - p.height).coerceAtLeast(0))
            layout(constraints.maxWidth, constraints.maxHeight) { p.place(x, y) }
        },
    ) {
        // Width of the icon row: the dividers must not widen the bar to full screen.
        Column(Modifier.width(IntrinsicSize.Max).padding(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = sel.commonColor
                WithTooltip(stringResource(R.string.selection_color)) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .padding(8.dp)
                            .border(2.dp, if (panel == Panel.COLOR) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                            .padding(3.dp)
                            .background(color?.let { Color(it) } ?: Color.Gray, CircleShape)
                            .clickable { panel = if (panel == Panel.COLOR) Panel.NONE else Panel.COLOR },
                    )
                }
                if (sel.hasStrokeWidth) {
                    PanelToggle(Icons.Rounded.LineWeight, stringResource(R.string.selection_width), panel == Panel.WIDTH) {
                        panel = if (panel == Panel.WIDTH) Panel.NONE else Panel.WIDTH
                    }
                }
                if (sel.shapes.isNotEmpty()) {
                    PanelToggle(Icons.Rounded.LineStyle, stringResource(R.string.selection_style), panel == Panel.STYLE) {
                        panel = if (panel == Panel.STYLE) Panel.NONE else Panel.STYLE
                    }
                }
                VerticalDivider(Modifier.size(width = 1.dp, height = 28.dp).padding(horizontal = 0.dp))
                // Freehand strokes only: they can become text, in the language chosen here. Where the distribution
                // has no recognition the icon stays, grey, and a tap explains why.
                if (sel.elements.all { it is InkStroke } && !Handwriting.AVAILABLE) {
                    val limit = Limit.FDROID_HANDWRITING
                    ActionIcon(Icons.Rounded.TextFields, stringResource(limit.tooltip!!), unavailable = true) { session.limitNotice = limit }
                } else if (sel.elements.all { it is InkStroke }) {
                    if (session.handwritingBusy) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.5.dp)
                    } else {
                        ActionIcon(Icons.Rounded.TextFields, stringResource(R.string.handwriting_convert)) { session.convertSelectionToText() }
                    }
                    val next = Handwriting.LANGUAGES.let { l -> l[(l.indexOf(session.handwritingLanguage) + 1) % l.size] }
                    WithTooltip(stringResource(R.string.handwriting_language)) {
                        TextButton(onClick = { session.updateHandwritingLanguage(next) }) {
                            Text(session.handwritingLanguage.uppercase())
                        }
                    }
                }
                ActionIcon(Icons.Rounded.LibraryAdd, stringResource(R.string.selection_duplicate)) { session.duplicateSelection() }
                ActionIcon(Icons.Rounded.ContentCopy, stringResource(R.string.selection_copy)) { session.copySelection() }
                ActionIcon(Icons.Rounded.DeleteOutline, stringResource(R.string.selection_delete)) { session.deleteSelection() }
            }
            when (panel) {
                Panel.NONE -> Unit
                Panel.COLOR -> {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    key(sel.origin) {
                        ColorChoices(session, selected = sel.commonColor, highlight = sel.elements.all { it.isHighlight }) { session.recolorSelection(it) }
                    }
                }
                Panel.WIDTH -> {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Slider(
                        value = (sel.commonWidth ?: 2f).coerceIn(WIDTH_RANGE),
                        onValueChange = { w -> session.editSelectionLive { it.withStrokeWidth(w) } },
                        onValueChangeFinished = { session.endLiveSelectionEdit() },
                        valueRange = WIDTH_RANGE,
                        modifier = Modifier.width(280.dp).padding(horizontal = 8.dp),
                    )
                }
                Panel.STYLE -> {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    ShapeStyle(session)
                }
            }
        }
    }
}

private enum class Panel { NONE, COLOR, WIDTH, STYLE }

private val WIDTH_RANGE = 0.5f..20f

@Composable
private fun PanelToggle(icon: ImageVector, label: String, checked: Boolean, onClick: () -> Unit) {
    WithTooltip(label) {
        FilledTonalIconToggleButton(checked = checked, onCheckedChange = { onClick() }) { Icon(icon, contentDescription = label) }
    }
}

/** Palette (the highlighter's if the selection is made only of highlights), recent colors and free picker. */
@Composable
private fun ColorChoices(session: PresenterSession, selected: Int?, highlight: Boolean, onPick: (Int) -> Unit) {
    val tools = session.tools
    val palette = if (highlight) Palette.HIGHLIGHTER else Palette.INK
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(300.dp)) {
        for (row in palette.chunked(6)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in row) ColorSwatch(Color(c), selected = selected == c) { onPick(c) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in tools.recentColors.filter { it !in palette }) ColorSwatch(Color(c), selected = selected == c) { onPick(c) }
            CustomColorSwatch(selected = picking) { picking = !picking }
        }
        if (picking) {
            ColorPicker(
                selected ?: palette.first(),
                onChange = { c -> session.editSelectionLive { it.recolored(c) } },
                onChangeFinished = {
                    session.endLiveSelectionEdit()
                    tools.addRecentColor(it)
                },
            )
        }
    }
}

/** Dashes, arrow (open shapes) and fill (closed shapes) of the selected shapes. */
@Composable
private fun ShapeStyle(session: PresenterSession) {
    val sel = session.selection ?: return
    val shapes = sel.shapes
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(300.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((dash, icon, label) in listOf(
                Triple(LineDash.SOLID, ToolIcons.DashSolid, R.string.dash_solid),
                Triple(LineDash.DASHED, ToolIcons.DashDashed, R.string.dash_dashed),
                Triple(LineDash.DOTTED, ToolIcons.DashDotted, R.string.dash_dotted),
            )) {
                PanelToggle(icon, stringResource(label), shapes.all { it.dash == dash }) { session.styleSelection(dash = dash) }
            }
            if (shapes.any { !it.closed }) {
                val arrow = shapes.filter { !it.closed }.all { it.arrow }
                PanelToggle(ToolIcons.Arrow, stringResource(R.string.shape_arrow), arrow) { session.styleSelection(arrow = !arrow) }
            }
        }
        val closed = shapes.filter { it.closed }
        if (closed.isNotEmpty()) {
            val fill = closed.map { it.fill }.distinct().singleOrNull()
            val alpha = fill?.takeIf { it != 0 }?.let { it ushr 24 } ?: DEFAULT_FILL_ALPHA
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FormatColorFill, contentDescription = stringResource(R.string.shape_fill), modifier = Modifier.size(20.dp))
                PanelToggle(Icons.Rounded.FormatColorReset, stringResource(R.string.shape_no_fill), fill == 0) { session.styleSelection(fill = 0) }
            }
            for (row in Palette.INK.chunked(6)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in row) {
                        val value = withAlpha(c, alpha)
                        ColorSwatch(Color(value), selected = fill == value) { session.styleSelection(fill = value) }
                    }
                }
            }
            if (fill != null && fill != 0) {
                Slider(
                    value = alpha / 255f,
                    onValueChange = { a ->
                        session.editSelectionLive { e ->
                            if (e is Shape && e.closed && e.fill != 0) e.copy(fill = withAlpha(e.fill, (a * 255).roundToInt())) else e
                        }
                    },
                    onValueChangeFinished = { session.endLiveSelectionEdit() },
                    valueRange = 0.1f..1f,
                )
            }
        }
    }
}

/** Opacity proposed for the fill: visible, but it does not cover the slide. */
const val DEFAULT_FILL_ALPHA = 0x59
