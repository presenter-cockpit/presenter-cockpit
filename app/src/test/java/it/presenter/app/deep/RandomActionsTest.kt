package it.presenter.app.deep

import it.presenter.app.doc.PageEdit
import it.presenter.app.doc.PresenterDocument
import it.presenter.app.testing.Property
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Random sequences of user actions on a PDF (draw, erase, undo, redo, turn page, save, move, delete or add
 * pages, close with or without saving, restart the app, the file changed by another app), checking after
 * every step what must always hold:
 * - the unsaved mark is there exactly when the document differs from the file (annotations, or a page
 *   order not saved yet);
 * - after a save the file holds what the app shows;
 * - a restart brings back what was shown, unsaved changes included;
 * - closing without saving leaves the file as it was;
 * - a file changed by another app is reloaded by itself if there is nothing to save, otherwise it is
 *   reloaded or overwritten only when asked.
 * The failing seed and the actions done are in the message (`-Pproperty.seed=N` replays it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowPdfRenderer::class, ShadowPdfRendererPage::class])
class RandomActionsTest {
    private val rig = AppRig()

    @After
    fun closeAll() = rig.session.closeEverything()

    @Test
    fun `random actions keep the unsaved mark and the file consistent`() = Property.check("random actions", runs = 40) { r ->
        val file = rig.samplePdf("talk-${r.nextInt(1_000_000)}.pdf")
        var doc = rig.open(file)
        // Model of the pages shown: page of the file on disk shown by each one (-1: blank page added).
        var pages = (0 until doc.pageCount).toList()
        var diskPages = doc.pageCount
        // The page order differs from the file (page edits not saved yet): undo cannot take it back.
        fun orderChanged() = pages != (0 until diskPages).toList()
        // Unsaved changes brought back by a restart: they cannot be undone either.
        var restoredUnsaved = false
        fun fileOrder() {
            pages = (0 until doc.pageCount).toList()
            diskPages = doc.pageCount
            restoredUnsaved = false
        }
        val log = ArrayList<String>()

        fun check(step: String) {
            log += step
            val differs = rig.annotations(doc) != rig.annotationsOnDisk(file, doc)
            assertEquals(
                "unsaved mark (order changed: ${orderChanged()}, restored: $restoredUnsaved, annotations differ: $differs) " +
                    "after: ${log.joinToString(", ")}",
                orderChanged() || restoredUnsaved || differs, doc.isDirty,
            )
        }

        repeat(30) {
            when (r.nextInt(14)) {
                0, 1, 2 -> {
                    rig.draw(doc)
                    check("draw p${doc.pageIndex}")
                }
                3 -> {
                    val elements = doc.inkOf(doc.pageIndex).elements
                    if (elements.isNotEmpty()) {
                        rig.erase(doc, doc.pageIndex, elements[r.nextInt(elements.size)])
                        check("erase p${doc.pageIndex}")
                    }
                }
                4 -> {
                    rig.session.undo()
                    check("undo p${doc.pageIndex}")
                }
                5 -> {
                    rig.session.redo()
                    check("redo p${doc.pageIndex}")
                }
                6 -> {
                    rig.goTo(doc, r.nextInt(doc.pageCount))
                    check("go to p${doc.pageIndex}")
                }
                7 -> {
                    rig.flows.save(doc)
                    fileOrder()
                    log += "save"
                    assertEquals("file after: ${log.joinToString(", ")}", rig.annotations(doc), rig.annotationsOnDisk(file, doc))
                    check("saved")
                }
                8 -> {
                    // A page outside the first slide to the front: the order really changes.
                    val page = (1 until doc.pageCount).filter { doc.slides.firstPageOf(it) > 0 }.randomOrNull(r) ?: return@repeat
                    rig.flows.editPages(doc, PageEdit.Move(page..page, 0))
                    doc = rig.documentOf(file)!!
                    rig.session.finishTransition()
                    pages = listOf(pages[page]) + pages.filterIndexed { i, _ -> i != page }
                    check("move p$page to the front")
                }
                9 -> {
                    // Close without saving and open again: the file is what was saved.
                    val bytes = file.readBytes()
                    rig.flows.closeDocument(doc)
                    log += "close without saving"
                    assertTrue("file untouched after: ${log.joinToString(", ")}", bytes.contentEquals(file.readBytes()))
                    doc = rig.open(file)
                    fileOrder()
                    assertEquals("reopened after: ${log.joinToString(", ")}", rig.annotationsOnDisk(file, doc), rig.annotations(doc))
                    check("reopened")
                }
                11 -> {
                    // A slide or a single page (overlays off) deleted from the overview, at least three pages left; or a
                    // blank page added.
                    val page = if (r.nextBoolean()) doc.pageIndex else r.nextInt(doc.pageCount)
                    val slide = doc.slides.pagesOf(page)
                    val edit = if (doc.pageCount - slide.count() >= 3 && r.nextBoolean()) {
                        PageEdit.Delete(if (r.nextBoolean()) slide else page..page)
                    } else {
                        PageEdit.InsertBlank(page)
                    }
                    rig.flows.editPages(doc, edit)
                    doc = rig.documentOf(file)!!
                    rig.session.finishTransition()
                    pages = when (edit) {
                        is PageEdit.Delete -> pages.filterIndexed { i, _ -> i !in edit.pages }
                        is PageEdit.InsertBlank -> pages.take(edit.after + 1) + -1 + pages.drop(edit.after + 1)
                        else -> error("$edit")
                    }
                    // Often to the new blank page, to draw there.
                    if (edit is PageEdit.InsertBlank && r.nextBoolean()) rig.goTo(doc, edit.after + 1)
                    check("$edit")
                }
                12, 13 -> {
                    // Another app writes the file (here: the talk without annotations), then the app checks the disk.
                    val shown = rig.annotations(doc)
                    val dirty = doc.isDirty
                    val before = doc
                    rig.changeOnDisk(file)
                    rig.flows.checkDisk()
                    log += "changed on disk"
                    doc = rig.documentOf(file)!!
                    if (!dirty) {
                        assertTrue("reloaded by itself after: ${log.joinToString(", ")}", doc !== before)
                        fileOrder()
                        check("reloaded by itself")
                    } else {
                        // Nothing lost: the user is told and decides.
                        assertTrue("kept after: ${log.joinToString(", ")}", doc === before && doc.changedOnDisk)
                        assertEquals("kept after: ${log.joinToString(", ")}", shown, rig.annotations(doc))
                        if (r.nextBoolean()) {
                            rig.flows.reload(doc)
                            doc = rig.documentOf(file)!!
                            rig.session.finishTransition()
                            fileOrder()
                            assertEquals("reloaded after: ${log.joinToString(", ")}", rig.annotationsOnDisk(file, doc), rig.annotations(doc))
                            check("reloaded, changes discarded")
                        } else {
                            rig.flows.save(doc)
                            fileOrder()
                            assertEquals("overwritten after: ${log.joinToString(", ")}", shown, rig.annotationsOnDisk(file, doc))
                            check("overwritten")
                        }
                    }
                }
                10 -> {
                    val shown = rig.annotations(doc)
                    val dirty = doc.isDirty
                    val page = doc.pageIndex
                    rig.restart()
                    doc = rig.documentOf(file)!!
                    log += "restart"
                    assertEquals("restored after: ${log.joinToString(", ")}", shown, rig.annotations(doc))
                    assertEquals("page after: ${log.joinToString(", ")}", page, doc.pageIndex)
                    // Restored unsaved changes cannot be undone: they stay to save until saved.
                    restoredUnsaved = dirty
                    check("restored")
                }
            }
        }
        rig.session.closeEverything()
        file.delete()
    }
}
