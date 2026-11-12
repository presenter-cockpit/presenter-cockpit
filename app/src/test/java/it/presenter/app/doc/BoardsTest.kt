package it.presenter.app.doc

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardsTest {
    @Test
    fun `blank A4 pages in landscape, with a free name`() {
        val dir = Files.createTempDirectory("boards").toFile()
        try {
            val first = Boards.create(dir, "Whiteboard 2026-01-02 10.30")
            val second = Boards.create(dir, "Whiteboard 2026-01-02 10.30")
            assertEquals("Whiteboard 2026-01-02 10.30.pdf", first.name)
            assertEquals("Whiteboard 2026-01-02 10.30 (2).pdf", second.name)
            PdfInkStore.load(first).use { doc ->
                assertEquals(Boards.PAGES, doc.numberOfPages)
                val box = doc.getPage(Boards.PAGES - 1).mediaBox
                assertEquals(842f, box.width, 1f)
                assertEquals(595f, box.height, 1f)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
