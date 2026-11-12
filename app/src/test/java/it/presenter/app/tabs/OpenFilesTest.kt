package it.presenter.app.tabs

import it.presenter.app.doc.DocStamp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFilesTest {
    @Test
    fun `editing a preview keeps it open and marks it unsaved`() {
        val f = OpenFile("/a/b.c", "x", preview = true)
        assertFalse(f.isDirty)
        f.edit("x")
        assertTrue(f.preview)
        f.edit("xy")
        assertFalse(f.preview)
        assertTrue(f.isDirty)
    }

    @Test
    fun `files saved in the session and read back`() {
        val a = OpenFile("/p/a.py", "print(1)").apply { cursor = 3; firstVisibleLine = 2 }
        val b = OpenFile("/p/b.py", "x = 1", preview = true).apply { edit("x = 2") }
        val c = OpenFile("/p/c.py", "", preview = true)
        val o = JSONObject().put("files", WorkTabStore.filesToJson(listOf(a, b, c))).put("active", 1)
        val (files, active) = WorkTabStore.editorFiles(JSONObject(o.toString()))
        assertEquals(1, active)
        assertEquals(listOf("/p/a.py", "/p/b.py", "/p/c.py"), files.map { it.path })
        // Without changes: the text is read on connecting, meanwhile it is not to be saved.
        assertFalse(files[0].loaded)
        assertFalse(files[0].isDirty)
        assertEquals(2, files[0].firstVisibleLine)
        // With changes: the text is there at once and is to be saved until the disk is read.
        assertTrue(files[1].loaded)
        assertEquals("x = 2", files[1].text)
        assertTrue(files[1].isDirty)
        assertTrue(files[2].preview)
        files[0].loadedFromDisk("print(1)")
        assertEquals("print(1)", files[0].text)
        assertEquals(3, files[0].cursor)
        files[1].loadedFromDisk("x = 2")
        assertFalse(files[1].isDirty)
    }

    @Test
    fun `older session with a single file`() {
        val o = JSONObject().put("open", "/p/main.c").put("unsaved", "int x;").put("cursor", 99).put("line", 4)
        val (files, active) = WorkTabStore.editorFiles(o)
        assertEquals(0, active)
        assertEquals("/p/main.c", files.single().path)
        assertEquals("int x;", files.single().text)
        assertEquals(6, files.single().cursor)
        assertNull(files.single().savedText)
        assertTrue(WorkTabStore.editorFiles(JSONObject()).first.isEmpty())
    }

    @Test
    fun `discarding the changes goes back to the disk text`() {
        val f = OpenFile("/home/u/a.txt", "disk")
        f.edit("changed")
        assertTrue(f.isDirty)
        f.discardChanges()
        assertEquals("disk", f.text)
        assertFalse(f.isDirty)
        // Restored offline with changes and not read yet: no longer counted as changed, read again later.
        val pending = OpenFile("/home/u/b.txt", "unsaved", savedText = null)
        assertTrue(pending.isDirty)
        pending.discardChanges()
        assertFalse(pending.isDirty)
        pending.loadedFromDisk("disk")
        assertEquals("disk", pending.text)
    }

    @Test
    fun `file changed by someone else`() {
        val stamp = DocStamp(5, 1000)
        // Without changes here: the new text comes in.
        val clean = OpenFile("/home/u/a.txt", "disk")
        clean.diskChanged("new", stamp)
        assertEquals("new", clean.text)
        assertFalse(clean.isDirty)
        assertFalse(clean.changedOnDisk)
        // With changes: they stay, and the file is marked; reloading takes the new text.
        val dirty = OpenFile("/home/u/b.txt", "disk").apply { edit("mine") }
        dirty.diskChanged("theirs", stamp)
        assertEquals("mine", dirty.text)
        assertTrue(dirty.changedOnDisk)
        dirty.reloadFromDisk()
        assertEquals("theirs", dirty.text)
        assertFalse(dirty.isDirty)
        assertFalse(dirty.changedOnDisk)
        // Rewritten with the same text: nothing changes.
        val same = OpenFile("/home/u/c.txt", "disk").apply { edit("mine") }
        same.diskChanged("disk", stamp)
        assertFalse(same.changedOnDisk)
        assertEquals(stamp, same.diskStamp)
        // Saved here: the mark goes away.
        dirty.edit("again")
        dirty.diskChanged("other", stamp)
        dirty.saved("again", stamp)
        assertFalse(dirty.changedOnDisk)
        assertFalse(dirty.isDirty)
    }
}
