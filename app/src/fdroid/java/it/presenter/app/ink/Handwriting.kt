package it.presenter.app.ink

import it.presenter.app.settings.Limit

/**
 * Handwriting turned into text: missing in the "fdroid" distribution, because the recognizer of the other
 * distributions (ML Kit, `src/mlkit`) is not free software. Same names as there, so the rest of the app is
 * the same; the icon stays, grey, and explains ([Limit.FDROID_HANDWRITING]).
 */
object Handwriting {
    /** Recognition is part of this distribution. */
    const val AVAILABLE = false

    val LANGUAGES = listOf("it", "en")

    sealed interface Result {
        class Text(val text: String) : Result

        object Downloading : Result

        class Failed(val error: Throwable?) : Result
    }

    @Suppress("UNUSED_PARAMETER")
    fun recognize(lines: List<List<InkStroke>>, slant: Float, language: String, onResult: (Result) -> Unit) = onResult(Result.Failed(null))
}
