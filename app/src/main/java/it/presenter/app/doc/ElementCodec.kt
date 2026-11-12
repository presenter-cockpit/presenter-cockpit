package it.presenter.app.doc

import androidx.ink.brush.Brush
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import it.presenter.app.ink.BrushFamilies
import it.presenter.app.ink.InkStroke
import it.presenter.app.ink.LineDash
import it.presenter.app.ink.PageElement
import it.presenter.app.ink.Shape
import it.presenter.app.ink.ShapeType
import it.presenter.app.ink.TextBox
import it.presenter.app.ink.TextFont
import it.presenter.app.ink.TextHighlight
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Serialization of a page element, in page coordinates (so independent of the PDF format). It goes into
 * the private key of the PDF annotations and into the session files.
 *
 * - Version 1: an Ink stroke, brush (family, color, size) + original inputs encoded by ink-storage.
 * - Version 2: the other elements (shapes, texts, highlights), a type byte and the fields.
 */
object ElementCodec {
    private const val STROKE = 1
    private const val ELEMENT = 2

    private const val SHAPE = 1
    private const val TEXT = 2
    private const val HIGHLIGHT = 3

    fun encode(element: PageElement): ByteArray? = runCatching { encodeOrThrow(element) }.getOrNull()

    private fun encodeOrThrow(element: PageElement): ByteArray? = when (element) {
        is InkStroke -> encodeStroke(element.stroke)
        else -> {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { d ->
                d.writeByte(ELEMENT)
                when (element) {
                    is Shape -> {
                        d.writeByte(SHAPE)
                        d.writeUTF(element.type.name)
                        writeFloats(d, element.points)
                        d.writeInt(element.color)
                        d.writeFloat(element.width)
                        d.writeInt(element.fill)
                        d.writeUTF(element.dash.name)
                        d.writeBoolean(element.arrow)
                    }
                    is TextBox -> {
                        d.writeByte(TEXT)
                        // Not writeUTF: it stops at 64 KB, and a pasted text can exceed that.
                        writeString(d, element.text)
                        d.writeFloat(element.x)
                        d.writeFloat(element.y)
                        d.writeFloat(element.width)
                        d.writeFloat(element.height)
                        d.writeFloat(element.size)
                        d.writeInt(element.color)
                        d.writeUTF(element.font.name)
                        d.writeBoolean(element.bold)
                        d.writeBoolean(element.italic)
                        d.writeFloat(element.rotation)
                    }
                    is TextHighlight -> {
                        d.writeByte(HIGHLIGHT)
                        writeFloats(d, element.quads)
                        d.writeInt(element.color)
                    }
                    is InkStroke -> error("unreachable")
                }
            }
            out.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): PageElement? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { d ->
            when (d.readUnsignedByte()) {
                STROKE -> decodeStroke(d)?.let(::InkStroke)
                ELEMENT -> when (d.readUnsignedByte()) {
                    SHAPE -> {
                        val type = ShapeType.valueOf(d.readUTF())
                        val points = readFloats(d)
                        Shape(type, points, d.readInt(), d.readFloat(), d.readInt(), LineDash.valueOf(d.readUTF()), d.readBoolean())
                            .takeIf { points.size >= minPoints(type) * 2 && points.size % 2 == 0 }
                    }
                    TEXT -> TextBox(
                        text = readString(d), x = d.readFloat(), y = d.readFloat(), width = d.readFloat(), height = d.readFloat(),
                        size = d.readFloat(), color = d.readInt(), font = TextFont.of(d.readUTF()),
                        bold = d.readBoolean(), italic = d.readBoolean(), rotation = d.readFloat(),
                    )
                    HIGHLIGHT -> TextHighlight(readFloats(d), d.readInt()).takeIf { it.quads.isNotEmpty() && it.quads.size % 8 == 0 }
                    else -> null
                }
                else -> null
            }
        }
    }.getOrNull()

    private fun minPoints(type: ShapeType) = when (type) {
        ShapeType.LINE -> 2
        ShapeType.CURVE -> 3
        ShapeType.POLYLINE -> 2
        ShapeType.POLYGON -> 3
        ShapeType.ELLIPSE -> 4
    }

    private fun writeString(d: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        d.writeInt(bytes.size)
        d.write(bytes)
    }

    private fun readString(d: DataInputStream): String {
        val n = d.readInt()
        // Never more than what is left: damaged data must not allocate megabytes for nothing.
        require(n in 0..minOf(MAX_FLOATS * 4, d.available()))
        return String(ByteArray(n).also(d::readFully), Charsets.UTF_8)
    }

    private fun writeFloats(d: DataOutputStream, values: FloatArray) {
        d.writeInt(values.size)
        values.forEach(d::writeFloat)
    }

    private fun readFloats(d: DataInputStream): FloatArray {
        val n = d.readInt()
        require(n in 0..minOf(MAX_FLOATS, d.available() / 4))
        return FloatArray(n) { d.readFloat() }
    }

    private fun encodeStroke(stroke: Stroke): ByteArray? {
        val key = BrushFamilies.keyOf(stroke.brush.family) ?: return null
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeByte(STROKE)
            d.writeUTF(key)
            d.writeInt(stroke.brush.colorIntArgb)
            d.writeFloat(stroke.brush.size)
            d.writeFloat(stroke.brush.epsilon)
            val inputs = ByteArrayOutputStream().also { stroke.inputs.encode(it) }.toByteArray()
            d.writeInt(inputs.size)
            d.write(inputs)
        }
        return out.toByteArray()
    }

    private fun decodeStroke(d: DataInputStream): Stroke? {
        val family = BrushFamilies.byKey(d.readUTF()) ?: return null
        val color = d.readInt()
        val size = d.readFloat()
        val epsilon = d.readFloat()
        val inputSize = d.readInt()
        require(inputSize in 0..d.available())
        val inputs = ByteArray(inputSize).also { d.readFully(it) }
        val batch = StrokeInputBatch.decode(ByteArrayInputStream(inputs))
        return Stroke(Brush.createWithColorIntArgb(family, color, size, epsilon), batch)
    }

    private const val MAX_FLOATS = 1 shl 20
}
