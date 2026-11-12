package it.presenter.app.doc

import com.tom_roush.pdfbox.pdmodel.PDPage
import it.presenter.app.ink.PAGE_WIDTH

/**
 * Mapping between Presenter's page coordinates (width [PAGE_WIDTH], origin at the top left, page as
 * displayed) and PDF coordinates (points, origin at the bottom left), taking CropBox and /Rotate into
 * account. [aspect] is the height/width ratio of the displayed page.
 */
class PageGeometry(
    private val llx: Float,
    private val lly: Float,
    private val urx: Float,
    private val ury: Float,
    rotation: Int,
    aspect: Float,
) {
    private val rotation = ((rotation % 360) + 360) % 360
    private val sideways = this.rotation == 90 || this.rotation == 270
    private val sx = (if (sideways) ury - lly else urx - llx) / PAGE_WIDTH
    private val sy = (if (sideways) urx - llx else ury - lly) / (PAGE_WIDTH * aspect)

    /** PDF points per page unit (average of the two axes). */
    val scale = (sx + sy) / 2f

    /** From page coordinates ([u], [v]) to PDF coordinates, into [out]. */
    fun map(u: Float, v: Float, out: FloatArray) {
        val dx = u * sx
        val dy = v * sy
        when (rotation) {
            90 -> { out[0] = llx + dy; out[1] = lly + dx }
            180 -> { out[0] = urx - dx; out[1] = lly + dy }
            270 -> { out[0] = urx - dy; out[1] = ury - dx }
            else -> { out[0] = llx + dx; out[1] = ury - dy }
        }
    }

    /** From PDF coordinates ([x], [y]) to page coordinates, into [out]: the inverse of [map]. */
    fun unmap(x: Float, y: Float, out: FloatArray) {
        when (rotation) {
            90 -> { out[0] = (y - lly) / sx; out[1] = (x - llx) / sy }
            180 -> { out[0] = (urx - x) / sx; out[1] = (y - lly) / sy }
            270 -> { out[0] = (ury - y) / sx; out[1] = (urx - x) / sy }
            else -> { out[0] = (x - llx) / sx; out[1] = (ury - y) / sy }
        }
    }

    companion object {
        fun of(page: PDPage, aspect: Float): PageGeometry {
            val box = page.cropBox
            return PageGeometry(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY, page.rotation, aspect)
        }
    }
}
