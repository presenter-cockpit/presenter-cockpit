package it.presenter.app.session

import it.presenter.app.deep.AppRig
import it.presenter.app.deep.ShadowPdfRenderer
import it.presenter.app.deep.ShadowPdfRendererPage
import it.presenter.app.tabs.BrowserTab
import it.presenter.app.tabs.EditorTab
import it.presenter.app.testing.Elements
import it.presenter.app.tools.ToolKind
import it.presenter.app.tools.ToolPlace
import it.presenter.app.tools.ToolRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tools usable on pages, code and web pages: switching to the laser and back, and the rail commands. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowPdfRenderer::class, ShadowPdfRendererPage::class])
class ToolPlaceSessionTest {
    private val rig = AppRig()
    private val session get() = rig.session
    private val tools get() = session.tools

    @After
    fun closeAll() = session.closeEverything()

    private fun presetOf(kind: ToolKind) = ToolRef.Preset(tools.presets.first { it.kind == kind }.id)

    private fun kindSelected() = tools.preset(tools.selected)?.kind

    @Test
    fun `a tool that does not work on code switches to the laser and comes back on the page`() {
        rig.open(rig.samplePdf())
        val shape = presetOf(ToolKind.SHAPE)
        tools.select(shape)
        val editor = EditorTab.local(rig.app.filesDir.path)
        session.openWork(editor)
        assertEquals(ToolPlace.CODE, session.toolPlace)
        assertEquals(ToolKind.LASER, kindSelected())
        assertFalse(session.toolAllowed(shape))
        session.showPdf()
        assertEquals(shape, tools.selected)
    }

    @Test
    fun `the pen stays on code, and on a web page waits for the code tab`() {
        rig.open(rig.samplePdf())
        val pen = presetOf(ToolKind.PEN)
        tools.select(pen)
        val editor = EditorTab.local(rig.app.filesDir.path)
        session.openWork(editor)
        assertEquals(pen, tools.selected)
        val web = BrowserTab("https://example.org")
        session.openWork(web)
        assertEquals(ToolPlace.WEB, session.toolPlace)
        assertEquals(ToolKind.LASER, kindSelected())
        assertFalse(session.toolAllowed(ToolRef.Eraser))
        session.showWork(editor)
        assertEquals(pen, tools.selected)
        assertTrue(session.toolAllowed(ToolRef.Eraser))
        assertFalse(session.toolAllowed(ToolRef.Lasso))
    }

    @Test
    fun `clear on a code tab clears its strokes, undo leaves the page behind alone`() {
        val doc = rig.open(rig.samplePdf())
        rig.draw(doc)
        val editor = EditorTab.local(rig.app.filesDir.path)
        session.openWork(editor)
        editor.codeInk.ink.add(listOf(Elements.shape(1)))
        assertFalse(editor.codeInk.ink.isEmpty)
        session.undo()
        session.clearPage()
        assertTrue(editor.codeInk.ink.isEmpty)
        assertFalse(doc.inkOf(doc.pageIndex).isEmpty)
    }

    @Test
    fun `the pen button keeps the dual of the user's tool in every tab`() {
        rig.open(rig.samplePdf())
        val shape = presetOf(ToolKind.SHAPE)
        val pen = presetOf(ToolKind.PEN)
        tools.select(shape)
        tools.setDualOfSelected(pen)
        session.openWork(EditorTab.local(rig.app.filesDir.path))
        // The laser took the place of the shapes, but the button still gives the pen.
        assertEquals(ToolKind.LASER, kindSelected())
        assertEquals(pen, tools.dual)
        assertEquals(pen, tools.effective(buttonPressed = true, eraserTip = false))
        // A dual chosen here is the dual of the shapes too.
        val marker = presetOf(ToolKind.MARKER)
        tools.setDualOfSelected(marker)
        session.showPdf()
        assertEquals(shape, tools.selected)
        assertEquals(marker, tools.dual)
    }

    @Test
    fun `a dual that cannot be used here is ignored`() {
        rig.open(rig.samplePdf())
        val pen = presetOf(ToolKind.PEN)
        tools.select(pen)
        tools.setDualOfSelected(ToolRef.Lasso)
        assertEquals(ToolRef.Lasso, tools.effective(buttonPressed = true, eraserTip = false))
        session.openWork(EditorTab.local(rig.app.filesDir.path))
        assertEquals(pen, tools.effective(buttonPressed = true, eraserTip = false))
        // The rail keeps the ring on the pen while the button is held.
        tools.buttonHeld = true
        assertEquals(pen, tools.inUse)
        tools.buttonHeld = false
        // On a web page the default dual, the eraser, is ignored too: the laser stays.
        tools.setDualOfSelected(ToolRef.Eraser)
        session.openWork(BrowserTab("https://example.org"))
        assertEquals(ToolKind.LASER, tools.preset(tools.effective(buttonPressed = true, eraserTip = false))?.kind)
    }
}
