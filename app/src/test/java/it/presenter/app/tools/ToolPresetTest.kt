package it.presenter.app.tools

import it.presenter.app.ink.TextFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPresetTest {

    @Test
    fun `json round trip`() {
        val list = ToolPreset.defaults +
            ToolPreset("c", ToolKind.MARKER, Palette.INK[2], 6f, chisel = true) +
            ToolPreset("f", ToolKind.PEN, 0xFF123456.toInt(), 3f, fadeAfterMs = 7000L)
        assertEquals(list, ToolPreset.listFromJson(ToolPreset.listToJson(list)))
    }

    @Test
    fun `handwriting fonts of older versions are read as the handwriting font`() {
        assertEquals(TextFont.HAND, TextFont.of("HAND"))
        assertEquals(TextFont.HAND, TextFont.of("HAND_KALAM"))
        assertEquals(TextFont.MONO, TextFont.of("MONO"))
        assertTrue(runCatching { TextFont.of("COMIC") }.isFailure)
        val json = """[{"id":"t","kind":"TEXT","color":1,"size":20.0,"font":"HAND_CAVEAT"}]"""
        assertEquals(TextFont.HAND, ToolPreset.listFromJson(json).single().font)
    }

    @Test
    fun `damaged json does not crash the app`() {
        assertTrue(ToolPreset.listFromJson("{not json").isEmpty())
        // An invalid entry is dropped, the others stay.
        val json = """[{"id":"a","kind":"PEN","color":1,"size":2.0},{"id":"b","kind":"UNKNOWN","color":1,"size":2.0}]"""
        assertEquals(listOf("a"), ToolPreset.listFromJson(json).map { it.id })
    }

    @Test
    fun `normalized brings the width within the kind's limits`() {
        val p = ToolPreset("x", ToolKind.HIGHLIGHTER, Palette.HIGHLIGHTER[0], 2f).normalized()
        assertEquals(ToolPreset.sizeRange(ToolKind.HIGHLIGHTER).start, p.size)
    }

    @Test
    fun `changing kind to laser the color becomes one of its palette`() {
        val p = ToolPreset("x", ToolKind.PEN, Palette.INK[1], 3f).withKind(ToolKind.LASER)
        assertTrue(p.color in Palette.LASER)
    }

    @Test
    fun `between pen and marker the color stays`() {
        val p = ToolPreset("x", ToolKind.PEN, Palette.INK[3], 3f).withKind(ToolKind.MARKER)
        assertEquals(Palette.INK[3], p.color)
    }

    @Test
    fun `changing kind to highlighter the color becomes one of its palette`() {
        val p = ToolPreset("x", ToolKind.PEN, Palette.INK[0], 3f).withKind(ToolKind.HIGHLIGHTER)
        assertTrue(p.color in Palette.HIGHLIGHTER)
    }

    @Test
    fun `a custom color stays, also between pen and marker`() {
        val custom = 0xFF123456.toInt()
        val p = ToolPreset("x", ToolKind.PEN, custom, 3f)
        assertEquals(custom, p.normalized().color)
        assertEquals(custom, p.withKind(ToolKind.MARKER).color)
        assertTrue(p.withKind(ToolKind.HIGHLIGHTER).color in Palette.HIGHLIGHTER)
    }

    @Test
    fun `vanishing pen with time within limits and none for the laser`() {
        val pen = ToolPreset("x", ToolKind.PEN, Palette.INK[0], 3f, fadeAfterMs = 99_000L).normalized()
        assertEquals(ToolPreset.FADE_RANGE_MS.last, pen.fadeAfterMs)
        assertEquals(0L, pen.copy(fadeAfterMs = -5L).normalized().fadeAfterMs)
        assertEquals(0L, pen.withKind(ToolKind.LASER).fadeMs)
    }
}
