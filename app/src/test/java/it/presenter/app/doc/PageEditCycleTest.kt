package it.presenter.app.doc

import it.presenter.app.ink.PageElement
import it.presenter.app.testing.BeamerPdf
import it.presenter.app.testing.Elements
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Whole cycle of page editing, with the app's code from reading to saving: a Beamer PDF with notes and
 * annotations is read ([PdfInkStore.inspect], [PdfpcNotes.read]), edited several times ([PageEdits],
 * [PdfInkStore.rearrange], [PageStructure.edited]), saved ([PdfInkStore.write], [PdfpcNotes.toJson]) and
 * read again: the file must say exactly what the app showed before saving.
 */
@RunWith(RobolectricTestRunner::class)
class PageEditCycleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pages = listOf(
        BeamerPdf.Page("1", "P0 Title of the talk"),
        BeamerPdf.Page("2", "P1 Plan what we will see today"),
        BeamerPdf.Page("2", "P2 Plan what we will see today pots and soil"),
        BeamerPdf.Page("3", "P3 Light hours of direct sun"),
        // Same label but another text: a section page, not an overlay.
        BeamerPdf.Page("3", "P4 Section two sowing and watering"),
        BeamerPdf.Page("4", "P5 Watering early in the morning"),
        BeamerPdf.Page("4", "P6 Watering early in the morning at the foot"),
    )

    private val pdfpc = JSONObject()
        .put("pdfpcFormat", 2).put("disableMarkdown", false).put("duration", 20).put("endSlide", 5)
        .put(
            "pages",
            org.json.JSONArray()
                .put(JSONObject().put("idx", 1).put("label", "2").put("overlay", 0).put("note", "note P1"))
                .put(JSONObject().put("idx", 2).put("label", "2").put("overlay", 1).put("note", "note P2"))
                .put(JSONObject().put("idx", 3).put("label", "3").put("overlay", 0).put("hidden", true))
                .put(JSONObject().put("idx", 4).put("label", "3").put("overlay", 1).put("note", "note P4"))
                .put(JSONObject().put("idx", 6).put("label", "4").put("overlay", 1).put("note", "note P6")),
        ).toString()

    /** Original page shown by each page: "P3"…, "" for a blank one. */
    private fun ids(file: File) = BeamerPdf.texts(file).map { it.substringBefore(' ') }

    /** Ink grouped by slide, comparable. */
    private fun bySlide(ink: Map<Int, List<PageElement>>, s: PageStructure) =
        ink.entries.groupBy({ s.slides.firstPageOf(it.key) }, { Elements.bytes(it.value)!! }).mapValues { it.value.flatten() }

    @Test
    fun `edit, save and read again`() {
        var file = tmp.newFile("talk.pdf")
        BeamerPdf.write(file, pages)
        val read = PdfInkStore.inspect(file)
        assertEquals(setOf(2, 6), read.overlays)
        val ink = mapOf(1 to listOf(Elements.shape(1)), 3 to listOf(Elements.text("P3 ink")), 5 to listOf(Elements.shape(5)))
        var state = PageStructure(
            pages.size, read.labels, read.overlays, ink, PdfpcNotes.read(pdfpc, pages.size, read.labels), null, pages.size,
        )
        assertEquals("note P4", state.pdfpc!!.noteFor(4))

        // What the app does at each edit from the overview.
        var step = 0
        fun edit(edit: PageEdit) {
            val slots = PageEdits.slots(edit, state.pageCount, state.labels)
            val out = File(tmp.root, "step-${step++}.pdf")
            val labels = PageEdits.labels(slots, state.labels)
            val done = PdfInkStore.rearrange(file, out, slots, labels, PageEdits.overlayCandidates(slots, labels))
            state = state.edited(slots, done.slots, done.overlays)
            file = out
        }
        // Last slide (P5-P6) to the front; blank page after P2; P3 removed; overlay P1 removed with overlays off.
        edit(PageEdit.Move(5..6, 0))
        assertEquals(listOf("P5", "P6", "P0", "P1", "P2", "P3", "P4"), ids(file))
        edit(PageEdit.InsertBlank(4))
        edit(PageEdit.Delete(6..6))
        edit(PageEdit.Delete(3..3))
        assertEquals(listOf("P5", "P6", "P0", "P2", "", "P4"), ids(file))
        assertEquals(listOf("4", "4", "1", "2", "2.1", "3"), state.labels)
        assertEquals(setOf(1), state.overlays)
        // The ink of P1's slide moved to P2, that of P3 left with it.
        assertEquals(setOf(0, 3), state.ink.keys)
        // Order relative to the file on disk, as the session keeps it.
        assertEquals(listOf(5, 6, 0, 2, 4), state.layout!!.filterIsInstance<PageSlot.Page>().map { it.index })

        // Save, as PresenterDocument.save does.
        val saved = File(tmp.root, "saved.pdf")
        PdfInkStore.write(file, saved, state.ink, BeamerPdf.aspects(file), state.slides::pagesOf)
        val json = state.pdfpc!!.toJson(state.pageCount, state.labels)

        // Read again, as when the PDF is reopened.
        val again = PdfInkStore.inspect(saved)
        assertEquals(state.labels, again.labels)
        assertEquals(state.overlays, again.overlays)
        val reopened = PageStructure(state.pageCount, again.labels, again.overlays, again.elements, null, null, state.pageCount)
        assertEquals(bySlide(state.ink, state), bySlide(again.elements, reopened))
        val notes = PdfpcNotes.read(json, state.pageCount, again.labels)!!
        val expected = mapOf("P2" to "note P2", "P4" to "note P4", "P6" to "note P6")
        ids(saved).forEachIndexed { page, id -> assertEquals("page $page ($id)", expected[id], notes.noteFor(page)) }
        val root = JSONObject(json)
        assertEquals(20, root.getInt("duration"))
        assertFalse(root.has("endSlide"))
        // The "hidden" field followed the removed page P3: gone.
        assertFalse(json.contains("hidden"))
        // The blank page is as large as the others.
        assertTrue(BeamerPdf.aspects(saved).all { it == BeamerPdf.aspects(saved)[0] })
        BeamerPdf.qpdfCheck(saved)
    }

    @Test
    fun `an overlay moved away and back joins its slide again`() {
        var file = tmp.newFile("overlays.pdf")
        BeamerPdf.write(
            file,
            listOf(
                BeamerPdf.Page("1", "Title of the talk"),
                BeamerPdf.Page("2", "Plan what we will see today"),
                BeamerPdf.Page("2", "Plan what we will see today pots and soil"),
                BeamerPdf.Page("2", "Plan what we will see today pots and soil and watering"),
                BeamerPdf.Page("3", "Light hours of direct sun"),
            ),
        )
        val read = PdfInkStore.inspect(file)
        assertEquals(setOf(2, 3), read.overlays)
        var state = PageStructure(5, read.labels, read.overlays, mapOf(1 to listOf(Elements.shape(1))), null, null, 5)
        var step = 0
        fun edit(edit: PageEdit) {
            val slots = PageEdits.slots(edit, state.pageCount, state.labels)
            val labels = PageEdits.labels(slots, state.labels)
            val out = File(tmp.root, "o-${step++}.pdf")
            val done = PdfInkStore.rearrange(file, out, slots, labels, PageEdits.overlayCandidates(slots, labels))
            state = state.edited(slots, done.slots, done.overlays)
            file = out
        }
        // Third overlay (page 3) to the end, with overlays off: it is a slide of its own there.
        edit(PageEdit.Move(3..3, 5))
        assertEquals(setOf(2), state.overlays)
        // Back after the second overlay: one slide again, with its ink.
        edit(PageEdit.Move(4..4, 3))
        assertEquals(setOf(2, 3), state.overlays)
        assertEquals(1..3, state.slides.pagesOf(1))
        assertEquals(setOf(1), state.ink.keys)
        assertEquals(null, state.layout)
    }
}
