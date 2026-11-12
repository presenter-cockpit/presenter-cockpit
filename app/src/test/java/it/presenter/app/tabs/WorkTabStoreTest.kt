package it.presenter.app.tabs

import it.presenter.app.remote.ServerProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Work tabs saved in the session and in workspaces ([WorkTabStore]). Terminals are left out: their
 * emulator is native code of termlib.
 */
@RunWith(RobolectricTestRunner::class)
class WorkTabStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun roundTrip(tab: WorkTab): WorkTab = WorkTabStore.fromJson(JSONObject(WorkTabStore.toJson(tab).toString()), tmp.newFile())!!

    @Test
    fun `local IDE with open files, unsaved text and tree state`() {
        val root = tmp.newFolder("project").path
        val editor = EditorTab.local(root)
        editor.showHidden = true
        editor.treeWidth = 333f
        editor.restoreExpanded(listOf("$root/src", "$root/docs"))
        editor.restoreFiles(
            listOf(
                OpenFile("$root/a.py", "print(1)", savedText = "print(0)"),
                OpenFile("$root/b.md", "# b", preview = true),
            ),
            activeIndex = 1,
        )
        val back = roundTrip(editor) as EditorTab
        assertEquals(editor.id, back.id)
        assertEquals(root, back.root)
        assertTrue(back.showHidden)
        assertEquals(333f, back.treeWidth, 0f)
        assertEquals(setOf("$root/src", "$root/docs"), back.expandedDirs.toSet())
        assertEquals(listOf("$root/a.py", "$root/b.md"), back.files.map { it.path })
        assertEquals("$root/b.md", back.openPath)
        // The unsaved change comes back; the clean file is reread from disk.
        assertEquals("print(1)", back.files[0].text)
        assertTrue(back.files[0].isDirty)
        assertTrue(back.files[1].preview)
        editor.close()
        back.close()
    }

    @Test
    fun `remote IDE keeps its root`() {
        val server = ServerProfile(name = "Lab", host = "h.example", user = "u", dir = "~/work")
        // Opened with "IDE here" on a subfolder: back there, not in the folder of the server profile.
        val editor = EditorTab.remote(server, tmp.newFile(), "/home/u/work/site")
        val back = roundTrip(editor) as EditorTab
        assertEquals("/home/u/work/site", back.root)
        assertEquals(server, back.server)
        // Saved before connecting (root unknown): the folder of the profile, decided on connecting.
        val fresh = roundTrip(EditorTab.remote(server, tmp.newFile())) as EditorTab
        assertEquals("", fresh.root)
        listOf(editor, back, fresh).forEach { it.close() }
    }

    @Test
    fun `browser with its position`() {
        val tab = BrowserTab("https://example.org/page").apply { scrollFraction = 0.25f }
        val back = roundTrip(tab) as BrowserTab
        assertEquals("https://example.org/page", back.url)
        assertEquals(0.25f, back.scrollFraction, 0f)
        assertEquals(tab.id, back.id)
    }

    @Test
    fun `unknown or broken entries are skipped`() {
        assertNull(WorkTabStore.fromJson(JSONObject().put("type", "spreadsheet"), tmp.newFile()))
        assertNull(WorkTabStore.fromJson(JSONObject().put("type", "browser"), tmp.newFile()))
        // A remote terminal without its server.
        assertNull(WorkTabStore.fromJson(JSONObject().put("type", "terminal"), tmp.newFile()))
    }
}
