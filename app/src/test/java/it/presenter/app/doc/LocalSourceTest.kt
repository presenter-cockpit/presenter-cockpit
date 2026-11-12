package it.presenter.app.doc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** PDF on the tablet's storage ([DocSource.Local]): copy, atomic overwrite, notes next to it, stamp. */
@RunWith(RobolectricTestRunner::class)
class LocalSourceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `copy and overwrite leave no temporary files`() {
        val pdf = tmp.newFile("talk.pdf").apply { writeBytes(ByteArray(1000) { it.toByte() }) }
        val source = DocSource.Local(pdf)
        val copy = tmp.newFile("copy.pdf")
        source.copyTo(context, copy)
        assertArrayEquals(pdf.readBytes(), copy.readBytes())

        val before = source.stamp(context)!!
        assertEquals(1000L, before.size)
        // Shorter content: nothing of the old file must be left at the end.
        val new = tmp.newFile("new.pdf").apply { writeBytes(ByteArray(10) { 7 }) }
        source.overwriteWith(context, new)
        assertArrayEquals(ByteArray(10) { 7 }, pdf.readBytes())
        assertNotEquals(before, source.stamp(context))
        assertEquals(setOf("talk.pdf", "copy.pdf", "new.pdf"), tmp.root.list()!!.toSet())
    }

    @Test
    fun `notes written atomically next to the PDF and read back`() {
        val pdf = tmp.newFile("talk.pdf")
        val source = DocSource.Local(pdf)
        assertNull(source.readPdfpc(context))
        source.writePdfpc(context, "{\"pages\": [], \"note\": \"è\"}")
        assertEquals("{\"pages\": [], \"note\": \"è\"}", source.readPdfpc(context))
        assertTrue(File(tmp.root, "talk.pdfpc").isFile)
        assertEquals(setOf("talk.pdf", "talk.pdfpc"), tmp.root.list()!!.toSet())
    }

    @Test
    fun `a failed overwrite keeps the original file`() {
        val dir = tmp.newFolder("locked")
        val pdf = File(dir, "talk.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        dir.setWritable(false)
        try {
            val result = runCatching { DocSource.Local(pdf).overwriteWith(context, tmp.newFile("new.pdf")) }
            assertTrue(result.isFailure)
            assertArrayEquals(byteArrayOf(1, 2, 3), pdf.readBytes())
            assertEquals(listOf("talk.pdf"), dir.list()!!.toList())
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun `stamp and identity`() {
        val pdf = File(tmp.root, "gone.pdf")
        assertNull(DocSource.Local(pdf).stamp(context))
        assertTrue(DocSource.Local(File(tmp.root, "x/../gone.pdf").canonicalFile).sameAs(DocSource.Local(pdf)))
        assertFalse(DocSource.Local(pdf).sameAs(DocSource.Local(File(tmp.root, "other.pdf"))))
    }
}
