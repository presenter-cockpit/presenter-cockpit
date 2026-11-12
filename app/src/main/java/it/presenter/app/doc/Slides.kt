package it.presenter.app.doc

/**
 * Pages grouped into slides: consecutive pages with the same label are the overlays of a Beamer slide
 * (as PDFPC recognizes them). Without labels each page is a slide.
 *
 * Beamer, however, also gives the same label to a section start page and the slide before it: [confirm]
 * lets you discard the pairs that are not real overlays (see [isContinuation]), otherwise turning slides
 * would skip the section pages.
 *
 * Navigation rules (user's choices): forward goes to the **first** overlay of the next slide, back to the
 * **last** one of the previous slide; a tap shows the next overlay of the same slide, and does nothing on
 * the last one.
 */
class Slides(labels: List<String>?, val pageCount: Int, confirm: (page: Int) -> Boolean = { true }) {
    /** First page of the slide of each page. */
    private val first = IntArray(pageCount)

    /** Last page of the slide of each page. */
    private val last = IntArray(pageCount)

    /** Slide index of each page (0 for the first). */
    private val index = IntArray(pageCount)

    /** At least one slide has more than one page. */
    val hasOverlays: Boolean

    /** Number of slides (the overlays of a slide count once). */
    val slideCount: Int

    init {
        var start = 0
        var overlays = false
        for (p in 0 until pageCount) {
            val same = p > 0 && labels != null && p < labels.size && labels[p] == labels[p - 1] && confirm(p)
            if (!same) start = p else overlays = true
            first[p] = start
            index[p] = if (p == 0) 0 else index[p - 1] + if (same) 0 else 1
        }
        var end = pageCount - 1
        for (p in pageCount - 1 downTo 0) {
            last[p] = end
            if (first[p] == p) end = p - 1
        }
        hasOverlays = overlays
        slideCount = if (pageCount == 0) 0 else index[pageCount - 1] + 1
    }

    /** Slide index of [page] (0 for the first): it is the number shown in the top bar. */
    fun slideOf(page: Int): Int = index[page.coerceIn(0, pageCount - 1)]

    fun firstPageOf(page: Int): Int = first[page.coerceIn(0, pageCount - 1)]

    fun lastPageOf(page: Int): Int = last[page.coerceIn(0, pageCount - 1)]

    /** Pages of the slide of [page]. */
    fun pagesOf(page: Int): IntRange = firstPageOf(page)..lastPageOf(page)

    /** First overlay of the next slide, or null if [page] is in the last slide. */
    fun nextSlide(page: Int): Int? = (lastPageOf(page) + 1).takeIf { it < pageCount }

    /** Last overlay of the previous slide, or null if [page] is in the first slide. */
    fun previousSlide(page: Int): Int? = (firstPageOf(page) - 1).takeIf { it >= 0 }

    /** Like [nextSlide], skipping the slides for which [hidden] is true (hidden slides of PDFPC). */
    fun nextSlide(page: Int, hidden: (Int) -> Boolean): Int? = generateSequence(nextSlide(page), ::nextSlide).firstOrNull { !hidden(it) }

    /** Like [previousSlide], skipping the slides for which [hidden] is true. */
    fun previousSlide(page: Int, hidden: (Int) -> Boolean): Int? = generateSequence(previousSlide(page), ::previousSlide).firstOrNull { !hidden(it) }

    /** Next overlay of the same slide, or null if [page] is the last. */
    fun nextOverlay(page: Int): Int? = (page + 1).takeIf { it <= lastPageOf(page) }

    companion object {
        /** Every page a slide (PDF without labels, or overlays turned off). */
        fun single(pageCount: Int) = Slides(null, pageCount)

        /**
         * A page is an overlay of the previous one if it repeats most of its text (an overlay adds parts to the
         * same slide); a section page has a different text. With no text on the previous page (only images) the
         * labels are trusted.
         */
        fun isContinuation(previousText: String, text: String): Boolean {
            val before = words(previousText)
            if (before.isEmpty()) return true
            val after = words(text)
            return before.count { it in after } >= before.size * MIN_SHARED_WORDS
        }

        private fun words(s: String): Set<String> =
            TextSearch.normalize(s).text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()

        private const val MIN_SHARED_WORDS = 0.6f
    }
}
