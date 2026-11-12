package it.presenter.app.doc

import android.content.Context
import androidx.ink.brush.ColorFunction
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.text.PDFTextStripper
import it.presenter.app.R
import it.presenter.app.UserFacingException
import it.presenter.app.ink.InkStroke
import it.presenter.app.ink.LineDash
import it.presenter.app.ink.PageElement
import it.presenter.app.ink.Shape
import it.presenter.app.ink.ShapeType
import it.presenter.app.ink.TextBox
import it.presenter.app.ink.TextHighlight
import it.presenter.app.ink.TextLayouts
import java.io.File
import java.util.Calendar
import java.util.UUID

/**
 * Annotations saved inside the PDF as standard annotations (`/Ink`, `/Line`, `/FreeText`, `/Highlight`…):
 * any PDF reader shows them (thanks to the included vector appearance) and any editor can remove them.
 * Each annotation also carries the private key `/PresenterInk` with the original element encoded: on
 * reopening, Presenter reads it back and turns it into an editable element again.
 *
 * Display stays with the system PdfRenderer (faster and more faithful): PdfBox is used only to read and
 * write the annotations and the page structure.
 */
object PdfInkStore {
    private val INK_KEY: COSName = COSName.getPDFName("PresenterInk")

    /**
     * Copy of an element on the other overlays of the same Beamer slide (ink is shared by the overlays):
     * visible in other readers, ignored and removed by Presenter on reopening, because the original is on the
     * first page of the slide.
     */
    private val COPY_KEY: COSName = COSName.getPDFName("PresenterInkCopy")

    fun init(context: Context) = PDFBoxResourceLoader.init(context.applicationContext)

    /**
     * [overlays]: pages with the same label as the previous one that also repeat its text, i.e. real Beamer
     * overlays (not section start pages).
     */
    class Inspection(val elements: Map<Int, List<PageElement>>, val labels: List<String>?, val overlays: Set<Int> = emptySet())

    /**
     * Reads from [file] the page labels (needed to recognize Beamer overlays) and Presenter's elements, and
     * rewrites it without them, so the PdfRenderer does not draw them twice.
     */
    fun inspect(file: File): Inspection {
        val elements = HashMap<Int, MutableList<PageElement>>()
        var labels: List<String>? = null
        var overlays: Set<Int> = emptySet()
        var stripped = false
        val tmp = File(file.path + ".strip")
        load(file).use { doc ->
            labels = runCatching { doc.documentCatalog.pageLabels?.labelsByPageIndices?.toList() }.getOrNull()
            overlays = runCatching { confirmOverlays(doc, labels) }.getOrDefault(emptySet())
            // The annotations of every page are always inspected: searching the bytes for "/PresenterInk" would miss
            // files rewritten with compressed objects (object streams, e.g. Master PDF Editor), and the strokes would
            // vanish from the app while staying in the file.
            for ((index, page) in doc.pages.withIndex()) {
                val annotations = page.annotations
                val keep = ArrayList<PDAnnotation>(annotations.size)
                for (a in annotations) {
                    if (a.cosObject.containsKey(COPY_KEY)) continue
                    val bytes = (a.cosObject.getDictionaryObject(INK_KEY) as? COSString)?.bytes
                    // An element that cannot be decoded (future version, damaged data) stays an ordinary annotation:
                    // removing it would make it disappear from the file.
                    val element = bytes?.let(ElementCodec::decode)
                    if (element == null) keep.add(a) else elements.getOrPut(index) { ArrayList() }.add(element)
                }
                if (keep.size != annotations.size) {
                    page.annotations = keep
                    stripped = true
                }
            }
            if (stripped) doc.save(tmp)
        }
        // The rename happens with the document closed: PdfBox reads the original file until the end.
        if (stripped && !tmp.renameTo(file)) throw UserFacingException(R.string.error_prepare_document)
        return Inspection(elements, labels, overlays)
    }

    /** Pages with the same label as the previous one that really are its overlays ([Slides.isContinuation]). */
    private fun confirmOverlays(doc: PDDocument, labels: List<String>?, only: Set<Int>? = null): Set<Int> {
        if (labels == null) return emptySet()
        val stripper = PDFTextStripper()
        // Each page is extracted once: in a slide with several overlays it is both "after" and "before".
        val cache = HashMap<Int, String>()
        fun text(page: Int): String = cache.getOrPut(page) {
            stripper.startPage = page + 1
            stripper.endPage = page + 1
            stripper.getText(doc)
        }
        val out = HashSet<Int>()
        for (p in 1 until minOf(labels.size, doc.numberOfPages)) {
            if (only != null && p !in only) continue
            if (labels[p] == labels[p - 1] && Slides.isContinuation(text(p - 1), text(p))) out.add(p)
        }
        return out
    }

    /**
     * Writes into [dest] the PDF [src] (without Presenter's elements) plus the elements [elements], per
     * slide: they go on the first page of the slide and, as copies, on the other overlays ([pagesOf]).
     * [aspects] are the height/width ratios used for page coordinates.
     */
    fun write(src: File, dest: File, elements: Map<Int, List<PageElement>>, aspects: FloatArray, pagesOf: (Int) -> IntRange) {
        load(src).use { doc ->
            for ((index, list) in elements) {
                if (list.isEmpty() || index !in 0 until doc.numberOfPages) continue
                for (target in pagesOf(index)) {
                    if (target !in 0 until doc.numberOfPages) continue
                    val page = doc.getPage(target)
                    val geometry = PageGeometry.of(page, aspects[target])
                    val annotations = page.annotations
                    for (e in list) annotation(doc, e, geometry, copy = target != index)?.let(annotations::add)
                    page.annotations = annotations
                }
            }
            doc.save(dest)
        }
    }

    /**
     * Writes into [dest] the PDF [src] with the pages in the order [slots] (blank pages included) and the
     * labels [labels] (one per page; null: the existing ones are kept). Pages stay the same objects, so links
     * and outline keep pointing at them; those to removed pages lead nowhere (the pages are emptied).
     * [candidates]: pages to check as overlays of the page before them ([PageEdits.overlayCandidates]).
     */
    fun rearrange(src: File, dest: File, slots: List<PageSlot>, labels: List<String>?, candidates: Set<Int> = emptySet()): Rearranged {
        load(src).use { doc ->
            val old = doc.pages.toList()
            // All pages end up directly under the root: what they inherited from intermediate tree nodes (resources,
            // format, rotation) must be written on the page itself.
            for (p in old) {
                p.resources = p.resources
                p.mediaBox = p.mediaBox
                p.cropBox = p.cropBox
                p.rotation = p.rotation
            }
            val root = doc.pages.cosObject
            val kids = COSArray()
            val resolved = slots.map { slot ->
                val (page, done) = when (slot) {
                    is PageSlot.Page -> old[slot.index] to slot
                    is PageSlot.Blank -> {
                        val (w, h) = if (slot.like >= 0) displayedSize(old[slot.like]) else slot.width to slot.height
                        // Explicit crop box and rotation too: the root could have some to inherit.
                        PDPage(PDRectangle(w, h)).apply { resources = PDResources(); cropBox = PDRectangle(w, h); rotation = 0 } to
                            slot.copy(like = -1, width = w, height = h)
                    }
                }
                page.cosObject.setItem(COSName.PARENT, root)
                kids.add(page.cosObject)
                done
            }
            root.setItem(COSName.KIDS, kids)
            root.setInt(COSName.COUNT, kids.size())
            // Removed pages are often still referenced (outline, links, Beamer's named destinations) and would be
            // written to the file with all their content: emptied, only a dictionary that leads nowhere is left.
            val kept = slots.filterIsInstance<PageSlot.Page>().mapTo(HashSet()) { it.index }
            old.forEachIndexed { i, p -> if (i !in kept) p.cosObject.clear() }
            if (labels != null) {
                // One range per page with just the label as prefix: the previous labels stay (also the "5", "5" of
                // Beamer overlays) in any order.
                val pageLabels = PDPageLabels(doc)
                labels.forEachIndexed { j, label -> pageLabels.setLabelItem(j, PDPageLabelRange().apply { prefix = label }) }
                doc.documentCatalog.pageLabels = pageLabels
            }
            // New neighbors with the same label: overlays only if the text continues, as when reading.
            val confirmed = if (candidates.isEmpty()) emptySet() else confirmOverlays(doc, labels, candidates)
            doc.save(dest)
            return Rearranged(resolved, confirmed)
        }
    }

    /** Result of [rearrange]: the order with the blank pages measured (points) and the confirmed overlays. */
    class Rearranged(val slots: List<PageSlot>, val overlays: Set<Int>)

    /** Width and height of the page as displayed (crop box, with rotation), in points. */
    private fun displayedSize(page: PDPage): Pair<Float, Float> {
        val box = page.cropBox
        return if (page.rotation % 180 != 0) box.height to box.width else box.width to box.height
    }

    /**
     * PDFs protected only by permissions (no opening password) are common: without removing the protection
     * PdfBox refuses to save them.
     */
    internal fun load(file: File): PDDocument =
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).apply { isAllSecurityToBeRemoved = true }

    private fun annotation(doc: PDDocument, e: PageElement, g: PageGeometry, copy: Boolean): PDAnnotation? = when (e) {
        is InkStroke -> inkAnnotation(doc, e, g, copy)
        is Shape -> shapeAnnotation(doc, e, g, copy)
        is TextBox -> textAnnotation(doc, e, g, copy)
        is TextHighlight -> highlightAnnotation(doc, e, g, copy)
    }

    private fun inkAnnotation(doc: PDDocument, element: InkStroke, g: PageGeometry, copy: Boolean): PDAnnotation? {
        val stroke = element.stroke
        val data = ElementCodec.encode(element) ?: return null
        val coats = outlines(stroke, g)
        if (coats.isEmpty()) return null
        val rect = rectOf(coats.values.flatten(), 1f)
        val argb = stroke.brush.colorIntArgb
        val rgb = rgbOf(argb)
        val highlighter = element.isHighlight
        val annotation = markup(doc, "Ink", rect, argb) { cs ->
            if (highlighter) {
                cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { blendMode = BlendMode.MULTIPLY })
            }
            cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2])
            // One layer per brush coat (the soft marker has transparent halos under the solid core), each with its
            // own opacity, as on screen.
            for ((group, polygons) in coats) {
                val opacity = coatOpacity(stroke, group)
                cs.saveGraphicsState()
                if (opacity < 1f) cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = opacity })
                for (poly in polygons) cs.polygon(poly, closed = true)
                // Nonzero rule: the union of the outlines, without holes where the stroke crosses itself.
                cs.fill()
                cs.restoreGraphicsState()
            }
        }
        // The opacity is only in /CA: applied in the appearance too, it would double.
        val alpha = alphaOf(argb)
        if (alpha < 1f) annotation.constantOpacity = alpha
        annotation.cosObject.setItem(COSName.getPDFName("InkList"), inkList(stroke, g))
        annotation.cosObject.setItem(COSName.BS, COSDictionary().apply { setFloat(COSName.W, stroke.brush.size * g.scale) })
        mark(annotation, data, copy)
        return annotation
    }

    /** On copies the mark is enough: the real (editable) element is on the first page of the slide. */
    private fun mark(annotation: PDAnnotation, data: ByteArray, copy: Boolean) {
        if (copy) annotation.cosObject.setBoolean(COPY_KEY, true) else annotation.cosObject.setItem(INK_KEY, COSString(data))
    }

    /** Empty markup annotation of type [subtype], with color, rectangle and appearance drawn by [draw]. */
    private fun markup(
        doc: PDDocument,
        subtype: String,
        rect: PDRectangle,
        color: Int?,
        draw: (PDPageContentStream) -> Unit,
    ): PDAnnotationMarkup {
        val annotation = PDAnnotationMarkup()
        annotation.cosObject.setName(COSName.SUBTYPE, subtype)
        annotation.rectangle = rect
        if (color != null) annotation.color = PDColor(rgbOf(color), PDDeviceRGB.INSTANCE)
        annotation.isPrinted = true
        annotation.annotationName = "presenter-${UUID.randomUUID()}"
        annotation.setModifiedDate(Calendar.getInstance())
        val appearance = PDAppearanceStream(doc)
        appearance.bBox = rect
        appearance.resources = PDResources()
        PDPageContentStream(doc, appearance).use(draw)
        annotation.appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance) }
        return annotation
    }

    private fun rgbOf(argb: Int) = floatArrayOf(((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f)

    private fun alphaOf(argb: Int) = (argb ushr 24) / 255f

    private fun mapped(points: FloatArray, g: PageGeometry): FloatArray {
        val out = FloatArray(points.size)
        val p = FloatArray(2)
        for (k in points.indices step 2) {
            g.map(points[k], points[k + 1], p)
            out[k] = p[0]
            out[k + 1] = p[1]
        }
        return out
    }

    private fun rectOf(polygons: List<FloatArray>, pad: Float): PDRectangle {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (poly in polygons) for (k in poly.indices step 2) {
            minX = minOf(minX, poly[k]); maxX = maxOf(maxX, poly[k])
            minY = minOf(minY, poly[k + 1]); maxY = maxOf(maxY, poly[k + 1])
        }
        return PDRectangle(minX - pad, minY - pad, maxX - minX + 2 * pad, maxY - minY + 2 * pad)
    }

    private fun PDPageContentStream.polygon(poly: FloatArray, closed: Boolean) {
        moveTo(poly[0], poly[1])
        for (k in 2 until poly.size step 2) lineTo(poly[k], poly[k + 1])
        if (closed) closePath()
    }

    private fun PDPageContentStream.alpha(stroking: Float, nonStroking: Float, multiply: Boolean = false) {
        if (stroking >= 1f && nonStroking >= 1f && !multiply) return
        setGraphicsStateParameters(PDExtendedGraphicsState().apply {
            if (stroking < 1f) strokingAlphaConstant = stroking
            if (nonStroking < 1f) nonStrokingAlphaConstant = nonStroking
            if (multiply) blendMode = BlendMode.MULTIPLY
        })
    }

    private fun floats(values: FloatArray) = COSArray().apply { values.forEach { add(COSFloat(it)) } }

    /** Shape: standard `/Line`, `/PolyLine` or `/Polygon` (curves and ellipses sampled), with the vector appearance. */
    private fun shapeAnnotation(doc: PDDocument, s: Shape, g: PageGeometry, copy: Boolean): PDAnnotation? {
        val data = ElementCodec.encode(s) ?: return null
        val outline = mapped(s.outline(), g)
        val head = s.arrowHead()?.let { mapped(it, g) }
        val width = s.width * g.scale
        val rect = rectOf(listOfNotNull(outline, head), width + 1f)
        val subtype = when {
            s.closed -> "Polygon"
            s.type == ShapeType.LINE -> "Line"
            else -> "PolyLine"
        }
        val annotation = markup(doc, subtype, rect, s.color) { cs ->
            cs.saveGraphicsState()
            cs.alpha(alphaOf(s.color), alphaOf(s.fill))
            cs.setLineWidth(width)
            cs.setLineCapStyle(1)
            cs.setLineJoinStyle(1)
            val stroke = rgbOf(s.color)
            cs.setStrokingColor(stroke[0], stroke[1], stroke[2])
            if (s.closed && s.fill != 0) {
                val f = rgbOf(s.fill)
                cs.setNonStrokingColor(f[0], f[1], f[2])
                cs.polygon(outline, closed = true)
                cs.fill()
            }
            when (s.dash) {
                LineDash.SOLID -> Unit
                LineDash.DASHED -> cs.setLineDashPattern(floatArrayOf(4f * width + 2f * g.scale, 3f * width + 2f * g.scale), 0f)
                LineDash.DOTTED -> cs.setLineDashPattern(floatArrayOf(0.01f, 2.2f * width + g.scale), 0f)
            }
            cs.polygon(outline, s.closed)
            cs.stroke()
            cs.restoreGraphicsState()
            if (head != null) {
                // The arrow head has the line's opacity.
                cs.saveGraphicsState()
                cs.alpha(alphaOf(s.color), alphaOf(s.color))
                cs.setStrokingColor(stroke[0], stroke[1], stroke[2])
                cs.setNonStrokingColor(stroke[0], stroke[1], stroke[2])
                cs.setLineWidth(width)
                cs.setLineJoinStyle(1)
                cs.polygon(head, closed = true)
                cs.fillAndStroke()
                cs.restoreGraphicsState()
            }
        }
        val o = annotation.cosObject
        when (subtype) {
            "Line" -> o.setItem(COSName.getPDFName("L"), floats(outline.copyOfRange(0, 4)))
            else -> o.setItem(COSName.getPDFName("Vertices"), floats(outline))
        }
        if (s.hasArrow) {
            o.setItem(COSName.getPDFName("LE"), COSArray().apply {
                add(COSName.getPDFName("None"))
                add(COSName.getPDFName("ClosedArrow"))
            })
        }
        if (s.closed && s.fill != 0) o.setItem(COSName.getPDFName("IC"), floats(rgbOf(s.fill)))
        o.setItem(COSName.BS, COSDictionary().apply {
            setFloat(COSName.W, width)
            if (s.dash != LineDash.SOLID) {
                setName(COSName.S, "D")
                setItem(COSName.D, floats(if (s.dash == LineDash.DASHED) floatArrayOf(4f * width, 3f * width) else floatArrayOf(1f, 2f * width)))
            }
        })
        // Opacities are already in the appearance: in /CA too they would multiply.
        mark(annotation, data, copy)
        return annotation
    }

    /** Text box: `/FreeText` with the text in `/Contents` and the glyphs drawn as paths. */
    private fun textAnnotation(doc: PDDocument, t: TextBox, g: PageGeometry, copy: Boolean): PDAnnotation? {
        val data = ElementCodec.encode(t) ?: return null
        val glyphs = TextLayouts.outlines(t).map { mapped(it, g) }
        val corners = mapped(t.corners(), g)
        val rect = rectOf(listOf(corners) + glyphs, 1f)
        val rgb = rgbOf(t.color)
        // For /FreeText the /C color is the box background: the text color goes in /DA.
        val annotation = markup(doc, "FreeText", rect, null) { cs ->
            if (glyphs.isEmpty()) return@markup
            cs.alpha(1f, alphaOf(t.color))
            cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2])
            for (poly in glyphs) cs.polygon(poly, closed = true)
            cs.fill()
        }
        annotation.contents = t.text
        val o = annotation.cosObject
        o.setString(COSName.DA, "/Helv ${"%.1f".format(java.util.Locale.ROOT, t.size * g.scale)} Tf ${rgb.joinToString(" ") { "%.3f".format(java.util.Locale.ROOT, it) }} rg")
        o.setItem(COSName.BS, COSDictionary().apply { setFloat(COSName.W, 0f) })
        mark(annotation, data, copy)
        return annotation
    }

    /**
     * Highlight snapped to text: a standard `/Highlight` with the lines' `/QuadPoints` (other readers
     * recognize it as a text highlight), opacity in `/CA`, Multiply blending.
     */
    private fun highlightAnnotation(doc: PDDocument, h: TextHighlight, g: PageGeometry, copy: Boolean): PDAnnotation? {
        val data = ElementCodec.encode(h) ?: return null
        val quads = (0 until h.quadCount).map { mapped(h.quad(it), g) }
        val rect = rectOf(quads, 1f)
        val rgb = rgbOf(h.color)
        val annotation = markup(doc, "Highlight", rect, h.color) { cs ->
            cs.alpha(1f, 1f, multiply = true)
            cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2])
            for (q in quads) cs.polygon(q, closed = true)
            cs.fill()
        }
        annotation.constantOpacity = alphaOf(h.color)
        // QuadPoints order as Acrobat writes it: top-left, top-right, bottom-left, bottom-right.
        annotation.cosObject.setItem(COSName.getPDFName("QuadPoints"), COSArray().apply {
            for (q in quads) for (k in intArrayOf(0, 1, 2, 3, 6, 7, 4, 5)) add(COSFloat(q[k]))
        })
        mark(annotation, data, copy)
        return annotation
    }

    /** Opacity of a brush layer (coat), from its color functions. */
    @OptIn(ExperimentalInkCustomBrushApi::class)
    private fun coatOpacity(stroke: Stroke, group: Int): Float {
        val paint = stroke.brush.family.coats.getOrNull(group)?.paintPreferences?.firstOrNull() ?: return 1f
        return paint.colorFunctions.filterIsInstance<ColorFunction.OpacityMultiplier>().fold(1f) { a, f -> a * f.multiplier }
    }

    /**
     * Outlines of the stroke (in PDF coordinates) as flat lists x0, y0, x1, y1, …, grouped by brush layer
     * (coat), from the first drawn to the last.
     */
    private fun outlines(stroke: Stroke, g: PageGeometry): Map<Int, List<FloatArray>> {
        val shape = stroke.shape
        val v = MutableVec()
        val out = FloatArray(2)
        val result = LinkedHashMap<Int, MutableList<FloatArray>>()
        for (group in 0 until shape.getRenderGroupCount()) {
            for (o in 0 until shape.getOutlineCount(group)) {
                val n = shape.getOutlineVertexCount(group, o)
                if (n < 3) continue
                val poly = FloatArray(n * 2)
                for (k in 0 until n) {
                    shape.populateOutlinePosition(group, o, k, v)
                    g.map(v.x, v.y, out)
                    poly[2 * k] = out[0]
                    poly[2 * k + 1] = out[1]
                }
                result.getOrPut(group) { ArrayList() }.add(poly)
            }
        }
        return result
    }

    private fun inkList(stroke: Stroke, g: PageGeometry): COSArray {
        val path = COSArray()
        val input = StrokeInput()
        val out = FloatArray(2)
        for (k in 0 until stroke.inputs.size) {
            stroke.inputs.populate(k, input)
            g.map(input.x, input.y, out)
            path.add(COSFloat(out[0]))
            path.add(COSFloat(out[1]))
        }
        return COSArray().apply { add(path) }
    }

}
