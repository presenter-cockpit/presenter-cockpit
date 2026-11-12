package it.presenter.app.ink

import androidx.ink.brush.Brush
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Elements of a page ([PageElement], in page coordinates: strokes, shapes, texts, highlights), with
 * undo/redo and eraser.
 *
 * Every change keeps the list before and the list after (elements are immutable): adding, erasing,
 * moving or recoloring a selection, editing a shape or a text are all undone in one go. "Live" changes
 * (eraser, dragging a handle, typing text) happen between [beginChange] and [endChange]: during the
 * gesture the list changes without entering the history, at the end it becomes a single change.
 *
 * The eraser: [beginChange], many [eraseAlong], [endChange]. During the gesture the erased parts stay
 * visible, very faint, in [ghosts]; lifting the pen they disappear.
 */
class PageInk {
    private val _elements = ArrayList<PageElement>()
    val elements: List<PageElement> get() = _elements

    val isEmpty get() = _elements.isEmpty()

    /**
     * Parts just erased, while the eraser gesture is in progress. They have the full color: whoever draws them
     * puts them all in a single transparent layer ([GHOST_ALPHA]), so where pieces erased in successive steps
     * overlap no darker spots appear.
     */
    private val _ghosts = ArrayList<PageElement>()
    val ghosts: List<PageElement> get() = _ghosts

    /**
     * Stroke of the vanishing pen: solid until [fadeStart] (`uptimeMillis` time), then it fades in
     * [FADE_OUT_MS] and disappears. It stays out of undo/redo, session and PDF.
     */
    class FadingStroke(val stroke: Stroke, val fadeStart: Long)

    private val _fading = ArrayList<FadingStroke>()
    val fading: List<FadingStroke> get() = _fading

    fun addFading(strokes: Collection<Stroke>, fadeStart: Long) {
        for (s in strokes) _fading.add(FadingStroke(s, fadeStart))
    }

    /** Removes the vanishing strokes that are no longer visible; true if it removed any. */
    fun pruneFading(now: Long): Boolean = _fading.removeAll { now >= it.fadeStart + FADE_OUT_MS }

    enum class EraseMode {
        /** Erases only the part of a stroke touched by the eraser. */
        PARTIAL,

        /** Erases the whole strokes touched by the eraser. */
        STROKE,
    }

    /** List before and after a change: undoing puts the first back. */
    private class Edit(var before: List<PageElement>, var after: List<PageElement>)

    private val undoStack = ArrayList<Edit>()
    private val redoStack = ArrayList<Edit>()

    /** List as it was at the start of the live change in progress, or null. */
    private var beforeChange: List<PageElement>? = null

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /** Elements as they are in the file (read on opening or just saved): see [changedSinceSave]. */
    private var saved: List<PageElement> = emptyList()

    /**
     * The page differs from the file: compared element by element (they are immutable, and undo puts back the
     * same instances), so undoing every change since the last save makes the page unchanged again.
     */
    val changedSinceSave: Boolean
        get() = _elements.size != saved.size || _elements.indices.any { _elements[it] !== saved[it] }

    /** Elements as they are in the file. */
    val savedElements: List<PageElement> get() = saved

    /** The file now holds [elements] for this page. */
    fun markSaved(elements: List<PageElement>) {
        saved = elements.toList()
    }

    /** Elements read from the PDF or the session on opening: not a change, not undoable. */
    fun restore(elements: Collection<PageElement>) {
        _elements.addAll(elements)
        saved = _elements.toList()
    }

    fun add(elements: Collection<PageElement>) {
        if (elements.isEmpty()) return
        commit(_elements + elements)
    }

    /** Removes [elements] (a selection). */
    fun remove(elements: Collection<PageElement>) {
        val gone = elements.toSet()
        if (_elements.none { it in gone }) return
        commit(_elements.filter { it !in gone })
    }

    /** Replaces each element of [old] with the matching one of [new], at the same place in the drawing order. */
    fun replace(old: List<PageElement>, new: List<PageElement>) {
        commit(substituted(_elements, old, new) ?: return)
    }

    /** New complete list as a single undoable change. */
    fun commit(after: List<PageElement>) {
        // A live change left open becomes its own step, before this one.
        endChange()
        val before = ArrayList(_elements)
        if (before == after) return
        _elements.clear()
        _elements.addAll(after)
        push(Edit(before, ArrayList(after)))
    }

    fun clear() {
        _fading.clear()
        if (_elements.isEmpty()) return
        commit(emptyList())
    }

    /** Start of a live change (eraser, handle, text): the history sees it only at [endChange]. */
    fun beginChange() {
        if (beforeChange == null) beforeChange = ArrayList(_elements)
        _ghosts.clear()
    }

    /** During a live change: [old] becomes [new] at once, without going through the history. */
    fun replaceLive(old: List<PageElement>, new: List<PageElement>) {
        val list = substituted(_elements, old, new) ?: return
        _elements.clear()
        _elements.addAll(list)
    }

    /** During a live change: adds or removes elements without going through the history. */
    fun setLive(elements: List<PageElement>) {
        _elements.clear()
        _elements.addAll(elements)
    }

    /** End of the live change; true if the page changed (and the change entered the history). */
    fun endChange(): Boolean {
        val before = beforeChange ?: return false
        beforeChange = null
        _ghosts.clear()
        if (before == _elements) return false
        push(Edit(before, ArrayList(_elements)))
        return true
    }

    val isChanging get() = beforeChange != null

    /** Cancels the live change in progress: the page goes back to how it was at its start. */
    fun cancelChange() {
        val before = beforeChange ?: return
        _elements.clear()
        _elements.addAll(before)
        beforeChange = null
        _ghosts.clear()
    }

    /**
     * Replacement that is not a user change (a highlighter stroke that becomes a text-snapped highlight once
     * the page text is ready): [new] takes the place of [old] in the history too, so undoing removes the
     * result directly.
     */
    fun swap(old: PageElement, new: PageElement): Boolean {
        val i = _elements.indexOf(old)
        if (i < 0) return false
        _elements[i] = new
        for (e in undoStack + redoStack) {
            e.before = e.before.map { if (it === old) new else it }
            e.after = e.after.map { if (it === old) new else it }
        }
        beforeChange = beforeChange?.map { if (it === old) new else it }
        return true
    }

    /**
     * Eraser step along segment [x0],[y0] → [x1],[y1], [radius] wide. Returns true if something changed.
     * Shapes, texts and highlights are erased whole; freehand strokes according to [mode].
     */
    fun eraseAlong(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, mode: EraseMode): Boolean {
        val reach = Box(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1)).inflate(radius)
        var changed = false
        var i = _elements.size - 1
        while (i >= 0) {
            val element = _elements[i]
            if (element.bounds().inflate(element.strokeWidth ?: 0f).intersects(reach)) {
                val replacement = when {
                    element is InkStroke && mode == EraseMode.PARTIAL -> splitStroke(element.stroke, x0, y0, x1, y1, radius)?.map(::InkStroke)
                    element.touches(x0, y0, x1, y1, radius) -> {
                        _ghosts.add(element)
                        emptyList()
                    }
                    else -> null
                }
                if (replacement != null) {
                    _elements.removeAt(i)
                    // The remaining pieces take the place of the original stroke (same drawing order).
                    _elements.addAll(i, replacement)
                    changed = true
                }
            }
            i--
        }
        return changed
    }

    /**
     * Removes [e] if it was just added (the last step of the history is exactly its addition): the step
     * disappears, as if it had never been. Otherwise it removes it with a new step.
     */
    fun retract(e: PageElement) {
        val last = undoStack.lastOrNull()
        if (beforeChange == null && last != null && last.after == _elements && e in last.after && e !in last.before && last.after.size == last.before.size + 1) {
            undoStack.removeAt(undoStack.size - 1)
            _elements.clear()
            _elements.addAll(last.before)
        } else {
            remove(listOf(e))
        }
    }

    fun undo(): Boolean {
        endChange()
        val edit = undoStack.removeLastOrNull() ?: return false
        _elements.clear()
        _elements.addAll(edit.before)
        redoStack.add(edit)
        return true
    }

    fun redo(): Boolean {
        endChange()
        val edit = redoStack.removeLastOrNull() ?: return false
        _elements.clear()
        _elements.addAll(edit.after)
        undoStack.add(edit)
        return true
    }

    private fun push(edit: Edit) {
        undoStack.add(edit)
        redoStack.clear()
    }

    /** [list] with each element of [old] replaced by the matching one of [new]; null if nothing changes. */
    private fun substituted(list: List<PageElement>, old: List<PageElement>, new: List<PageElement>): List<PageElement>? {
        if (old.isEmpty()) return null
        val map = HashMap<PageElement, PageElement>()
        for ((k, o) in old.withIndex()) new.getOrNull(k)?.let { map[o] = it }
        var changed = false
        val out = list.map { e -> map[e]?.also { changed = true } ?: e }
        return if (changed) out else null
    }

    // ------------------------------------------------------------------ partial eraser

    private var xs = FloatArray(256)
    private var ys = FloatArray(256)
    private val inputA = StrokeInput()
    private val inputB = StrokeInput()
    private val inputOut = StrokeInput()

    /**
     * Removes from [stroke] the part under the eraser. Returns the remaining pieces (possibly none), or null if
     * the eraser does not touch it. The removed parts go into [ghosts].
     */
    private fun splitStroke(stroke: Stroke, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float): List<Stroke>? {
        val inputs = stroke.inputs
        val n = inputs.size
        if (n == 0) return null
        if (xs.size < n) {
            xs = FloatArray(max(n, xs.size * 2))
            ys = FloatArray(xs.size)
        }
        for (k in 0 until n) {
            inputs.populate(k, inputA)
            xs[k] = inputA.x
            ys[k] = inputA.y
        }
        // The eraser touches the stroke when it reaches the edge of the ink, not only its center line.
        val reach = radius + stroke.brush.size / 2f
        val runs = EraseGeometry.split(xs, ys, n, x0, y0, x1, y1, reach, step = max(0.5f, radius * 0.3f)) ?: return null

        val kept = ArrayList<Stroke>()
        for ((r, run) in runs.withIndex()) {
            if (!run.erased) {
                if (run.samples.size >= 2) buildStroke(stroke, stroke.brush, run.samples)?.let(kept::add)
            } else {
                // The ghost includes the boundary points, so it joins the remaining pieces.
                val samples = ArrayList<EraseGeometry.Sample>(run.samples.size + 2)
                runs.getOrNull(r - 1)?.let { samples.add(it.samples.last()) }
                samples.addAll(run.samples)
                runs.getOrNull(r + 1)?.let { samples.add(it.samples.first()) }
                buildStroke(stroke, stroke.brush, samples)?.let { _ghosts.add(InkStroke(it)) }
            }
        }
        return kept
    }

    private fun buildStroke(original: Stroke, brush: Brush, samples: List<EraseGeometry.Sample>): Stroke? {
        val inputs = original.inputs
        val batch = MutableStrokeInputBatch()
        for (s in samples) {
            inputs.populate(s.segment, inputA)
            if (s.t == 0f || s.segment + 1 >= inputs.size) {
                runCatching { batch.add(inputA) }
                continue
            }
            inputs.populate(s.segment + 1, inputB)
            val t = s.t
            inputOut.update(
                x = inputA.x + (inputB.x - inputA.x) * t,
                y = inputA.y + (inputB.y - inputA.y) * t,
                elapsedTimeMillis = inputA.elapsedTimeMillis + ((inputB.elapsedTimeMillis - inputA.elapsedTimeMillis) * t).roundToLong(),
                toolType = inputA.toolType,
                strokeUnitLengthCm = inputA.strokeUnitLengthCm,
                pressure = if (inputA.hasPressure && inputB.hasPressure) inputA.pressure + (inputB.pressure - inputA.pressure) * t else StrokeInput.NO_PRESSURE,
                tiltRadians = if (inputA.hasTilt && inputB.hasTilt) inputA.tiltRadians + (inputB.tiltRadians - inputA.tiltRadians) * t else StrokeInput.NO_TILT,
                orientationRadians = if (inputA.hasOrientation) inputA.orientationRadians else StrokeInput.NO_ORIENTATION,
            )
            // Duplicate or out-of-order points (rounding) are dropped: Ink would reject them.
            runCatching { batch.add(inputOut) }
        }
        if (batch.size == 0) return null
        return runCatching { Stroke(brush, batch) }.getOrNull()
    }

    companion object {
        /** Opacity (0–255) of the layer with the parts just erased. */
        const val GHOST_ALPHA = 46

        /** Duration of the final fade of the vanishing strokes. */
        const val FADE_OUT_MS = 600L

        /** Opacity (0–1) of a vanishing stroke at time [now]. */
        fun fadingAlpha(f: FadingStroke, now: Long): Float =
            (1f - (now - f.fadeStart).toFloat() / FADE_OUT_MS).coerceIn(0f, 1f)
    }
}
