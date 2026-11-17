package it.presenter.app.tabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMarkTest {
    private fun mark(vararg lines: String, rows: Int = 8, scrollback: Int = 0, alternate: Boolean = false) =
        ScreenMark(rows, 40, alternate, scrollback, lines.toList() + List(rows - lines.size) { "" })

    @Test
    fun `typing on the prompt is not a scroll`() {
        val before = mark("$ ls", "notes.txt  talk.pdf", "$ ec")
        assertFalse(before.movedTo(mark("$ ls", "notes.txt  talk.pdf", "$ echo hi")))
        // Also on a screen with only the prompt, and with a command that wraps to the next row.
        assertFalse(mark("$ ").movedTo(mark("$ cat")))
        assertFalse(mark("$ x").movedTo(mark("$ xxxxxxxx", "xxxx")))
    }

    @Test
    fun `output below without scrolling is not a scroll`() {
        val before = mark("$ ls", "a", "$ ")
        assertFalse(before.movedTo(mark("$ ls", "a", "$ ls", "a", "$ ")))
        assertFalse(mark("$ ").movedTo(mark("$ seq 3", "1", "2", "3", "$ ")))
    }

    @Test
    fun `lines pushed into the scrollback are a scroll`() {
        val full = (1..8).map { "line $it" }.toTypedArray()
        val shifted = (2..9).map { "line $it" }.toTypedArray()
        assertTrue(mark(*full, scrollback = 10).movedTo(mark(*shifted, scrollback = 11)))
        // With the scrollback full its size stays the same: the shifted text tells.
        assertTrue(mark(*full, scrollback = 1000).movedTo(mark(*shifted, scrollback = 1000)))
    }

    @Test
    fun `a full-screen program scrolling up or down is a scroll`() {
        // tmux: the status row at the bottom stays.
        val before = mark("fun a() {", "  b()", "}", "", "fun c() {", "  d()", "}", "[0] bash", alternate = true)
        val up = mark("  b()", "}", "", "fun c() {", "  d()", "}", "fun e() {", "[0] bash", alternate = true)
        val down = mark("// top", "fun a() {", "  b()", "}", "", "fun c() {", "  d()", "[0] bash", alternate = true)
        assertTrue(before.movedTo(up))
        assertTrue(before.movedTo(down))
    }

    @Test
    fun `a cleared or replaced screen counts as moved`() {
        assertTrue(mark("$ ls", "a", "b", "$ clear").movedTo(mark("$ ")))
        assertTrue(mark("one", "two", "three").movedTo(mark("alpha", "beta", "gamma")))
    }

    @Test
    fun `size or screen changes count as moved`() {
        val m = mark("$ ls")
        assertTrue(m.movedTo(m.copy(rows = 10, lines = m.lines + listOf("", ""))))
        assertTrue(m.movedTo(m.copy(cols = 60)))
        assertTrue(m.movedTo(m.copy(alternate = true)))
    }

    @Test
    fun `nothing changed is not moved`() {
        val m = mark("$ ls", "a")
        assertFalse(m.movedTo(m.copy()))
    }
}
