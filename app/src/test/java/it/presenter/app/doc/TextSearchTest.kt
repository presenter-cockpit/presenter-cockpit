package it.presenter.app.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {
    @Test
    fun `case and accents do not matter`() {
        val text = "Naïve café FAÇADE"
        val n = TextSearch.normalize(text)
        assertEquals(listOf(6..9), TextSearch.find(n, "cafe"))
        assertEquals(listOf(0..4), TextSearch.find(n, "naive"))
        assertEquals(listOf(11..16), TextSearch.find(n, "façàde"))
    }

    @Test
    fun `ligatures are found and map back to the original character`() {
        val text = "a ﬁne day"
        val hits = TextSearch.find(TextSearch.normalize(text), "fine")
        assertEquals(1, hits.size)
        assertEquals("ﬁne", text.substring(hits[0].first, hits[0].last + 1))
    }

    @Test
    fun `multiple spaces and line breaks count as one`() {
        val text = "fresh\n   water"
        assertEquals(listOf(0..13), TextSearch.find(TextSearch.normalize(text), "fresh water"))
    }

    @Test
    fun `several results and empty search`() {
        val n = TextSearch.normalize("aaa")
        assertEquals(listOf(0..1, 1..2), TextSearch.find(n, "aa"))
        assertTrue(TextSearch.find(n, "  ").isEmpty())
    }

    @Test
    fun `snippet around the result`() {
        val text = "x".repeat(100) + "FOUND" + "y".repeat(100)
        val s = TextSearch.snippet(text, 100..104, context = 5)
        assertEquals("…xxxxxFOUNDyyyyy…", s)
    }
}
