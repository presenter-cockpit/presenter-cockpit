package it.presenter.app.doc

import it.presenter.app.ink.PageElement
import java.util.TreeMap



/** A page of the document after a structure edit ([PageEdits.slots]). */
sealed interface PageSlot {
    /** Page [index] of the starting document. */
    data class Page(val index: Int) : PageSlot

    /**
     * New blank page: as large as page [like] of the starting document or, with [like] = -1, [width] x
     * [height] points (already measured: this is how it is saved in the session). [label]: page label, null
     * if the PDF has no labels.
     */
    data class Blank(val like: Int, val label: String?, val width: Float = 0f, val height: Float = 0f) : PageSlot
}

/** Structure edit of the PDF made from the overview, on pages of the current document. */
sealed interface PageEdit {
    /** Moves pages [pages] (a slide) in front of page [before] ([before] = page count: at the end). */
    data class Move(val pages: IntRange, val before: Int) : PageEdit

    /** Blank page right after page [after], with the same format. */
    data class InsertBlank(val after: Int) : PageEdit

    /** Removes pages [pages] (a slide). */
    data class Delete(val pages: IntRange) : PageEdit
}

/**
 * What follows the pages of a document in a structure edit: [labels], [overlays], [ink] per slide,
 * presenter notes ([pdfpc]) and order relative to the file on disk ([layout], [diskPages]).
 */
class PageStructure(
    val pageCount: Int,
    val labels: List<String>?,
    val overlays: Set<Int>,
    val ink: Map<Int, List<PageElement>>,
    val pdfpc: PdfpcNotes?,
    val layout: List<PageSlot>?,
    val diskPages: Int,
) {
    val slides = Slides(labels, pageCount) { it in overlays }

    /**
     * The same after the new order [slots]; [resolved]: [slots] with the blank pages measured, and
     * [confirmed]: the [PageEdits.overlayCandidates] that the text confirms, as [PdfInkStore.rearrange]
     * returns them.
     */
    fun edited(slots: List<PageSlot>, resolved: List<PageSlot>, confirmed: Set<Int>): PageStructure {
        val newLabels = PageEdits.labels(slots, labels)
        val newOverlays = PageEdits.overlays(slots, newLabels, overlays) { it in confirmed }
        val newSlides = Slides(newLabels, slots.size) { it in newOverlays }
        return PageStructure(
            slots.size,
            newLabels,
            newOverlays,
            PageEdits.remapInk(ink, slots, pageCount, slides::pagesOf, newSlides::firstPageOf),
            pdfpc?.remapped(slots, pageCount),
            PageEdits.compose(layout, resolved, diskPages),
            diskPages,
        )
    }
}

/**
 * Logic of structure edits: from the operation to the new page order, and from that labels, overlays,
 * ink, notes and current page in the new numbering.
 */
object PageEdits {
    fun slots(edit: PageEdit, pageCount: Int, labels: List<String>?): List<PageSlot> = when (edit) {
        is PageEdit.Move -> {
            val rest = (0 until pageCount).filter { it !in edit.pages }
            val at = rest.count { it < edit.before }
            (rest.take(at) + edit.pages + rest.drop(at)).map { PageSlot.Page(it) }
        }
        is PageEdit.InsertBlank -> {
            val label = labels?.let { blankLabel(it.getOrElse(edit.after) { "" }, it.toSet()) }
            (0..edit.after).map { PageSlot.Page(it) } + PageSlot.Blank(edit.after, label) +
                (edit.after + 1 until pageCount).map { PageSlot.Page(it) }
        }
        is PageEdit.Delete -> (0 until pageCount).filter { it !in edit.pages }.map { PageSlot.Page(it) }
    }

    /**
     * Label of a blank page after the one labelled [base]: "5.1", then "5.2"… (the first free one). Unique,
     * so neither Presenter nor PDFPC takes it for an overlay of the slide before.
     */
    fun blankLabel(base: String, used: Set<String>): String =
        generateSequence(1) { it + 1 }.map { "$base.$it" }.first { it !in used }

    /** New index of each starting page, -1 if removed. */
    fun newIndexOf(slots: List<PageSlot>, pageCount: Int): IntArray {
        val out = IntArray(pageCount) { -1 }
        slots.forEachIndexed { j, s -> if (s is PageSlot.Page && s.index in 0 until pageCount) out[s.index] = j }
        return out
    }

    /**
     * Where page [page] ends up: its new index; if it was removed, the first remaining page after it (or, at
     * the end, the one before).
     */
    fun follow(page: Int, newIndex: IntArray): Int {
        if (newIndex.isEmpty()) return 0
        val p = page.coerceIn(0, newIndex.size - 1)
        newIndex[p].takeIf { it >= 0 }?.let { return it }
        for (i in p + 1 until newIndex.size) if (newIndex[i] >= 0) return newIndex[i]
        // At the end: after the last remaining page before it (the same place, one less).
        for (i in p - 1 downTo 0) if (newIndex[i] >= 0) return newIndex[i]
        return 0
    }

    fun labels(slots: List<PageSlot>, labels: List<String>?): List<String>? = labels?.let { old ->
        slots.map { s -> if (s is PageSlot.Page) old.getOrElse(s.index) { "" } else (s as PageSlot.Blank).label.orEmpty() }
    }

    /**
     * Overlays in the order [slots] ([labels]: the new ones), as reading the file would find them (same
     * label as the page before and text that continues it, [PdfInkStore.inspect]): a page still after the
     * page it followed keeps its state ([overlays]); a page with the same label as a new page before it is
     * checked on the text ([confirm], only for [overlayCandidates]). So two overlays separated and then put
     * back side by side are one slide again, also after a save.
     */
    fun overlays(slots: List<PageSlot>, labels: List<String>?, overlays: Set<Int>, confirm: (Int) -> Boolean): Set<Int> {
        if (labels == null) return emptySet()
        return (1 until slots.size).filterTo(HashSet()) { j ->
            val a = slots[j - 1]
            val b = slots[j]
            when {
                // Blank pages have labels of their own (never the same as the page before).
                a !is PageSlot.Page || b !is PageSlot.Page || labels[j] != labels[j - 1] -> false
                b.index == a.index + 1 -> b.index in overlays
                else -> confirm(j)
            }
        }
    }

    /** Pages of [slots] whose overlay state must be checked on the text (see [overlays]). */
    fun overlayCandidates(slots: List<PageSlot>, labels: List<String>?): Set<Int> {
        if (labels == null) return emptySet()
        return (1 until slots.size).filterTo(HashSet()) { j ->
            val a = slots[j - 1]
            val b = slots[j]
            a is PageSlot.Page && b is PageSlot.Page && labels[j] == labels[j - 1] && b.index != a.index + 1
        }
    }

    /** Per-page values ([map], starting indices) in the new numbering; those of removed pages disappear. */
    fun <T> remap(map: Map<Int, T>, slots: List<PageSlot>, pageCount: Int): Map<Int, T> {
        val newIndex = newIndexOf(slots, pageCount)
        return map.entries.mapNotNull { (k, v) -> newIndex.getOrNull(k)?.takeIf { it >= 0 }?.let { it to v } }.toMap()
    }

    /**
     * Ink per slide ([map]: key = a page of the slide, [pagesOf]) in the new numbering, on the first page of
     * the new slides ([firstOf]). If the first page was removed (with overlays off pages are removed one at
     * a time), the ink moves to the first remaining page of the same slide; it disappears only with the
     * whole slide. Ink of slides that become one (overlays put back together) is merged, in page order.
     */
    fun <T> remapInk(
        map: Map<Int, List<T>>,
        slots: List<PageSlot>,
        pageCount: Int,
        pagesOf: (Int) -> IntRange,
        firstOf: (Int) -> Int = { it },
    ): Map<Int, List<T>> {
        val newIndex = newIndexOf(slots, pageCount)
        val out = TreeMap<Int, MutableList<T>>()
        val moved = map.entries.mapNotNull { (k, v) ->
            val page = pagesOf(k).firstOrNull { newIndex.getOrNull(it)?.let { n -> n >= 0 } == true } ?: return@mapNotNull null
            newIndex[page] to v
        }.sortedBy { it.first }
        for ((page, v) in moved) out.getOrPut(firstOf(page)) { ArrayList() } += v
        return out
    }

    /**
     * Order relative to the file on disk after applying [slots] to a document that already had order
     * [layout] (null: the file's). The blank pages of [slots] must already be measured ([PageSlot.Blank.like]
     * = -1). Null if the result is exactly the file on disk ([diskPages] pages).
     */
    fun compose(layout: List<PageSlot>?, slots: List<PageSlot>, diskPages: Int): List<PageSlot>? {
        val out = slots.map { s -> if (s is PageSlot.Page) layout?.getOrNull(s.index) ?: s else s }
        val identity = out.size == diskPages && out.withIndex().all { (i, s) -> s is PageSlot.Page && s.index == i }
        return out.takeUnless { identity }
    }

    /** Session order valid for a file of [pageCount] pages (the file may have changed meanwhile). */
    fun isValid(layout: List<PageSlot>, pageCount: Int): Boolean {
        val pages = layout.filterIsInstance<PageSlot.Page>().map { it.index }
        return pages.all { it in 0 until pageCount } && pages.toSet().size == pages.size &&
            layout.all { it !is PageSlot.Blank || (it.like == -1 && it.width > 0f && it.height > 0f) }
    }
}
