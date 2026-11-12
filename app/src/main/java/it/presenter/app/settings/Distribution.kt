package it.presenter.app.settings

import androidx.annotation.StringRes
import it.presenter.app.BuildConfig
import it.presenter.app.R

/** Distribution of the app (build flavor: "full", "store", "fdroid"). */
object Distribution {
    val isStore: Boolean get() = BuildConfig.DISTRIBUTION == "store"

    /**
     * Public page of the project, opened from the icon in the limitation notices. Placeholder: the site
     * is not published yet; while it is null the notice has no icon.
     */
    val projectUrl: String? = null
}

/**
 * Feature missing (or limited) in a distribution: [message] is the explanation in the notice at the
 * bottom, [tooltip] the name of the grey icon standing for it (if there is one). The icon stays
 * visible and a tap shows the notice.
 */
enum class Limit(@param:StringRes val message: Int, @param:StringRes val tooltip: Int? = null) {
    /** Store: the Android shell could not see the tablet's folders. */
    STORE_TERMINAL(R.string.limit_store_terminal, R.string.limit_store_terminal_tooltip),

    /** Store: no access to all files, only granted folders (notice the first time the local IDE is opened). */
    STORE_FILES(R.string.limit_store_files),

    /** F-Droid: no handwriting recognition (the recognizer of the other distributions is not free software). */
    FDROID_HANDWRITING(R.string.limit_fdroid_handwriting, R.string.limit_fdroid_handwriting_tooltip),
}
