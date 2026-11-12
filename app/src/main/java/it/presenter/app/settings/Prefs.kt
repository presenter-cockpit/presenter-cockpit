package it.presenter.app.settings

import android.content.Context
import androidx.core.content.edit
import it.presenter.app.laser.LaserTrail
import it.presenter.app.remote.ServerProfile
import it.presenter.app.session.Hint
import it.presenter.app.tools.ToolRef
import org.json.JSONObject

/** Persistent preferences: tool presets, last folders, notes panel, settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("presenter", Context.MODE_PRIVATE)

    var presetsJson: String?
        get() = sp.getString("presets", null)
        set(value) = sp.edit { putString("presets", value) }

    /** Preset selected before [selectedTool] existed (only for migration). */
    val legacyActivePresetId: String? get() = sp.getString("activePreset", null)

    /** Tool selected in the rail ([ToolRef.encode]). */
    var selectedTool: String?
        get() = sp.getString("selectedTool", null)
        set(value) = sp.edit { putString("selectedTool", value) }

    /** Last selected preset: "+" duplicates it even while the eraser is selected. */
    var lastPresetId: String?
        get() = sp.getString("lastPreset", null) ?: legacyActivePresetId
        set(value) = sp.edit { putString("lastPreset", value) }

    /** Last selected laser: taken when entering a tab where you can only point. */
    var lastLaserId: String?
        get() = sp.getString("lastLaser", null)
        set(value) = sp.edit { putString("lastLaser", value) }

    /** Last selected text tool: its font is used for handwriting turned into text. */
    var lastTextId: String?
        get() = sp.getString("lastText", null)
        set(value) = sp.edit { putString("lastText", value) }

    /**
     * External displays turned on or off by hand in the outputs menu, by display name (the others follow the
     * default: trusted displays on, those of other apps off).
     */
    var displayChoices: Map<String, Boolean>
        get() = runCatching {
            val o = JSONObject(sp.getString("displayChoices", null) ?: "{}")
            o.keys().asSequence().associateWith { o.getBoolean(it) }
        }.getOrDefault(emptyMap())
        set(value) = sp.edit { putString("displayChoices", JSONObject(value).toString()) }

    /** Colors chosen with the free picker, most recent first (ARGB, comma separated). */
    var recentColors: List<Int>
        get() = sp.getString("recentColors", "").orEmpty().split(',').mapNotNull { it.toLongOrNull()?.toInt() }
        set(value) = sp.edit { putString("recentColors", value.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }) }

    /** Dual tool of each tool (JSON, see [ToolRef.encodeDuals]). */
    var dualTools: String?
        get() = sp.getString("dualTools", null)
        set(value) = sp.edit { putString("dualTools", value) }

    /** Eraser that removes only the touched part (true) or whole strokes (false). */
    var eraserPartial: Boolean
        get() = sp.getBoolean("eraserPartial", true)
        set(value) = sp.edit { putBoolean("eraserPartial", value) }

    var eraserSize: Float
        get() = sp.getFloat("eraserSize", 16f)
        set(value) = sp.edit { putFloat("eraserSize", value) }

    var fingerDraws: Boolean
        get() = sp.getBoolean("fingerDraws", false)
        set(value) = sp.edit { putBoolean("fingerDraws", value) }

    /** Last folder a PDF was opened from (file system path). */
    var lastFolder: String?
        get() = sp.getString("lastFolder", null)
        set(value) = sp.edit { putString("lastFolder", value) }

    /** Last granted folder a PDF was opened from ("store" distribution, a `TreeFiles` path). */
    var lastTreeFolder: String?
        get() = sp.getString("lastTreeFolder", null)
        set(value) = sp.edit { putString("lastTreeFolder", value) }

    /** Last tablet folder chosen for an IDE: the folder picker and new local terminals start there. */
    var lastCodeFolder: String?
        get() = sp.getString("lastCodeFolder", null)
        set(value) = sp.edit { putString("lastCodeFolder", value) }

    /** Last document opened with the system picker: the picker starts there. */
    var lastPickerUri: String?
        get() = sp.getString("lastPickerUri", null)
        set(value) = sp.edit { putString("lastPickerUri", value) }

    var notesFontSp: Float
        get() = sp.getFloat("notesFont", 20f)
        set(value) = sp.edit { putFloat("notesFont", value) }

    /** Opacity of the notes panel background (1 = opaque), to see the slide underneath. */
    var notesOpacity: Float
        get() = sp.getFloat("notesOpacity", 1f)
        set(value) = sp.edit { putFloat("notesOpacity", value) }

    // ---- Settings (Settings screen, see [Settings])

    /** Side of the tool rail: "left" or "right". */
    var railSide: String
        get() = sp.getString("railSide", "left") ?: "left"
        set(value) = sp.edit { putString("railSide", value) }

    /** Trail-style laser: how long the trail stays after lifting the pen, before fading. */
    var laserTrailHoldMs: Long
        get() = sp.getLong("laserTrailHoldMs", LaserTrail.DEFAULT_INK_HOLD_MS)
        set(value) = sp.edit { putLong("laserTrailHoldMs", value) }

    /** First-use hints enabled ([it.presenter.app.session.Hints]). */
    var hintsEnabled: Boolean
        get() = sp.getBoolean("hintsEnabled", true)
        set(value) = sp.edit { putBoolean("hintsEnabled", value) }

    /** Hints already seen and closed (names of [Hint]). */
    var hintsSeen: Set<String>
        get() = sp.getString("hintsSeen", "").orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
        set(value) = sp.edit { putString("hintsSeen", value.joinToString(",")) }

    /** Two-finger tap: undo; three fingers: redo. */
    var fingerUndo: Boolean
        get() = sp.getBoolean("fingerUndo", true)
        set(value) = sp.edit { putBoolean("fingerUndo", value) }

    /**
     * Beamer overlays in navigation: by slide, with a tap for the next overlay. Ink is not affected: it is
     * always shared by the overlays of a slide.
     */
    var beamerOverlays: Boolean
        get() = sp.getBoolean("beamerOverlays", true)
        set(value) = sp.edit { putBoolean("beamerOverlays", value) }

    var animatedTransitions: Boolean
        get() = sp.getBoolean("animatedTransitions", true)
        set(value) = sp.edit { putBoolean("animatedTransitions", value) }

    var sessionEnabled: Boolean
        get() = sp.getBoolean("sessionEnabled", true)
        set(value) = sp.edit { putBoolean("sessionEnabled", value) }

    /** Private start: at launch (or after more than 2 hours away) the projector shows only the logo. */
    var privateStart: Boolean
        get() = sp.getBoolean("privateStart", false)
        set(value) = sp.edit { putBoolean("privateStart", value) }

    /** Last time the app was left (ms), for the private start after a long absence. */
    var lastAwayAt: Long
        get() = sp.getLong("lastAwayAt", 0L)
        set(value) = sp.edit { putLong("lastAwayAt", value) }

    var projectorInverted: Boolean
        get() = sp.getBoolean("projectorInverted", false)
        set(value) = sp.edit { putBoolean("projectorInverted", value) }

    /** Minutes before the end of the presentation when the remaining time turns yellow, then orange. */
    var timerYellowMinutes: Int
        get() = sp.getInt("timerYellowMinutes", 10)
        set(value) = sp.edit { putInt("timerYellowMinutes", value) }

    var timerOrangeMinutes: Int
        // The key keeps its old name (the threshold was red before the end turned red).
        get() = sp.getInt("timerRedMinutes", 5)
        set(value) = sp.edit { putInt("timerRedMinutes", value) }

    /** Language of handwriting recognition ("it" or "en"), null = the app language. */
    var handwritingLanguage: String?
        get() = sp.getString("handwritingLanguage", null)
        set(value) = sp.edit { putString("handwritingLanguage", value) }

    /** Saved servers (JSON, [ServerProfile]). */
    var serversJson: String?
        get() = sp.getString("servers", null)
        set(value) = sp.edit { putString("servers", value) }

    /** Light theme for editor and terminal (dark otherwise). */
    var codeLightTheme: Boolean
        get() = sp.getBoolean("codeLightTheme", false)
        set(value) = sp.edit { putBoolean("codeLightTheme", value) }

    /** Store flavor: notice about the file access limits already shown (only once, user's choice). */
    var storeFilesNoticeShown: Boolean
        get() = sp.getBoolean("storeFilesNoticeShown", false)
        set(value) = sp.edit { putBoolean("storeFilesNoticeShown", value) }

    /** Tablet screen always on while the app is open (otherwise it turns off as set in Android). */
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepScreenOn", true)
        set(value) = sp.edit { putBoolean("keepScreenOn", value) }

    /** Row of extra keys (Esc, Ctrl, Tab, arrows…) below the terminal with the on-screen keyboard. */
    var terminalExtraKeys: Boolean
        get() = sp.getBoolean("terminalExtraKeys", true)
        set(value) = sp.edit { putBoolean("terminalExtraKeys", value) }

    /** Web links of PDFs open in the in-app browser tab (otherwise in the system browser). */
    var linksInApp: Boolean
        get() = sp.getBoolean("linksInApp", true)
        set(value) = sp.edit { putBoolean("linksInApp", value) }

    /** Workspace in use (kept up to date automatically), or null. */
    var currentWorkspace: String?
        get() = sp.getString("currentWorkspace", null)
        set(value) = sp.edit { putString("currentWorkspace", value) }

    /** End time of the presentation (epoch ms), 0 if not set. The key keeps its old name. */
    /** PDFs opened lately, as JSON (see `RecentDocs`). */
    var recentDocs: String?
        get() = sp.getString("recentDocs", null)
        set(value) = sp.edit { putString("recentDocs", value) }

    /** When the timer was set or the stopwatch started (epoch ms), 0 if neither. */
    var presentationStart: Long
        get() = sp.getLong("presentationStart", 0L)
        set(value) = sp.edit { putLong("presentationStart", value) }

    /** Current time and battery in the top bar (off by default: the bar has little room). */
    var showClock: Boolean
        get() = sp.getBoolean("showClock", false)
        set(value) = sp.edit { putBoolean("showClock", value) }

    /** Do not disturb while an external display is connected (on by default). */
    var dndWithDisplay: Boolean
        get() = sp.getBoolean("dndWithDisplay", true)
        set(value) = sp.edit { putBoolean("dndWithDisplay", value) }

    /** Do not disturb was turned on by the app: it turns it off when the display goes. */
    var dndByApp: Boolean
        get() = sp.getBoolean("dndByApp", false)
        set(value) = sp.edit { putBoolean("dndByApp", value) }

    var presentationEnd: Long
        get() = sp.getLong("lessonEnd", 0L)
        set(value) = sp.edit { putLong("lessonEnd", value) }

    /** Position and size of the notes panel in dp; negative values = default position. */
    var notesBounds: FloatArray
        get() = floatArrayOf(
            sp.getFloat("notesX", -1f), sp.getFloat("notesY", -1f),
            sp.getFloat("notesW", 420f), sp.getFloat("notesH", 260f),
        )
        set(v) = sp.edit {
            putFloat("notesX", v[0]); putFloat("notesY", v[1])
            putFloat("notesW", v[2]); putFloat("notesH", v[3])
        }
}
