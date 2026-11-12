package it.presenter.app.doc

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionNamed
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File

/** Rectangle in page coordinates (1000 wide, origin at the top left). */
data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** Where a link leads: a page of the document or an external address. */
sealed interface LinkTarget {
    data class Page(val index: Int) : LinkTarget
    data class Uri(val uri: String) : LinkTarget
}

class PdfLink(val rect: PageRect, val target: LinkTarget)

/** Outline entry (PDF bookmarks), with the page it leads to (if it can be resolved). */
class OutlineItem(val title: String, val page: Int?, val children: List<OutlineItem>)

/**
 * Structure of the PDF read with PdfBox after opening: links per page and outline. Used to follow links
 * with the finger and by the navigation panel.
 */
class PdfStructure(val links: Map<Int, List<PdfLink>>, val outline: List<OutlineItem>) {

    /** Link under page point ([x], [y]), or null (the last one drawn wins). */
    fun linkAt(page: Int, x: Float, y: Float): PdfLink? = links[page]?.lastOrNull { it.rect.contains(x, y) }

    companion object {
        val EMPTY = PdfStructure(emptyMap(), emptyList())

        private const val MAX_OUTLINE_DEPTH = 12

        /**
         * Reads [file] (call from a worker thread). [aspects] are the height/width ratios of the pages used for
         * page coordinates. Every damaged part is skipped: an incomplete structure is better than none.
         */
        fun load(file: File, aspects: FloatArray): PdfStructure = PdfInkStore.load(file).use { doc ->
            val pages = doc.pages.toList()
            val reader = Reader(doc, pages)
            val links = HashMap<Int, List<PdfLink>>()
            for ((i, page) in pages.withIndex()) {
                val list = runCatching { reader.links(i, page, aspects.getOrElse(i) { 1f }) }.getOrDefault(emptyList())
                if (list.isNotEmpty()) links[i] = list
            }
            val outline = runCatching {
                doc.documentCatalog.documentOutline?.children()?.let { reader.outline(it, depth = 0) }
            }.getOrNull().orEmpty()
            PdfStructure(links, outline)
        }
    }

    private class Reader(private val doc: PDDocument, private val pages: List<PDPage>) {
        private val point = FloatArray(2)

        fun pageIndex(page: PDPage?): Int? = page?.let { p -> pages.indexOfFirst { it.cosObject === p.cosObject } }?.takeIf { it >= 0 }

        fun resolve(dest: PDDestination?): Int? = when (dest) {
            is PDPageDestination -> pageIndex(dest.page) ?: dest.pageNumber.takeIf { it in pages.indices }
            is PDNamedDestination -> resolve(runCatching { doc.documentCatalog.findNamedDestinationPage(dest) }.getOrNull())
            else -> null
        }

        fun links(index: Int, page: PDPage, aspect: Float): List<PdfLink> {
            val geometry = PageGeometry.of(page, aspect)
            val out = ArrayList<PdfLink>()
            for (a in page.annotations) {
                if (a !is PDAnnotationLink) continue
                val target = runCatching { target(a, index) }.getOrNull() ?: continue
                val r = a.rectangle ?: continue
                geometry.unmap(r.lowerLeftX, r.lowerLeftY, point)
                val (x1, y1) = point[0] to point[1]
                geometry.unmap(r.upperRightX, r.upperRightY, point)
                out.add(PdfLink(PageRect(minOf(x1, point[0]), minOf(y1, point[1]), maxOf(x1, point[0]), maxOf(y1, point[1])), target))
            }
            return out
        }

        private fun target(link: PDAnnotationLink, index: Int): LinkTarget? = when (val action = link.action) {
            is PDActionGoTo -> resolve(action.destination)?.let { LinkTarget.Page(it) }
            is PDActionURI -> action.uri?.takeIf { it.isNotBlank() }?.let { LinkTarget.Uri(it) }
            // Navigation symbols of Beamer and the like.
            is PDActionNamed -> when (action.n) {
                "NextPage" -> index + 1
                "PrevPage" -> index - 1
                "FirstPage" -> 0
                "LastPage" -> pages.lastIndex
                else -> null
            }?.takeIf { it in pages.indices }?.let { LinkTarget.Page(it) }
            null -> resolve(link.destination)?.let { LinkTarget.Page(it) }
            else -> null
        }

        fun outline(items: Iterable<PDOutlineItem>, depth: Int): List<OutlineItem> {
            // Malformed outlines may contain cycles: stop beyond this depth.
            if (depth > MAX_OUTLINE_DEPTH) return emptyList()
            return items.mapNotNull { item ->
                runCatching {
                    val page = runCatching { pageIndex(item.findDestinationPage(doc)) }.getOrNull()
                    OutlineItem(item.title.orEmpty().trim(), page, outline(item.children(), depth + 1))
                }.getOrNull()
            }
        }
    }
}
