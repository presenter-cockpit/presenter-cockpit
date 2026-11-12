package it.presenter.app.doc

import it.presenter.app.testing.SampleTalk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Page reordering with PdfBox ([PdfInkStore.rearrange]) on the sample Beamer PDF ([SampleTalk]). The result
 * stays in `build/tmp/rearranged.pdf` to check it with qpdf and poppler.
 */
class PdfRearrangeSampleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `pages moved, blank added, slide removed, labels kept`() {
        val sample = SampleTalk.pdf(tmp.root)
        val labels = PdfInkStore.load(sample).use { it.documentCatalog.pageLabels.labelsByPageIndices.toList() }
        val count = labels.size
        // Page 0 goes to the end, a blank one after page 1, page 3 removed.
        val slots = PageEdits.slots(PageEdit.Move(0..0, count), count, labels)
        val newLabels = PageEdits.labels(slots, labels)!!
        val out1 = File("build/tmp/rearranged-1.pdf").apply { parentFile?.mkdirs() }
        PdfInkStore.rearrange(sample, out1, slots, newLabels)
        val slots2 = PageEdits.slots(PageEdit.InsertBlank(1), count, newLabels)
        val labels2 = PageEdits.labels(slots2, newLabels)!!
        val out2 = File("build/tmp/rearranged-2.pdf")
        val resolved = PdfInkStore.rearrange(out1, out2, slots2, labels2).slots
        val blank = resolved[2] as PageSlot.Blank
        assertTrue(blank.like == -1 && blank.width > 0f && blank.height > 0f)
        val slots3 = PageEdits.slots(PageEdit.Delete(3..3), count + 1, labels2)
        val labels3 = PageEdits.labels(slots3, labels2)!!
        val out = File("build/tmp/rearranged.pdf")
        PdfInkStore.rearrange(out2, out, slots3, labels3)
        PdfInkStore.load(out).use { doc ->
            assertEquals(count, doc.numberOfPages)
            assertEquals(labels3, doc.documentCatalog.pageLabels.labelsByPageIndices.toList())
            assertEquals(labels[0], labels3.last())
            assertEquals("${labels[2]}.1", labels3[2])
            val box = doc.getPage(2).mediaBox
            assertEquals(blank.width, box.width, 0.01f)
            assertEquals(blank.height, box.height, 0.01f)
            // The pages are the same as before, with their content and resources.
            assertTrue(doc.getPage(0).resources.xObjectNames.iterator().hasNext() || doc.getPage(0).resources.fontNames.iterator().hasNext())
        }
    }
}
