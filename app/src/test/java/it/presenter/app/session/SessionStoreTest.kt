package it.presenter.app.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import it.presenter.app.doc.DocSource
import it.presenter.app.doc.DocStamp
import it.presenter.app.doc.PageSlot
import it.presenter.app.testing.Elements
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Session written and read back ([SessionStore.write], [SessionStore.read]) in the app's real storage. */
@RunWith(RobolectricTestRunner::class)
class SessionStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() = SessionStore.clear(context)

    private val layout = listOf(PageSlot.Page(2), PageSlot.Blank(-1, "1.1", 842f, 595.5f), PageSlot.Page(0))

    private fun sample() = SessionStore.snapshotOf(
        listOf(
            SessionStore.SavedDocument(
                DocSource.Local(File("/sdcard/a.pdf")), 3, true, mapOf(0 to listOf(Elements.shape(1), Elements.text("è x"))), true,
                layout, DocStamp(1234, 5678),
            ),
            SessionStore.SavedDocument(DocSource.Local(File("/sdcard/b.pdf")), 0, false, null),
            // Kept aside (file missing) with an empty set of annotations: still to save.
            SessionStore.SavedDocument(DocSource.Remote("srv", "/home/u/c.pdf"), 7, false, emptyMap()),
        ),
        active = 1,
        viewport = FloatArray(10) { it * 0.5f },
        work = listOf(JSONObject().put("type", "browser").put("url", "https://example.org")),
        activeWork = 0,
        order = listOf("doc:0", "work:x"),
    )

    @Test
    fun `everything comes back as written`() {
        SessionStore.write(context, sample())
        val saved = SessionStore.read(context)!!
        assertEquals(1, saved.active)
        assertArrayEquals(FloatArray(10) { it * 0.5f }, saved.viewport, 0f)
        assertEquals("https://example.org", saved.work.single().getString("url"))
        assertEquals(0, saved.activeWork)
        assertEquals(listOf("doc:0", "work:x"), saved.order)

        val (a, b, c) = saved.documents
        assertEquals("/sdcard/a.pdf", (a.source as DocSource.Local).file.path)
        assertEquals(3, a.page)
        assertEquals(true, a.notesVisible)
        assertEquals(true, a.inverted)
        assertEquals(layout, a.layout)
        assertEquals(DocStamp(1234, 5678), a.layoutStamp)
        assertEquals(Elements.bytes(mapOf(0 to listOf(Elements.shape(1), Elements.text("è x")))), Elements.bytes(a.unsaved!!))

        assertNull(b.unsaved)
        assertNull(b.layout)
        assertNull(b.layoutStamp)

        assertEquals("/home/u/c.pdf", (c.source as DocSource.Remote).path)
        assertEquals(emptyMap<Int, Any>(), c.unsaved)
    }

    @Test
    fun `interrupted between the two renames the previous session is read`() {
        SessionStore.write(context, sample())
        // State left by [SessionStore.write] stopped after moving the old session aside.
        File(context.filesDir, "session").renameTo(File(context.filesDir, "session.old"))
        assertEquals(3, SessionStore.read(context)!!.documents.size)
    }

    @Test
    fun `a damaged annotations file loses only those annotations`() {
        SessionStore.write(context, sample())
        File(context.filesDir, "session/doc-0.ink").writeBytes(byteArrayOf(0, 0, 0, 5, 1))
        val saved = SessionStore.read(context)!!
        assertNull(saved.documents[0].unsaved)
        assertEquals(layout, saved.documents[0].layout)
        assertEquals(3, saved.documents.size)
    }

    @Test
    fun `another version or damaged json gives no session`() {
        SessionStore.write(context, sample())
        val json = File(context.filesDir, "session/session.json")
        json.writeText(JSONObject(json.readText()).put("version", 99).toString())
        assertNull(SessionStore.read(context))
        json.writeText("{\"version\": 1, \"documents\": [")
        assertNull(SessionStore.read(context))
    }

    @Test
    fun `a second write replaces the first`() {
        SessionStore.write(context, sample())
        SessionStore.write(context, SessionStore.snapshotOf(emptyList(), -1, FloatArray(10), emptyList(), -1, emptyList()))
        val saved = SessionStore.read(context)!!
        assertEquals(0, saved.documents.size)
        assertEquals(listOf("session.json"), File(context.filesDir, "session").list()!!.toList())
    }
}
