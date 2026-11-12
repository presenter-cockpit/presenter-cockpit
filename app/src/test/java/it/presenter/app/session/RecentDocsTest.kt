package it.presenter.app.session

import androidx.test.core.app.ApplicationProvider
import it.presenter.app.doc.DocSource
import it.presenter.app.settings.Prefs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecentDocsTest {
    private val prefs = Prefs(ApplicationProvider.getApplicationContext())

    private fun names() = RecentDocs.read(prefs).map { it.displayName }

    @Test
    fun `the last opened first, without repeats, at most eight`() {
        for (i in 1..10) RecentDocs.add(prefs, DocSource.Local(File("/home/u/talk$i.pdf")))
        assertEquals(RecentDocs.MAX, names().size)
        assertEquals("talk10.pdf", names().first())
        RecentDocs.add(prefs, DocSource.Local(File("/home/u/talk5.pdf")))
        assertEquals(listOf("talk5.pdf", "talk10.pdf", "talk9.pdf"), names().take(3))
        assertEquals(1, names().count { it == "talk5.pdf" })
        RecentDocs.add(prefs, DocSource.Remote("s1", "/home/u/remote.pdf"))
        assertEquals("remote.pdf", names().first())
    }
}
