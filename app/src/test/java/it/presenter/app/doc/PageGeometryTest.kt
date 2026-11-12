package it.presenter.app.doc

import org.junit.Assert.assertEquals
import org.junit.Test

class PageGeometryTest {
    @Test
    fun `unmap is the inverse of map for every rotation and with a moved CropBox`() {
        for (rotation in listOf(0, 90, 180, 270, -90)) {
            val sideways = rotation == 90 || rotation == 270 || rotation == -90
            // CropBox 30..630 x 50..500 (600 x 450 points): displayed page 4:3 or 3:4.
            val aspect = if (sideways) 600f / 450f else 450f / 600f
            val g = PageGeometry(30f, 50f, 630f, 500f, rotation, aspect)
            val pdf = FloatArray(2)
            val back = FloatArray(2)
            for ((u, v) in listOf(0f to 0f, 1000f to 0f, 250f to 400f, 999f to 1000f * aspect)) {
                g.map(u, v, pdf)
                g.unmap(pdf[0], pdf[1], back)
                assertEquals("u, rotation $rotation", u, back[0], 0.01f)
                assertEquals("v, rotation $rotation", v, back[1], 0.01f)
            }
        }
    }

    @Test
    fun `the top-left corner of the displayed page`() {
        val out = FloatArray(2)
        PageGeometry(0f, 0f, 600f, 450f, 0, 0.75f).map(0f, 0f, out)
        assertEquals(0f, out[0], 0.001f)
        assertEquals(450f, out[1], 0.001f)
    }
}
