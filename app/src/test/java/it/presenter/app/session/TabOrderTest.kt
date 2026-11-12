package it.presenter.app.session

import org.junit.Assert.assertEquals
import org.junit.Test

class TabOrderTest {
    @Test
    fun `new tabs at the end, closed ones removed`() {
        assertEquals(listOf("t", "a", "b"), TabOrder.arrange(listOf("t", "x", "a"), listOf("a", "b", "t")))
        // Without an order: the natural one (PDFs first, then the others).
        assertEquals(listOf("a", "b"), TabOrder.arrange(emptyList(), listOf("a", "b")))
    }

    @Test
    fun `moving right and left`() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), TabOrder.move(l, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), TabOrder.move(l, 3, 0))
        assertEquals(listOf("a", "b", "d", "c"), TabOrder.move(l, 2, 99))
        assertEquals(l, TabOrder.move(l, 7, 0))
    }

    @Test
    fun `order saved with the keys, also with missing tabs`() {
        val present = listOf("pdf1", "pdf2", "term")
        val keys = listOf("k:term", "k:gone", "k:pdf2")
        assertEquals(listOf("term", "pdf2", "pdf1"), TabOrder.byKeys(keys, present) { "k:$it" })
        // Older session, without an order.
        assertEquals(present, TabOrder.byKeys(emptyList(), present) { "k:$it" })
    }

    @Test
    fun `new tab right after another one`() {
        // The terminal just opened (at the end) goes right after the IDE it comes from.
        assertEquals(listOf("pdf", "ide", "term", "web"), TabOrder.insertAfter(listOf("pdf", "ide", "web", "term"), "term", "ide"))
        assertEquals(listOf("ide", "term"), TabOrder.insertAfter(listOf("ide"), "term", "ide"))
        // Reference tab gone: at the end.
        assertEquals(listOf("pdf", "term"), TabOrder.insertAfter(listOf("pdf", "term"), "term", "gone"))
    }

    @Test
    fun `the tab beside a closed one`() {
        val bar = listOf("pdf", "ide", "term")
        assertEquals("term", TabOrder.neighbor(bar, "ide"))
        // The last one: the one before it.
        assertEquals("ide", TabOrder.neighbor(bar, "term"))
        assertEquals(null, TabOrder.neighbor(listOf("pdf"), "pdf"))
        assertEquals(null, TabOrder.neighbor(bar, "gone"))
    }
}
