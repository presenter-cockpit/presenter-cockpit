package it.presenter.app.doc

import it.presenter.app.testing.SampleTalk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PDFPC notes of the sample talk ([SampleTalk]) on its Beamer PDF, read as the app reads them: labels and
 * overlays with [PdfInkStore.inspect] (Robolectric: PdfBox's resources for the text).
 */
@RunWith(RobolectricTestRunner::class)
class PdfpcSampleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val text = SampleTalk.notesText
    private val entries = JSONObject(text).getJSONArray("pages").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
    private val inspection by lazy { PdfInkStore.inspect(SampleTalk.pdf(tmp.root)) }
    private val labels by lazy { inspection.labels!! }

    @Test
    fun `the sample has the expected shape`() {
        assertEquals(SampleTalk.PAGES, labels.size)
        assertEquals(7, entries.size)
        for (page in SampleTalk.SECTION_PAGES) assertEquals("page $page", labels[page - 1], labels[page])
    }

    @Test
    fun `every note lands on the given page, with and without labels`() {
        for (useLabels in listOf(true, false)) {
            val notes = PdfpcNotes.parse(text, SampleTalk.PAGES, if (useLabels) labels else null)!!
            for (e in entries) {
                assertEquals("page ${e.getInt("idx")}", e.getString("note"), notes.noteFor(e.getInt("idx")))
            }
            assertEquals(true, notes.markdown)
        }
    }

    @Test
    fun `the pages before the notes have none`() {
        val notes = PdfpcNotes.parse(text, SampleTalk.PAGES, labels)!!
        for (page in 0..1) assertNull("page $page", notes.noteFor(page))
    }

    @Test
    fun `overlays are recognized from labels and text, section pages are not`() {
        assertEquals(SampleTalk.OVERLAYS, inspection.overlays)
    }

    @Test
    fun `the note is shown on every overlay of its slide, not on the section page after it`() {
        val notes = PdfpcNotes.parse(text, SampleTalk.PAGES, labels)!!
        val slides = Slides(labels, SampleTalk.PAGES) { it in inspection.overlays }
        fun shown(page: Int) = notes.noteFor(page, slides.pagesOf(page))
        // Slide "5": three overlays, the note is on the first.
        assertEquals(notes.noteFor(6), shown(7))
        assertEquals(notes.noteFor(6), shown(8))
        // Label "6": the slide (page 9, with a note) and the section page after it (without).
        assertNotNull(shown(9))
        for (page in SampleTalk.SECTION_PAGES) assertNull("page $page", shown(page))
    }

    @Test
    fun `a PDF with fewer pages does not crash the parser`() {
        val notes = PdfpcNotes.parse(text, 8, labels.take(8))!!
        assertNull(notes.noteFor(15))
        assertNotNull(notes.noteFor(4))
    }

    @Test
    fun `slides with overlays, recognized from the labels alone`() {
        // Labels alone also join the section pages to the slide before them (the app checks the text too).
        val slides = Slides(labels, SampleTalk.PAGES)
        val groups = (0 until SampleTalk.PAGES).map { slides.pagesOf(it) }.distinct()
        assertEquals(5, groups.count { it.count() == 2 })
        assertEquals(1, groups.count { it.count() == 3 })
        assertEquals(10, groups.size)
    }
}
