package it.presenter.app.workspace

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkspaceJsonTest {
    private fun roundTrip(ws: Workspace) = Workspace.fromJson(JSONObject(ws.toJson().toString()))!!

    @Test
    fun `preview position saved and read back, default when missing`() {
        assertEquals(0.25f to 0.75f, roundTrip(Workspace(name = "A", previewPosition = 0.25f to 0.75f)).previewPosition)
        assertNull(roundTrip(Workspace(name = "B")).previewPosition)
        // Kept by copy (the workspace follows the open tabs with copies).
        assertEquals(1f to 0f, Workspace(name = "C", previewPosition = 1f to 0f).copy(name = "D").previewPosition)
        // Out of range in the file: brought back inside.
        val o = Workspace(name = "E").toJson().put("previewX", 3.0).put("previewY", -1.0)
        assertEquals(1f to 0f, Workspace.fromJson(o)!!.previewPosition)
    }
}
