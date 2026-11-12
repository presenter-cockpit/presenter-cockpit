package it.presenter.app.code

import it.presenter.app.code.Highlighter.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlighterTest {
    private fun kinds(text: String, lang: Highlighter.Language) =
        Highlighter.highlight(text, lang).map { text.substring(it.start, it.end) to it.kind }

    @Test
    fun `C with directives, comments, strings and numbers`() {
        val k = kinds("#include <stdio.h>\nint main() { // x\n  return printf(\"hello %d\", 42); /* a\nb */ }", Highlighter.C)
        assertTrue("#include <stdio.h>" to Kind.PREPROCESSOR in k)
        assertTrue("int" to Kind.TYPE in k)
        assertTrue("return" to Kind.KEYWORD in k)
        assertTrue("// x" to Kind.COMMENT in k)
        assertTrue("\"hello %d\"" to Kind.STRING in k)
        assertTrue("42" to Kind.NUMBER in k)
        assertTrue("/* a\nb */" to Kind.COMMENT in k)
    }

    @Test
    fun `Python and language from the file name`() {
        assertEquals(Highlighter.PYTHON, Highlighter.forFile("a.py"))
        assertEquals(Highlighter.SOLIDITY, Highlighter.forFile("Token.sol"))
        assertEquals(Highlighter.MAKE, Highlighter.forFile("Makefile"))
        assertEquals(Highlighter.CMAKE, Highlighter.forFile("CMakeLists.txt"))
        val k = kinds("def f(x):  # c\n    return 'a\\'b'", Highlighter.PYTHON)
        assertTrue("def" to Kind.KEYWORD in k)
        assertTrue("# c" to Kind.COMMENT in k)
        assertTrue("'a\\'b'" to Kind.STRING in k)
    }

    @Test
    fun `names with digits are not numbers`() {
        val k = kinds("uint256 x1 = 0x10;", Highlighter.SOLIDITY)
        assertTrue("uint256" to Kind.TYPE in k)
        assertTrue("0x10" to Kind.NUMBER in k)
        assertTrue(k.none { it.first == "1" })
    }

    @Test
    fun `a period outside numbers and strings does not block`() {
        val k = kinds("import os.path\nos.path.join(a, b). x = .5", Highlighter.PYTHON)
        assertTrue("import" to Kind.KEYWORD in k)
        assertTrue(".PHONY" to Kind.KEYWORD in kinds(".PHONY: all\nall: a.o", Highlighter.MAKE))
        assertTrue(kinds("fine.", Highlighter.C).isEmpty())
    }
}
