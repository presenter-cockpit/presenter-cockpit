package it.presenter.app.deep

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.FileInputStream
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow

/**
 * The system [PdfRenderer] is native and does not run on the JVM: this one reads the page sizes with PdfBox
 * (crop box, rotation included) and draws nothing. Enough for everything but the look of the pages.
 */
@Implements(PdfRenderer::class)
class ShadowPdfRenderer {
    @RealObject
    private lateinit var real: PdfRenderer

    /** Width and height of each page, in points. */
    var sizes: List<Pair<Int, Int>> = emptyList()
        private set

    @Implementation
    fun __constructor__(input: ParcelFileDescriptor) {
        // Not closed: the descriptor belongs to the caller.
        val bytes = FileInputStream(input.fileDescriptor).readBytes()
        sizes = PDDocument.load(bytes).use { doc ->
            doc.pages.map { page ->
                val box = page.cropBox
                val w = box.width.toInt()
                val h = box.height.toInt()
                if (page.rotation % 180 != 0) h to w else w to h
            }
        }
    }

    @Implementation
    fun getPageCount(): Int = sizes.size

    @Implementation
    fun openPage(index: Int): PdfRenderer.Page {
        val constructor = PdfRenderer.Page::class.java.getDeclaredConstructor(PdfRenderer::class.java, Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(real, index)
    }

    @Implementation
    fun close() = Unit
}

@Implements(PdfRenderer.Page::class)
class ShadowPdfRendererPage {
    private var index = 0
    private var size = 1 to 1

    @Implementation
    fun __constructor__(renderer: PdfRenderer, index: Int) {
        this.index = index
        size = Shadow.extract<ShadowPdfRenderer>(renderer).sizes[index]
    }

    @Implementation
    fun getIndex(): Int = index

    @Implementation
    fun getWidth(): Int = size.first

    @Implementation
    fun getHeight(): Int = size.second

    @Implementation
    fun render(destination: Bitmap, destClip: Rect?, transform: Matrix?, renderMode: Int) = Unit

    @Implementation
    fun close() = Unit
}
