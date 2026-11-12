package it.presenter.app.code

import org.junit.Assert.assertEquals
import org.junit.Test

class TextFindTest {
    private val text = "fun main() {\n    println(\"Naïve café\")\n    MAIN()\n}"

    @Test
    fun `matches ignore case and do not overlap`() {
        assertEquals(listOf(4, 43), TextFind.matches(text, "main"))
        assertEquals(listOf(0, 2), TextFind.matches("aaaa", "aa"))
        assertEquals(emptyList<Int>(), TextFind.matches(text, ""))
        assertEquals(listOf(text.indexOf("Naïve")), TextFind.matches(text, "naïve"))
    }

    @Test
    fun `lines and their starts`() {
        assertEquals(0, TextFind.lineStart(text, 1))
        assertEquals(13, TextFind.lineStart(text, 2))
        // Beyond the last line: the last one; below 1: the first.
        assertEquals(TextFind.lineStart(text, 4), TextFind.lineStart(text, 99))
        assertEquals(0, TextFind.lineStart(text, 0))
        assertEquals(2, TextFind.lineOf(text, 43))
        assertEquals(0, TextFind.lineOf(text, 0))
    }
}
