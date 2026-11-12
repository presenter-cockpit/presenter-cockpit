package it.presenter.app.doc

import it.presenter.app.ink.Shape
import it.presenter.app.ink.ShapeType
import it.presenter.app.ink.TextBox
import it.presenter.app.ink.TextFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cases the random round trip of [CodecPropertyTest] does not reach. */
class ElementCodecTest {
    @Test
    fun `text longer than 64 KB`() {
        val text = "è".repeat(40_000)
        val t = TextBox(text, 1f, 2f, 300f, 40f, 32f, 0xFF000000.toInt(), TextFont.SERIF, bold = true, italic = false, rotation = 0.3f)
        val d = ElementCodec.decode(ElementCodec.encode(t)!!) as TextBox
        assertEquals(text, d.text)
        assertEquals(TextFont.SERIF, d.font)
        assertEquals(0.3f, d.rotation, 0f)
    }

    @Test
    fun `damaged data`() {
        assertNull(ElementCodec.decode(byteArrayOf(2, 1, 0)))
        // A polygon with fewer than 3 vertices is not accepted.
        val bad = Shape(ShapeType.POLYGON, floatArrayOf(0f, 0f, 1f, 1f), 0, 1f)
        assertNull(ElementCodec.decode(ElementCodec.encode(bad)!!))
    }
}
