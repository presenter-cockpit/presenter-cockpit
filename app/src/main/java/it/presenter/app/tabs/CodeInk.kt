package it.presenter.app.tabs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.ink.strokes.InProgressStroke
import it.presenter.app.ink.PageInk

/**
 * Handwritten strokes over the editor's code: tied to the screen, they disappear as soon as you scroll
 * (or change file or font size). They are not saved and stay out of undo/redo.
 *
 * Coordinates: tablet pixels relative to the start of the text (first line, first column), i.e. in the
 * editor content and not on the screen. The projector scales them by the ratio between its line height
 * and [lineHeightPx], so they stay over the same lines.
 */
class CodeInk {
    /** Finished strokes; the eraser uses the same geometry as the pages (partial erasing too). */
    var ink = PageInk()
        private set

    /** Stroke in progress and the moment it started (for Ink's geometry). */
    var live: InProgressStroke? = null
    var liveDown = 0L

    /** Line height (tablet px) the strokes were written with. */
    var lineHeightPx by mutableFloatStateOf(1f)

    /**
     * Code font size (tablet px). Horizontally the scale towards the projector is that of the characters,
     * not of the lines: the line height is rounded to pixels on both sides.
     */
    var fontPx by mutableFloatStateOf(0f)

    /**
     * Editor viewport on the tablet: visible height and scroll (px). The projector derives the scale from
     * them and shows exactly the same lines, first to last.
     */
    var viewportHeightPx by mutableFloatStateOf(0f)
    var viewportWidthPx by mutableFloatStateOf(0f)
    var scrollPx by mutableIntStateOf(0)
    var hscrollPx by mutableIntStateOf(0)

    /**
     * Start of the text in the coordinates of [ref] (null if the editor is not visible): used by the laser,
     * which over code anchors to the text like the pen.
     */
    var originIn: ((ref: LayoutCoordinates) -> Offset?)? = null

    /** Grows at every change: tablet and projector redraw. */
    var version by mutableIntStateOf(0)

    fun changed() {
        version++
    }

    fun clear() {
        if (ink.isEmpty && live == null) return
        ink = PageInk()
        live = null
        changed()
    }
}
