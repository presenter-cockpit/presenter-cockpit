package it.presenter.app.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPlaceTest {
    private val preset = ToolRef.Preset("p")

    private fun allowed(place: ToolPlace): Set<String> = buildSet {
        for (kind in ToolKind.entries) if (place.allows(preset, kind)) add(kind.name)
        if (place.allows(ToolRef.Eraser, null)) add("ERASER")
        if (place.allows(ToolRef.Lasso, null)) add("LASSO")
        if (place.allows(ToolRef.Spotlight, null)) add("SPOTLIGHT")
    }

    @Test
    fun `every tool on the page`() {
        assertEquals(ToolKind.entries.map { it.name }.toSet() + setOf("ERASER", "LASSO", "SPOTLIGHT"), allowed(ToolPlace.PAGE))
    }

    @Test
    fun `code and terminal have the pen tools, the eraser and the laser`() {
        assertEquals(setOf("PEN", "MARKER", "HIGHLIGHTER", "LASER", "ERASER"), allowed(ToolPlace.CODE))
    }

    @Test
    fun `web pages only the laser`() {
        assertEquals(setOf("LASER"), allowed(ToolPlace.WEB))
    }

    @Test
    fun `the laser points and does not draw`() {
        assertEquals(false, ToolPlace.CODE.draws(ToolKind.LASER))
        assertEquals(true, ToolPlace.CODE.draws(ToolKind.MARKER))
        assertEquals(false, ToolPlace.CODE.draws(ToolKind.SHAPE))
        assertEquals(false, ToolPlace.WEB.draws(ToolKind.PEN))
    }
}
