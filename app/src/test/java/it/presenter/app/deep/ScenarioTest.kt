package it.presenter.app.deep

import it.presenter.app.doc.PageEdit
import it.presenter.app.tabs.BrowserTab
import it.presenter.app.tabs.EditorTab
import it.presenter.app.workspace.Workspace
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whole flows as the user does them, checking after each step that what the app shows (unsaved mark,
 * text, open tabs) matches the files. Each bug of this kind found by hand becomes a scenario here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowPdfRenderer::class, ShadowPdfRendererPage::class])
class ScenarioTest {
    private val rig = AppRig()

    @After
    fun closeAll() = rig.session.closeEverything()

    @Test
    fun `undoing back to the opened state clears the unsaved mark`() {
        val doc = rig.open(rig.samplePdf())
        assertFalse(doc.isDirty)
        rig.draw(doc)
        assertTrue(doc.isDirty)
        rig.session.undo()
        assertFalse(doc.isDirty)
        rig.session.redo()
        assertTrue(doc.isDirty)
    }

    @Test
    fun `after saving, undo marks the document again and redo clears it`() {
        val file = rig.samplePdf()
        val doc = rig.open(file)
        rig.draw(doc)
        rig.flows.save(doc)
        assertFalse(doc.isDirty)
        assertEquals(rig.annotations(doc), rig.annotationsOnDisk(file, doc))
        rig.session.undo()
        assertTrue(doc.isDirty)
        rig.session.redo()
        assertFalse(doc.isDirty)
    }

    @Test
    fun `saved annotations come back when the file is opened again`() {
        val file = rig.samplePdf()
        val doc = rig.open(file)
        rig.draw(doc, page = 0)
        rig.goTo(doc, 5)
        rig.draw(doc, page = 5)
        val shown = rig.annotations(doc)
        rig.flows.save(doc)
        rig.flows.closeDocument(doc)
        val again = rig.open(file)
        assertEquals(shown, rig.annotations(again))
        assertFalse(again.isDirty)
    }

    @Test
    fun `closing asks only when there is something to save`() {
        val doc = rig.open(rig.samplePdf())
        rig.flows.requestClose(doc)
        assertTrue(doc !in rig.session.documents)
        val other = rig.open(rig.samplePdf("other.pdf"))
        rig.draw(other)
        rig.flows.requestClose(other)
        assertEquals(other, rig.flows.closing)
        assertTrue(other in rig.session.documents)
    }

    @Test
    fun `moved pages stay to save until saved, whatever is undone`() {
        val file = rig.samplePdf()
        rig.open(file).let { rig.flows.editPages(it, PageEdit.Move(12..12, 0)) }
        val doc = rig.documentOf(file)!!
        assertTrue(doc.isDirty)
        rig.session.undo()
        assertTrue(doc.isDirty)
        rig.flows.save(doc)
        assertFalse(doc.isDirty)
    }

    @Test
    fun `a blank page drawn on and deleted again leaves nothing to save`() {
        val file = rig.samplePdf()
        rig.open(file).let { rig.flows.editPages(it, PageEdit.InsertBlank(0)) }
        var doc = rig.documentOf(file)!!
        rig.goTo(doc, 1)
        rig.draw(doc)
        assertTrue(doc.isDirty)
        rig.flows.editPages(doc, PageEdit.Delete(1..1))
        doc = rig.documentOf(file)!!
        assertEquals(rig.annotationsOnDisk(file, doc), rig.annotations(doc))
        assertFalse(doc.isDirty)
    }

    @Test
    fun `unsaved annotations survive a restart, saved ones do not mark the document`() {
        val dirty = rig.samplePdf("dirty.pdf")
        val clean = rig.samplePdf("clean.pdf")
        rig.draw(rig.open(dirty))
        rig.open(clean).let { rig.draw(it); rig.flows.save(it) }
        val shown = rig.annotations(rig.documentOf(dirty)!!)
        rig.restart()
        val d = rig.documentOf(dirty)!!
        assertEquals(shown, rig.annotations(d))
        assertTrue(d.isDirty)
        assertFalse(rig.documentOf(clean)!!.isDirty)
    }

    @Test
    fun `don't save when switching workspace drops annotations and code changes`() {
        val pdf = rig.samplePdf()
        val dir = File(rig.app.filesDir, "code").apply { mkdirs() }
        val source = File(dir, "a.txt").apply { writeText("disk") }
        val doc = rig.open(pdf)
        val editor = EditorTab.local(dir.path)
        rig.session.openWork(editor)
        editor.open(source.path)
        rig.waitUntil("file read") { editor.active?.loaded == true }
        rig.session.saveAsWorkspace("one")

        rig.draw(doc)
        editor.active!!.edit("changed")
        val two = Workspace(name = "two")
        rig.flows.requestWorkspace(two)
        assertEquals(two, rig.flows.switchingTo)
        rig.flows.switchingTo = null
        rig.flows.discardAndOpenWorkspace(two)
        assertTrue(rig.session.documents.isEmpty())

        rig.flows.requestWorkspace(rig.session.workspaces.first { it.name == "one" })
        assertNull("nothing to save: no question", rig.flows.switchingTo)
        rig.idle()
        val back = rig.session.workTabs.filterIsInstance<EditorTab>().single()
        rig.waitUntil("file reopened") { back.active?.loaded == true && back.active?.savedText != null }
        assertEquals("disk", back.active!!.text)
        assertFalse(back.hasUnsaved)
        val reopened = rig.documentOf(pdf)
        assertNotNull(reopened)
        assertFalse(reopened!!.isDirty)
        assertEquals("disk", source.readText())
    }

    @Test
    fun `save when switching workspace writes annotations and code`() {
        val pdf = rig.samplePdf()
        val dir = File(rig.app.filesDir, "code").apply { mkdirs() }
        val source = File(dir, "a.txt").apply { writeText("disk") }
        val doc = rig.open(pdf)
        val editor = EditorTab.local(dir.path)
        rig.session.openWork(editor)
        editor.open(source.path)
        rig.waitUntil("file read") { editor.active?.loaded == true }
        rig.session.saveAsWorkspace("one")
        rig.draw(doc)
        val shown = rig.annotations(doc)
        editor.active!!.edit("changed")

        rig.flows.saveAndOpenWorkspace(Workspace(name = "two"))
        rig.waitUntil("workspace switched") { rig.session.documents.isEmpty() && rig.session.workTabs.isEmpty() }
        assertEquals("changed", source.readText())
        assertEquals(shown, rig.annotationsOnDisk(pdf, doc))
    }

    @Test
    fun `a code file changed outside the IDE comes in, or waits if changed here too`() {
        val dir = File(rig.app.filesDir, "code").apply { mkdirs() }
        val a = File(dir, "a.txt").apply { writeText("one") }
        val b = File(dir, "b.txt").apply { writeText("one") }
        val editor = EditorTab.local(dir.path)
        rig.session.openWork(editor)
        editor.open(a.path, pin = true)
        rig.waitUntil("a read") { editor.active?.path == a.path }
        editor.open(b.path, pin = true)
        rig.waitUntil("b read") { editor.active?.path == b.path }
        val fa = editor.files.first { it.path == a.path }
        val fb = editor.files.first { it.path == b.path }
        fb.edit("mine")
        // A terminal (git pull…) rewrites both files and adds one.
        for ((f, text) in listOf(a to "two", b to "theirs")) {
            f.writeText(text)
            f.setLastModified(f.lastModified() + 5_000)
        }
        File(dir, "new.txt").writeText("")
        editor.checkDisk()
        rig.waitUntil("files checked") { fa.text == "two" && fb.changedOnDisk }
        assertEquals("mine", fb.text)
        assertFalse(fa.isDirty)
        rig.waitUntil("tree listed again") { editor.children[dir.path].orEmpty().any { it.name == "new.txt" } }
        // Told: saving now overwrites the other version.
        editor.save(fb)
        rig.waitUntil("saved") { !fb.isDirty }
        assertEquals("mine", b.readText())
        assertFalse(fb.changedOnDisk)
    }

    @Test
    fun `saving does not overwrite a code file changed outside the IDE without telling`() {
        val dir = File(rig.app.filesDir, "code2").apply { mkdirs() }
        val c = File(dir, "c.txt").apply { writeText("one") }
        val editor = EditorTab.local(dir.path)
        rig.session.openWork(editor)
        editor.open(c.path, pin = true)
        rig.waitUntil("c read") { editor.active?.path == c.path }
        val fc = editor.active!!
        fc.edit("mine")
        c.writeText("theirs")
        c.setLastModified(c.lastModified() + 5_000)
        var result: Boolean? = null
        editor.save(fc) { result = it }
        rig.waitUntil("save answered") { result != null }
        assertEquals(false, result)
        assertEquals("theirs", c.readText())
        assertTrue(fc.changedOnDisk)
        assertNotNull(editor.error)
        // Second save: the user knows, it overwrites.
        result = null
        editor.save(fc) { result = it }
        rig.waitUntil("saved again") { result != null }
        assertEquals(true, result)
        assertEquals("mine", c.readText())
    }

    @Test
    fun `closing the tab in front shows the one beside it in the bar`() {
        val one = rig.open(rig.samplePdf("one.pdf"))
        val web = BrowserTab("https://example.org")
        rig.session.openWork(web)
        val two = rig.open(rig.samplePdf("two.pdf"))
        // Bar: one, two, web. The web tab in front, closed: the one before it (it is the last), the PDF "two".
        rig.session.showWork(web)
        rig.session.closeWork(web, discard = true)
        assertNull(rig.session.activeWork)
        assertEquals(two, rig.session.current)
        // Bar: one, two, ide. Closing the PDF in front brings the IDE after it in front, with the nearest PDF behind.
        val ide = EditorTab.local(rig.app.filesDir.path)
        rig.session.openWork(ide)
        rig.session.switchTo(two)
        rig.session.finishTransition()
        rig.flows.closeDocument(two)
        assertEquals(ide, rig.session.activeWork)
        assertEquals(one, rig.session.current)
    }

    @Test
    fun `slides hidden from the overview are written to the pdfpc with the PDF`() {
        val file = rig.samplePdf()
        val pdfpc = File(file.path.removeSuffix(".pdf") + ".pdfpc")
        val before = pdfpc.readText()
        var doc = rig.open(file)
        val slide = doc.slides.firstPageOf(5)
        rig.flows.setHidden(doc, slide, hidden = true)
        assertTrue(doc.isHidden(slide))
        // In memory until the PDF is saved; undo does not take it back.
        assertTrue(doc.isDirty)
        assertEquals(before, pdfpc.readText())
        rig.session.undo()
        assertTrue(doc.isDirty)
        rig.flows.save(doc)
        assertFalse(doc.isDirty)
        rig.flows.closeDocument(doc)
        doc = rig.open(file)
        assertTrue(doc.isHidden(slide))
        // With pages moved and not saved, the .pdfpc follows the new order when saved.
        rig.flows.editPages(doc, PageEdit.Move(doc.pageCount - 1..doc.pageCount - 1, 0))
        doc = rig.documentOf(file)!!
        val moved = doc.slides.firstPageOf(slide + 1)
        assertTrue(doc.isHidden(moved))
        rig.flows.setHidden(doc, moved, hidden = false)
        rig.flows.save(doc)
        rig.flows.closeDocument(doc)
        doc = rig.open(file)
        assertFalse(doc.isHidden(moved))
    }

    @Test
    fun `notes and bookmarks edited on the tablet survive a restart and are saved with the PDF`() {
        val file = rig.samplePdf()
        val pdfpc = File(file.path.removeSuffix(".pdf") + ".pdfpc")
        val before = pdfpc.readText()
        var doc = rig.open(file)
        val slide = doc.slides.firstPageOf(7)
        rig.flows.setNote(doc, slide, "A new note with *Markdown*")
        rig.flows.setBookmark(doc, slide, on = true)
        // An overlay of the slide shows the same note and the same bookmark.
        assertEquals("A new note with *Markdown*", doc.noteFor(doc.slides.lastPageOf(slide)))
        assertTrue(doc.isBookmarked(doc.slides.lastPageOf(slide)))
        assertTrue(doc.isDirty)
        assertEquals(before, pdfpc.readText())
        // Not saved yet: a restart brings them back, still to save.
        rig.restart()
        doc = rig.documentOf(file)!!
        assertEquals("A new note with *Markdown*", doc.noteFor(slide))
        assertEquals(listOf(slide), doc.bookmarks)
        assertTrue(doc.isDirty)
        rig.flows.save(doc)
        rig.flows.closeDocument(doc)
        doc = rig.open(file)
        assertEquals("A new note with *Markdown*", doc.noteFor(slide))
        assertEquals(listOf(slide), doc.bookmarks)
        // Without a .pdfpc it is created when the PDF is saved.
        val board = it.presenter.app.doc.Boards.create(rig.app.filesDir, "board")
        val blank = rig.open(board)
        rig.flows.setNote(blank, 0, "First thoughts")
        assertFalse(File(rig.app.filesDir, "board.pdfpc").isFile)
        rig.flows.save(blank)
        assertTrue(File(rig.app.filesDir, "board.pdfpc").isFile)
    }
}
