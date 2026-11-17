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
 * Handwritten strokes over the code of the editor or over a terminal: tied to the screen, they disappear as
 * soon as the text scrolls (or the file or the font size changes). They are not saved and stay out of
 * undo/redo.
 *
 * Coordinates: tablet pixels relative to the start of the text (editor: first line, first column, i.e. in
 * the editor content and not on the screen) or to the corner of the grid (terminal). The projector scales
 * them by the ratio between its line height and [lineHeightPx] (editor) or like the font (terminal), so they
 * stay over the same lines. The viewport fields are the editor's.
 */
class CodeInk {
    /** Finished strokes; the eraser uses the same geometry as the pages (partial erasing too). */
    var ink = PageInk()
        private set

    /** Stroke in progress and the moment it started (for Ink's geometry). */
    var live: InProgressStroke? = null
    var liveDown = 0L

    /** Time last given to the stroke in progress (ms from [liveDown]); Ink refuses going back in time. */
    var liveElapsed = 0L

    /**
     * Time of the stroke in progress at [uptimeMs], never earlier than the last one used. The screens draw it at
     * the frame time, which can come after the time of the pen event that ends it: with that earlier time Ink
     * refused to finish the stroke, and the stroke was lost.
     */
    fun elapsed(uptimeMs: Long): Long {
        liveElapsed = maxOf(liveElapsed, uptimeMs - liveDown)
        return liveElapsed
    }

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
