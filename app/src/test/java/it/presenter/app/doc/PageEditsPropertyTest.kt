package it.presenter.app.doc

import it.presenter.app.ink.PageElement
import it.presenter.app.session.SessionStore
import it.presenter.app.testing.Elements
import it.presenter.app.testing.Property
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Random sequences of page edits, made as the overview makes them (with overlays on or off), on random
 * documents (labels with Beamer overlays, section pages, repeated labels, no labels). Alongside, a model
 * tracks which page of the starting file each page shows; after every edit the document must agree with
 * it: order relative to the file, labels, overlays, ink, notes, session order, current page.
 */
class PageEditsPropertyTest {
    /** Starting document and what the model knows of it. */
    private class Start(val labels: List<String>?, val overlays: Set<Int>, val notes: Map<Int, String>, val ink: Map<Int, List<PageElement>>) {
        var pageCount = 0

        /** Text of starting page y continues that of x (what [Slides.isContinuation] says on the file). */
        lateinit var continues: (Int, Int) -> Boolean

        /** Overlays as reading a file with these pages in the order [ids] finds them (-1: blank page). */
        fun overlaysOf(ids: List<Int>): Set<Int> = if (labels == null) emptySet() else (1 until ids.size).filterTo(HashSet()) { j ->
            ids[j - 1] >= 0 && ids[j] >= 0 && labels[ids[j - 1]] == labels[ids[j]] && continues(ids[j - 1], ids[j])
        }
        val notesJson: String
            get() = JSONObject().put("pdfpcFormat", 2).put("duration", 10).put("savedSlide", 3).put(
                "pages", JSONArray().apply { notes.forEach { (p, n) -> put(JSONObject().put("idx", p).put("note", n)) } },
            ).toString()
    }

    private fun randomStart(r: Random): Start {
        val n = r.nextInt(1, 16)
        val labels = if (r.nextInt(5) == 0) null else {
            val out = ArrayList<String>()
            var next = 1
            for (i in 0 until n) out += when {
                i > 0 && r.nextInt(10) < 4 -> out[i - 1]
                // \againframe and similar: a label already used, further back.
                i > 1 && r.nextInt(10) == 0 -> out[r.nextInt(i - 1)]
                else -> (next++).toString()
            }
            out
        }
        // Overlays usually continue any earlier page of their slide; sometimes not (a section page).
        val continuation = Array(n) { BooleanArray(n) { r.nextInt(5) != 0 } }
        val continues = { x: Int, y: Int -> continuation[x][y] }
        val overlays = if (labels == null) emptySet() else (1 until n).filterTo(HashSet()) { labels[it] == labels[it - 1] && continues(it - 1, it) }
        val notes = (0 until n).filter { r.nextBoolean() }.associateWith { "note $it" }
        val slides = Slides(labels, n) { it in overlays }
        val ink = (0 until n).filter { slides.firstPageOf(it) == it && r.nextBoolean() }.associateWith { listOf<PageElement>(Elements.shape(it)) }
        return Start(labels, overlays, notes, ink).also {
            it.pageCount = n
            it.continues = continues
        }
    }

    /** One edit as the overview makes it: slides of the navigation in use (overlays on or off). */
    private fun randomEdit(r: Random, s: PageStructure): PageEdit {
        val nav = if (r.nextBoolean()) s.slides else Slides.single(s.pageCount)
        val firsts = (0 until s.pageCount).filter { nav.firstPageOf(it) == it }
        val from = r.nextInt(firsts.size)
        val pages = firsts[from]..nav.lastPageOf(firsts[from])
        return when (r.nextInt(if (firsts.size > 1) 3 else 1)) {
            0 -> PageEdit.InsertBlank(pages.last)
            1 -> PageEdit.Delete(pages)
            else -> {
                val to = r.nextInt(firsts.size)
                // As Overview.move.
                PageEdit.Move(pages, if (to < from) firsts[to] else firsts.getOrNull(to + 1) ?: s.pageCount)
            }
        }
    }

    /** What PdfInkStore.rearrange does: the blank pages measured, the candidate overlays checked on the "text". */
    private fun rearranged(start: Start, s: PageStructure, slots: List<PageSlot>, ids: List<Int>): PageStructure {
        val labels = PageEdits.labels(slots, s.labels)
        val newIds = slots.map { if (it is PageSlot.Page) ids[it.index] else -1 }
        val confirmed = PageEdits.overlayCandidates(slots, labels).filterTo(HashSet()) { j -> start.continues(newIds[j - 1], newIds[j]) }
        val measured = slots.map { if (it is PageSlot.Blank) it.copy(like = -1, width = 362.83f, height = 272.13f) else it }
        return s.edited(slots, measured, confirmed)
    }

    @Test
    fun `edits agree with the model`() = Property.check("page edits", runs = 400) { r ->
        val start = randomStart(r)
        val n = start.pageCount
        var s = PageStructure(n, start.labels, start.overlays, start.ink, PdfpcNotes.read(start.notesJson, n, start.labels), null, n)
        // Model: starting page shown by each page, -1 for a blank page.
        var ids = (0 until n).toList()
        var current = r.nextInt(n)
        repeat(r.nextInt(1, 12)) {
            val edit = randomEdit(r, s)
            val slots = PageEdits.slots(edit, s.pageCount, s.labels)
            val before = s
            val oldIds = ids
            s = rearranged(start, s, slots, oldIds)
            ids = slots.map { if (it is PageSlot.Page) oldIds[it.index] else -1 }
            val newIndex = PageEdits.newIndexOf(slots, before.pageCount)
            val msg = "after $edit on ${oldIds}"

            // Page count.
            assertEquals(msg, ids.size, s.pageCount)
            when (edit) {
                is PageEdit.Move -> assertEquals(msg, before.pageCount, s.pageCount)
                is PageEdit.InsertBlank -> assertEquals(msg, before.pageCount + 1, s.pageCount)
                is PageEdit.Delete -> assertEquals(msg, before.pageCount - edit.pages.count(), s.pageCount)
            }
            assertTrue(msg, s.pageCount >= 1)

            // Order relative to the file on disk (session), null exactly when it is the file's.
            val layout = s.layout
            if (ids == (0 until n).toList()) {
                assertNull(msg, layout)
            } else {
                assertNotNull(msg, layout)
                assertEquals(msg, ids, layout!!.map { if (it is PageSlot.Page) it.index else -1 })
                assertTrue(msg, PageEdits.isValid(layout, n))
                assertEquals(msg, layout, SessionStore.layoutFromJson(JSONArray(SessionStore.layoutToJson(layout).toString())))
            }

            // Labels: those of the starting pages; a new blank page has a label no other page has.
            if (start.labels == null) {
                assertNull(msg, s.labels)
            } else {
                val labels = s.labels!!
                ids.forEachIndexed { j, id -> if (id >= 0) assertEquals(msg, start.labels[id], labels[j]) }
                if (edit is PageEdit.InsertBlank) {
                    val j = edit.after + 1
                    assertEquals(msg, 1, labels.count { it == labels[j] })
                }
            }

            // Overlays: exactly those that reading the file in this order would find.
            assertEquals(msg, start.overlaysOf(ids), s.overlays)

            // Ink: on the first page of the slide of the first surviving page of its slide; it disappears only with
            // the whole slide; slides that become one keep all of it. Always on first pages of slides.
            for ((k, v) in before.ink) {
                val survivor = before.slides.pagesOf(k).firstOrNull { newIndex[it] >= 0 }
                val all = s.ink.values.flatten()
                if (survivor == null) {
                    assertTrue(msg, v.none { e -> all.any { it === e } })
                } else {
                    val at = s.ink[s.slides.firstPageOf(newIndex[survivor])].orEmpty()
                    assertTrue(msg, v.all { e -> at.any { it === e } })
                }
            }
            for (k in s.ink.keys) assertEquals(msg, k, s.slides.firstPageOf(k))

            // Notes: those of the starting pages, also after writing the file and reading it back with the new labels.
            val notes = s.pdfpc!!
            ids.forEachIndexed { j, id -> assertEquals(msg, start.notes[id], notes.noteFor(j)) }
            val json = notes.toJson(s.pageCount, s.labels)
            val reread = PdfpcNotes.read(json, s.pageCount, s.labels)!!
            for (j in 0 until s.pageCount) assertEquals("$msg, page $j of $json", notes.noteFor(j), reread.noteFor(j))
            assertFalse(msg, JSONObject(json).has("savedSlide"))
            assertEquals(msg, 10, JSONObject(json).getInt("duration"))

            // Current page: stays on its page if it survived, otherwise on a neighbor; always valid.
            val oldCurrent = current
            current = PageEdits.follow(current, newIndex)
            assertTrue(msg, current in 0 until s.pageCount)
            if (newIndex[oldCurrent] >= 0) assertEquals(msg, newIndex[oldCurrent], current)
        }

        // Reopening from the session: the saved order applied to the file on disk gives the same document.
        val layout = s.layout ?: return@check
        val restored = rearranged(
            start, PageStructure(n, start.labels, start.overlays, start.ink, PdfpcNotes.read(start.notesJson, n, start.labels), null, n),
            layout, (0 until n).toList(),
        )
        assertEquals(s.labels, restored.labels)
        assertEquals(s.overlays, restored.overlays)
        assertEquals(s.layout, restored.layout)
        for (j in 0 until s.pageCount) assertEquals(s.pdfpc!!.noteFor(j), restored.pdfpc!!.noteFor(j))
    }
}
