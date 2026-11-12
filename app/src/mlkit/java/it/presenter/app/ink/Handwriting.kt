package it.presenter.app.ink

import androidx.ink.strokes.StrokeInput
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import kotlin.math.cos
import kotlin.math.sin

/**
 * Handwriting turned into text with ML Kit Digital Ink Recognition: the language model is downloaded once
 * (network needed), then recognition runs entirely on the tablet.
 *
 * ML Kit is not free software: this file is in `src/mlkit`, compiled only in the "full" and "store"
 * distributions; "fdroid" has its own `Handwriting` without recognition.
 */
object Handwriting {
    /** Recognition is part of this distribution. */
    const val AVAILABLE = true

    /** Languages offered (user's choice: Italian and English). */
    val LANGUAGES = listOf("it", "en")

    sealed interface Result {
        class Text(val text: String) : Result

        /** The model is being downloaded (first time for this language). */
        object Downloading : Result

        class Failed(val error: Throwable?) : Result
    }

    /**
     * Recognizes the [lines] of strokes (from the top, each in writing order; see [HandwritingLines]) written
     * with slant [slant] (radians; they are straightened first) in language [language]: the text has one line
     * for each, those not recognized left out. [onResult] is called on the main thread, first with
     * [Result.Downloading] if the model must be downloaded.
     */
    fun recognize(lines: List<List<InkStroke>>, slant: Float, language: String, onResult: (Result) -> Unit) {
        val straight = -slant
        val id = runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(language) }.getOrNull()
        if (id == null) return onResult(Result.Failed(null))
        val model = DigitalInkRecognitionModel.builder(id).build()
        val manager = RemoteModelManager.getInstance()
        manager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (downloaded) {
                    run(model, lines, straight, onResult)
                } else {
                    onResult(Result.Downloading)
                    manager.download(model, DownloadConditions.Builder().build())
                        .addOnSuccessListener { run(model, lines, straight, onResult) }
                        .addOnFailureListener { onResult(Result.Failed(it)) }
                }
            }
            .addOnFailureListener { onResult(Result.Failed(it)) }
    }

    /** One line after the other with the same recognizer; the text of each is added to [done]. */
    private fun run(
        model: DigitalInkRecognitionModel,
        lines: List<List<InkStroke>>,
        angle: Float,
        onResult: (Result) -> Unit,
        recognizer: DigitalInkRecognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build()),
        done: List<String> = emptyList(),
    ) {
        val strokes = lines.firstOrNull()
        if (strokes == null) {
            recognizer.close()
            onResult(if (done.isEmpty()) Result.Failed(null) else Result.Text(done.joinToString("\n")))
            return
        }
        val (ink, box) = toInk(strokes, angle)
        val context = RecognitionContext.builder()
            // The line before helps with the words that continue it.
            .setPreContext(done.lastOrNull().orEmpty())
            .setWritingArea(WritingArea(box.width.coerceAtLeast(1f), box.height.coerceAtLeast(1f)))
            .build()
        recognizer.recognize(ink, context)
            .addOnSuccessListener { r ->
                val text = r.candidates.firstOrNull()?.text?.trim()
                run(model, lines.drop(1), angle, onResult, recognizer, if (text.isNullOrEmpty()) done else done + text)
            }
            .addOnFailureListener {
                recognizer.close()
                onResult(Result.Failed(it))
            }
    }

    /**
     * Ink strokes turned by [angle] → ML Kit ink, with its box; each stroke's times continue those of the
     * previous one.
     */
    private fun toInk(strokes: List<InkStroke>, angle: Float): Pair<Ink, Box> {
        val sin = sin(angle)
        val cos = cos(angle)
        val builder = Ink.builder()
        val input = StrokeInput()
        var offset = 0L
        var box: Box? = null
        for (s in strokes) {
            val inputs = s.stroke.inputs
            val stroke = Ink.Stroke.builder()
            var last = 0L
            for (k in 0 until inputs.size) {
                inputs.populate(k, input)
                last = input.elapsedTimeMillis
                val x = input.x * cos - input.y * sin
                val y = input.x * sin + input.y * cos
                stroke.addPoint(Ink.Point.create(x, y, offset + last))
                box = box?.union(Box(x, y, x, y)) ?: Box(x, y, x, y)
            }
            builder.addStroke(stroke.build())
            offset += last + STROKE_GAP_MS
        }
        return builder.build() to (box ?: Box(0f, 0f, 0f, 0f))
    }

    private const val STROKE_GAP_MS = 200L
}
