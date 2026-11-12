package it.presenter.app.doc

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import it.presenter.app.ink.PAGE_WIDTH
import it.presenter.app.ink.PageElement
import it.presenter.app.ink.PageInk
import it.presenter.app.pdf.PdfPages
import java.io.File

/**
 * An open PDF: pages, ink per page, presenter notes and save state.
 *
 * The renderer always reads a private copy ([cacheFile], without Presenter's saved elements): so the
 * original file can be overwritten at any time.
 */
class PresenterDocument private constructor(
    val source: DocSource,
    private val cacheFile: File,
    val pages: PdfPages,
    restored: Map<Int, List<PageElement>>,
    /** The .pdfpc file read (even without notes: it is rewritten if the pages change), or null. */
    pdfpc: PdfpcNotes?,
    /** Page labels from the PDF, or null. */
    val labels: List<String>?,
    /** Pages that are overlays of the previous one (same label and text that continues). */
    private val overlays: Set<Int>,
    /** Pages of the file on disk (with [layout] the document may have a different number). */
    private var diskPages: Int,
    /**
     * Page order relative to the file on disk, if changed from the overview and not saved yet (pages moved,
     * removed, blank pages added); null: the file's order.
     */
    layout: List<PageSlot>?,
) {
    /** The .pdfpc as it is now (slides hidden from the overview change it). */
    private var pdfpc by mutableStateOf(pdfpc)

    /** Presenter notes, if the .pdfpc has any. */
    val notes: PdfpcNotes? get() = pdfpc?.takeUnless { it.isEmpty }

    @Volatile
    var layout: List<PageSlot>? = layout
        private set

    /** Pages grouped into slides (Beamer overlays, without the section start pages). */
    val slides = Slides(labels, pages.pageCount) { it in overlays }

    /** Pages of the slides hidden in the .pdfpc: going forward and back skips them, as in PDFPC. */
    private val hiddenPages: Set<Int> get() = pdfpc?.hiddenPages.orEmpty()

    /** Hides or shows the slide of [page] (overview); see [updatePdfpc]. */
    fun setHidden(page: Int, hidden: Boolean) = updatePdfpc { it.withHidden(slides.pagesOf(page), hidden) }

    /** Puts or takes off a bookmark on the slide of [page]; see [updatePdfpc]. */
    fun setBookmark(page: Int, on: Boolean) = updatePdfpc { it.withBookmark(slides.pagesOf(page), on) }

    /** Writes [text] as the note shown on [page] (blank: removed); see [updatePdfpc]. */
    fun setNote(page: Int, text: String) = updatePdfpc { it.withNote(noteOwner(page), text) }

    /**
     * Page holding the note shown on [page]: its own, otherwise the first of its slide with one (as PDFPC shows
     * it), otherwise the first page of the slide (where PDFPC writes it).
     */
    private fun noteOwner(page: Int): Int {
        val p = pdfpc ?: return slides.firstPageOf(page)
        if (p.noteFor(page) != null) return page
        return slides.pagesOf(page).firstOrNull { p.noteFor(it) != null } ?: slides.firstPageOf(page)
    }

    /**
     * Changes the .pdfpc ([change]; a new one if there was none). It stays in memory and the document is to save:
     * the file is written together with the PDF (user's choice), and undo does not take the change back.
     */
    private fun updatePdfpc(change: (PdfpcNotes) -> PdfpcNotes) {
        pdfpc = change(pdfpc ?: PdfpcNotes.empty())
        markPdfpcEdit()
    }

    /** Value of [editCount] at the last change of the .pdfpc ([updatePdfpc], [restorePdfpc]). */
    private var pdfpcEdit = 0

    private fun markPdfpcEdit() {
        markStructuralEdit()
        pdfpcEdit = editCount
    }

    /** The .pdfpc changed here and is not saved yet. */
    private val pdfpcPending: Boolean get() = pdfpcEdit > savedEditCount

    /** The .pdfpc to keep in the session while it is not saved, or null. */
    val unsavedPdfpc: String? get() = pdfpc?.takeIf { pdfpcPending }?.toJson(pageCount, labels)

    /** Session restore: the .pdfpc not saved yet ([unsavedPdfpc]) replaces the one read, to save. */
    fun restorePdfpc(json: String) {
        pdfpc = PdfpcNotes.read(json, pageCount, labels) ?: return
        markPdfpcEdit()
    }

    /** First pages of the slides with a bookmark, in order. */
    val bookmarks: List<Int> get() = pdfpc?.bookmarkPages.orEmpty().map(slides::firstPageOf).distinct().sorted()

    /** The slide of [page] has a bookmark. */
    fun isBookmarked(page: Int): Boolean = pdfpc?.bookmarkPages.orEmpty().let { b -> b.isNotEmpty() && slides.pagesOf(page).any { it in b } }

    /** Notes can be shown here: the .pdfpc has some, or it can be written to add them. */
    val notesAvailable: Boolean get() = notes != null || source.canWritePdfpc

    /** [page] belongs to a slide hidden in the .pdfpc (jumps still reach it). */
    fun isHidden(page: Int): Boolean = hiddenPages.isNotEmpty() && slides.pagesOf(page).any { it in hiddenPages }

    /** Note of [page]: that of its slide, shown on all the overlays as in PDFPC. */
    fun noteFor(page: Int): String? = notes?.noteFor(page, slides.pagesOf(page))

    /** Every page a slide: navigation with Beamer overlays turned off. */
    val singlePages = Slides.single(pages.pageCount)

    val title: String = source.title
    val pageCount get() = pages.pageCount

    var pageIndex by mutableIntStateOf(0)

    /** Links and outline, read in the background after opening ([loadStructure]); null until ready. */
    var structure by mutableStateOf<PdfStructure?>(null)

    @Volatile
    private var textIndex: PdfTextIndex? = null

    /**
     * Text with positions, built at the first search (call from a worker thread: a long PDF takes a few
     * seconds) and then kept in memory.
     */
    @Synchronized
    fun textIndex(): PdfTextIndex {
        textIndex?.let { return it }
        // An error (memory, document closed halfway) is not remembered: the next search retries.
        return runCatching { PdfTextIndex.build(cacheFile) }.getOrNull()?.also { textIndex = it } ?: PdfTextIndex(emptyList())
    }

    private val pageTexts = HashMap<Int, PdfTextIndex.PageText>()

    /**
     * Text with positions of page [page] alone (snapping highlighter): from the search index if already
     * built, otherwise extracted and kept aside. From a worker thread.
     */
    fun pageText(page: Int): PdfTextIndex.PageText {
        textIndex?.pages?.getOrNull(page)?.let { return it }
        synchronized(pageTexts) { pageTexts[page]?.let { return it } }
        val text = runCatching { PdfTextIndex.page(cacheFile, page) }.getOrNull() ?: return PdfTextIndex.PageText("", FloatArray(0))
        synchronized(pageTexts) { pageTexts[page] = text }
        return text
    }

    /** Page with label [label] (as Beamer prints it), or null. */
    fun pageWithLabel(label: String): Int? = labels?.indexOfFirst { it.equals(label.trim(), ignoreCase = true) }?.takeIf { it >= 0 }

    /** Reads links and outline (call from a worker thread; the result goes into [structure] on the main thread). */
    fun loadStructure(): PdfStructure = runCatching { PdfStructure.load(cacheFile, pages.aspects) }.getOrDefault(PdfStructure.EMPTY)

    /** Notes panel open: starts closed, the icon in the bar shows that there are notes. */
    var notesVisible by mutableStateOf(false)

    /**
     * Size and date of the file when Presenter read or saved it: if they change, someone else modified it on
     * disk.
     */
    @Volatile
    var diskStamp: DocStamp? = null

    /** Version of the file that could not be reread: no retry until it changes again. */
    @Volatile
    var unreadableStamp: DocStamp? = null

    /** The file changed on disk while there were unsaved changes: icon to reload it. */
    var changedOnDisk by mutableStateOf(false)

    /** Inverted colors on the projector for this document (icon at the top, remembered in the session). */
    var inverted by mutableStateOf(false)

    private val inks = HashMap<Int, PageInk>()

    /**
     * Grows at every ink or page change; compared with [savedEditCount] it tells whether there is something to save.
     */
    var editCount by mutableIntStateOf(0)
        private set
    var savedEditCount by mutableIntStateOf(0)
        private set

    /**
     * Value of [editCount] at the last change that undo cannot take back (page order, annotations restored from
     * the session): until a save after it, the document differs from the file whatever the ink.
     */
    private var structuralEdit = 0

    /**
     * Something to save: a change since the last save that is still there (ink undone back to the saved state
     * does not count).
     */
    val isDirty get() = editCount != savedEditCount && (structuralEdit > savedEditCount || inks.values.any { it.changedSinceSave })

    /** Annotations restored from the session: what the file holds is not known, they stay to save until saved. */
    private var restoredUnsaved = false
    var isSaving by mutableStateOf(false)
        private set

    init {
        restoreInk(restored)
    }

    /** Elements per page onto the slides (after a move two keys may end up on the same slide). */
    private fun restoreInk(elements: Map<Int, List<PageElement>>) {
        for ((page, list) in bySlide(elements)) inkOf(page).restore(list)
    }

    private fun bySlide(elements: Map<Int, List<PageElement>>): Map<Int, List<PageElement>> {
        val bySlide = HashMap<Int, MutableList<PageElement>>()
        for ((page, list) in elements.toSortedMap()) if (page in 0 until pageCount) bySlide.getOrPut(slides.firstPageOf(page)) { ArrayList() } += list
        return bySlide
    }

    /** What the file holds, page by page (see [PageInk.savedElements]). */
    fun savedElements(): Map<Int, List<PageElement>> =
        inks.filterValues { it.savedElements.isNotEmpty() }.mapValues { it.value.savedElements }

    /** The file holds [saved] (in this document's numbering): every page compares its ink with it. */
    private fun markSavedInk(saved: Map<Int, List<PageElement>>) {
        val bySlide = bySlide(saved)
        for (page in inks.keys.toList() + bySlide.keys) inkOf(page).markSaved(bySlide[page].orEmpty())
    }

    fun aspect(page: Int): Float = pages.aspects.getOrElse(page) { 1f }
    fun pageHeight(page: Int): Float = PAGE_WIDTH * aspect(page)
    /**
     * Ink of the page: shared by the overlays of a slide (keyed by the first page of the slide), so the
     * strokes stay while the next parts appear.
     */
    fun inkOf(page: Int): PageInk = inks.getOrPut(slides.firstPageOf(page)) { PageInk() }
    fun inkIfPresent(page: Int): PageInk? = inks[slides.firstPageOf(page)]

    fun markEdited() {
        editCount++
    }

    /** A change that undo cannot take back (see [structuralEdit]). */
    private fun markStructuralEdit() {
        editCount++
        structuralEdit = editCount
    }

    /** All elements, page by page (they are immutable: readable from any thread). */
    fun allElements(): Map<Int, List<PageElement>> =
        inks.filterValues { !it.isEmpty }.mapValues { it.value.elements.toList() }

    /**
     * Session restore: the annotations not yet saved in the PDF replace those read from the file, and the
     * document is marked as to be saved.
     */
    fun restoreUnsaved(strokes: Map<Int, List<PageElement>>) {
        inks.clear()
        restoreInk(strokes)
        restoredUnsaved = true
        markStructuralEdit()
    }

    /**
     * Saves the annotations (and the page order) into the original file; call from a worker thread. The
     * elements, edit count and layout are taken on the main thread with [snapshot] before starting.
     * Returns the error of the .pdfpc rewrite, or null: at that point the PDF is already saved and the save
     * still counts (the notes are left to fix by hand).
     */
    fun save(context: Context, snapshot: Snapshot): Throwable? {
        val dir = cacheDir(context)
        val out = File.createTempFile("save", ".pdf", dir)
        try {
            PdfInkStore.write(cacheFile, out, snapshot.elements, pages.aspects, slides::pagesOf)
            source.overwriteWith(context, out)
            // The file is now Presenter's: it must not be mistaken for a change by someone else.
            diskStamp = source.stamp(context)
            // The file on disk now has these pages, in this order: from here the order must not be applied again
            // (even if the .pdfpc fails, and also for a session saved meanwhile).
            if (snapshot.layout != null && snapshot.layout === layout) {
                layout = null
                diskPages = pageCount
            }
            // Pages changed, or notes, hidden slides and bookmarks edited: the .pdfpc is written right after the PDF.
            snapshot.pdfpc?.let { json -> return runCatching { source.writePdfpc(context, json) }.exceptionOrNull() }
            return null
        } finally {
            out.delete()
        }
    }

    /** What [save] writes, taken on the main thread: annotations, page order, the .pdfpc to write (or null). */
    class Snapshot(val elements: Map<Int, List<PageElement>>, val edit: Int, val layout: List<PageSlot>?, val pdfpc: String?)

    fun snapshot(): Snapshot {
        isSaving = true
        // The .pdfpc follows the pages (if there is one) or holds edits made here.
        val json = pdfpc?.takeIf { (layout != null || pdfpcPending) && source.canWritePdfpc }?.toJson(pageCount, labels)
        return Snapshot(allElements(), editCount, layout, json)
    }

    fun onSaved(snapshot: Snapshot?) {
        isSaving = false
        if (snapshot != null) {
            savedEditCount = snapshot.edit
            for ((page, ink) in inks) ink.markSaved(snapshot.elements[page].orEmpty())
            restoredUnsaved = false
            // Saving chose this version: the one changed on disk has been overwritten.
            changedOnDisk = false
        }
    }

    fun close() {
        pages.close()
        cacheFile.delete()
    }

    /** Result of [edited]: the new document and the new index of each page of this one (-1: removed). */
    class Edited(val doc: PresenterDocument, val newIndex: IntArray)

    /**
     * Document with the pages changed by [edit] (call from a worker thread): private copy rewritten, with ink
     * [elements] and what the file holds [saved] (taken on the main thread with [allElements] and
     * [savedElements]), notes, labels and overlays following the pages. This document stays as it is
     * (whoever replaces it closes it). The new one is to be saved unless it went back exactly to the file on
     * disk: same pages and the same annotations.
     */
    fun edited(
        context: Context,
        edit: PageEdit,
        elements: Map<Int, List<PageElement>>,
        saved: Map<Int, List<PageElement>>,
    ): Edited {
        val slots = PageEdits.slots(edit, pageCount, labels)
        val doc = rebuilt(context, slots, elements, saved)
        doc.restoredUnsaved = restoredUnsaved
        // Page order, restored annotations or an edited .pdfpc: to save whatever the ink. Otherwise the ink
        // compared with the file.
        if (pdfpcPending) doc.markPdfpcEdit()
        if (doc.layout != null || doc.restoredUnsaved) doc.markStructuralEdit() else doc.markEdited()
        return Edited(doc, PageEdits.newIndexOf(slots, pageCount))
    }

    /**
     * Reopening from the session: the unsaved page order [layout] (relative to the file just read, which was
     * [stamp] back then) is applied again and this document is closed. Null if it no longer applies: file
     * changed meanwhile (even with the same page count) or rewrite failed; then this document stays open as
     * it is, and the caller must not apply the unsaved annotations to it (they are in the new numbering).
     * From a worker thread.
     */
    fun withLayout(context: Context, layout: List<PageSlot>, stamp: DocStamp?): PresenterDocument? {
        if (stamp != null && diskStamp != null && stamp != diskStamp) return null
        if (!PageEdits.isValid(layout, pageCount)) return null
        val doc = runCatching { rebuilt(context, layout, allElements(), savedElements()) }.getOrElse { return null }
        doc.notesVisible = notesVisible
        doc.inverted = inverted
        if (doc.layout != null) doc.markStructuralEdit()
        close()
        return doc
    }

    private fun rebuilt(
        context: Context,
        slots: List<PageSlot>,
        elements: Map<Int, List<PageElement>>,
        saved: Map<Int, List<PageElement>>,
    ): PresenterDocument {
        val out = File.createTempFile("doc", ".pdf", cacheDir(context))
        var fd: ParcelFileDescriptor? = null
        try {
            val newLabels = PageEdits.labels(slots, labels)
            val done = PdfInkStore.rearrange(cacheFile, out, slots, newLabels, PageEdits.overlayCandidates(slots, newLabels))
            val next = PageStructure(pageCount, labels, overlays, elements, pdfpc, layout, diskPages).edited(slots, done.slots, done.overlays)
            fd = ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)
            val newPages = PdfPages.open(fd)
            return PresenterDocument(
                source, out, newPages, next.ink, next.pdfpc, next.labels, next.overlays, next.diskPages, next.layout,
            ).also {
                it.markSavedInk(PageEdits.remapInk(saved, slots, pageCount, slides::pagesOf, next.slides::firstPageOf))
                it.pageIndex = PageEdits.follow(pageIndex, PageEdits.newIndexOf(slots, pageCount)).coerceIn(0, (it.pageCount - 1).coerceAtLeast(0))
                it.diskStamp = diskStamp
                it.unreadableStamp = unreadableStamp
            }
        } catch (e: Throwable) {
            runCatching { fd?.close() }
            out.delete()
            throw e
        }
    }

    companion object {
        private fun cacheDir(context: Context) = File(context.cacheDir, "docs").apply { mkdirs() }

        /**
         * Opens [source] (call from a worker thread). [fresh]: reload, the server's version is wanted even for a
         * remote PDF (no fallback to the tablet copy).
         */
        fun open(context: Context, source: DocSource, fresh: Boolean = false): PresenterDocument {
            val cache = File.createTempFile("doc", ".pdf", cacheDir(context))
            var fd: ParcelFileDescriptor? = null
            try {
                // Read before the copy: a change during the copy will show at the next check. For a remote PDF the date
                // of the tablet copy counts (read from the server before downloading, or the last one if the server
                // does not answer).
                val remote = source as? DocSource.Remote
                val stamp = if (remote == null) source.stamp(context) else null
                if (remote != null) remote.copyTo(context, cache, fallback = !fresh) else source.copyTo(context, cache)
                val pdfpc = runCatching { source.readPdfpc(context) }.getOrNull()
                // A PDF that PdfBox cannot read can still be shown, without saved annotations.
                val inspection = runCatching { PdfInkStore.inspect(cache) }
                    .getOrElse { PdfInkStore.Inspection(emptyMap(), null) }
                fd = ParcelFileDescriptor.open(cache, ParcelFileDescriptor.MODE_READ_ONLY)
                val pages = PdfPages.open(fd)
                val notes = pdfpc?.let { PdfpcNotes.read(it, pages.pageCount, inspection.labels) }
                return PresenterDocument(source, cache, pages, inspection.elements, notes, inspection.labels, inspection.overlays, pages.pageCount, null)
                    .also { it.diskStamp = remote?.copyStamp(context) ?: stamp }
            } catch (e: Throwable) {
                // PdfPages.open closes the renderer if it fails; the descriptor must be closed here.
                runCatching { fd?.close() }
                cache.delete()
                throw e
            }
        }

        /** Copies left from a previous session (app killed by the system). */
        fun clearCache(context: Context) {
            cacheDir(context).listFiles()?.forEach { it.delete() }
        }
    }
}
