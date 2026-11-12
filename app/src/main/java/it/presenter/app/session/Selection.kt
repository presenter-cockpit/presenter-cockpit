package it.presenter.app.session

import it.presenter.app.doc.PresenterDocument
import it.presenter.app.ink.InkStroke
import it.presenter.app.ink.PageElement
import it.presenter.app.ink.PageInk
import it.presenter.app.ink.SelectionGeometry
import it.presenter.app.ink.Shape
import it.presenter.app.ink.TextBox
import it.presenter.app.ink.TextHighlight

/**
 * Elements selected on a page (by the lasso, or the shape just drawn or touched), tablet only. Usually
 * it starts from the **box** ([boxMode]: resize and rotate, oriented by [angle]); a shape just drawn
 * starts from its **vertices** instead, to drag one by one (user's choice). For a single shape a tap
 * (pen or finger) switches between the two modes. Other selections have only the box. Dragging inside
 * moves it in both cases.
 */
class Selection(
    val doc: PresenterDocument,
    val ink: PageInk,
    val elements: List<PageElement>,
    /** Orientation of the box: at first that of the elements (rotating snaps to their horizontal and vertical). */
    val angle: Float = SelectionGeometry.naturalAngle(elements),
    val boxMode: Boolean = true,
    /** Identity of the selection: stays the same across its edits ([with]), changes with a new selection. */
    val origin: Any = Any(),
) {
    fun with(elements: List<PageElement> = this.elements, angle: Float = this.angle, boxMode: Boolean = this.boxMode) =
        Selection(doc, ink, elements, angle, boxMode, origin)

    /**
     * The selected shape, if it is the only element (it has vertices to drag; for an ellipse the corners of its
     * parallelogram).
     */
    val shape: Shape? get() = elements.singleOrNull() as? Shape

    /** The shape's vertices are shown (otherwise the box with its handles). */
    val showsVertices: Boolean get() = shape != null && !boxMode

    /** Margin (dp) between the elements and the box. */
    val padDp: Float get() = PAD_DP

    /** Box in the frame rotated by [angle], enlarged by [pad] (see [SelectionGeometry.localBox]). */
    fun box(pad: Float): FloatArray = SelectionGeometry.localBox(elements, angle, pad)

    val hasStrokeWidth get() = elements.any { it.strokeWidth != null }
    val shapes get() = elements.filterIsInstance<Shape>()

    /** Common color of the selected elements, or null if they differ. */
    val commonColor: Int?
        get() = elements.map { it.color and 0x00FFFFFF }.distinct().singleOrNull()?.let { it or 0xFF000000.toInt() }

    val commonWidth: Float?
        get() = elements.mapNotNull { it.strokeWidth }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    companion object {
        const val PAD_DP = 10f
    }
}

/** Distinct copy of an element (elements are only equal to themselves). */
fun PageElement.duplicate(): PageElement = when (this) {
    is InkStroke -> InkStroke(stroke)
    is Shape -> copy()
    is TextBox -> copy()
    is TextHighlight -> TextHighlight(quads.copyOf(), color)
}

/**
 * Text box being edited on the tablet: [box] is the current one in the page. [original]: the box as it
 * was before editing (null if new).
 */
class TextEdit(
    val doc: PresenterDocument,
    val ink: PageInk,
    val box: TextBox,
    val original: TextBox? = null,
    /** Cursor when opening (position in the text, -1 = at the end): where it was touched. */
    val cursor: Int = -1,
    /** Different at every opening: the text field is reset even when moving to another box on the same page. */
    val serial: Long = nextSerial++,
) {
    fun with(box: TextBox) = TextEdit(doc, ink, box, original, cursor, serial)

    private companion object {
        var nextSerial = 0L
    }
}
