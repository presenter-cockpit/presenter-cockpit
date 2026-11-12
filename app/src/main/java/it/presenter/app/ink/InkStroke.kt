package it.presenter.app.ink

import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput

/** Freehand Jetpack Ink stroke (pen, marker, highlighter). */
class InkStroke(val stroke: Stroke) : PageElement {
    override val color: Int get() = stroke.brush.colorIntArgb

    override val isHighlight: Boolean by lazy { BrushFamilies.keyOf(stroke.brush.family) == BrushFamilies.HIGHLIGHTER }

    /**
     * The same stroke with a solid color: the highlighter is drawn this way in a single semi-transparent
     * layer, so where two strokes overlap the color does not add up.
     */
    val opaque: Stroke by lazy { stroke.copy(stroke.brush.copyWithColorIntArgb(color or 0xFF000000.toInt())) }

    override fun bounds(): Box {
        val box = stroke.shape.computeBoundingBox() ?: return Box(0f, 0f, 0f, 0f)
        return Box(box.xMin, box.yMin, box.xMax, box.yMax)
    }

    override fun transformed(t: Affine): InkStroke {
        if (t.isIdentity) return this
        val inputs = stroke.inputs
        val input = StrokeInput()
        val batch = MutableStrokeInputBatch()
        for (k in 0 until inputs.size) {
            inputs.populate(k, input)
            val x = t.mapX(input.x, input.y)
            val y = t.mapY(input.x, input.y)
            input.update(
                x = x, y = y,
                elapsedTimeMillis = input.elapsedTimeMillis,
                toolType = input.toolType,
                strokeUnitLengthCm = input.strokeUnitLengthCm,
                pressure = input.pressure,
                tiltRadians = input.tiltRadians,
                orientationRadians = input.orientationRadians,
            )
            runCatching { batch.add(input) }
        }
        val brush = stroke.brush.copy(size = (stroke.brush.size * t.scale).coerceAtLeast(MIN_SIZE))
        return runCatching { InkStroke(Stroke(brush, batch)) }.getOrDefault(this)
    }

    override fun recolored(color: Int) = InkStroke(stroke.copy(stroke.brush.copyWithColorIntArgb(keepAlpha(this.color, color))))

    override val strokeWidth: Float get() = stroke.brush.size

    override fun withStrokeWidth(width: Float) = InkStroke(Stroke(stroke.brush.copy(size = width), stroke.inputs))

    override fun touches(x0: Float, y0: Float, x1: Float, y1: Float, r: Float): Boolean {
        val area = ImmutableParallelogram.fromSegmentAndPadding(ImmutableSegment(ImmutableVec(x0, y0), ImmutableVec(x1, y1)), r)
        return stroke.shape.computeCoverageIsGreaterThan(area, 0f)
    }

    override fun samplePoints(): FloatArray {
        val inputs = stroke.inputs
        val input = StrokeInput()
        return FloatArray(inputs.size * 2).also { out ->
            for (k in 0 until inputs.size) {
                inputs.populate(k, input)
                out[2 * k] = input.x
                out[2 * k + 1] = input.y
            }
        }
    }

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)

    private companion object {
        const val MIN_SIZE = 0.3f
    }
}
