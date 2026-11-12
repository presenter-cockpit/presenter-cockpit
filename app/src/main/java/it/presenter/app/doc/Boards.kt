package it.presenter.app.doc

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File

/**
 * Whiteboards: PDFs of blank A4 pages in landscape (user's choice: no templates, [PAGES] pages to start),
 * kept in the app's files. Pages are added or removed from the overview, as in any PDF, and the annotations
 * are saved in the file.
 */
object Boards {
    const val PAGES = 10

    /** Creates the board [name].pdf in [dir]; if the name is taken, "name (2).pdf" and so on. */
    fun create(dir: File, name: String): File {
        dir.mkdirs()
        var file = File(dir, "$name.pdf")
        var n = 2
        while (file.exists()) file = File(dir, "$name (${n++}).pdf")
        PDDocument().use { doc ->
            repeat(PAGES) { doc.addPage(PDPage(PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width))) }
            doc.save(file)
        }
        return file
    }
}
