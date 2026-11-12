package it.presenter.app.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.VelocityTracker
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.ImmutableStrokeInputBatch
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor
import it.presenter.app.BuildConfig
import it.presenter.app.doc.PresenterDocument
import it.presenter.app.ink.Affine
import it.presenter.app.ink.Box
import it.presenter.app.ink.BrushFamilies
import it.presenter.app.ink.InkStroke
import it.presenter.app.ink.PAGE_WIDTH
import it.presenter.app.ink.PageElement
import it.presenter.app.ink.PageInk
import it.presenter.app.ink.PenGestures
import it.presenter.app.ink.SelectionGeometry
import it.presenter.app.ink.Shape
import it.presenter.app.ink.ShapeRecognizer
import it.presenter.app.ink.ShapeType
import it.presenter.app.ink.Snapping
import it.presenter.app.ink.TextBox
import it.presenter.app.ink.TextLayouts
import it.presenter.app.session.Hint
import it.presenter.app.session.PageRef
import it.presenter.app.session.PresenterSession
import it.presenter.app.session.PresenterSession.Change
import it.presenter.app.session.Selection
import it.presenter.app.tools.ToolKind
import it.presenter.app.tools.ToolPreset
import it.presenter.app.tools.ToolRef
import it.presenter.app.tools.ToolState
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Drawing surface of the tablet, made of three layers:
 * 1. [PageLayerView]: page and finished ("dry") strokes, page transitions;
 * 2. Jetpack Ink's [InProgressStrokesView]: strokes in progress ("wet"), drawn in the low-latency front
 *    buffer with motion prediction;
 * 3. [OverlayView]: laser, eraser cursor, selection.
 *
 * Input:
 * - S Pen: uses the selected tool; with the side button held, the dual tool (the eraser, unless another
 *   one is chosen in the rail). With a selection (shape, lasso) the pen drags its handles or moves it;
 *   with the text tool it opens or creates a box;
 * - fingers: one to turn pages (a swipe that really drags the page), two to move the sheet and zoom
 *   (pinch); double tap to go back to the whole page; a two-finger tap undoes, three fingers redo. Zoom
 *   and position stay when changing page;
 * - fingers are ignored while the pen is near the screen (resting palm).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PresenterCanvas(context: Context, private val session: PresenterSession) : FrameLayout(context) {

    private val pageLayer = PageLayerView(context, session)
    private val inkView = InProgressStrokesView(context)
    private val overlay = OverlayView(context, session)
    private val predictor = MotionEventPredictor.newInstance(this)
    private val density = resources.displayMetrics.density
    private val xdpi = resources.displayMetrics.xdpi
    private val tools get() = session.tools

    // ---------------------------------------------------------------- pen

    private sealed interface Action {
        class Ink(
            val strokeId: InProgressStrokeId,
            val live: PresenterSession.LiveStroke?,
            val doc: PresenterDocument,
            val ink: PageInk,
            /** The stroke can become a gesture (lasso circle with the tip held still): pen, marker and shapes. */
            val gestures: Boolean,
            val kind: ToolKind,
            val sx: Float, val sy: Float,
        ) : Action {
            /** Points of the stroke in page coordinates (to recognize the lasso circle). */
            val points = ArrayList<Float>()

            /** Where the tip last stopped (screen): the pause is measured from there. */
            var stillX = sx
            var stillY = sy
        }
        object Laser : Action
        class Erase(val doc: PresenterDocument, val ink: PageInk, var lastX: Float, var lastY: Float) : Action {
            var erasedAny = false
        }

        /** Gesture on the selection: move, resize, rotate it or drag a vertex of the shape. */
        class Transform(
            val kind: TransformKind,
            val start: Selection,
            /** Starting point in page and screen coordinates. */
            val x0: Float, val y0: Float, val sx0: Float, val sy0: Float,
            /** Box of the selection at the start (rotated frame). */
            val box: FloatArray,
            val handle: SelectionGeometry.Handle? = null,
            val vertex: Int = -1,
            /** From the finger to the grabbed handle (page units): the handle does not jump under the tip. */
            val offX: Float = 0f, val offY: Float = 0f,
        ) : Action {
            var moved = false

            /**
             * Started by holding still on an element ([grab]): lifting without moving is not a tap on the selection.
             */
            var held = false

            /** Shapes of the page to snap to (the others, not selected). */
            var targets = Snapping.Targets.EMPTY
        }

        class Lasso(val doc: PresenterDocument, val ink: PageInk, val points: ArrayList<Float>) : Action

        /** Pen down with the text tool: on lifting it opens or creates a box. */
        class TextPress(val doc: PresenterDocument, val ink: PageInk, val preset: ToolPreset, val x0: Float, val y0: Float, val sx0: Float, val sy0: Float) : Action {
            var x1 = x0
            var sx1 = sx0
            var sy1 = sy0
        }

        /** Rectangle of the spotlight, from the starting point (page coordinates). */
        class Spotlight(val ref: PageRef, val x0: Float, val y0: Float) : Action

        /** Consumed gesture (it closed the open text box): it does nothing else. */
        object Swallow : Action
    }

    private enum class TransformKind { MOVE, RESIZE, ROTATE, VERTEX }

    private var action: Action? = null
    private var actionPointerId = INVALID

    /**
     * The gesture in progress is a finger on an object (handles, moving the selection): the finger does what
     * the pen does on what already exists, without creating or erasing.
     */
    private var fingerAction = false

    /** Last short pen tap (for the double tap on a text box) and the dot it left. */
    private var penTapTime = Long.MIN_VALUE / 2
    private var penTapX = 0f
    private var penTapY = 0f
    private var penTapStroke: InProgressStrokeId? = null
    private var penTapDot: Pair<PageInk, PageElement>? = null

    /** Stroke of the first tap of a double tap not yet handed over by Ink: discarded on hand-off. */
    private var discardStroke: InProgressStrokeId? = null
    private var penDownTime = 0L
    private var penDownX = 0f
    private var penDownY = 0f

    /** This pen touch removed the selection (it landed outside it): if it is just a tap, it does nothing else. */
    private var deselectedOnDown = false

    /** How far the pen went from where it touched (screen px): a tap never leaves that point. */
    private var penMaxMove = 0f

    /** This pen touch may be the second tap of a double tap on this text box (decided on lifting). */
    private var doubleTapBox: TextBox? = null
    private var doubleTapX = 0f
    private var doubleTapY = 0f

    /** Largest contact size (px) of the single finger of the gesture: a palm is larger than a fingertip. */
    private var fingerMajor = 0f
    private var lastStylusTime = 0L
    private var penButtonDown = false
    private var lastButtonRelease = Long.MIN_VALUE / 2

    /**
     * Where each stroke goes (Ink's hand-off may arrive after a page or document change) and which tool it
     * was born with: shapes and the snapping highlighter transform it once the pen is lifted.
     */
    private class Target(val doc: PresenterDocument, val ink: PageInk, val page: Int, val preset: ToolPreset? = null) {
        val fadeAfterMs get() = preset?.fadeMs ?: 0L
    }

    private val strokeTargets = HashMap<InProgressStrokeId, Target>()

    private val realBatch = MutableStrokeInputBatch()
    private val predictedBatch = MutableStrokeInputBatch()
    private val point = FloatArray(2)

    // ---------------------------------------------------------------- fingers

    private val fingerIds = HashSet<Int>()
    private var fingerCx = 0f
    private var fingerCy = 0f
    private var fingerSpan = 0f
    private var fingerCount = 0
    private var pinchStartSpan = 0f
    private var pinching = false

    /** Multi-finger pan started (beyond [PAN_SLOP_DP] or with the pinch) and where it is measured from. */
    private var panning = false
    private var panStartX = 0f
    private var panStartY = 0f

    /** For undo/redo with a tap: maximum number of fingers of the gesture and whether they moved. */
    private var gestureMaxFingers = 0
    private var gestureMoved = false
    private val fingerScratch = FloatArray(4)
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureStartTime = 0L
    private var gestureMultiTouch = false
    private var swipeDirection = 0
    private var swipeDistance = 1f
    private var velocity: VelocityTracker? = null
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var shownPage: PageRef? = null
    private val windowLocation = IntArray(2)

    private val sessionListener = PresenterSession.Listener { change ->
        when (change) {
            Change.PAGE -> {
                // Only a real page change interrupts the stroke in progress.
                val ref = session.currentRef
                if (ref != shownPage) {
                    shownPage = ref
                    cancelAction()
                    inkView.requestHandoff()
                }
                updateInkMask()
                pageLayer.invalidate()
                overlay.invalidate()
            }
            Change.VIEWPORT, Change.LAYOUT -> {
                updateInkMask()
                pageLayer.invalidate()
                overlay.invalidate()
            }
            Change.BITMAP, Change.FADE -> pageLayer.invalidate()
            Change.INK -> {
                pageLayer.invalidate()
                // Handles and box of the selection follow the elements.
                overlay.invalidate()
            }
            Change.TRANSITION -> {
                pageLayer.invalidate()
                overlay.invalidate()
                if (session.transition == null) updateInkMask()
            }
            Change.LASER -> overlay.invalidate()
            Change.LIVE, Change.PROJECTOR -> Unit
        }
    }

    init {
        // The surface keeps the focus in touch mode too: otherwise, at the first key after a touch (the remote's
        // PgDn), Android leaves touch mode moving the focus to an icon and consumes that key, which would be lost.
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        // No grey "focused element" veil over the slide after using the keys.
        defaultFocusHighlightEnabled = false
        val match = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(pageLayer, match)
        addView(inkView, match)
        addView(overlay, match)
        inkView.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) = handOff(strokes)
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        session.addListener(sessionListener)
        shownPage = session.currentRef
        // Initializes Ink's renderer at once: avoids the small delay on the first stroke.
        post {
            inkView.eagerInit()
            requestFocus()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // After a dialog or another app the focus comes back here: the remote's keys are not lost. Not with a
        // work tab in front: there the keys belong to the terminal or editor.
        if (hasWindowFocus && !isFocused && session.activeWork == null) requestFocus()
    }

    override fun onDetachedFromWindow() {
        session.removeListener(sessionListener)
        removeCallbacks(lassoHold)
        removeCallbacks(penHold)
        removeCallbacks(fingerHold)
        removeCallbacks(singleTap)
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        session.viewport.setViewSize(w, h)
        session.notify(Change.VIEWPORT)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) updateInkMask()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !isFocused) requestFocus()
        predictor.record(event)
        trackPenButton(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                if (isStylusLike(event, index)) {
                    lastStylusTime = event.eventTime
                    // The pen wins: the finger gesture in progress is cancelled.
                    if (fingerAction) cancelFingerAction()
                    if (action == null && session.current != null) {
                        cancelFingers()
                        session.finishTransition()
                        requestUnbufferedDispatch(event)
                        begin(event, index)
                    }
                } else {
                    // Another finger while a finger moves something: it becomes a multi-finger gesture (pan, zoom).
                    if (fingerAction) handFingerActionToGesture(event, index)
                    else fingerDown(event, index)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // The pen resting on the screen keeps palm rejection active even without a stroke in progress (e.g.
                // after a page change with the pen down).
                if (hasStylusPointer(event)) lastStylusTime = event.eventTime
                if (action != null) move(event)
                fingersMoved(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) == actionPointerId) {
                    lastStylusTime = event.eventTime
                    // FLAG_CANCELED: the system recognized an accidental touch (palm).
                    if (event.flags and MotionEvent.FLAG_CANCELED != 0) cancelAction(event) else end(event, index)
                } else {
                    fingerUp(event, index)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelAction(event)
                cancelFingers()
            }
        }
        return true
    }

    private fun isStylusLike(event: MotionEvent, index: Int): Boolean = when (event.getToolType(index)) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER, MotionEvent.TOOL_TYPE_MOUSE -> true
        else -> tools.fingerDraws
    }

    private fun hasStylusPointer(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            val t = event.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) return true
        }
        return false
    }

    private fun isPenButtonPressed(event: MotionEvent) =
        event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY) ||
            (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS && event.isButtonPressed(MotionEvent.BUTTON_SECONDARY))

    /** Tool for a gesture starting now. With the side button held it is the dual tool ([ToolState.effective]). */
    private fun toolFor(event: MotionEvent, index: Int): ToolRef =
        tools.effective(isPenButtonPressed(event), event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER)

    /** Tool in hover or for the cursor, without an event at hand: the last button state counts. */
    private fun currentTool(): ToolRef = tools.effective(penButtonDown, eraserTip = false)

    /** Eraser: the fixed tool, or a preset that no longer exists. */
    private fun isEraser(ref: ToolRef) = ref != ToolRef.Lasso && ref != ToolRef.Spotlight && tools.preset(ref) == null

    /** Side button pressed or released: in hover the cursor switches to the dual tool and back at once. */
    private fun trackPenButton(event: MotionEvent) {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return
        val pressed = isPenButtonPressed(event)
        if (pressed != penButtonDown) {
            penButtonDown = pressed
            // Double click (second press shortly after the first): eraser while the button is held.
            if (pressed) {
                tools.buttonDoubleClick = event.eventTime - lastButtonRelease < DOUBLE_CLICK_MS
            } else {
                lastButtonRelease = event.eventTime
                tools.buttonDoubleClick = false
            }
            // The rail shows the dual (or the eraser) selected while the button is held.
            tools.buttonHeld = pressed
            refreshHoverDot()
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // ACTION_BUTTON_PRESS/RELEASE with the pen in hover arrive here, not as a touch.
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            trackPenButton(event)
            val a = event.actionMasked
            if (a == MotionEvent.ACTION_BUTTON_PRESS || a == MotionEvent.ACTION_BUTTON_RELEASE) return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ---------------------------------------------------------------- strokes

    private fun begin(event: MotionEvent, index: Int) {
        val doc = session.current ?: return
        val ink = doc.inkOf(doc.pageIndex)
        val pointerId = event.getPointerId(index)
        val ref = toolFor(event, index)
        val preset = tools.preset(ref)
        actionPointerId = pointerId
        val sx = event.getX(index)
        val sy = event.getY(index)
        toPage(sx, sy)
        penDownTime = event.eventTime
        penDownX = sx
        penDownY = sy
        deselectedOnDown = false
        penMaxMove = 0f
        doubleTapBox = null
        // Text box open: touching the page closes it, and nothing else.
        if (session.textEdit != null) {
            session.endTextEdit()
            action = Action.Swallow
            return
        }
        // Double tap on a text box: edit ([editOnDoubleTap]). Not with the eraser, nor with the tools one writes
        // with (pen, marker, highlighter): in small handwriting two strokes of a letter are as short and as close
        // as two taps, and writing over a box opened it (seen on the tablet, also with the strictest limits).
        // Here only the candidate: whether this touch is a tap too is known on lifting.
        val writing = preset?.kind == ToolKind.PEN || preset?.kind == ToolKind.MARKER || preset?.kind == ToolKind.HIGHLIGHTER
        if (!isEraser(ref) && !writing && event.eventTime - penTapTime < DOUBLE_TAP_MS && hypot(sx - penTapX, sy - penTapY) < PEN_DOUBLE_TAP_SLOP_DP * density) {
            doubleTapBox = textBoxAt(ink, point[0], point[1])
            doubleTapX = point[0]
            doubleTapY = point[1]
        }
        // Selection: the pen takes its handles or moves it; elsewhere the selection ends.
        session.selection?.let { sel ->
            if (sel.ink === ink) beginTransform(sel, sx, sy)?.let {
                trace("pen on the selection (${sel.elements.size} elements, first ${sel.elements.first().javaClass.simpleName}): ${it.kind}")
                action = it
                return
            }
            session.selection = null
            deselectedOnDown = true
            overlay.invalidate()
        }
        action = when {
            ref == ToolRef.Spotlight -> {
                session.spotlight = null
                Action.Spotlight(PageRef(doc, doc.pageIndex), point[0], point[1])
            }
            ref == ToolRef.Lasso -> {
                session.lassoPath = floatArrayOf(point[0], point[1])
                Action.Lasso(doc, ink, arrayListOf(point[0], point[1]))
            }
            preset?.kind == ToolKind.TEXT -> Action.TextPress(doc, ink, preset, point[0], point[1], sx, sy)
            preset == null -> {
                ink.beginChange()
                val erase = Action.Erase(doc, ink, point[0], point[1])
                if (ink.eraseAlong(point[0], point[1], point[0], point[1], tools.eraserSize, tools.eraseMode)) {
                    erase.erasedAny = true
                    session.notify(Change.INK)
                }
                showEraser(event.getX(index), event.getY(index))
                erase
            }
            preset.kind == ToolKind.LASER -> {
                session.laser.begin(
                    preset.laserStyle, preset.color, preset.size, preset.pressure,
                    point[0], point[1], event.eventTime, pressureOf(event, index, -1),
                )
                session.notify(Change.LASER)
                Action.Laser
            }
            else -> {
                val brush = BrushFamilies.brushFor(preset)!!
                val strokeId = inkView.startStroke(event, pointerId, brush, Matrix(session.viewport.viewToPage))
                strokeTargets[strokeId] = Target(doc, ink, doc.pageIndex, preset)
                val live = if (session.mirrorActive) {
                    val stroke = InProgressStroke().apply { start(brush) }
                    PresenterSession.LiveStroke(stroke, ink, event.eventTime).also {
                        session.liveStrokes[strokeId] = it
                        appendInputs(realBatch, event, index, it.downTime, history = false)
                        runCatching { stroke.enqueueInputs(realBatch, ImmutableStrokeInputBatch.EMPTY) }
                        session.notify(Change.LIVE)
                    }
                } else null
                // With the shape tool too (user's request): a circle around elements, held, selects them instead of
                // becoming an ellipse.
                val gestures = (preset.kind == ToolKind.PEN || preset.kind == ToolKind.MARKER || preset.kind == ToolKind.SHAPE) && preset.fadeMs == 0L
                Action.Ink(strokeId, live, doc, ink, gestures, preset.kind, sx, sy).also {
                    it.points.add(point[0])
                    it.points.add(point[1])
                    if (gestures) {
                        removeCallbacks(lassoHold)
                        postDelayed(lassoHold, LASSO_HOLD_MS)
                    }
                }
            }
        }
        // Tip held still on an element: it is selected (see [penHold]). Not with the tools one writes with.
        val a = action
        removeCallbacks(penHold)
        if (a is Action.Lasso || a is Action.TextPress || (a is Action.Ink && a.gestures && a.kind == ToolKind.SHAPE)) {
            postDelayed(penHold, PEN_HOLD_MS)
        }
    }

    private fun move(event: MotionEvent) {
        val index = event.findPointerIndex(actionPointerId)
        if (index < 0) return
        if (!fingerAction) {
            val moved = hypot(event.getX(index) - penDownX, event.getY(index) - penDownY)
            penMaxMove = maxOf(penMaxMove, moved)
            if (moved > HOLD_SLOP_DP * density) removeCallbacks(penHold)
        }
        when (val a = action) {
            is Action.Ink -> {
                trackInkGesture(a, event, index)
                val predicted = predictor.predict()
                try {
                    inkView.addToStroke(event, actionPointerId, a.strokeId, predicted)
                    a.live?.let { live ->
                        appendInputs(realBatch, event, index, live.downTime, history = true)
                        predictedBatch.clear()
                        if (predicted != null) {
                            val pi = predicted.findPointerIndex(actionPointerId)
                            if (pi >= 0) appendInputs(predictedBatch, predicted, pi, live.downTime, history = true)
                        }
                        runCatching { live.stroke.enqueueInputs(realBatch, predictedBatch) }
                        session.notify(Change.LIVE)
                    }
                } finally {
                    predicted?.recycle()
                }
            }
            Action.Laser -> {
                val laser = session.laser
                for (h in 0 until event.historySize) {
                    toPage(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                    laser.add(point[0], point[1], event.getHistoricalEventTime(h), pressureOf(event, index, h))
                }
                toPage(event.getX(index), event.getY(index))
                laser.add(point[0], point[1], event.eventTime, pressureOf(event, index, -1))
                val predicted = predictor.predict()
                val pi = predicted?.findPointerIndex(actionPointerId) ?: -1
                if (predicted != null && pi >= 0) {
                    toPage(predicted.getX(pi), predicted.getY(pi))
                    laser.predict(point[0], point[1])
                } else {
                    laser.clearPrediction()
                }
                predicted?.recycle()
                session.notify(Change.LASER)
            }
            is Action.Erase -> {
                // Undo or redo (keyboard, remote) in the middle of a gesture closed the edit: the rest of the gesture
                // must not change the page outside the history.
                if (!a.ink.isChanging) {
                    overlay.hideEraser()
                    action = Action.Swallow
                    return
                }
                var erased = false
                val r = tools.eraserSize
                for (h in 0..event.historySize) {
                    val current = h == event.historySize
                    toPage(
                        if (current) event.getX(index) else event.getHistoricalX(index, h),
                        if (current) event.getY(index) else event.getHistoricalY(index, h),
                    )
                    erased = a.ink.eraseAlong(a.lastX, a.lastY, point[0], point[1], r, tools.eraseMode) || erased
                    a.lastX = point[0]
                    a.lastY = point[1]
                }
                if (erased) {
                    a.erasedAny = true
                    session.notify(Change.INK)
                }
                showEraser(event.getX(index), event.getY(index))
            }
            // S Pen button held while dragging: no snapping (user's choice).
            is Action.Transform -> transformMoved(a, event.getX(index), event.getY(index), snap = !isPenButtonPressed(event))
            is Action.Spotlight -> {
                toPage(event.getX(index), event.getY(index))
                val h = a.ref.doc.pageHeight(a.ref.index)
                val x1 = point[0].coerceIn(0f, PAGE_WIDTH)
                val y1 = point[1].coerceIn(0f, h)
                val x0 = a.x0.coerceIn(0f, PAGE_WIDTH)
                val y0 = a.y0.coerceIn(0f, h)
                session.spotlight = if (abs(x1 - x0) < pageUnits(TAP_SLOP_DP) || abs(y1 - y0) < pageUnits(TAP_SLOP_DP)) null
                else PresenterSession.Spotlight(a.ref, minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
            }
            is Action.Lasso -> {
                for (h in 0..event.historySize) {
                    val current = h == event.historySize
                    toPage(
                        if (current) event.getX(index) else event.getHistoricalX(index, h),
                        if (current) event.getY(index) else event.getHistoricalY(index, h),
                    )
                    a.points.add(point[0])
                    a.points.add(point[1])
                }
                session.lassoPath = a.points.toFloatArray()
                overlay.invalidate()
            }
            is Action.TextPress -> {
                a.sx1 = event.getX(index)
                a.sy1 = event.getY(index)
                toPage(a.sx1, a.sy1)
                a.x1 = point[0]
            }
            Action.Swallow, null -> Unit
        }
    }

    private fun end(event: MotionEvent, index: Int) {
        removeCallbacks(lassoHold)
        removeCallbacks(penHold)
        // A tap is short and never leaves its point: a small closed letter ("o") ends where it started, but is
        // not a tap.
        val tap = !fingerAction && event.eventTime - penDownTime < TAP_MAX_MS &&
            maxOf(penMaxMove, hypot(event.getX(index) - penDownX, event.getY(index) - penDownY)) < TAP_SLOP_DP * density
        if (tap && editOnDoubleTap(event)) return
        // A pen tap outside the selection only removes it, as a finger tap does (user's choice): no dot, no new
        // text box, nothing erased. Lasso and shape tools keep their tap (it selects what is under the pen).
        val onlyDeselect = tap && deselectedOnDown
        if (!fingerAction) {
            // Short pen tap: it may be the first of a double tap on a text box.
            if (tap && action !is Action.Swallow) {
                penTapTime = event.eventTime
                penTapX = penDownX
                penTapY = penDownY
                penTapStroke = (action as? Action.Ink)?.strokeId
                penTapDot = null
            } else {
                penTapTime = Long.MIN_VALUE / 2
            }
        }
        fingerAction = false
        // The last segment, from the previous move to the lift, counts for laser and eraser too.
        if (action is Action.Laser || action is Action.Erase || action is Action.Lasso || action is Action.TextPress || action is Action.Spotlight) move(event)
        when (val a = action) {
            is Action.Ink -> if (onlyDeselect && a.kind != ToolKind.SHAPE) {
                cancelInk(a, event)
                penTapStroke = null
            } else {
                inkView.finishStroke(event, actionPointerId, a.strokeId)
                a.live?.let { live ->
                    appendInputs(realBatch, event, index, live.downTime, history = true)
                    runCatching {
                        live.stroke.enqueueInputs(realBatch, ImmutableStrokeInputBatch.EMPTY)
                        live.stroke.finishInput()
                    }
                    session.notify(Change.LIVE)
                }
            }
            Action.Laser -> {
                session.laser.end(event.eventTime)
                session.notify(Change.LASER)
            }
            is Action.Erase -> if (onlyDeselect) {
                a.ink.cancelChange()
                overlay.hideEraser()
                session.notify(Change.INK)
            } else finishErase(a)
            is Action.Transform -> finishTransform(a)
            is Action.Lasso -> finishLasso(a)
            is Action.TextPress -> if (!onlyDeselect) finishTextPress(a)
            is Action.Spotlight, Action.Swallow, null -> Unit
        }
        action = null
        actionPointerId = INVALID
    }

    /**
     * Second tap of a pen double tap on a text box ([doubleTapBox]): the box opens for editing, with the cursor
     * where it was touched, and nothing of the two taps stays on the page. False if this is not the case.
     */
    private fun editOnDoubleTap(event: MotionEvent): Boolean {
        val box = doubleTapBox ?: return false
        val doc = session.current ?: return false
        val ink = doc.inkOf(doc.pageIndex)
        if (box !in ink.elements) return false
        // What this tap started with the tool in use.
        when (val a = action) {
            is Action.Ink -> cancelInk(a, event)
            is Action.Lasso -> session.lassoPath = null
            Action.Laser -> {
                session.laser.end(event.eventTime)
                session.notify(Change.LASER)
            }
            is Action.TextPress, is Action.Spotlight, Action.Swallow, null -> Unit
            // On a selection, or erasing: not a double tap on a box.
            is Action.Transform, is Action.Erase -> return false
        }
        // The dot left by the first tap.
        val dot = penTapDot
        if (dot != null) {
            if (dot.first === ink) dot.first.retract(dot.second)
        } else {
            discardStroke = penTapStroke
        }
        penTapDot = null
        penTapStroke = null
        penTapTime = Long.MIN_VALUE / 2
        doubleTapBox = null
        action = null
        actionPointerId = INVALID
        trace("text edit: pen double tap")
        session.beginTextEdit(doc, ink, box, isNew = false, cursor = TextLayouts.offsetAt(box, doubleTapX, doubleTapY))
        overlay.invalidate()
        return true
    }

    /**
     * Debug builds: why a selection or a text edit started (`adb logcat -s PresenterCanvas`), to find unwanted ones.
     */
    private fun trace(message: String) {
        if (BuildConfig.DEBUG) Log.d("PresenterCanvas", message)
    }

    /** Drops the stroke in progress of [a]: nothing of it stays on the page. */
    private fun cancelInk(a: Action.Ink, event: MotionEvent?) {
        inkView.cancelStroke(a.strokeId, event)
        strokeTargets.remove(a.strokeId)
        if (session.liveStrokes.remove(a.strokeId) != null) session.notify(Change.LIVE)
    }

    private fun cancelAction(event: MotionEvent? = null) {
        removeCallbacks(lassoHold)
        removeCallbacks(penHold)
        if (fingerAction) {
            cancelFingerAction()
            return
        }
        when (val a = action) {
            is Action.Ink -> cancelInk(a, event)
            Action.Laser -> {
                session.laser.end(SystemClock.uptimeMillis())
                session.notify(Change.LASER)
            }
            is Action.Erase -> finishErase(a)
            is Action.Transform -> finishTransform(a)
            is Action.Lasso -> {
                session.lassoPath = null
                overlay.invalidate()
            }
            is Action.TextPress, is Action.Spotlight, Action.Swallow, null -> Unit
        }
        action = null
        actionPointerId = INVALID
    }

    private fun finishErase(a: Action.Erase) {
        a.ink.endChange()
        overlay.hideEraser()
        // Lifting the pen, the erased parts (still faintly visible) disappear.
        if (a.erasedAny) session.onInkEdited(a.doc) else session.notify(Change.INK)
    }

    /** Ink hands over finished strokes: they move to the dry layer in the same frame, without flicker. */
    private fun handOff(strokes: Map<InProgressStrokeId, Stroke>) {
        val byTarget = LinkedHashMap<Target, MutableList<Stroke>>()
        for ((id, stroke) in strokes) {
            session.liveStrokes.remove(id)
            if (id == discardStroke) {
                discardStroke = null
                strokeTargets.remove(id)
                continue
            }
            val target = strokeTargets.remove(id) ?: session.current?.let { Target(it, it.inkOf(it.pageIndex), it.pageIndex) } ?: continue
            byTarget.getOrPut(target) { ArrayList() }.add(stroke)
        }
        for ((target, list) in byTarget) {
            // A document closed meanwhile receives nothing more.
            if (target.doc !in session.documents) continue
            val preset = target.preset
            when {
                // Vanishing pen: not a change to the document (no save, no undo).
                target.fadeAfterMs > 0 -> session.addFading(target.ink, list, target.fadeAfterMs)
                preset?.kind == ToolKind.SHAPE -> for (s in list) strokeToShape(target, preset, s)
                // Scribble with pen or marker over elements: erases them and does not stay.
                (preset?.kind == ToolKind.PEN || preset?.kind == ToolKind.MARKER) && list.size == 1 && eraseByScribble(target, list[0]) -> Unit
                else -> {
                    val elements = list.map(::InkStroke)
                    target.ink.add(elements)
                    // First pen or marker stroke: the two gestures that tool can make.
                    if (preset?.kind == ToolKind.PEN || preset?.kind == ToolKind.MARKER) {
                        session.hints.offer(Hint.SCRIBBLE)
                        session.hints.offer(Hint.CIRCLE_LASSO)
                    }
                    // The dot of a short tap: if it is the first of a double tap on a box, it is removed.
                    if (penTapStroke != null && penTapStroke in strokes.keys && elements.size == 1) penTapDot = target.ink to elements[0]
                    target.doc.markEdited()
                    if (preset?.kind == ToolKind.HIGHLIGHTER && preset.snapToText) {
                        for (e in elements) session.snapHighlight(target.doc, target.ink, target.page, e)
                    }
                }
            }
        }
        session.notify(Change.INK)
        session.notify(Change.LIVE)
        inkView.removeFinishedStrokes(strokes.keys)
    }

    // ---------------------------------------------------------------- pen gestures

    /** Stroke points and pause of the tip: if it stays still for [LASSO_HOLD_MS], [lassoHold] starts. */
    private fun trackInkGesture(a: Action.Ink, event: MotionEvent, index: Int) {
        if (!a.gestures) return
        for (h in 0..event.historySize) {
            val current = h == event.historySize
            toPage(
                if (current) event.getX(index) else event.getHistoricalX(index, h),
                if (current) event.getY(index) else event.getHistoricalY(index, h),
            )
            a.points.add(point[0])
            a.points.add(point[1])
        }
        val x = event.getX(index)
        val y = event.getY(index)
        if (hypot(x - a.stillX, y - a.stillY) > HOLD_SLOP_DP * density) {
            a.stillX = x
            a.stillY = y
            removeCallbacks(lassoHold)
            postDelayed(lassoHold, LASSO_HOLD_MS)
        }
    }

    /**
     * The tip stayed still at the end of a circle: the stroke becomes a selection of the elements it
     * encloses (like the lasso), without changing tool. If it encloses nothing, it stays a normal stroke.
     */
    private val lassoHold = Runnable {
        val a = action as? Action.Ink ?: return@Runnable
        val pts = a.points.toFloatArray()
        if (!PenGestures.isLassoLoop(pts)) return@Runnable
        val chosen = a.ink.elements.filter { SelectionGeometry.lassoCoverage(it, pts) >= LASSO_COVERAGE }
        if (chosen.isEmpty()) return@Runnable
        cancelInk(a, null)
        trace("selection: circle held still, ${chosen.size} elements")
        session.selection = Selection(a.doc, a.ink, chosen)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // The rest of the gesture (until lifting) no longer draws.
        action = Action.Swallow
        overlay.invalidate()
    }

    /**
     * Hold to select (user's choice, the same with pen and finger): the tip held still on an element since it
     * touched, for [PEN_HOLD_MS], selects that element instead of drawing; with the shape, text and lasso
     * tools. Not with pen and marker: while writing, a short hesitation at the start of a letter over a text
     * box or next to other strokes selected them and broke the writing (seen on the tablet); there the circle
     * held still remains. Nor where pausing is normal (highlighter, laser, eraser, spotlight). Dragging on,
     * without lifting, moves it.
     */
    private val penHold = Runnable {
        val a = action
        val (doc, ink) = when (a) {
            is Action.Ink -> a.doc to a.ink
            is Action.Lasso -> a.doc to a.ink
            is Action.TextPress -> a.doc to a.ink
            else -> return@Runnable
        }
        if (session.currentInk !== ink) return@Runnable
        toPage(penDownX, penDownY)
        val hit = elementAt(ink, point[0], point[1], pageUnits(HANDLE_HIT_DP / 2)) ?: return@Runnable
        removeCallbacks(lassoHold)
        when (a) {
            is Action.Ink -> cancelInk(a, null)
            is Action.Lasso -> session.lassoPath = null
            else -> Unit
        }
        trace("selection: pen held still on ${hit.javaClass.simpleName}")
        grab(doc, ink, hit, penDownX, penDownY)
    }

    /** A finger held still on an element for [FINGER_HOLD_MS] selects it, like the pen ([penHold]). */
    private val fingerHold = Runnable {
        if (action != null || fingerIds.size != 1 || gestureMultiTouch || gestureMoved || swipeDirection != 0) return@Runnable
        if (session.textEdit != null || session.transition != null) return@Runnable
        // Pen used or near the screen a moment ago, or a contact larger than a fingertip: the still "finger"
        // is almost certainly the palm of the writing hand (seen on the tablet: resting between two letters
        // with the pen lifted, it selected the text box under it and the next stroke moved the box).
        if (SystemClock.uptimeMillis() - lastStylusTime < PEN_RECENT_MS) return@Runnable
        val fingertip = FINGERTIP_MAX_MM / 25.4f * xdpi
        trace("finger hold: contact ${fingerMajor.toInt()} px, limit ${fingertip.toInt()} px")
        if (fingerMajor > fingertip) return@Runnable
        val doc = session.current ?: return@Runnable
        val ink = session.currentInk ?: return@Runnable
        toPage(gestureStartX, gestureStartY)
        val hit = elementAt(ink, point[0], point[1], pageUnits(FINGER_HIT_DP)) ?: return@Runnable
        // From here the finger belongs to the selection: no swipe, no tap.
        val id = fingerIds.first()
        cancelFingers()
        removeCallbacks(singleTap)
        trace("selection: finger held still on ${hit.javaClass.simpleName}")
        grab(doc, ink, hit, gestureStartX, gestureStartY)
        actionPointerId = id
        fingerAction = true
    }

    /** Topmost element of [ink] within [r] of page point ([x], [y]). */
    private fun elementAt(ink: PageInk, x: Float, y: Float, r: Float) = ink.elements.lastOrNull { it.touches(x, y, x, y, r) }

    /**
     * [element], held at screen point ([sx], [sy]), becomes the selection and the gesture goes on as a move
     * of it (page point already in [point]).
     */
    private fun grab(doc: PresenterDocument, ink: PageInk, element: PageElement, sx: Float, sy: Float) {
        val sel = Selection(doc, ink, listOf(element))
        session.selection = sel
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        action = Action.Transform(TransformKind.MOVE, sel, point[0], point[1], sx, sy, sel.box(pageUnits(sel.padDp))).also {
            it.held = true
            it.targets = Snapping.Targets.of(ink.elements, sel.elements)
        }
        ink.beginChange()
        overlay.invalidate()
    }

    /** Stroke [stroke] is a scribble over elements: erases them (one undo step). */
    private fun eraseByScribble(target: Target, stroke: Stroke): Boolean {
        val pts = InkStroke(stroke).samplePoints()
        if (!PenGestures.isScribble(pts)) return false
        val gone = PenGestures.scribbled(target.ink.elements, pts, stroke.brush.size)
        if (gone.isEmpty()) return false
        target.ink.remove(gone)
        target.doc.markEdited()
        return true
    }

    // ---------------------------------------------------------------- shapes, selection, lasso, text

    /** Page units for [dp] screen dp, at the current zoom. */
    private fun pageUnits(dp: Float): Float = dp * density / session.viewport.pageToView.mapRadius(1f).coerceAtLeast(1e-3f)

    /**
     * The shape tool's stroke becomes the recognized shape, selected with its vertices. A tap selects the
     * shape under the pen (with the box).
     */
    private fun strokeToShape(target: Target, preset: ToolPreset, stroke: Stroke) {
        val points = InkStroke(stroke).samplePoints()
        val box = Box.of(points) ?: return
        val current = session.current?.let { it.inkOf(it.pageIndex) } === target.ink
        if (maxOf(box.width, box.height) < pageUnits(TAP_SLOP_DP)) {
            if (!current) return
            val hit = target.ink.elements.lastOrNull { it is Shape && it.touches(box.centerX, box.centerY, box.centerX, box.centerY, pageUnits(HANDLE_HIT_DP / 2)) }
            session.selection = hit?.let { Selection(target.doc, target.ink, listOf(it)) }
            overlay.invalidate()
            return
        }
        val r = ShapeRecognizer.recognize(points)
        val closed = r.type == ShapeType.POLYGON || r.type == ShapeType.ELLIPSE
        val shape = Shape(r.type, r.points, preset.color, preset.size, if (closed) preset.fill else 0, preset.dash, preset.arrow)
        target.ink.add(listOf(shape))
        target.doc.markEdited()
        if (current) {
            // Just drawn it starts from the vertices (user's choice); a tap switches to the box.
            session.selection = Selection(target.doc, target.ink, listOf(shape), boxMode = false)
            overlay.invalidate()
        }
    }

    /** Gesture starting at ([sx], [sy]) on screen: on a handle or inside the selection, otherwise null. */
    private fun beginTransform(sel: Selection, sx: Float, sy: Float): Action.Transform? {
        val px = point[0]
        val py = point[1]
        val hit = HANDLE_HIT_DP * density
        val box = sel.box(pageUnits(sel.padDp))
        val m = session.viewport.pageToView
        val scr = FloatArray(2)
        fun onScreen(x: Float, y: Float): Float {
            scr[0] = x; scr[1] = y
            m.mapPoints(scr)
            return SelectionGeometry.distance(scr[0], scr[1], sx, sy)
        }
        // Shape vertices or box handles, depending on the mode.
        var action: Action.Transform? = null
        var best = hit
        val shape = sel.shape
        if (shape != null && sel.showsVertices) {
            for (i in 0 until shape.pointCount) {
                val d = onScreen(shape.points[2 * i], shape.points[2 * i + 1])
                if (d < best) {
                    best = d
                    action = Action.Transform(
                        TransformKind.VERTEX, sel, px, py, sx, sy, box, vertex = i,
                        offX = shape.points[2 * i] - px, offY = shape.points[2 * i + 1] - py,
                    )
                }
            }
        } else {
            for (h in SelectionGeometry.Handle.entries) {
                val pos = SelectionGeometry.handlePosition(h, box, sel.angle, pageUnits(ROTATE_OFFSET_DP))
                val d = onScreen(pos[0], pos[1])
                if (d < best) {
                    best = d
                    val kind = if (h == SelectionGeometry.Handle.ROTATE) TransformKind.ROTATE else TransformKind.RESIZE
                    action = Action.Transform(kind, sel, px, py, sx, sy, box, handle = h, offX = pos[0] - px, offY = pos[1] - py)
                }
            }
        }
        if (action == null && SelectionGeometry.inside(box, sel.angle, px, py)) {
            action = Action.Transform(TransformKind.MOVE, sel, px, py, sx, sy, box)
        }
        if (action != null) {
            action.targets = Snapping.Targets.of(sel.ink.elements, sel.elements)
            sel.ink.beginChange()
        }
        return action
    }

    private fun transformMoved(a: Action.Transform, sx: Float, sy: Float, snap: Boolean) {
        if (!a.moved && hypot(sx - a.sx0, sy - a.sy0) < TAP_SLOP_DP * density) return
        // Selection removed or edit closed halfway (undo, page change from the keyboard): the drag ends here,
        // without touching the page outside the history.
        if (!a.start.ink.isChanging || session.selection?.ink !== a.start.ink) {
            session.guides = emptyList()
            action = Action.Swallow
            overlay.invalidate()
            return
        }
        a.moved = true
        toPage(sx, sy)
        val px = point[0] + a.offX
        val py = point[1] + a.offY
        val start = a.start
        var angle = start.angle
        val tolerance = pageUnits(SNAP_DP)
        val targets = if (snap) a.targets else Snapping.Targets.EMPTY
        var guides = emptyList<SelectionGeometry.Guide>()
        val after: List<PageElement> = when (a.kind) {
            TransformKind.MOVE -> {
                var dx = point[0] - a.x0
                var dy = point[1] - a.y0
                // The snap points of the moved shapes against those of the others.
                val moving = Snapping.anchorsOf(start.shapes)
                if (!targets.isEmpty && moving.isNotEmpty()) {
                    for (k in moving.indices) moving[k] += if (k % 2 == 0) dx else dy
                    val m = Snapping.snapMove(moving, targets, tolerance)
                    dx += m.dx
                    dy += m.dy
                    guides = m.guides
                }
                start.elements.map { it.transformed(Affine.translate(dx, dy)) }
            }
            TransformKind.RESIZE -> {
                val h = a.handle!!
                val corner = h.u != 0 && h.v != 0
                var scales = SelectionGeometry.resizeScales(h, a.box, start.angle, px, py, uniform = corner)
                // A single shape: width or height like another one of the same family.
                val shape = start.shape
                var models = emptyList<Pair<Shape, Boolean>>()
                if (shape != null && !targets.isEmpty) {
                    val sc = Snapping.snapScale(shape, start.angle, scales[0], scales[1], h.u != 0, h.v != 0, corner, targets, tolerance)
                    scales = floatArrayOf(sc.sx, sc.sy)
                    models = sc.models
                }
                val t = SelectionGeometry.resizeAffine(h, a.box, start.angle, scales[0], scales[1])
                val resized = start.elements.map { it.transformed(t) }
                // The equal measure, on the model shape and on the resized one: outside the selection box, which would
                // hide it otherwise.
                val gap = pageUnits(Selection.PAD_DP + MEASURE_GAP_DP)
                guides = models.flatMap { (model, horizontal) ->
                    Snapping.measure(model, start.angle, horizontal, gap) +
                        Snapping.measure(resized.single() as Shape, start.angle, horizontal, gap)
                }
                resized
            }
            TransformKind.ROTATE -> {
                val c = SelectionGeometry.toPage((a.box[0] + a.box[2]) / 2f, (a.box[1] + a.box[3]) / 2f, start.angle)
                val delta = SelectionGeometry.rotation(c[0], c[1], a.x0 + a.offX, a.y0 + a.offY, px, py, start.angle)
                angle = start.angle + delta
                start.elements.map { it.transformed(Affine.rotate(delta, c[0], c[1])) }
            }
            TransformKind.VERTEX -> {
                val shape = start.shape!!
                val p = Snapping.snapVertex(shape, a.vertex, px, py, targets, tolerance)
                guides = p.guides
                listOf(shape.withPoint(a.vertex, p.x, p.y))
            }
        }
        session.guides = guides
        val current = session.selection?.elements ?: start.elements
        start.ink.replaceLive(current, after)
        session.selection = start.with(elements = after, angle = angle)
        session.notify(Change.INK)
        overlay.invalidate()
    }

    private fun finishTransform(a: Action.Transform) {
        session.guides = emptyList()
        val start = a.start
        if (start.ink.endChange()) {
            session.onInkEdited(start.doc)
        } else if (a.held) {
            // Held and lifted without moving: just selected.
        } else if (!a.moved && a.kind == TransformKind.MOVE && start.shape != null && session.selection?.ink === start.ink) {
            // Tap on the shape: from the box (resize, rotate) to the vertices and back.
            session.selection = start.with(boxMode = !start.boxMode, angle = SelectionGeometry.naturalAngle(start.elements))
        } else if (!a.moved && a.kind == TransformKind.MOVE && session.selection?.ink === start.ink) {
            // Tap on a selected text box (pen or finger): edit, cursor where it was touched.
            val box = start.elements.singleOrNull() as? TextBox
            if (box != null) {
                trace("text edit: tap on the selected text box")
                session.beginTextEdit(start.doc, start.ink, box, isNew = false, cursor = TextLayouts.offsetAt(box, a.x0, a.y0))
            }
        }
        overlay.invalidate()
    }

    /** Lasso closed: selects the elements mostly inside; a tap takes the element under the pen. */
    private fun finishLasso(a: Action.Lasso) {
        session.lassoPath = null
        val pts = a.points.toFloatArray()
        val box = Box.of(pts)
        val chosen = if (box == null || maxOf(box.width, box.height) < pageUnits(TAP_SLOP_DP)) {
            val x = pts[0]
            val y = pts[1]
            val r = pageUnits(HANDLE_HIT_DP / 2)
            listOfNotNull(a.ink.elements.lastOrNull { it.touches(x, y, x, y, r) })
        } else {
            a.ink.elements.filter { SelectionGeometry.lassoCoverage(it, pts) >= LASSO_COVERAGE }
        }
        if (chosen.isNotEmpty()) trace("selection: lasso, ${chosen.size} elements")
        session.selection = if (chosen.isEmpty()) null else Selection(a.doc, a.ink, chosen)
        overlay.invalidate()
    }

    /** Text tool: tap on a box to edit it, elsewhere to create one (dragging chooses its width). */
    private fun finishTextPress(a: Action.TextPress) {
        if (session.current?.let { it.inkOf(it.pageIndex) } !== a.ink) return
        val tap = hypot(a.sx1 - a.sx0, a.sy1 - a.sy0) < TAP_SLOP_DP * density
        if (tap) {
            val pad = pageUnits(TAP_SLOP_DP)
            val hit = a.ink.elements.lastOrNull { it is TextBox && it.contains(a.x0, a.y0, pad) } as TextBox?
            if (hit != null) {
                trace("text edit: text tool on a box")
                session.beginTextEdit(a.doc, a.ink, hit, isNew = false, cursor = TextLayouts.offsetAt(hit, a.x0, a.y0))
                return
            }
        }
        val p = a.preset
        val left = if (tap) a.x0 else minOf(a.x0, a.x1)
        val width = if (tap) (PAGE_WIDTH - left - TEXT_MARGIN).coerceIn(MIN_TEXT_WIDTH, DEFAULT_TEXT_WIDTH) else abs(a.x1 - a.x0).coerceAtLeast(MIN_TEXT_WIDTH)
        val box = TextBox(
            text = "", x = left, y = a.y0 - p.size * 0.6f, width = width, height = p.size * TextBox.LINE_SPACING,
            size = p.size, color = p.color, font = p.font, bold = p.bold, italic = p.italic,
        )
        trace("text edit: text tool, new box")
        session.beginTextEdit(a.doc, a.ink, box, isNew = true)
    }

    /** Pressure 0…1 for the pen, -1 for fingers and mouse. [h] = history index, -1 = current point. */
    private fun pressureOf(event: MotionEvent, index: Int, h: Int): Float {
        if (event.getToolType(index) != MotionEvent.TOOL_TYPE_STYLUS) return -1f
        return (if (h < 0) event.getPressure(index) else event.getHistoricalPressure(index, h)).coerceIn(0f, 1f)
    }

    /** Converts the points of [event] into Ink inputs in page coordinates (copy for the projector). */
    private fun appendInputs(out: MutableStrokeInputBatch, event: MotionEvent, index: Int, downTime: Long, history: Boolean) {
        out.clear()
        val stylus = event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS
        val toolType = when (event.getToolType(index)) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> InputToolType.STYLUS
            MotionEvent.TOOL_TYPE_MOUSE -> InputToolType.MOUSE
            else -> InputToolType.TOUCH
        }
        // Physical centimeters on the tablet per page unit (needed by the speed-based behaviors).
        val unitCm = session.viewport.pageToView.mapRadius(1f) * 2.54f / xdpi
        val from = if (history) 0 else event.historySize
        for (h in from..event.historySize) {
            val current = h == event.historySize
            toPage(
                if (current) event.getX(index) else event.getHistoricalX(index, h),
                if (current) event.getY(index) else event.getHistoricalY(index, h),
            )
            val t = if (current) event.eventTime else event.getHistoricalEventTime(h)
            val pressure = if (stylus) pressureOf(event, index, if (current) -1 else h) else StrokeInput.NO_PRESSURE
            val tilt = if (stylus) {
                (if (current) event.getAxisValue(MotionEvent.AXIS_TILT, index)
                else event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, h)).coerceIn(0f, HALF_PI)
            } else StrokeInput.NO_TILT
            val orientation = if (stylus) {
                val o = if (current) event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index)
                else event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, index, h)
                (o + 2.5f * PI).mod(2f * PI)
            } else StrokeInput.NO_ORIENTATION
            // Duplicate or out-of-order inputs are normal on real hardware: they are dropped.
            runCatching { out.add(toolType, point[0], point[1], t - downTime, unitCm, pressure, tilt, orientation) }
        }
    }

    private fun toPage(x: Float, y: Float) {
        point[0] = x
        point[1] = y
        session.viewport.viewToPage.mapPoints(point)
    }

    private fun showEraser(x: Float, y: Float) {
        overlay.showEraser(x, y, session.viewport.pageToView.mapRadius(tools.eraserSize))
    }

    /**
     * Wet ink must not appear where dry ink is not visible: outside the page (where it is clipped) and under
     * the notes panel.
     */
    private fun updateInkMask() {
        if (width == 0 || height == 0) return
        val mask = Path().apply { addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW) }
        session.currentRef?.let { ref ->
            val page = RectF(0f, 0f, PAGE_WIDTH, ref.doc.pageHeight(ref.index))
            session.viewport.pageToView.mapRect(page)
            mask.op(Path().apply { addRect(page, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        }
        session.notesBoundsInWindow?.let { r ->
            getLocationInWindow(windowLocation)
            val local = RectF(r).apply { offset(-windowLocation[0].toFloat(), -windowLocation[1].toFloat()) }
            mask.op(Path().apply { addRect(local, Path.Direction.CW) }, Path.Op.UNION)
        }
        inkView.maskPath = mask
    }

    // ---------------------------------------------------------------- S Pen hover

    private var hoverX = 0f
    private var hoverY = 0f
    private var hovering = false

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return super.onHoverEvent(event)
        lastStylusTime = event.eventTime
        trackPenButton(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hovering = true
                hoverX = event.x
                hoverY = event.y
                refreshHoverDot(event)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                hovering = false
                // Pen out of range: the digitizer will no longer say if the button is released.
                if (penButtonDown && event.buttonState == 0) {
                    penButtonDown = false
                    tools.buttonHeld = false
                    tools.buttonDoubleClick = false
                }
                clearHover(event.eventTime)
            }
        }
        return true
    }

    /** With the laser it is enough to bring the pen close: the dot appears without touching the screen. */
    private fun refreshHoverDot(event: MotionEvent? = null) {
        if (!hovering || session.current == null || session.transition != null) return
        val ref = if (event != null) toolFor(event, 0) else currentTool()
        val preset = tools.preset(ref)
        when {
            isEraser(ref) -> {
                showEraser(hoverX, hoverY)
                if (session.laser.hovering) {
                    session.laser.hoverExit(SystemClock.uptimeMillis())
                    session.notify(Change.LASER)
                }
            }
            preset?.kind == ToolKind.LASER -> {
                toPage(hoverX, hoverY)
                // No pressure in hover: a dot a little smaller than the maximum.
                session.laser.hover(preset.color, preset.size * HOVER_DOT_SCALE, point[0], point[1])
                session.notify(Change.LASER)
                overlay.hideEraser()
            }
            else -> clearHover(SystemClock.uptimeMillis())
        }
    }

    private fun clearHover(time: Long) {
        if (session.laser.hovering) {
            session.laser.hoverExit(time)
            session.notify(Change.LASER)
        }
        if (action !is Action.Erase) overlay.hideEraser()
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        if (event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_STYLUS &&
            currentTool().let { isEraser(it) || tools.preset(it)?.kind == ToolKind.LASER }
        ) {
            return PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
        }
        return super.onResolvePointerIcon(event, pointerIndex)
    }

    // ---------------------------------------------------------------- fingers

    private fun fingerDown(event: MotionEvent, index: Int, manipulate: Boolean = true) {
        // Pen near the screen or in use: the touch is almost certainly the palm.
        if (action != null || session.current == null || event.eventTime - lastStylusTime < PALM_GUARD_MS) return
        // One finger on a selection: handles, vertices, moving, at once (without waiting for the double tap), as
        // with the pen. Outside the selection: navigation as usual.
        if (manipulate && fingerIds.isEmpty() && session.textEdit == null && session.transition == null) {
            val sel = session.selection
            if (sel != null && sel.ink === session.currentInk) {
                val sx = event.getX(index)
                val sy = event.getY(index)
                toPage(sx, sy)
                beginTransform(sel, sx, sy)?.let {
                    trace("finger on the selection (${sel.elements.size} elements): ${it.kind}, contact ${event.getTouchMajor(index).toInt()} px, ${event.eventTime - lastStylusTime} ms after the pen")
                    removeCallbacks(singleTap)
                    action = it
                    actionPointerId = event.getPointerId(index)
                    fingerAction = true
                    return
                }
            }
        }
        if (fingerIds.isEmpty()) {
            gestureStartX = event.getX(index)
            gestureStartY = event.getY(index)
            gestureStartTime = event.eventTime
            gestureMultiTouch = false
            gestureMaxFingers = 0
            gestureMoved = false
            swipeDirection = 0
            velocity?.clear()
            session.finishTransition()
            // A finger held still on an element selects it (see [fingerHold]). Not while writing: a hand that
            // lands between two strokes, with the pen lifted, is the palm.
            removeCallbacks(fingerHold)
            fingerMajor = event.getTouchMajor(index)
            if (manipulate && session.textEdit == null && event.eventTime - lastStylusTime >= PEN_RECENT_MS) {
                postDelayed(fingerHold, FINGER_HOLD_MS)
            }
        } else {
            removeCallbacks(fingerHold)
            gestureMultiTouch = true
            if (swipeDirection != 0) {
                session.endSwipe(complete = false)
                swipeDirection = 0
            }
        }
        fingerIds.add(event.getPointerId(index))
        gestureMaxFingers = maxOf(gestureMaxFingers, fingerIds.size)
        trackVelocity(event)
        resetFingerReference(event, excludeIndex = -1)
    }

    private fun fingersMoved(event: MotionEvent) {
        if (fingerIds.isEmpty()) return
        trackVelocity(event)
        if (!computeFingers(event, excludeIndex = -1)) return
        val cx = fingerScratch[0]
        val cy = fingerScratch[1]
        val span = fingerScratch[2]
        val count = fingerScratch[3].toInt()
        if (count != fingerCount) {
            resetFingerReference(event, -1)
            return
        }
        // One finger: swipe to change page (also with the page zoomed).
        if (count == 1 && !gestureMultiTouch) {
            // A finger that has already moved (or is turning the page) is no longer part of an undo tap.
            if (hypot(cx - gestureStartX, cy - gestureStartY) >= TAP_SLOP_DP * density) {
                gestureMoved = true
                removeCallbacks(fingerHold)
            }
            // A resting palm grows after landing.
            for (i in 0 until event.pointerCount) {
                if (event.getPointerId(i) in fingerIds) fingerMajor = maxOf(fingerMajor, event.getTouchMajor(i))
            }
            swipeMoved(cx, cy)
            fingerCx = cx
            fingerCy = cy
            return
        }
        // Two or more fingers: pan and zoom (pinch). Zoom starts only when the distance between the fingers
        // really changes: before that the fingers only pan, otherwise the natural tremor makes the page pulse.
        if (!pinching && abs(span - pinchStartSpan) > PINCH_SLOP_DP * density) {
            pinching = true
            fingerSpan = span
        }
        // Panning too starts only beyond a small threshold: a two- or three-finger tap (undo/redo) must not move
        // the sheet.
        if (!panning) {
            if (!pinching && hypot(cx - panStartX, cy - panStartY) < PAN_SLOP_DP * density) return
            panning = true
            gestureMoved = true
            fingerCx = cx
            fingerCy = cy
        }
        val scale = if (pinching && fingerSpan > 0f) span / fingerSpan else 1f
        val dx = cx - fingerCx
        val dy = cy - fingerCy
        fingerCx = cx
        fingerCy = cy
        fingerSpan = span
        session.viewport.applyGesture(scale, cx, cy, dx, dy)
        session.notify(Change.VIEWPORT)
    }

    /** Full-page swipe: the page follows the finger, the next one enters from the opposite side. */
    private fun swipeMoved(x: Float, y: Float) {
        val dx = x - gestureStartX
        val dy = y - gestureStartY
        if (swipeDirection == 0) {
            if (abs(dx) < SWIPE_SLOP_DP * density || abs(dx) < 1.2f * abs(dy)) return
            val direction = if (dx < 0) 1 else -1
            if (!session.beginSwipe(direction)) return
            swipeDirection = direction
            swipeDistance = computeSwipeDistance(direction)
        }
        val progress = -dx * swipeDirection / swipeDistance
        if (progress < 0f) {
            // The finger went back past the starting point: cancel (and it can start again from the other side).
            session.cancelSwipe()
            swipeDirection = 0
            return
        }
        session.updateSwipe(progress)
    }

    private fun fingerUp(event: MotionEvent, index: Int) {
        val id = event.getPointerId(index)
        if (!fingerIds.remove(id)) return
        trackVelocity(event)
        removeCallbacks(fingerHold)
        if (fingerIds.isEmpty()) {
            if (swipeDirection != 0) {
                finishSwipe(event, index)
            } else if (event.flags and MotionEvent.FLAG_CANCELED == 0) {
                if (gestureMaxFingers >= 2) detectMultiFingerTap(event) else detectDoubleTap(event, index)
            }
        } else {
            resetFingerReference(event, excludeIndex = index)
        }
    }

    private fun finishSwipe(event: MotionEvent, index: Int) {
        val vt = velocity
        vt?.computeCurrentVelocity(1000)
        val v = -(vt?.xVelocity ?: 0f) * swipeDirection
        val progress = -(event.getX(index) - gestureStartX) * swipeDirection / swipeDistance
        val fling = v > FLING_DP_S * density && progress > 0.04f
        session.endSwipe(complete = progress > 0.35f || fling)
        swipeDirection = 0
    }

    /** Drag distance for a complete transition: the page follows the finger exactly. */
    private fun computeSwipeDistance(direction: Int): Float {
        val ref = session.currentRef ?: return width.toFloat().coerceAtLeast(1f)
        fun rectOf(index: Int): RectF {
            val m = Matrix().also { session.viewport.matrixFor(ref.doc.pageHeight(index), it) }
            return RectF(0f, 0f, PAGE_WIDTH, ref.doc.pageHeight(index)).also { m.mapRect(it) }
        }
        // The incoming page is the one prepared by beginSwipe (with overlays, not always the adjacent one).
        val target = session.transition?.to?.takeIf { it.doc === ref.doc }?.index ?: (ref.index + direction)
        return SceneRenderer.slideDistance(direction, width, rectOf(ref.index), rectOf(target))
    }

    /** Short, still tap with two fingers: undo; with three: redo (if enabled in the settings). */
    private fun detectMultiFingerTap(event: MotionEvent) {
        if (!session.settings.fingerUndo || gestureMoved || event.eventTime - gestureStartTime > MULTI_TAP_MS) return
        when (gestureMaxFingers) {
            2 -> session.undo()
            3 -> session.redo()
        }
    }

    private fun detectDoubleTap(event: MotionEvent, index: Int) {
        if (gestureMultiTouch) return
        val x = event.getX(index)
        val y = event.getY(index)
        val dist = hypot(x - gestureStartX, y - gestureStartY)
        if (dist >= TAP_SLOP_DP * density || event.eventTime - gestureStartTime >= TAP_MAX_MS) return
        if (event.eventTime - lastTapTime < DOUBLE_TAP_MS && hypot(x - lastTapX, y - lastTapY) < DOUBLE_TAP_SLOP_DP * density) {
            // Double tap: on a text box it edits it (cursor where touched), elsewhere whole page; the pending single
            // tap no longer applies.
            removeCallbacks(singleTap)
            lastTapTime = 0L
            val ink = session.currentInk
            toPage(x, y)
            val box = ink?.let { textBoxAt(it, point[0], point[1]) }
            val doc = session.current
            if (box != null && doc != null && session.textEdit == null) {
                trace("text edit: finger double tap (contact ${fingerMajor.toInt()} px, ${event.eventTime - lastStylusTime} ms after the pen)")
                session.beginTextEdit(doc, ink, box, isNew = false, cursor = TextLayouts.offsetAt(box, point[0], point[1]))
                return
            }
            session.viewport.reset()
            session.notify(Change.VIEWPORT)
        } else {
            lastTapX = x
            lastTapY = y
            removeCallbacks(singleTap)
            // A double tap here would do nothing (whole page already, no text box under the finger), so the tap
            // acts at once instead of waiting to see whether a second one comes.
            toPage(x, y)
            val box = session.currentInk?.let { textBoxAt(it, point[0], point[1]) }
            if (box == null && session.viewport.isReset) {
                lastTapTime = 0L
                singleTap.run()
                return
            }
            // Otherwise the single tap counts only if no second tap comes.
            lastTapTime = event.eventTime
            postDelayed(singleTap, DOUBLE_TAP_MS)
        }
    }

    /**
     * Confirmed single finger tap: follows the link under it or, if there is none, shows the next overlay of
     * the slide (Beamer).
     */
    private val singleTap = Runnable {
        if (action != null || fingerIds.isNotEmpty()) return@Runnable
        // With a text box open the tap just closes it (as with the pen): no overlay or link while typing.
        if (session.textEdit != null) {
            session.endTextEdit()
            return@Runnable
        }
        toPage(lastTapX, lastTapY)
        // Finger tap on a selected shape: switches mode (box ↔ vertices), as with the pen.
        val sel = session.selection
        val shape = sel?.shape
        if (sel != null && shape != null && session.currentInk === sel.ink &&
            SelectionGeometry.inside(sel.box(pageUnits(sel.padDp)), sel.angle, point[0], point[1])
        ) {
            session.selection = sel.with(boxMode = !sel.boxMode, angle = SelectionGeometry.naturalAngle(sel.elements))
            overlay.invalidate()
            return@Runnable
        }
        // Tap outside the selection: removes it (and nothing else).
        if (sel != null) {
            session.selection = null
            overlay.invalidate()
            return@Runnable
        }
        if (!session.followLinkAt(point[0], point[1])) session.nextOverlay()
    }

    /** Text box under the page point ([x], [y]), the topmost. */
    private fun textBoxAt(ink: PageInk, x: Float, y: Float): TextBox? =
        ink.elements.lastOrNull { it is TextBox && it.contains(x, y, pageUnits(TAP_SLOP_DP)) } as TextBox?

    /** The pen arrived while a finger was moving something: the finger gesture is cancelled, the page goes back. */
    private fun cancelFingerAction() {
        val a = action as? Action.Transform
        if (a != null) {
            a.start.ink.cancelChange()
            session.guides = emptyList()
            if (session.selection?.ink === a.start.ink) session.selection = a.start
            session.notify(Change.INK)
            overlay.invalidate()
        }
        action = null
        actionPointerId = INVALID
        fingerAction = false
    }

    /**
     * Second finger while a finger moves the selection: what was done stays (one undo step) and the fingers
     * switch to panning and zooming the sheet.
     */
    private fun handFingerActionToGesture(event: MotionEvent, index: Int) {
        val a = action as? Action.Transform
        if (a != null) {
            session.guides = emptyList()
            if (a.moved && a.start.ink.endChange()) session.onInkEdited(a.start.doc) else a.start.ink.cancelChange()
            overlay.invalidate()
        }
        val first = event.findPointerIndex(actionPointerId)
        action = null
        actionPointerId = INVALID
        fingerAction = false
        if (first >= 0) fingerDown(event, first, manipulate = false)
        fingerDown(event, index, manipulate = false)
    }

    private fun trackVelocity(event: MotionEvent) {
        val vt = velocity ?: VelocityTracker.obtain().also { velocity = it }
        vt.addMovement(event)
    }

    private fun cancelFingers() {
        removeCallbacks(fingerHold)
        fingerIds.clear()
        fingerCount = 0
        if (swipeDirection != 0) {
            session.endSwipe(complete = false)
            swipeDirection = 0
        }
    }

    private fun resetFingerReference(event: MotionEvent, excludeIndex: Int) {
        if (!computeFingers(event, excludeIndex)) {
            fingerCount = 0
            return
        }
        fingerCx = fingerScratch[0]
        fingerCy = fingerScratch[1]
        fingerSpan = fingerScratch[2]
        fingerCount = fingerScratch[3].toInt()
        pinchStartSpan = fingerSpan
        pinching = false
        panning = false
        panStartX = fingerCx
        panStartY = fingerCy
        session.viewport.beginGesture()
    }

    /** Centroid, average distance from the centroid and number of tracked fingers, in [fingerScratch]. */
    private fun computeFingers(event: MotionEvent, excludeIndex: Int): Boolean {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludeIndex || event.getPointerId(i) !in fingerIds) continue
            sx += event.getX(i)
            sy += event.getY(i)
            n++
        }
        if (n == 0) return false
        val cx = sx / n
        val cy = sy / n
        var span = 0f
        for (i in 0 until event.pointerCount) {
            if (i == excludeIndex || event.getPointerId(i) !in fingerIds) continue
            span += hypot(event.getX(i) - cx, event.getY(i) - cy)
        }
        fingerScratch[0] = cx
        fingerScratch[1] = cy
        fingerScratch[2] = span / n
        fingerScratch[3] = n.toFloat()
        return true
    }

    private companion object {
        const val INVALID = -1
        const val PI = Math.PI.toFloat()
        const val HALF_PI = PI / 2f
        const val PALM_GUARD_MS = 400L
        const val SWIPE_SLOP_DP = 16f
        const val PINCH_SLOP_DP = 12f
        const val PAN_SLOP_DP = 8f
        const val MULTI_TAP_MS = 300L
        const val DOUBLE_CLICK_MS = ToolState.DOUBLE_CLICK_MS
        const val FLING_DP_S = 600f
        const val TAP_SLOP_DP = 12f
        const val TAP_MAX_MS = 250L
        const val DOUBLE_TAP_MS = 320L
        const val DOUBLE_TAP_SLOP_DP = 40f

        /** Pen double tap: the pen lands twice on the same point (two dots of a colon are farther apart). */
        const val PEN_DOUBLE_TAP_SLOP_DP = 12f
        const val HOVER_DOT_SCALE = 0.75f
        const val HANDLE_HIT_DP = 24f
        const val ROTATE_OFFSET_DP = OverlayView.ROTATE_OFFSET_DP
        const val SNAP_DP = 10f

        /** Distance of the measure guide (equal size) beyond the selection box. */
        const val MEASURE_GAP_DP = 8f

        /** Fraction of an element's points that must be inside the lasso. */
        const val LASSO_COVERAGE = 0.6f
        /** Pause of the tip at the end of a circle that turns it into a lasso, and the tremor allowed. */
        const val LASSO_HOLD_MS = 600L
        const val HOLD_SLOP_DP = 6f

        /** Hold to select: time with the pen and with a finger (longer: a finger may rest there by chance). */
        const val PEN_HOLD_MS = 500L
        const val FINGER_HOLD_MS = 700L

        /** Hold to select with a finger: how far from the element it still counts (thin strokes too). */
        const val FINGER_HIT_DP = 22f

        /** Hold to select with a finger: not within this time from the last pen event (touch or hover)… */
        const val PEN_RECENT_MS = 3000L

        /** …nor with a contact wider than this (a fingertip is 7–10 mm, the side of the hand much more). */
        const val FINGERTIP_MAX_MM = 13f
        const val DEFAULT_TEXT_WIDTH = 420f
        const val MIN_TEXT_WIDTH = 60f
        const val TEXT_MARGIN = 20f
    }
}
