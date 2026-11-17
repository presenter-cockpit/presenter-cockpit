package it.presenter.app.tools

/**
 * Where the pen is working, and so which tools it can use there. The rail greys out the others, and a tab
 * opened with one of them selected switches to the laser (see `PresenterSession.adaptTool`).
 */
enum class ToolPlace {
    /** A PDF page or a whiteboard: every tool. */
    PAGE,

    /**
     * Code editor and terminal: pen, marker, highlighter, eraser and laser. The strokes stay over the text
     * until it scrolls (see `tabs/CodeInk`).
     */
    CODE,

    /** Web page: only the laser. */
    WEB,
    ;

    /** True if [ref] (a preset of kind [kind], or a fixed tool with [kind] null) works here. */
    fun allows(ref: ToolRef, kind: ToolKind?): Boolean = when (this) {
        PAGE -> true
        CODE -> ref == ToolRef.Eraser || kind in CODE_KINDS
        WEB -> kind == ToolKind.LASER
    }

    /** True if the pen draws strokes with a preset of kind [kind] here (the laser only points). */
    fun draws(kind: ToolKind?): Boolean = when (this) {
        PAGE -> kind != null && kind != ToolKind.LASER
        CODE -> kind in CODE_KINDS && kind != ToolKind.LASER
        WEB -> false
    }

    private companion object {
        val CODE_KINDS = setOf(ToolKind.PEN, ToolKind.MARKER, ToolKind.HIGHLIGHTER, ToolKind.LASER)
    }
}
