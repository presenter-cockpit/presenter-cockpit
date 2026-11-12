package it.presenter.app.ink

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Lines of handwriting, to turn several written lines into a text box with the same lines: each line is
 * recognized on its own and the font size comes from the height of a line, not of the whole selection.
 * Lines written slanted are cut along their slant ([slant], [boxes]).
 */
object HandwritingLines {
    /**
     * Angle (radians, y down: positive = the lines go down to the right) of the writing in [strokes] (flat
     * x, y points of each, in writing order), between ±[MAX_SLANT_DEG]; 0 for writing about horizontal.
     *
     * From one stroke to the next the hand moves along the line: the median direction of those steps (only
     * forward ones: going back is a new line, a dot, an accent) is the slant, refined with the projection
     * profile ([profile]) around it. With fewer steps (one or two words in cursive) only the profile, which
     * must be clearly sharper than the horizontal one.
     */
    fun slant(strokes: List<FloatArray>): Float {
        val boxes = strokes.mapNotNull { p -> boxOf(p, 0f, 1f) }
        if (boxes.isEmpty()) return 0f
        // Dots and accents are left out: from a dot to the next letter the hand goes down, not along the line.
        val typical = typicalSize(boxes)
        val letters = boxes.filter { maxOf(it.width, it.height) >= SMALL_MARK * typical }
        val steps = letters.zipWithNext().mapNotNull { (a, b) ->
            val dx = b.centerX - a.centerX
            val dy = b.centerY - a.centerY
            Math.toDegrees(atan2(dy, dx).toDouble()).takeIf { dx > 0f && abs(it) <= MAX_STEP_DEG }
        }
        val deg = if (steps.size >= MIN_STEPS) {
            val guess = steps.sorted()[steps.size / 2].roundToInt()
            profile(strokes, boxes, (guess - REFINE_DEG)..(guess + REFINE_DEG), gain = 1.0)
        } else {
            profile(strokes, boxes, -MAX_SLANT_DEG..MAX_SLANT_DEG, gain = MIN_GAIN)
        }
        return if (abs(deg) < FLAT_DEG) 0f else Math.toRadians(deg.coerceIn(-MAX_SLANT_DEG, MAX_SLANT_DEG).toDouble()).toFloat()
    }

    /**
     * Angle (degrees, in [range]) at which the points of [strokes] gather best in rows (sum of the squares of
     * a histogram across the lines); with [gain] above 1, horizontal unless another angle is that much sharper.
     */
    private fun profile(strokes: List<FloatArray>, boxes: List<Box>, range: IntRange, gain: Double): Int {
        val xs = strokes.flatMap { p -> (0 until p.size / 2).map { p[2 * it] } }
        val ys = strokes.flatMap { p -> (0 until p.size / 2).map { p[2 * it + 1] } }
        // Bins a quarter of a letter high: the rows of the profile are sharp only along the lines.
        val bin = (typicalSize(boxes) / 4f).coerceAtLeast(0.5f)
        fun score(deg: Int): Double {
            val a = Math.toRadians(deg.toDouble())
            val sin = sin(a)
            val cos = cos(a)
            // Each point split between its two nearest bins: a profile that does not jump with the angle.
            val counts = HashMap<Long, Double>()
            for (k in xs.indices) {
                val at = (-xs[k] * sin + ys[k] * cos) / bin
                val i = floor(at)
                counts.merge(i.toLong(), 1 - (at - i), Double::plus)
                counts.merge(i.toLong() + 1, at - i, Double::plus)
            }
            return counts.values.sumOf { it * it }
        }
        var best = 0
        var bestScore = if (gain > 1.0) score(0) * gain else Double.NEGATIVE_INFINITY
        for (deg in range) {
            val s = score(deg)
            if (s > bestScore) {
                best = deg
                bestScore = s
            }
        }
        return best
    }

    /**
     * Boxes of [strokes] (flat x, y points) seen with the lines horizontal, that is turned by -[angle], each
     * widened by its [pad] (half the line width).
     */
    fun boxes(strokes: List<FloatArray>, angle: Float, pad: List<Float>): List<Box> =
        strokes.mapIndexed { i, p -> (boxOf(p, sin(-angle), cos(-angle)) ?: Box(0f, 0f, 0f, 0f)).inflate(pad.getOrElse(i) { 0f }) }

    /** Box of the points [p] turned by the angle with [sin] and [cos]. */
    private fun boxOf(p: FloatArray, sin: Float, cos: Float): Box? {
        if (p.size < 2) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (k in 0 until p.size / 2) {
            val x = p[2 * k] * cos - p[2 * k + 1] * sin
            val y = p[2 * k] * sin + p[2 * k + 1] * cos
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }
        return Box(minX, minY, maxX, maxY)
    }

    /**
     * Groups the strokes with boxes [boxes] (in writing order) into lines, from the top: indices of the strokes
     * of each line, in writing order. Strokes whose heights overlap enough are on the same line; small marks
     * (dots, accents, commas) go to the nearest line, also above it (dots and accents sit above the letters).
     */
    fun split(boxes: List<Box>): List<List<Int>> {
        if (boxes.isEmpty()) return emptyList()
        val typical = typicalSize(boxes)
        val small = boxes.indices.filter { maxOf(boxes[it].width, boxes[it].height) < SMALL_MARK * typical }.toSet()
        val big = boxes.indices.filter { it !in small }
        // Bands of the lines: grown stroke by stroke from the top, then merged while two still overlap enough.
        val lines = ArrayList<Line>()
        for (i in big.sortedBy { boxes[it].minY }) {
            val b = boxes[i]
            val line = lines.firstOrNull { overlap(it.minY, it.maxY, b.minY, b.maxY) >= SAME_LINE }
            if (line != null) line.add(i, b) else lines += Line(b.minY, b.maxY).also { it.strokes += i }
        }
        var merged = true
        while (merged) {
            merged = false
            loop@ for (a in lines.indices) for (c in a + 1 until lines.size) {
                if (overlap(lines[a].minY, lines[a].maxY, lines[c].minY, lines[c].maxY) >= SAME_LINE) {
                    lines[a].absorb(lines[c])
                    lines.removeAt(c)
                    merged = true
                    break@loop
                }
            }
        }
        for (i in small) {
            val b = boxes[i]
            // Distance from the band widened upwards by half its height (the dot of an "i", an accent); between lines
            // close enough to hold it both, the one with the nearer middle.
            lines.minWith(
                compareBy<Line> { l ->
                    val top = l.minY - (l.maxY - l.minY) / 2f
                    when {
                        b.centerY < top -> top - b.centerY
                        b.centerY > l.maxY -> b.centerY - l.maxY
                        else -> 0f
                    }
                }.thenBy { l -> abs(b.centerY - (l.minY + l.maxY) / 2f) },
            ).strokes += i
        }
        return lines.sortedBy { it.minY }.map { it.strokes.sorted() }
    }

    /** Size of a letter or word: towards the top of the sizes, so that many dots and accents do not lower it. */
    private fun typicalSize(boxes: List<Box>): Float =
        boxes.map { maxOf(it.width, it.height) }.sorted().let { it[((it.size - 1) * TYPICAL_RANK).toInt()] }

    /** Height of a typical line among those of [lines] (their boxes): the median, so one tall line does not count. */
    fun lineHeight(lines: List<Box>): Float = median(lines.map { it.height })

    private class Line(var minY: Float, var maxY: Float) {
        val strokes = ArrayList<Int>()

        fun add(i: Int, b: Box) {
            strokes += i
            minY = minOf(minY, b.minY)
            maxY = maxOf(maxY, b.maxY)
        }

        fun absorb(o: Line) {
            strokes += o.strokes
            minY = minOf(minY, o.minY)
            maxY = maxOf(maxY, o.maxY)
        }
    }

    /** Overlap of two vertical ranges, as a fraction of the shorter one. */
    private fun overlap(a0: Float, a1: Float, b0: Float, b1: Float): Float {
        val shorter = minOf(a1 - a0, b1 - b0)
        val common = minOf(a1, b1) - maxOf(a0, b0)
        return if (shorter <= 0f) (if (common >= 0f) 1f else 0f) else common / shorter
    }

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        return if (s.isEmpty()) 0f else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    /** Largest slant looked for, in degrees. */
    private const val MAX_SLANT_DEG = 40

    /** Without steps between strokes, a slant counts only if its rows are this much sharper than the horizontal ones. */
    private const val MIN_GAIN = 1.15

    /** Steps between strokes steeper than this are not along the line (a new line, a mark). */
    private const val MAX_STEP_DEG = 50.0

    /** Forward steps needed to trust their direction. */
    private const val MIN_STEPS = 2

    /** The profile refines the direction of the steps within this many degrees. */
    private const val REFINE_DEG = 6

    /** Less than this is horizontal writing (no straightening). */
    private const val FLAT_DEG = 2

    /** Rank (0 smallest, 1 largest) of the stroke size taken as that of a letter or word. */
    private const val TYPICAL_RANK = 0.75f

    /** Marks smaller than this fraction of a typical stroke are dots, accents, commas. */
    private const val SMALL_MARK = 0.35f

    /** Two strokes (or lines) whose heights overlap at least this much are on the same line. */
    private const val SAME_LINE = 0.4f
}
