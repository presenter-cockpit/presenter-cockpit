package it.presenter.app.tools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import it.presenter.app.ink.PageInk
import it.presenter.app.settings.Prefs

/**
 * Available tools: the user's presets plus the fixed tools. Compose state (the rail updates by
 * itself), also read by the drawing surface when a stroke starts.
 *
 * [selected] is the tool chosen in the rail. Every tool has its dual, used while holding the S Pen
 * button (the eraser, unless the user picks another one): [dual] is the dual of the selected tool.
 */
class ToolState(private val prefs: Prefs) {
    val presets = mutableStateListOf<ToolPreset>().apply {
        val saved = prefs.presetsJson?.let(ToolPreset::listFromJson).orEmpty()
        addAll(saved.ifEmpty { ToolPreset.defaults })
    }

    var selected: ToolRef by mutableStateOf(
        valid(ToolRef.decode(prefs.selectedTool)) ?: ToolRef.Preset(lastPreset().id),
    )
        private set

    /** Duals chosen by the user; tools that do not appear have [ToolRef.defaultDual]. */
    private val duals = mutableStateMapOf<ToolRef, ToolRef>().apply {
        putAll(ToolRef.decodeDuals(prefs.dualTools).filter { (k, v) -> valid(k) != null && valid(v) != null })
    }

    fun dualOf(tool: ToolRef): ToolRef = duals[tool] ?: ToolRef.defaultDual

    /** Dual of the selected tool. */
    val dual: ToolRef get() = dualOf(selected)

    /** S Pen button held (pen near the screen): [dual] is in use now. */
    var buttonHeld by mutableStateOf(false)

    /** Button held after a double click: eraser for any tool while it is held. */
    var buttonDoubleClick by mutableStateOf(false)

    /** Tool in use right now, to highlight in the rail: the dual (or the eraser) while the button is held. */
    val inUse: ToolRef get() = if (buttonHeld) effective(buttonPressed = true, eraserTip = false) else selected

    var eraserSize by mutableFloatStateOf(prefs.eraserSize)
        private set

    var eraseMode by mutableStateOf(if (prefs.eraserPartial) PageInk.EraseMode.PARTIAL else PageInk.EraseMode.STROKE)
        private set

    fun updateEraseMode(mode: PageInk.EraseMode) {
        eraseMode = mode
        prefs.eraserPartial = mode == PageInk.EraseMode.PARTIAL
    }

    /** Colors chosen with the free picker, most recent first (at most [MAX_RECENT_COLORS]). */
    val recentColors = mutableStateListOf<Int>().apply { addAll(prefs.recentColors.take(MAX_RECENT_COLORS)) }

    /** A color chosen with the picker goes to the front of the recent ones (no duplicates). */
    fun addRecentColor(color: Int) {
        recentColors.remove(color)
        recentColors.add(0, color)
        while (recentColors.size > MAX_RECENT_COLORS) recentColors.removeAt(recentColors.lastIndex)
        prefs.recentColors = recentColors.toList()
    }

    /** For testing without a pen: the finger draws instead of panning/zooming. */
    var fingerDraws by mutableStateOf(prefs.fingerDraws)
        private set

    /** Preset [ref] points to, or null (fixed tool, or deleted preset). */
    fun preset(ref: ToolRef): ToolPreset? = (ref as? ToolRef.Preset)?.let { r -> presets.firstOrNull { it.id == r.id } }

    /** Tool of a pen gesture (see [ToolRef.effective]). */
    fun effective(buttonPressed: Boolean, eraserTip: Boolean): ToolRef =
        ToolRef.effective(selected, dual, buttonPressed, eraserTip, buttonDoubleClick)

    fun select(ref: ToolRef) {
        selected = valid(ref) ?: return
        prefs.selectedTool = selected.encode()
        if (ref is ToolRef.Preset) prefs.lastPresetId = ref.id
        if (preset(selected)?.kind == ToolKind.LASER) prefs.lastLaserId = (selected as ToolRef.Preset).id
        if (preset(selected)?.kind == ToolKind.TEXT) prefs.lastTextId = (selected as ToolRef.Preset).id
    }

    /** The last text tool used (or the first in the rail), if there is one. */
    fun lastText(): ToolPreset? {
        val texts = presets.filter { it.kind == ToolKind.TEXT }
        return texts.firstOrNull { it.id == prefs.lastTextId } ?: texts.firstOrNull()
    }

    /** The last laser used (or the first in the rail), if there is one. */
    fun lastLaser(): ToolRef.Preset? {
        val lasers = presets.filter { it.kind == ToolKind.LASER }
        val p = lasers.firstOrNull { it.id == prefs.lastLaserId } ?: lasers.firstOrNull() ?: return null
        return ToolRef.Preset(p.id)
    }

    /**
     * [ref] becomes the dual of the selected tool. Choosing the tool itself (or the eraser) goes back to
     * the default dual.
     */
    fun setDualOfSelected(ref: ToolRef) {
        val tool = valid(ref) ?: return
        if (tool == selected || tool == ToolRef.defaultDual) duals.remove(selected) else duals[selected] = tool
        persistDuals()
    }

    private fun persistDuals() {
        prefs.dualTools = ToolRef.encodeDuals(duals)
    }

    fun update(preset: ToolPreset) {
        val i = presets.indexOfFirst { it.id == preset.id }
        if (i < 0) return
        presets[i] = preset.normalized()
        persist()
    }

    /** Adds a copy of the last selected preset and selects it; returns the new preset. */
    fun duplicateActive(): ToolPreset {
        val source = lastPreset()
        val copy = source.copy(id = ToolPreset.newId())
        presets.add(presets.indexOfFirst { it.id == source.id } + 1, copy)
        persist()
        select(ToolRef.Preset(copy.id))
        return copy
    }

    fun remove(id: String) {
        if (presets.size <= 1) return
        val i = presets.indexOfFirst { it.id == id }
        if (i < 0) return
        presets.removeAt(i)
        val removed = ToolRef.Preset(id)
        if (selected == removed) select(ToolRef.Preset(presets[(i - 1).coerceAtLeast(0)].id))
        // The removed preset no longer has a dual and is no longer anybody's dual.
        duals.remove(removed)
        duals.entries.removeAll { it.value == removed }
        persistDuals()
        persist()
    }

    fun move(id: String, delta: Int) {
        val i = presets.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in presets.indices) return
        presets.add(j, presets.removeAt(i))
        persist()
    }

    private fun lastPreset(): ToolPreset = presets.firstOrNull { it.id == prefs.lastPresetId } ?: presets.first()

    /** [ref] if it still exists (a preset may have been deleted), otherwise null. */
    private fun valid(ref: ToolRef?): ToolRef? = when (ref) {
        null -> null
        ToolRef.Eraser, ToolRef.Lasso, ToolRef.Spotlight -> ref
        is ToolRef.Preset -> ref.takeIf { r -> presets.any { it.id == r.id } }
    }

    fun updateEraserSize(size: Float) {
        eraserSize = size
        prefs.eraserSize = size
    }

    fun toggleFingerDraws() {
        fingerDraws = !fingerDraws
        prefs.fingerDraws = fingerDraws
    }

    private fun persist() {
        prefs.presetsJson = ToolPreset.listToJson(presets)
    }

    companion object {
        /** Double click of the S Pen button: second press within this time (eraser while held). */
        const val DOUBLE_CLICK_MS = 500L

        val ERASER_SIZES = listOf(8f, 16f, 32f)
        const val MAX_RECENT_COLORS = 5
    }
}
