package it.presenter.app.ui

import it.presenter.app.testing.SampleTalk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `escapes, lists and styles`() {
        val s = Markdown.render("- the value x\\_(max)\n- 2\\* with **bold** and *italic* and `code`", enabled = true)
        assertEquals("•  the value x_(max)\n•  2* with bold and italic and code", s.text)
        assertEquals(3, s.spanStyles.size)
    }

    @Test
    fun `an underscore inside words is not italic`() {
        assertEquals("file_name and other_name", Markdown.render("file_name and other_name", true).text)
    }

    @Test
    fun `markdown off leaves the text as it is`() {
        assertEquals("**x** \\_ &lt;", Markdown.render("**x** \\_ &lt;", enabled = false).text)
    }

    @Test
    fun `HTML entities as in PDFPC, but not in code`() {
        val s = Markdown.render("- if a &lt; b &amp;&amp; c &gt;= d &rarr; **&#60;x&#x3E;**\n`&lt;` and &unknown; and \\&lt; and & alone", true)
        assertEquals("•  if a < b && c >= d → <x>\n&lt; and &unknown; and &lt; and & alone", s.text)
        assertEquals("&lt;", Markdown.render("```\n&lt;\n```", true).text)
    }

    @Test
    fun `the notes of the sample talk leave no escapes nor list markers`() {
        val text = SampleTalk.notesText
        val pages = JSONObject(text).getJSONArray("pages")
        for (i in 0 until pages.length()) {
            val rendered = Markdown.render(pages.getJSONObject(i).getString("note"), enabled = true).text
            assertFalse(rendered, rendered.contains("\\_") || rendered.contains("\\*"))
            for (line in rendered.lines()) assertFalse(line, line.startsWith("- "))
            assertTrue(rendered.isNotBlank())
        }
    }
}
