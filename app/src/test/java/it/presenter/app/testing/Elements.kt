package it.presenter.app.testing

import it.presenter.app.doc.ElementCodec
import it.presenter.app.ink.LineDash
import it.presenter.app.ink.PageElement
import it.presenter.app.ink.Shape
import it.presenter.app.ink.ShapeType
import it.presenter.app.ink.TextBox
import it.presenter.app.ink.TextFont
import it.presenter.app.ink.TextHighlight
import kotlin.random.Random

/**
 * Page elements for the tests. Only figures, text and highlights: pen strokes need the native library of
 * Jetpack Ink, which is not there on the JVM.
 */
object Elements {
    fun shape(n: Int) = Shape(ShapeType.POLYGON, floatArrayOf(n.toFloat(), 10f, n + 50f, 10f, n + 50f, 60f), 0xFF1E88E5.toInt(), 3f)

    fun text(s: String) = TextBox(s, 20f, 30f, 300f, 40f, 32f, 0xFF000000.toInt(), TextFont.SANS, bold = false, italic = false, rotation = 0f)

    /** A random valid element. */
    fun random(r: Random): PageElement = when (r.nextInt(3)) {
        0 -> {
            val type = ShapeType.entries.random(r)
            val n = r.nextInt(4, 12)
            Shape(
                type, FloatArray(n * 2) { r.nextFloat() * 1000f }, r.nextInt(), r.nextFloat() * 20f, r.nextInt(),
                LineDash.entries.random(r), r.nextBoolean(),
            )
        }
        1 -> TextBox(
            Strings.random(r, 40), r.nextFloat() * 900f, r.nextFloat() * 600f, r.nextFloat() * 400f, r.nextFloat() * 200f,
            r.nextFloat() * 60f + 4f, r.nextInt(), TextFont.entries.random(r), r.nextBoolean(), r.nextBoolean(), r.nextFloat() * 6f,
        )
        else -> TextHighlight(FloatArray(8 * r.nextInt(1, 4)) { r.nextFloat() * 1000f }, r.nextInt())
    }

    /** Comparable form of a list of elements (the classes hold arrays: no structural equality). */
    fun bytes(list: List<PageElement>?): List<List<Byte>>? = list?.map { ElementCodec.encode(it)!!.toList() }

    fun bytes(map: Map<Int, List<PageElement>>): Map<Int, List<List<Byte>>> = map.mapValues { bytes(it.value)!! }
}

object Strings {
    private const val CHARS = "abcdefghijklmnopqrstuvwxyz ABC 0123456789 àèéìòù €ℤ∑ \n\t\"'\\[]#{}:,&;"

    fun random(r: Random, max: Int): String = String(CharArray(r.nextInt(max + 1)) { CHARS[r.nextInt(CHARS.length)] })
}
