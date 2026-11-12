package it.presenter.app.tabs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebPdfTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `PDF links by type, address or header`() {
        assertTrue(WebPdf.isPdf("https://example.org/get?id=3", null, "application/pdf"))
        assertTrue(WebPdf.isPdf("https://example.org/talk.PDF?v=2#page=3", null, "application/octet-stream"))
        assertTrue(WebPdf.isPdf("https://example.org/get?id=3", "attachment; filename=\"slides.pdf\"", "application/octet-stream"))
        assertFalse(WebPdf.isPdf("https://example.org/archive.zip", null, "application/zip"))
    }

    @Test
    fun `one folder per address, the file named as the PDF`() {
        val a = WebPdf.fileFor(context, "https://example.org/a/talk.pdf", null, "application/pdf")
        val b = WebPdf.fileFor(context, "https://example.org/b/talk.pdf", null, "application/pdf")
        assertEquals("talk.pdf", a.name)
        assertNotEquals(a, b)
        assertEquals(a, WebPdf.fileFor(context, "https://example.org/a/talk.pdf", null, "application/pdf"))
        assertTrue(WebPdf.fileFor(context, "https://example.org/get?id=3", null, "application/pdf").name.endsWith(".pdf"))
    }
}
