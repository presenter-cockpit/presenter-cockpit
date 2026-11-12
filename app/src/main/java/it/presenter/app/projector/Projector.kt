package it.presenter.app.projector

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import it.presenter.app.R
import it.presenter.app.canvas.SceneRenderer
import it.presenter.app.ink.PAGE_WIDTH
import it.presenter.app.laser.LaserRenderer
import it.presenter.app.session.PageRef
import it.presenter.app.session.PageTransition
import it.presenter.app.session.PresenterSession
import it.presenter.app.ui.PresenterTheme
import it.presenter.app.ui.ProjectorWorkContent

/**
 * What the audience sees: only the page, the strokes and the laser. Shows the same part of the page
 * framed on the tablet (zooming there zooms here too), fitted to the projector's proportions, with the
 * same transitions. On top, the states chosen by the presenter: black screen, frozen image, inverted
 * colors.
 */
@SuppressLint("ViewConstructor")
class MirrorView(context: Context, private val session: PresenterSession) : View(context) {
    private val scene = SceneRenderer(shadow = false)
    private val laserRenderer = LaserRenderer()
    private val pageToMirror = Matrix()
    private val src = RectF()
    private val dst = RectF()

    private val framing = object : SceneRenderer.Framing {
        override fun current(ref: PageRef, out: Matrix) {
            if (session.viewport.isZoomed) {
                // When zoomed, the part framed on the tablet (within the page).
                session.viewport.visiblePageRect(src)
                if (!src.intersect(0f, 0f, PAGE_WIDTH, ref.doc.pageHeight(ref.index))) src.set(pageRect(ref))
            } else {
                src.set(pageRect(ref))
            }
            frame(src, out)
        }

        override fun incoming(ref: PageRef, out: Matrix) {
            val page = pageRect(ref)
            if (session.viewport.isZoomed) {
                session.viewport.visiblePageRectFor(page.height(), src)
                if (!src.intersect(page)) src.set(page)
            } else {
                src.set(page)
            }
            frame(src, out)
        }

        override fun outgoing(t: PageTransition, out: Matrix) {
            val from = t.from ?: return out.reset()
            frame(t.fromMirror?.let { r -> RectF(r).takeIf { it.intersect(pageRect(from)) } } ?: pageRect(from), out)
        }

        private fun pageRect(ref: PageRef) = RectF(0f, 0f, PAGE_WIDTH, ref.doc.pageHeight(ref.index))

        private fun frame(r: RectF, out: Matrix) {
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            out.setRectToRect(r, dst, Matrix.ScaleToFit.CENTER)
        }
    }

    private val listener = PresenterSession.Listener { change ->
        // Freeze: the snapshot is taken now, with what the audience sees at this moment.
        if (change == PresenterSession.Change.PROJECTOR && session.freezeRequested && width > 0 && height > 0) {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            // Code or browser tab: the whole screen is captured (including the Compose part).
            val root = parent as? View
            if (session.activeWork != null && root != null) root.draw(canvas) else drawLive(canvas, animate = false)
            ownFrozen = bmp
            session.onFrozen(bmp)
        }
        invalidate()
    }

    /** Snapshot of this output while frozen; without one (output added later) the tablet's preview is used. */
    private var ownFrozen: Bitmap? = null

    /**
     * The pause screen is drawn by Compose on top of this view (fading in over the scene): here the scene
     * keeps being drawn. Without Compose, black.
     */
    var pauseOnTop = false

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val frozenMatrix = Matrix()
    private val invertPaint = Paint().apply { colorFilter = ColorMatrixColorFilter(INVERT_KEEP_HUE) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        session.addListener(listener)
    }

    override fun onDetachedFromWindow() {
        session.removeListener(listener)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        // Private start: not even the page under the logo (nothing shows through during the fades).
        if (session.projectorMode == PresenterSession.ProjectorMode.PRIVATE) return
        if (session.projectorMode == PresenterSession.ProjectorMode.BLACK && !pauseOnTop) return
        if (session.frozen == null) ownFrozen = null
        val frozen = ownFrozen ?: session.frozen
        if (frozen != null) {
            // Proportions kept: after a resolution change the image is not distorted.
            src.set(0f, 0f, frozen.width.toFloat(), frozen.height.toFloat())
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            frozenMatrix.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER)
            canvas.drawBitmap(frozen, frozenMatrix, bitmapPaint)
            return
        }
        drawLive(canvas, animate = true)
    }

    /** The live scene: page (or transition), strokes in progress, laser. */
    private fun drawLive(canvas: Canvas, animate: Boolean) {
        canvas.drawColor(Color.BLACK)
        // Inverted colors: page and ink go through the filter; the laser stays as it is.
        val inverted = session.currentRef?.doc?.inverted == true
        // Inside the inverted layer the spotlight veil is drawn light, so it comes out dark.
        scene.inverted = inverted
        if (inverted) canvas.saveLayer(null, invertPaint)
        scene.draw(canvas, session, framing, width)
        val ref = session.currentRef
        if (session.transition != null || ref == null) {
            if (inverted) canvas.restore()
            return
        }
        framing.current(ref, pageToMirror)

        val now = SystemClock.uptimeMillis()
        val ink = ref.doc.inkOf(ref.index)
        val pageHeight = ref.doc.pageHeight(ref.index)
        for (live in session.liveStrokes.values) {
            if (live.target !== ink) continue
            // The tablet queues the inputs; the geometry is updated here, at drawing time.
            runCatching { live.stroke.updateShape(now - live.downTime) }
            scene.drawInProgress(canvas, live.stroke, pageHeight, pageToMirror)
        }
        if (inverted) canvas.restore()
        canvas.save()
        canvas.clipRect(RectF(0f, 0f, PAGE_WIDTH, pageHeight).also { pageToMirror.mapRect(it) })
        val animating = laserRenderer.draw(canvas, session.laser, pageToMirror, now)
        canvas.restore()
        if (animating && animate) postInvalidateOnAnimation()
    }

    companion object {
        /**
         * Inverted colors with hues kept: the lightness is flipped (negative) and the hue rotated by 180° (a
         * luminance-preserving rotation), so red stays red.
         */
        val INVERT_KEEP_HUE = ColorMatrix(
            floatArrayOf(
                -0.574f, 1.430f, 0.144f, 0f, 0f,
                0.426f, 0.430f, 0.144f, 0f, 0f,
                0.426f, 1.430f, -0.856f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ).apply {
            preConcat(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        }
    }
}

class ProjectorPresentation(
    private val outerContext: Context,
    display: Display,
    private val session: PresenterSession,
) : Presentation(outerContext, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.let { keepScreenOn(it, session.settings.keepScreenOn) }
        val activity = (ownerActivity ?: outerContext) as? ComponentActivity
        if (activity != null) {
            window?.decorView?.let {
                it.setViewTreeLifecycleOwner(activity)
                it.setViewTreeSavedStateRegistryOwner(activity)
                it.setViewTreeViewModelStoreOwner(activity)
            }
        }
        setContentView(audienceView(context, session, compose = activity != null, shareWindow = false))
    }
}

/**
 * What the audience sees, for a projector window or the window to share ([shareWindow], [AudienceActivity]):
 * the PDF scene and, with [compose] (the window needs the owners of a ComponentActivity), code, terminal and
 * browser tabs and the pause and private start screens on top. The window to share cannot show the tablet:
 * while the external displays mirror it, it shows the pause screen.
 */
fun audienceView(context: Context, session: PresenterSession, compose: Boolean, shareWindow: Boolean): View {
    val root = FrameLayout(context)
    val mirror = MirrorView(context, session)
    root.addView(mirror)
    if (!compose) return root
    mirror.pauseOnTop = true
    root.addView(
        ComposeView(context).apply {
            setContent {
                val tab = session.activeWork
                // The frozen image is drawn by the scene underneath.
                if (tab != null && session.frozen == null && session.projectorMode != PresenterSession.ProjectorMode.PRIVATE) {
                    PresenterTheme { ProjectorWorkContent(session, tab) }
                }
                // Pause instead of black, over PDFs and tabs, never inverted; fades in and out.
                AnimatedVisibility(
                    visible = session.projectorMode == PresenterSession.ProjectorMode.BLACK ||
                        shareWindow && session.projectorMode == PresenterSession.ProjectorMode.MIRROR,
                    enter = fadeIn(tween(PAUSE_FADE_MS)),
                    exit = fadeOut(tween(PAUSE_FADE_MS)),
                ) { PauseScreen() }
                // Private start: only the logo. If already active when the window opens, it appears without a fade.
                AnimatedVisibility(
                    visible = session.projectorMode == PresenterSession.ProjectorMode.PRIVATE,
                    enter = fadeIn(tween(PAUSE_FADE_MS)),
                    exit = fadeOut(tween(PAUSE_FADE_MS)),
                ) { PrivateStartScreen() }
            }
        },
    )
    return root
}

private const val PAUSE_FADE_MS = 300

/**
 * Follows the external displays (HDMI, USB-C, Smart View/Miracast) and shows a [ProjectorPresentation] on
 * each "presentation" one ([AudienceDisplays]): the audience sees the same thing on all of them. Start it
 * while the app is in front and stop it when it goes to the background, so the displays go back to
 * mirroring the tablet. The window to share ([AudienceActivity]) is one more output, listed in
 * [PresenterSession.projectorInfo].
 * To try without a projector: Developer options → "Simulate secondary displays".
 */
class ProjectorController(private val context: Context, private val session: PresenterSession) {
    /** "Keep screen on" setting changed: it applies to the projector windows too. */
    fun keepScreenOn(on: Boolean) {
        for (p in presentations.values) p.window?.let { keepScreenOn(it, on) }
    }

    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Projector windows by display id. */
    private val presentations = LinkedHashMap<Int, ProjectorPresentation>()
    private var started = false

    /** Black and frozen are drawn by the views; mirroring instead removes the Presentations. */
    private val sessionListener = PresenterSession.Listener { if (it == PresenterSession.Change.PROJECTOR) refresh() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    fun start() {
        if (started) return
        started = true
        displayManager.registerDisplayListener(displayListener, mainHandler)
        session.addListener(sessionListener)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        displayManager.unregisterDisplayListener(displayListener)
        session.removeListener(sessionListener)
        dismissAll()
        session.projectorInfo = null
        session.externalDisplays = emptyList()
    }

    private fun refresh() {
        if (!started) return
        val all = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList()
        val found = all.map { d ->
            val trusted = AudienceDisplays.isTrusted(d.flags)
            PresenterSession.ExternalDisplay(d.displayId, d.name, d.mode.physicalWidth, d.mode.physicalHeight, trusted, session.displayEnabled(d.name, trusted))
        }
        if (found != session.externalDisplays) session.externalDisplays = found
        val displays = all.filterIndexed { i, _ -> found[i].enabled }
        updateInfo(displays)
        if (displays.isEmpty() && !session.shareWindow) {
            dismissAll()
            // No output left: the next connection starts from the presentation again (the private start
            // instead stays until it is turned off).
            if (session.projectorMode != PresenterSession.ProjectorMode.PRIVATE) session.projectorMode = PresenterSession.ProjectorMode.PRESENT
            session.clearFrozen()
            return
        }
        // Mirroring with no external display left on (only the window to share): back to the presentation, or
        // the window would stay on the pause with no way back but B.
        if (displays.isEmpty() && session.projectorMode == PresenterSession.ProjectorMode.MIRROR) {
            session.projectorMode = PresenterSession.ProjectorMode.PRESENT
            return
        }
        // Mirroring: without a Presentation the external displays show the tablet again.
        if (session.projectorMode == PresenterSession.ProjectorMode.MIRROR) {
            dismissAll()
            return
        }
        val ids = displays.map { it.displayId }.toSet()
        for ((id, p) in presentations.entries.toList()) {
            if (id !in ids || !p.isShowing) {
                presentations.remove(id)
                p.dismiss()
            }
        }
        for (display in displays) {
            if (display.displayId in presentations) continue
            val p = ProjectorPresentation(context, display, session)
            // Android dismisses the Presentation by itself when the display changes (e.g. a new resolution): it
            // is created again.
            p.setOnDismissListener {
                if (presentations[display.displayId] === p) {
                    presentations.remove(display.displayId)
                    session.projectorWindows = presentations.size
                    mainHandler.post { refresh() }
                }
            }
            try {
                p.show()
            } catch (_: WindowManager.InvalidDisplayException) {
                continue
            }
            presentations[display.displayId] = p
        }
        session.projectorWindows = presentations.size
    }

    private fun updateInfo(displays: List<Display>) {
        val names = displays.map { d -> d.mode.let { "${d.name} · ${it.physicalWidth}×${it.physicalHeight}" } } +
            listOfNotNull(context.getString(R.string.share_window_name).takeIf { session.shareWindow })
        session.projectorInfo = names.joinToString(", ").ifEmpty { null }
    }

    private fun dismissAll() {
        val all = presentations.values.toList()
        presentations.clear()
        for (p in all) p.dismiss()
        session.projectorWindows = 0
    }
}

/**
 * Which "presentation" displays show what the audience sees by default: the trusted ones, made by Android
 * (HDMI, USB-C, Miracast, simulated displays) or by system apps allowed to (casting). Apps sharing or
 * recording the whole screen may create an untrusted one (e.g. a video call): it is listed in the outputs
 * menu but off until turned on (user's decision). Type and owner of a display are not visible to apps;
 * `FLAG_TRUSTED` is in the flags.
 */
object AudienceDisplays {
    /** `Display.FLAG_TRUSTED` (hidden constant). */
    private const val FLAG_TRUSTED = 1 shl 7

    fun isTrusted(flags: Int): Boolean = flags and FLAG_TRUSTED != 0
}

/** Screen kept on while the window is visible ([on]), or turned off as set in Android. */
fun keepScreenOn(window: Window, on: Boolean) {
    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
