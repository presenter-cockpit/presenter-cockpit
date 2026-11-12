package it.presenter.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolRefTest {
    private val pen = ToolRef.Preset("pen")
    private val laser = ToolRef.Preset("laser")

    @Test
    fun `save round trip`() {
        for (ref in listOf(pen, ToolRef.Eraser)) assertEquals(ref, ToolRef.decode(ref.encode()))
        assertNull(ToolRef.decode(null))
        assertNull(ToolRef.decode("preset:"))
        assertNull(ToolRef.decode("unknown"))
    }

    @Test
    fun `without the button the selected tool is used`() {
        assertEquals(pen, ToolRef.effective(pen, ToolRef.Eraser, buttonPressed = false, eraserTip = false))
        assertEquals(ToolRef.Eraser, ToolRef.effective(ToolRef.Eraser, pen, buttonPressed = false, eraserTip = false))
    }

    @Test
    fun `with the button the dual is used, also with the laser selected`() {
        assertEquals(ToolRef.Eraser, ToolRef.effective(pen, ToolRef.Eraser, buttonPressed = true, eraserTip = false))
        assertEquals(ToolRef.Eraser, ToolRef.effective(laser, ToolRef.Eraser, buttonPressed = true, eraserTip = false))
        assertEquals(laser, ToolRef.effective(pen, laser, buttonPressed = true, eraserTip = false))
    }

    @Test
    fun `the eraser end always erases`() {
        assertEquals(ToolRef.Eraser, ToolRef.effective(pen, laser, buttonPressed = true, eraserTip = true))
        assertEquals(ToolRef.Eraser, ToolRef.effective(laser, pen, buttonPressed = false, eraserTip = true))
    }

    @Test
    fun `duals of different tools, round trip`() {
        val duals = mapOf(laser to ToolRef.Preset("green-laser"), pen to ToolRef.Eraser, ToolRef.Eraser to pen)
        assertEquals(duals, ToolRef.decodeDuals(ToolRef.encodeDuals(duals)))
        assertEquals(emptyMap<ToolRef, ToolRef>(), ToolRef.decodeDuals("{broken"))
        assertEquals(mapOf(pen to ToolRef.Eraser), ToolRef.decodeDuals("""{"preset:pen":"eraser","unknown":"eraser","preset:x":"?"}"""))
    }

    @Test
    fun `a double click of the button always gives the eraser`() {
        assertEquals(ToolRef.Eraser, ToolRef.effective(laser, pen, buttonPressed = true, eraserTip = false, doubleClick = true))
        assertEquals(pen, ToolRef.effective(laser, pen, buttonPressed = true, eraserTip = false, doubleClick = false))
        assertEquals(laser, ToolRef.effective(laser, pen, buttonPressed = false, eraserTip = false, doubleClick = true))
    }
}
