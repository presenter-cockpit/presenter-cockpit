package it.presenter.app.doc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageEditsTest {
    private fun pages(vararg i: Int) = i.map { PageSlot.Page(it) }

    @Test
    fun `moving a slide forward and back`() {
        // A two-page slide (1, 2) after page 4.
        assertEquals(pages(0, 3, 4, 1, 2, 5), PageEdits.slots(PageEdit.Move(1..2, 5), 6, null))
        // At the end.
        assertEquals(pages(0, 3, 4, 5, 1, 2), PageEdits.slots(PageEdit.Move(1..2, 6), 6, null))
        // At the start.
        assertEquals(pages(4, 0, 1, 2, 3, 5), PageEdits.slots(PageEdit.Move(4..4, 0), 6, null))
    }

    @Test
    fun `blank page with a free label after the slide`() {
        val labels = listOf("1", "2", "2", "2.1", "3")
        val slots = PageEdits.slots(PageEdit.InsertBlank(2), 5, labels)
        assertEquals(PageSlot.Blank(2, "2.2"), slots[3])
        assertEquals(listOf("1", "2", "2", "2.2", "2.1", "3"), PageEdits.labels(slots, labels))
        // Without labels in the PDF the blank page has none.
        assertEquals(PageSlot.Blank(0, null), PageEdits.slots(PageEdit.InsertBlank(0), 2, null)[1])
    }

    @Test
    fun `deleting and the page that follows`() {
        val slots = PageEdits.slots(PageEdit.Delete(1..2), 5, null)
        assertEquals(pages(0, 3, 4), slots)
        val newIndex = PageEdits.newIndexOf(slots, 5)
        assertEquals(listOf(0, -1, -1, 1, 2), newIndex.toList())
        // From the removed page go to the first remaining one after it; at the end to the one before.
        assertEquals(1, PageEdits.follow(2, newIndex))
        val tail = PageEdits.newIndexOf(PageEdits.slots(PageEdit.Delete(3..4), 5, null), 5)
        assertEquals(2, PageEdits.follow(4, tail))
    }

    @Test
    fun `overlays kept, broken or checked on the text`() {
        fun check(slots: List<PageSlot>, labels: List<String>, overlays: Set<Int>, confirm: Boolean): Set<Int> {
            val newLabels = PageEdits.labels(slots, labels)
            return PageEdits.overlays(slots, newLabels, overlays) { j ->
                assertTrue(j in PageEdits.overlayCandidates(slots, newLabels))
                confirm
            }
        }
        // Pages 1 and 2 are overlays of 0; 4 of 3. The slide 3-4 is moved to the front: nothing to check.
        val moved = PageEdits.slots(PageEdit.Move(3..4, 0), 5, null)
        assertEquals(setOf(1, 3, 4), check(moved, listOf("1", "1", "1", "2", "2"), setOf(1, 2, 4), confirm = false))
        // A blank page in the middle breaks the slide (it has a label of its own).
        val blank = PageEdits.slots(PageEdit.InsertBlank(0), 3, null)
        assertEquals(setOf(3), check(blank, listOf("1", "1", "1"), setOf(1, 2), confirm = true))
        // An overlay removed from the middle (overlays off): the next one is checked on the text, as when reading.
        val removed = PageEdits.slots(PageEdit.Delete(1..1), 4, null)
        assertEquals(setOf(1), check(removed, listOf("1", "1", "1", "2"), setOf(1, 2), confirm = true))
        assertEquals(emptySet<Int>(), check(removed, listOf("1", "1", "1", "2"), setOf(1, 2), confirm = false))
        // The first page of a slide removed: the next one has another label than the page before.
        val first = PageEdits.slots(PageEdit.Delete(1..1), 3, null)
        assertEquals(emptySet<Int>(), check(first, listOf("1", "2", "2"), setOf(2), confirm = true))
    }

    @Test
    fun `ink and notes follow the pages`() {
        val slots = PageEdits.slots(PageEdit.Move(0..0, 3), 3, null)
        assertEquals(mapOf(2 to "a", 0 to "b"), PageEdits.remap(mapOf(0 to "a", 1 to "b"), slots, 3))
    }

    @Test
    fun `the slide's ink stays on the overlays if the first page is removed`() {
        // Slide 1-3 (overlays) with the ink under key 1; page 1 is removed (overlays off).
        val pagesOf = { p: Int -> if (p in 1..3) 1..3 else p..p }
        val slots = PageEdits.slots(PageEdit.Delete(1..1), 5, null)
        assertEquals(mapOf(1 to listOf("slide"), 0 to listOf("zero")), PageEdits.remapInk(mapOf(1 to listOf("slide"), 0 to listOf("zero")), slots, 5, pagesOf))
        // Two slides that become one: the ink is merged on its first page.
        assertEquals(mapOf(0 to listOf("zero", "slide")), PageEdits.remapInk(mapOf(1 to listOf("slide"), 0 to listOf("zero")), slots, 5, pagesOf) { 0 })
        // The whole slide removed: the ink disappears.
        val all = PageEdits.slots(PageEdit.Delete(1..3), 5, null)
        assertEquals(emptyMap<Int, List<String>>(), PageEdits.remapInk(mapOf(1 to listOf("slide")), all, 5, pagesOf))
    }

    @Test
    fun `reordered pdfpc without the fields holding a slide number`() {
        val text = """{"endSlide":10,"savedSlide":3,"duration":20,"pages":[{"idx":0,"note":"x"}]}"""
        val json = JSONObject(PdfpcNotes.read(text, 2, null)!!.remapped(PageEdits.slots(PageEdit.Move(0..0, 2), 2, null), 2).toJson(2, null))
        assertFalse(json.has("endSlide"))
        assertFalse(json.has("savedSlide"))
        assertEquals(20, json.getInt("duration"))
    }

    @Test
    fun `order relative to the file on disk`() {
        val blank = PageSlot.Blank(-1, "1.1", 100f, 50f)
        val first = PageEdits.compose(null, listOf(PageSlot.Page(0), blank, PageSlot.Page(1)), 2)
        assertEquals(listOf(PageSlot.Page(0), blank, PageSlot.Page(1)), first)
        // The blank page removed again: back to the file.
        assertNull(PageEdits.compose(first, pages(0, 2), 2))
        // A page removed: not the file even if the order is ascending.
        assertEquals(pages(0), PageEdits.compose(null, pages(0), 2))
    }

    @Test
    fun `session order validity`() {
        val layout = listOf(PageSlot.Page(1), PageSlot.Blank(-1, "2.1", 362.8f, 272.1f), PageSlot.Page(0), PageSlot.Blank(-1, null, 10f, 20f))
        assertTrue(PageEdits.isValid(layout, 2))
        assertFalse(PageEdits.isValid(layout, 1))
        assertFalse(PageEdits.isValid(pages(0, 0), 2))
    }

    @Test
    fun `pdfpc rewritten as JSON with the pages in the new order`() {
        val labels = listOf("1", "2", "2", "3")
        val text = """{"pdfpcFormat":2,"duration":20,"pages":[{"idx":0,"label":"1","overlay":0,"note":"One"},""" +
            """{"idx":2,"label":"2","overlay":1,"note":"Two b","hidden":true}]}"""
        val notes = PdfpcNotes.read(text, 4, labels)!!
        // Slide "2" (pages 1-2) goes to the end and a blank page enters after "1".
        val moved = PageEdits.slots(PageEdit.Move(1..2, 4), 4, labels)
        val movedLabels = PageEdits.labels(moved, labels)!!
        val notes2 = notes.remapped(moved, 4)
        val blank = PageEdits.slots(PageEdit.InsertBlank(0), 4, movedLabels)
        val finalLabels = PageEdits.labels(blank, movedLabels)!!
        assertEquals(listOf("1", "1.1", "3", "2", "2"), finalLabels)
        val json = JSONObject(notes2.remapped(blank, 4).toJson(5, finalLabels))
        assertEquals(20, json.getInt("duration"))
        val pages = json.getJSONArray("pages")
        assertEquals(2, pages.length())
        assertEquals(0, pages.getJSONObject(0).getInt("idx"))
        val b = pages.getJSONObject(1)
        assertEquals(4, b.getInt("idx"))
        assertEquals("2", b.getString("label"))
        assertEquals(1, b.getInt("overlay"))
        assertTrue(b.getBoolean("hidden"))
        // Reread with the new labels, the notes are in the right place.
        val reread = PdfpcNotes.parse(json.toString(), 5, finalLabels)!!
        assertEquals("One", reread.noteFor(0))
        assertEquals("Two b", reread.noteFor(4))
    }

    @Test
    fun `legacy pdfpc converted to JSON`() {
        val text = "[duration]\n15\n[end_time]\n10:30\n[skip]\n2,\n[notes]\n### 1\nFirst\n"
        val notes = PdfpcNotes.read(text, 3, null)!!
        val json = JSONObject(notes.toJson(3, null))
        assertEquals(15, json.getInt("duration"))
        assertEquals("10:30", json.getString("endTime"))
        val pages = json.getJSONArray("pages")
        // Pages 0 and 1 (overlay from [skip]) with the note of slide 1; page 1 also forcedOverlay.
        assertEquals("First", pages.getJSONObject(0).getString("note"))
        assertTrue(pages.getJSONObject(1).getBoolean("forcedOverlay"))
    }
}
