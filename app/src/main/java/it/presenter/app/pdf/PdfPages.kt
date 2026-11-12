package it.presenter.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Access to the pages of a PDF through the system [PdfRenderer]. The renderer is not thread-safe, so
 * everything goes through a single worker thread; ready bitmaps stay in a small cache (current and
 * nearby pages), so turning pages is instant.
 */
class PdfPages private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Height/width ratio of each page. */
    val aspects: FloatArray,
) : Closeable {

    val pageCount get() = aspects.size

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render") }
    private val main = Handler(Looper.getMainLooper())
    private val cache = LinkedHashMap<Int, Bitmap>(8, 0.75f, true)
    private val pending = HashMap<Int, MutableList<(Bitmap) -> Unit>>()

    @Volatile
    private var closed = false

    /** Maximum long side of the bitmaps (enough for a 2x zoom without losing sharpness). */
    var maxSidePx = 3200

    fun cached(index: Int): Bitmap? = cache[index]

    /** Delivers (on the main thread) the bitmap of page [index], rendering it if needed. */
    fun request(index: Int, onReady: (Bitmap) -> Unit) {
        if (closed || index !in 0 until pageCount) return
        cache[index]?.let { onReady(it); return }
        val waiting = pending[index]
        if (waiting != null) {
            waiting.add(onReady)
            return
        }
        pending[index] = mutableListOf(onReady)
        val maxSide = maxSidePx
        worker.execute {
            if (closed) return@execute
            val bmp = runCatching { render(index, maxSide) }.getOrNull()
            main.post {
                val callbacks = pending.remove(index) ?: return@post
                if (bmp == null || closed) return@post
                cache[index] = bmp
                trim()
                callbacks.forEach { it(bmp) }
            }
        }
    }

    /** Thumbnails (overview): small and in a separate cache, so they do not evict the large pages. */
    private val thumbs = object : LinkedHashMap<Int, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>) = size > THUMB_CACHE_SIZE
    }
    private val pendingThumbs = LinkedHashMap<Int, MutableList<(Bitmap) -> Unit>>()

    /** One thumbnail at a time on the render thread: real pages do not wait for the whole grid. */
    private var thumbRunning = false

    fun cachedThumbnail(index: Int): Bitmap? = thumbs[index]

    /** Delivers (on the main thread) the thumbnail of page [index]. */
    fun thumbnail(index: Int, onReady: (Bitmap) -> Unit) {
        if (closed || index !in 0 until pageCount) return
        thumbs[index]?.let { onReady(it); return }
        pendingThumbs[index]?.let { it.add(onReady); return }
        pendingThumbs[index] = mutableListOf(onReady)
        nextThumbnail()
    }

    /**
     * The thumbnail is no longer needed by [onReady] (scrolled out of the grid): if nobody else waits for it
     * and it has not started, it is skipped.
     */
    fun cancelThumbnail(index: Int, onReady: (Bitmap) -> Unit) {
        val waiting = pendingThumbs[index] ?: return
        waiting.remove(onReady)
        if (waiting.isEmpty()) pendingThumbs.remove(index)
    }

    private fun nextThumbnail() {
        if (thumbRunning || closed) return
        val index = pendingThumbs.keys.firstOrNull() ?: return
        thumbRunning = true
        worker.execute {
            val bmp = if (closed) null else runCatching { render(index, THUMB_SIDE_PX) }.getOrNull()
            main.post {
                thumbRunning = false
                val callbacks = pendingThumbs.remove(index)
                if (bmp != null && !closed) {
                    thumbs[index] = bmp
                    callbacks?.forEach { it(bmp) }
                }
                nextThumbnail()
            }
        }
    }

    /** Prepares the pages [indices] in advance (those reachable from the current one). */
    fun prefetch(indices: Iterable<Int>) {
        for (i in indices) request(i) {}
    }

    /**
     * Document no longer in front: only the current page stays in memory, so several open PDFs do not
     * exhaust it (each page takes ~20 MB).
     */
    fun trimTo(keep: Int) {
        val it = cache.keys.iterator()
        while (it.hasNext()) if (it.next() != keep) it.remove()
        thumbs.clear()
    }

    private fun trim() {
        while (cache.size > CACHE_SIZE) {
            val eldest = cache.keys.first()
            cache.remove(eldest)
        }
    }

    private fun render(index: Int, maxSide: Int): Bitmap {
        renderer.openPage(index).use { page ->
            val scale = maxSide / maxOf(page.width, page.height).toFloat()
            val w = (page.width * scale).roundToInt().coerceAtLeast(1)
            val h = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE) // the PDF has a transparent background
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp.prepareToDraw()
            return bmp
        }
    }

    override fun close() {
        // A second close would find the thread already stopped (RejectedExecutionException).
        if (closed) return
        closed = true
        cache.clear()
        pending.clear()
        thumbs.clear()
        pendingThumbs.clear()
        worker.execute {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
        worker.shutdown()
    }

    companion object {
        private const val CACHE_SIZE = 4
        private const val THUMB_SIDE_PX = 360
        private const val THUMB_CACHE_SIZE = 160

        fun open(fd: ParcelFileDescriptor): PdfPages {
            val renderer = PdfRenderer(fd)
            val aspects = try {
                FloatArray(renderer.pageCount) { i ->
                    renderer.openPage(i).use { it.height.toFloat() / it.width.toFloat() }
                }
            } catch (e: Throwable) {
                renderer.close()
                throw e
            }
            return PdfPages(fd, renderer, aspects)
        }
    }
}
