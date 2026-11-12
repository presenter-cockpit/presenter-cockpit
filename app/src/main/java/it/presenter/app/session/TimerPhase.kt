package it.presenter.app.session

/**
 * How close the end of the presentation is. The timer at the top and, on the tablet only, the background
 * around the slide take its color (user's choice): yellow, then orange, then red once the time is up.
 */
enum class TimerPhase {
    NONE, YELLOW, ORANGE, RED;

    companion object {
        /** Phase with [leftMs] to the end: yellow from [yellowMinutes] before it, orange from [orangeMinutes], red after. */
        fun of(leftMs: Long, yellowMinutes: Int, orangeMinutes: Int): TimerPhase = when {
            leftMs <= 0L -> RED
            leftMs <= orangeMinutes * 60_000L -> ORANGE
            leftMs <= yellowMinutes * 60_000L -> YELLOW
            else -> NONE
        }
    }
}
