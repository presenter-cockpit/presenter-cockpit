package it.presenter.app.testing

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import it.presenter.app.doc.PdfInkStore
import java.io.File

/**
 * Small PDF made like Beamer's: 16:9 pages with text and labels, overlays = consecutive pages with the
 * same label whose text continues the previous one. Needs PdfBox's fonts (Robolectric test, the app
 * loads them at start).
 */
object BeamerPdf {
    class Page(val label: String, val text: String)

    fun write(file: File, pages: List<Page>) {
        PDDocument().use { doc ->
            val labels = PDPageLabels(doc)
            pages.forEachIndexed { i, p ->
                val page = PDPage(PDRectangle(362.83f, 272.13f))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { c ->
                    c.beginText()
                    c.setFont(PDType1Font.HELVETICA, 10f)
                    c.newLineAtOffset(20f, 240f)
                    c.showText(p.text)
                    c.endText()
                }
                labels.setLabelItem(i, PDPageLabelRange().apply { prefix = p.label })
            }
            doc.documentCatalog.pageLabels = labels
            doc.save(file)
        }
    }

    /** Text of each page, trimmed (empty for a blank page). */
    fun texts(file: File): List<String> = PdfInkStore.load(file).use { doc ->
        val stripper = PDFTextStripper()
        (1..doc.numberOfPages).map { n ->
            stripper.startPage = n
            stripper.endPage = n
            stripper.getText(doc).trim()
        }
    }

    /**
     * Structure check of [file] with qpdf, an independent reader (if installed on the PC: otherwise
     * nothing). Warnings count as errors.
     */
    fun qpdfCheck(file: File) {
        val qpdf = listOf("/usr/bin/qpdf", "/usr/local/bin/qpdf").firstOrNull { File(it).canExecute() } ?: return
        val p = ProcessBuilder(qpdf, "--check", file.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "qpdf --check ${file.name}:\n$out" }
    }

    /** Height / width of each page as displayed, as the app measures it. */
    fun aspects(file: File): FloatArray = PdfInkStore.load(file).use { doc ->
        FloatArray(doc.numberOfPages) { i ->
            val p = doc.getPage(i)
            val box = p.cropBox
            if (p.rotation % 180 != 0) box.width / box.height else box.height / box.width
        }
    }
}
