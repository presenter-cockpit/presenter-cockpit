package it.presenter.app.tools

import org.json.JSONObject

/**
 * Reference to a tool of the rail: a user preset or a fixed tool (eraser, lasso, spotlight). Text and
 * shapes are preset [ToolKind]s instead, because they have color and size.
 */
sealed interface ToolRef {
    data class Preset(val id: String) : ToolRef

    data object Eraser : ToolRef

    /** Freehand selection of the elements on the page. */
    data object Lasso : ToolRef

    /** Spotlights an area of the slide: the rest is darkened. */
    data object Spotlight : ToolRef

    /** Form saved in the preferences. */
    fun encode(): String = when (this) {
        is Preset -> PRESET_PREFIX + id
        Eraser -> ERASER
        Lasso -> LASSO
        Spotlight -> SPOTLIGHT
    }

    companion object {
        private const val PRESET_PREFIX = "preset:"
        private const val ERASER = "eraser"
        private const val LASSO = "lasso"
        private const val SPOTLIGHT = "spotlight"

        fun decode(s: String?): ToolRef? = when {
            s == null -> null
            s == ERASER -> Eraser
            s == LASSO -> Lasso
            s == SPOTLIGHT -> Spotlight
            s.startsWith(PRESET_PREFIX) -> Preset(s.removePrefix(PRESET_PREFIX)).takeIf { it.id.isNotEmpty() }
            else -> null
        }

        /** Dual of a tool until the user picks another one: the eraser. */
        val defaultDual: ToolRef get() = Eraser

        /** Tool → dual map as JSON (`{"preset:…":"eraser", …}`). */
        fun encodeDuals(duals: Map<ToolRef, ToolRef>): String =
            JSONObject().apply { for ((k, v) in duals) put(k.encode(), v.encode()) }.toString()

        /** Inverse of [encodeDuals]; unreadable entries are dropped. */
        fun decodeDuals(json: String?): Map<ToolRef, ToolRef> {
            if (json == null) return emptyMap()
            return runCatching {
                val o = JSONObject(json)
                o.keys().asSequence().mapNotNull { k ->
                    val key = decode(k) ?: return@mapNotNull null
                    val value = decode(o.optString(k)) ?: return@mapNotNull null
                    key to value
                }.toMap()
            }.getOrDefault(emptyMap())
        }

        /**
         * Tool of a pen gesture: the [selected] one; with the side button held, the dual tool [dual]; with the
         * button held after a double click ([doubleClick]) or with the eraser end (pens that have one) always
         * the eraser. The single rule used by stroke, hover and cursor.
         */
        fun effective(
            selected: ToolRef,
            dual: ToolRef,
            buttonPressed: Boolean,
            eraserTip: Boolean,
            doubleClick: Boolean = false,
        ): ToolRef = when {
            eraserTip -> Eraser
            buttonPressed && doubleClick -> Eraser
            buttonPressed -> dual
            else -> selected
        }
    }
}

/** Editor of a tool opened next to the rail, at the height of its button (window px). */
data class ToolEditorRequest(val tool: ToolRef, val anchorY: Float)
