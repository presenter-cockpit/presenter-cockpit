package it.presenter.app.doc

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Page reordering with PdfBox ([PdfInkStore.rearrange]) on a PDF built here. */
class PdfRearrangeTest {
    @Test
    fun `a removed page leaves the file even if the outline points to it`() {
        val src = File("build/tmp/rearrange-src.pdf").apply { parentFile?.mkdirs() }
        val dest = File("build/tmp/rearrange-dest.pdf")
        PDDocument().use { doc ->
            val outline = PDDocumentOutline()
            for (name in listOf("KEPT-PAGE", "REMOVED-PAGE")) {
                val page = PDPage()
                // Not compressed: the mark is searched in the bytes of the file.
                page.setContents(PDStream(doc, "% $name\n0 0 m 10 10 l S\n".byteInputStream()))
                doc.addPage(page)
                outline.addLast(PDOutlineItem().apply { title = name; destination = PDPageFitDestination().apply { this.page = page } })
            }
            doc.documentCatalog.documentOutline = outline
            doc.save(src)
        }
        assertTrue("REMOVED-PAGE\n" in src.readText(Charsets.ISO_8859_1))
        PdfInkStore.rearrange(src, dest, PageEdits.slots(PageEdit.Delete(1..1), 2, null), null)
        val text = dest.readText(Charsets.ISO_8859_1)
        assertTrue("KEPT-PAGE\n" in text)
        assertFalse("REMOVED-PAGE\n" in text)
        PdfInkStore.load(dest).use { assertEquals(1, it.numberOfPages) }
    }
}
