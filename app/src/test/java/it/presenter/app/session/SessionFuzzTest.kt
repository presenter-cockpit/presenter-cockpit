package it.presenter.app.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import it.presenter.app.doc.DocSource
import it.presenter.app.doc.DocStamp
import it.presenter.app.doc.PageSlot
import it.presenter.app.doc.PdfInkStore
import it.presenter.app.tabs.WorkTabStore
import it.presenter.app.testing.BeamerPdf
import it.presenter.app.testing.Elements
import it.presenter.app.testing.Fuzz
import it.presenter.app.testing.Property
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Damaged files read at start: saved session, its annotations, saved tabs, PDFs. Reading them must never
 * crash the app (Robolectric: real storage and PdfBox's fonts).
 */
@RunWith(RobolectricTestRunner::class)
class SessionFuzzTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun writeSample() = SessionStore.write(
        context,
        SessionStore.snapshotOf(
            listOf(
                SessionStore.SavedDocument(
                    DocSource.Local(File("/sdcard/a.pdf")), 2, true, mapOf(0 to List(3) { Elements.shape(it) }, 4 to listOf(Elements.text("x"))),
                    false, listOf(PageSlot.Page(1), PageSlot.Blank(-1, "2.1", 100f, 50f)), DocStamp(1, 2),
                ),
                SessionStore.SavedDocument(DocSource.Remote("s", "/p.pdf"), 0, false, mapOf(1 to listOf(Elements.shape(9)))),
            ),
            0, FloatArray(10), listOf(JSONObject().put("type", "browser").put("url", "https://e.org")), 0, listOf("doc:0"),
        ),
    )

    @Test
    fun `damaged session files`() = Property.check("fuzz session", runs = 300) { r ->
        SessionStore.clear(context)
        writeSample()
        val dir = File(context.filesDir, "session")
        val target = listOf("session.json", "doc-0.ink", "doc-1.ink").random(r).let { File(dir, it) }
        target.writeBytes(Fuzz.mutate(r, target.readBytes()))
        SessionStore.read(context)?.let { saved ->
            // Tabs too are rebuilt from what was read.
            saved.work.forEach { WorkTabStore.fromJson(it, tmp.newFile())?.close() }
        }
    }

    @Test
    fun `an annotations file asking for gigabytes`() {
        SessionStore.clear(context)
        writeSample()
        // One page, one element of 2 GB.
        File(context.filesDir, "session/doc-0.ink").writeBytes(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0x7f, -1, -1, -1))
        assertNull(SessionStore.read(context)!!.documents[0].unsaved)
    }

    @Test
    fun `damaged PDFs`() {
        val good = tmp.newFile("good.pdf")
        BeamerPdf.write(good, listOf(BeamerPdf.Page("1", "one"), BeamerPdf.Page("1", "one two"), BeamerPdf.Page("2", "three")))
        val bytes = good.readBytes()
        val file = tmp.newFile("bad.pdf")
        Property.check("fuzz pdf", runs = 150) { r ->
            file.writeBytes(Fuzz.mutate(r, bytes))
            // Errors are allowed (the PDF is then shown without saved annotations), as long as they are exceptions.
            try {
                PdfInkStore.inspect(file)
            } catch (_: Exception) {
            }
        }
    }
}
