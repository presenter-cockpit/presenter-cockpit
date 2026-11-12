package it.presenter.app.testing

import java.io.File

/**
 * The sample talk in `src/test/resources/sample/`: a real Beamer PDF made with LaTeX (`talk.tex`, invented
 * text) and its PDFPC notes. 17 pages, labels "1"…"10": slides with two and three overlays, and three section
 * start pages that Beamer labels like the slide before them (pages 3, 10, 14). Notes on the first page of 7
 * slides; none on pages 0 and 1.
 */
object SampleTalk {
    const val PAGES = 17

    /** Section start pages: same label as the page before, different text. */
    val SECTION_PAGES = listOf(3, 10, 14)

    /** Pages that are overlays of the page before (same label, text that continues it). */
    val OVERLAYS = setOf(5, 7, 8, 12)

    val notesText: String get() = text("talk.pdfpc")

    /** Copy of the PDF in [dir] (reading may rewrite the file it is given). */
    fun pdf(dir: File): File = File(dir, "talk.pdf").also { out ->
        SampleTalk::class.java.getResourceAsStream("/sample/talk.pdf")!!.use { input -> out.outputStream().use { input.copyTo(it) } }
    }

    private fun text(name: String): String =
        SampleTalk::class.java.getResourceAsStream("/sample/$name")!!.bufferedReader().readText()
}
