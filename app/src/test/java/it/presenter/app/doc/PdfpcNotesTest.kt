package it.presenter.app.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfpcNotesTest {

    @Test
    fun `json - note by index when labels are missing`() {
        val text = """{"pdfpcFormat":2,"pages":[{"idx":0,"label":"1","overlay":0,"note":"First"},{"idx":2,"label":"3","overlay":0,"note":"Third"}]}"""
        val notes = PdfpcNotes.parse(text, pageCount = 4, labels = null)!!
        assertEquals("First", notes.noteFor(0))
        assertNull(notes.noteFor(1))
        assertEquals("Third", notes.noteFor(2))
        assertEquals(true, notes.markdown)
    }

    @Test
    fun `json - label and overlay win over the index if the PDF changed`() {
        // In the current PDF slide "2" has two overlays (pages 2 and 3): the saved index (1) is stale.
        val labels = listOf("1", "1", "2", "2", "3")
        val text = """{"pages":[{"idx":1,"label":"2","overlay":1,"note":"Second overlay"}]}"""
        val notes = PdfpcNotes.parse(text, pageCount = 5, labels = labels)!!
        assertEquals("Second overlay", notes.noteFor(3))
    }

    @Test
    fun `as in PDFPC the note of the first overlay is shown on all the overlays of the slide`() {
        val labels = listOf("1", "2", "2", "2", "3")
        val text = """{"pages":[{"idx":1,"label":"2","overlay":0,"note":"Slide two"}]}"""
        val notes = PdfpcNotes.parse(text, 5, labels)!!
        // Stored on its page only.
        assertEquals("Slide two", notes.noteFor(1))
        assertNull(notes.noteFor(2))
        // Shown on every page of the slide (pages 1-3), not on the others.
        for (page in 1..3) assertEquals("Slide two", notes.noteFor(page, 1..3))
        assertNull(notes.noteFor(0, 0..0))
        assertNull(notes.noteFor(4, 4..4))
    }

    @Test
    fun `an overlay with its own note shows that one`() {
        val labels = listOf("1", "1", "1")
        val text = """{"pages":[{"idx":0,"label":"1","overlay":0,"note":"First"},{"idx":2,"label":"1","overlay":2,"note":"Third"}]}"""
        val notes = PdfpcNotes.parse(text, 3, labels)!!
        assertEquals("First", notes.noteFor(1, 0..2))
        assertEquals("Third", notes.noteFor(2, 0..2))
    }

    @Test
    fun `json - disableMarkdown`() {
        val text = """{"disableMarkdown":true,"pages":[{"idx":0,"note":"x"}]}"""
        assertEquals(false, PdfpcNotes.parse(text, 1, null)!!.markdown)
    }

    @Test
    fun `without notes it returns null`() {
        assertNull(PdfpcNotes.parse("""{"pdfpcFormat":2,"pages":[]}""", 3, null))
        assertNull(PdfpcNotes.parse("neither json nor legacy", 3, null))
    }

    @Test
    fun `legacy - blocks per user slide, escapes and skip`() {
        val text = """
            [duration]
            20
            [skip]
            2,
            [notes]
            ### 1
            Introduction \[draft\]
            ### 2
            Final \# results
        """.trimIndent()
        // Page 2 (index 1) is an overlay of slide 1: the user slides are {0,1}, {2}.
        val notes = PdfpcNotes.parse(text, pageCount = 3, labels = null)!!
        assertEquals("Introduction [draft]", notes.noteFor(0))
        assertEquals("Introduction [draft]", notes.noteFor(1))
        assertEquals("Final # results", notes.noteFor(2))
    }

    @Test
    fun `hidden slides`() {
        val text = """{"pages":[{"idx":1,"hidden":true},{"idx":2,"note":"x","hidden":false},{"idx":3,"hidden":true,"note":"y"}]}"""
        assertEquals(setOf(1, 3), PdfpcNotes.read(text, 5, null)!!.hiddenPages)
    }

    @Test
    fun `slides hidden and shown again`() {
        val text = """{"duration":20,"pages":[{"idx":1,"note":"x","hidden":true},{"idx":3,"forcedOverlay":true}]}"""
        val notes = PdfpcNotes.read(text, 6, null)!!
        // Hidden: marked on the first page of the slide; shown: the mark leaves every page, the rest stays.
        val hidden = notes.withHidden(3..4, hidden = true)
        assertEquals(setOf(1, 3), hidden.hiddenPages)
        val shown = hidden.withHidden(0..1, hidden = false).withHidden(3..4, hidden = false)
        assertEquals(emptySet<Int>(), shown.hiddenPages)
        val json = org.json.JSONObject(shown.toJson(6, null))
        assertEquals(20, json.getInt("duration"))
        assertEquals("x", shown.noteFor(1))
        assertEquals(true, json.getJSONArray("pages").toString().contains("forcedOverlay"))
        // From nothing: a .pdfpc with only the hidden slide.
        assertEquals(setOf(2), PdfpcNotes.read(PdfpcNotes.empty().withHidden(2..2, true).toJson(4, null), 4, null)!!.hiddenPages)
    }

    @Test
    fun `notes and bookmarks edited`() {
        val notes = PdfpcNotes.read("""{"pages":[{"idx":0,"note":"One"},{"idx":2,"note":"Three"}]}""", 4, null)!!
        val edited = notes.withNote(2, "  Three, **new**  ").withNote(0, " ").withNote(3, "Four")
        val back = PdfpcNotes.read(edited.toJson(4, null), 4, null)!!
        assertNull(back.noteFor(0))
        assertEquals("Three, **new**", back.noteFor(2))
        assertEquals("Four", back.noteFor(3))
        val marked = back.withBookmark(1..2, true).withBookmark(3..3, true).withBookmark(3..3, false)
        assertEquals(setOf(1), PdfpcNotes.read(marked.toJson(4, null), 4, null)!!.bookmarkPages)
    }
}
