package it.presenter.app.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Side of the screen where the tool rail sits. */
enum class RailSide { LEFT, RIGHT }

/** App language: [SYSTEM] follows Android. */
enum class AppLanguage(val tag: String) { SYSTEM(""), ENGLISH("en"), ITALIAN("it") }

/**
 * Options of the Settings screen: state observable from Compose, saved to [Prefs] on every change.
 * [onLaserTrailChanged] tells the user of the trail duration (the session's laser). Per-tool settings
 * stay in the presets (editor next to the rail).
 */
class Settings(private val prefs: Prefs, private val onLaserTrailChanged: (Long) -> Unit) {
    var railSide by mutableStateOf(if (prefs.railSide == "right") RailSide.RIGHT else RailSide.LEFT)
        private set

    var laserTrailHoldMs by mutableLongStateOf(prefs.laserTrailHoldMs.coerceIn(LASER_TRAIL_RANGE))
        private set

    var fingerUndo by mutableStateOf(prefs.fingerUndo)
        private set

    var animatedTransitions by mutableStateOf(prefs.animatedTransitions)
        private set

    var beamerOverlays by mutableStateOf(prefs.beamerOverlays)
        private set

    fun updateBeamerOverlays(on: Boolean) {
        beamerOverlays = on
        prefs.beamerOverlays = on
    }

    var sessionEnabled by mutableStateOf(prefs.sessionEnabled)
        private set

    init {
        onLaserTrailChanged(laserTrailHoldMs)
    }

    fun updateRailSide(side: RailSide) {
        railSide = side
        prefs.railSide = if (side == RailSide.RIGHT) "right" else "left"
    }

    fun updateLaserTrailHoldMs(ms: Long) {
        laserTrailHoldMs = ms.coerceIn(LASER_TRAIL_RANGE)
        prefs.laserTrailHoldMs = laserTrailHoldMs
        onLaserTrailChanged(laserTrailHoldMs)
    }

    fun updateFingerUndo(on: Boolean) {
        fingerUndo = on
        prefs.fingerUndo = on
    }

    fun updateAnimatedTransitions(on: Boolean) {
        animatedTransitions = on
        prefs.animatedTransitions = on
    }

    var privateStart by mutableStateOf(prefs.privateStart)
        private set

    fun updatePrivateStart(on: Boolean) {
        privateStart = on
        prefs.privateStart = on
    }

    var projectorInverted by mutableStateOf(prefs.projectorInverted)
        private set

    fun updateProjectorInverted(on: Boolean) {
        projectorInverted = on
        prefs.projectorInverted = on
    }

    /** Thresholds of the remaining time (minutes): yellow, then orange (red once the time is up). Orange never exceeds yellow. */
    var timerYellowMinutes by mutableIntStateOf(prefs.timerYellowMinutes)
        private set
    var timerOrangeMinutes by mutableIntStateOf(prefs.timerOrangeMinutes)
        private set

    fun updateTimerThresholds(yellow: Int, orange: Int) {
        timerYellowMinutes = yellow.coerceIn(TIMER_RANGE)
        timerOrangeMinutes = orange.coerceIn(TIMER_RANGE).coerceAtMost(timerYellowMinutes)
        prefs.timerYellowMinutes = timerYellowMinutes
        prefs.timerOrangeMinutes = timerOrangeMinutes
    }

    var codeLightTheme by mutableStateOf(prefs.codeLightTheme)
        private set

    fun updateCodeLightTheme(on: Boolean) {
        codeLightTheme = on
        prefs.codeLightTheme = on
    }

    var keepScreenOn by mutableStateOf(prefs.keepScreenOn)
        private set

    var showClock by mutableStateOf(prefs.showClock)
        private set

    fun updateShowClock(on: Boolean) {
        showClock = on
        prefs.showClock = on
    }

    var dndWithDisplay by mutableStateOf(prefs.dndWithDisplay)
        private set

    fun updateDndWithDisplay(on: Boolean) {
        dndWithDisplay = on
        prefs.dndWithDisplay = on
    }

    fun updateKeepScreenOn(on: Boolean) {
        keepScreenOn = on
        prefs.keepScreenOn = on
    }

    var terminalExtraKeys by mutableStateOf(prefs.terminalExtraKeys)
        private set

    fun updateTerminalExtraKeys(on: Boolean) {
        terminalExtraKeys = on
        prefs.terminalExtraKeys = on
    }

    var linksInApp by mutableStateOf(prefs.linksInApp)
        private set

    fun updateLinksInApp(on: Boolean) {
        linksInApp = on
        prefs.linksInApp = on
    }

    fun updateSessionEnabled(on: Boolean) {
        sessionEnabled = on
        prefs.sessionEnabled = on
    }

    companion object {
        val LASER_TRAIL_RANGE = 200L..4000L
        val TIMER_RANGE = 1..30

        /** A per-app language can be chosen from Android 13; before that the app always follows the system. */
        val languageSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        fun language(context: Context): AppLanguage {
            if (!languageSupported) return AppLanguage.SYSTEM
            val tags = context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
            return AppLanguage.entries.firstOrNull { it != AppLanguage.SYSTEM && tags.startsWith(it.tag) } ?: AppLanguage.SYSTEM
        }

        /**
         * Changes the app language. The Activity handles the change itself (`locale` in `configChanges`):
         * Compose redraws the texts without recreating it, so the projector is not interrupted.
         */
        fun setLanguage(context: Context, language: AppLanguage) {
            if (!languageSupported) return
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (language == AppLanguage.SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
        }
    }
}
