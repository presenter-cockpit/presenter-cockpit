package it.presenter.app.deep

import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import it.presenter.app.PresenterApp
import it.presenter.app.doc.DocSource
import it.presenter.app.doc.PdfInkStore
import it.presenter.app.doc.PresenterDocument
import it.presenter.app.ink.PageElement
import it.presenter.app.session.DocumentFlows
import it.presenter.app.session.PresenterSession
import it.presenter.app.session.SessionStore
import it.presenter.app.testing.Elements
import java.io.File
import java.time.Duration
import java.util.concurrent.Executor
import org.robolectric.Shadows.shadowOf

/**
 * The app without its UI, for the deep tests: the real session and [DocumentFlows], with executors that run
 * at once (every flow ends before the call returns). The actions are those the UI would do.
 */
class AppRig {
    val app: PresenterApp = ApplicationProvider.getApplicationContext()
    val session: PresenterSession get() = app.session
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Delayed work (retries) goes through the main looper: [waitUntil] lets the time pass. */
    val flows = DocumentFlows(
        app, Executor { it.run() }, Executor { it.run() }, main = { it() },
        later = { ms, block -> mainHandler.postDelayed(block, ms) },
    )

    init {
        // As after the restore at launch: the session is saved from now on.
        app.sessionRestored = true
        // Process-wide: left over by an earlier test.
        SessionStore.pending.clear()
    }

    /** A copy of the sample talk (13 pages, Beamer overlays, notes) in the app's files. */
    fun samplePdf(name: String = "talk.pdf"): File {
        val file = File(app.filesDir, name)
        File("src/test/resources/sample/talk.pdf").copyTo(file, overwrite = true)
        File("src/test/resources/sample/talk.pdfpc").copyTo(File(app.filesDir, name.removeSuffix(".pdf") + ".pdfpc"), overwrite = true)
        return file
    }

    fun open(file: File): PresenterDocument {
        flows.open(DocSource.Local(file))
        idle()
        session.finishTransition()
        return documentOf(file) ?: error("not opened: ${file.name}; message: ${flows.message}")
    }

    /** Shows page [page] of [doc], like a jump from the navigation panel. */
    fun goTo(doc: PresenterDocument, page: Int) {
        session.jumpTo(doc, page, record = false)
        session.finishTransition()
    }

    /** What happens when the app is killed and launched again: session written, everything closed, restored. */
    fun restart() {
        flows.saveSession(force = true)
        session.closeEverything()
        app.sessionRestored = false
        flows.restoreSession {}
        idle()
        session.finishTransition()
    }

    private var changes = 0

    /** Another app rewrites [file] with the sample talk (no annotations), with a newer date. */
    fun changeOnDisk(file: File) {
        File("src/test/resources/sample/talk.pdf").copyTo(file, overwrite = true)
        file.setLastModified(file.lastModified() + 10_000L * ++changes)
    }

    fun documentOf(file: File): PresenterDocument? =
        session.documents.firstOrNull { (it.source as? DocSource.Local)?.file == file }

    private var drawn = 0

    /** What the canvas does at the end of a stroke (a shape: real strokes need the native Ink library). */
    fun draw(doc: PresenterDocument, page: Int = doc.pageIndex, element: PageElement = Elements.shape(++drawn)): PageElement {
        doc.inkOf(page).add(listOf(element))
        session.onInkEdited(doc)
        return element
    }

    /** Erases [element] whole, like the eraser does at the end of a gesture. */
    fun erase(doc: PresenterDocument, page: Int, element: PageElement) {
        val ink = doc.inkOf(page)
        ink.beginChange()
        ink.setLive(ink.elements.filter { it !== element })
        if (ink.endChange()) session.onInkEdited(doc)
    }

    /**
     * Annotations in the file on disk, by slide, comparable with [annotations]. Read from a copy: [PdfInkStore.inspect]
     * rewrites the file it reads without them (it is meant for the private copy).
     */
    fun annotationsOnDisk(file: File, doc: PresenterDocument): Map<Int, List<List<Byte>>> {
        val copy = File.createTempFile("disk", ".pdf", app.cacheDir)
        try {
            file.copyTo(copy, overwrite = true)
            return bySlide(PdfInkStore.inspect(copy).elements, doc)
        } finally {
            copy.delete()
        }
    }

    /** Annotations shown by [doc], by slide. */
    fun annotations(doc: PresenterDocument): Map<Int, List<List<Byte>>> = bySlide(doc.allElements(), doc)

    private fun bySlide(map: Map<Int, List<PageElement>>, doc: PresenterDocument) =
        map.entries.groupBy({ doc.slides.firstPageOf(it.key) }, { Elements.bytes(it.value)!! })
            .mapValues { it.value.flatten() }.filterValues { it.isNotEmpty() }

    /** Runs what is waiting on the main thread. */
    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Waits for work on other threads (tabs, renderer) that ends on the main thread. */
    fun waitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            if (condition()) return
            check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }
            Thread.sleep(10)
        }
    }
}
