package it.presenter.app.ink

import it.presenter.app.testing.Property
import org.junit.Assert.assertEquals
import org.junit.Test

class HandwritingLinesTest {
    /** A letter or word of a line whose x-height goes from [top] to [top] + 40 (page units). */
    private fun letter(x: Float, top: Float, w: Float = 30f, h: Float = 40f) = Box(x, top, x + w, top + h)

    @Test
    fun `one line stays one`() {
        // Ascenders and descenders ("l", "g") are taller than the other letters, but on the same line.
        val boxes = listOf(letter(0f, 100f), letter(40f, 80f, h = 60f), letter(80f, 100f, h = 65f), letter(120f, 100f))
        assertEquals(listOf(listOf(0, 1, 2, 3)), HandwritingLines.split(boxes))
    }

    @Test
    fun `lines from the top, strokes in writing order`() {
        // Second line written first, then the first: the lines come out from the top anyway.
        val boxes = listOf(letter(0f, 200f), letter(40f, 205f), letter(0f, 100f), letter(40f, 98f), letter(0f, 300f, w = 120f))
        assertEquals(listOf(listOf(2, 3), listOf(0, 1), listOf(4)), HandwritingLines.split(boxes))
    }

    @Test
    fun `dots, accents and commas go with their line`() {
        val boxes = listOf(
            letter(0f, 100f),
            // Dot of an "i" above the first line.
            Box(10f, 80f, 14f, 84f),
            letter(0f, 200f),
            // Comma at the bottom of the second line.
            Box(35f, 236f, 39f, 246f),
            // Accent above the second line, closer to it than to the first.
            Box(50f, 180f, 58f, 186f),
        )
        assertEquals(listOf(listOf(0, 1), listOf(2, 3, 4)), HandwritingLines.split(boxes))
    }

    @Test
    fun `a slightly slanted line is still one`() {
        val boxes = (0 until 8).map { letter(it * 40f, 100f - it * 6f) }
        assertEquals(listOf((0 until 8).toList()), HandwritingLines.split(boxes))
    }

    @Test
    fun `one stroke, or nothing`() {
        assertEquals(listOf(listOf(0)), HandwritingLines.split(listOf(letter(0f, 0f))))
        assertEquals(emptyList<List<Int>>(), HandwritingLines.split(emptyList()))
    }

    @Test
    fun `font size from a typical line, not from all of them`() {
        val lines = listOf(Box(0f, 0f, 100f, 50f), Box(0f, 80f, 100f, 130f), Box(0f, 160f, 100f, 230f))
        assertEquals(50f, HandwritingLines.lineHeight(lines), 0f)
    }

    /**
     * Strokes of [lines] lines of [letters] letters written along [deg] degrees (y down): each letter is a zigzag
     * 25 wide and 40 high, lines 80 apart. In writing order, line by line.
     */
    private fun written(deg: Double, lines: Int = 2, letters: Int = 10): List<FloatArray> {
        val a = Math.toRadians(deg)
        val sin = kotlin.math.sin(a).toFloat()
        val cos = kotlin.math.cos(a).toFloat()
        return (0 until lines).flatMap { j ->
            (0 until letters).map { i ->
                val pts = ArrayList<Float>()
                for (k in 0..8) {
                    val u = i * 35f + (k % 2) * 25f
                    val v = j * 80f + k * 5f
                    pts += u * cos - v * sin
                    pts += u * sin + v * cos
                }
                pts.toFloatArray()
            }
        }
    }

    @Test
    fun `slanted lines are found and cut along the slant`() {
        for (deg in listOf(-25.0, 15.0, 30.0)) {
            val strokes = written(deg)
            val slant = HandwritingLines.slant(strokes)
            assertEquals("slant for $deg", deg, Math.toDegrees(slant.toDouble()), 2.0)
            val boxes = HandwritingLines.boxes(strokes, slant, List(strokes.size) { 1f })
            assertEquals("lines for $deg", listOf((0 until 10).toList(), (10 until 20).toList()), HandwritingLines.split(boxes))
            // Along the writing a line is as high as a letter (with the pad), not stretched by the slant.
            assertEquals(42f, HandwritingLines.lineHeight(listOf(boxes.take(10).reduce(Box::union))), 4f)
        }
    }

    @Test
    fun `horizontal writing has no slant`() {
        assertEquals(0f, HandwritingLines.slant(written(0.0)), 0f)
        assertEquals(0f, HandwritingLines.slant(written(0.0, lines = 1, letters = 3)), 0f)
        assertEquals(0f, HandwritingLines.slant(emptyList()), 0f)
    }

    @Test
    fun `random slanted lines, written unevenly`() = Property.check("handwriting lines", runs = 200) { r ->
        val deg = r.nextDouble(-35.0, 35.0)
        val lines = r.nextInt(1, 4)
        val letters = r.nextInt(4, 12)
        val a = Math.toRadians(deg)
        val sin = kotlin.math.sin(a).toFloat()
        val cos = kotlin.math.cos(a).toFloat()
        // Letters of different heights, a little above or below the line, lines 74-106 apart (at least one and a
        // half letters); some with the dot of an "i" written right after them, above.
        fun turned(u: Float, v: Float) = floatArrayOf(u * cos - v * sin, u * sin + v * cos)
        val strokes = ArrayList<FloatArray>()
        val expected = (0 until lines).map { j ->
            val base = j * 90f + r.nextFloat() * 16f - 8f
            val line = ArrayList<Int>()
            for (i in 0 until letters) {
                val h = 30f + r.nextFloat() * 15f
                val dv = r.nextFloat() * 8f - 4f
                line += strokes.size
                strokes += (0..12).flatMap { k ->
                    turned(i * 35f + (if (k % 2 == 0) 0f else 25f) + r.nextFloat() * 3f, base + dv + k * h / 12f).toList()
                }.toFloatArray()
                if (r.nextInt(4) == 0) {
                    line += strokes.size
                    strokes += (turned(i * 35f + 12f, base - 12f) + turned(i * 35f + 14f, base - 10f))
                }
            }
            line
        }
        val slant = HandwritingLines.slant(strokes)
        val boxes = HandwritingLines.boxes(strokes, slant, List(strokes.size) { 1f })
        assertEquals("$lines lines of $letters letters at $deg°, slant ${Math.toDegrees(slant.toDouble())}°", expected, HandwritingLines.split(boxes))
    }
}
