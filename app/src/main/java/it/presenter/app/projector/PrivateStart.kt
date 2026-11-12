package it.presenter.app.projector

/** Rule of the private start (a setting): when the projector must start showing only the logo. */
object PrivateStart {
    /** Time away from the app after which it restarts in private. */
    const val AFTER_MS = 2 * 60 * 60 * 1000L

    /**
     * With the setting on ([enabled]): at the first start of the process ([firstStart]) or when coming back
     * more than [AFTER_MS] after the last exit ([lastAwayAt]; 0 = never left).
     */
    fun shouldStart(enabled: Boolean, firstStart: Boolean, now: Long, lastAwayAt: Long): Boolean =
        enabled && (firstStart || now - lastAwayAt > AFTER_MS)
}
