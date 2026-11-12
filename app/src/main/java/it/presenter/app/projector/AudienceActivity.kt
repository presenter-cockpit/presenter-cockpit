package it.presenter.app.projector

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import it.presenter.app.MainActivity
import it.presenter.app.PresenterApp
import it.presenter.app.R
import it.presenter.app.session.PresenterSession

/**
 * Window to share: what the audience sees, full screen in a task of its own, to share in a video call
 * (Teams, Meet…) as "one app" ([PresenterSession.shareWindow]). Opened from Cockpit, it brings Cockpit back
 * in front, translucent so this task stays visible and keeps drawing (see `MainActivity`). A tap here goes
 * back to Cockpit; Back, or the button in Cockpit, closes it.
 */
class AudienceActivity : ComponentActivity() {
    private val session get() = (application as PresenterApp).session
    private var returned = false
    private lateinit var content: View
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Screen sharing (WebRTC) sends frames only when the image changes and may drop the one after a big
     * change (e.g. from a code tab to a slide): the participants keep the old image until the next change.
     * Redrawing the same image a few times per second gives the encoder frames to catch up with.
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            content.invalidate()
            (content as? ViewGroup)?.let { g -> for (i in 0 until g.childCount) g.getChildAt(i).invalidate() }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /** Closed from Cockpit. */
    private val listener = PresenterSession.Listener {
        if (it == PresenterSession.Change.PROJECTOR && !session.shareWindow && !isFinishing) finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session.shareWindow = true
        session.addListener(listener)
        // Its own name in the recent apps.
        val label = getString(R.string.share_window_name)
        setTaskDescription(
            if (Build.VERSION.SDK_INT >= 33) ActivityManager.TaskDescription.Builder().setLabel(label).build()
            else @Suppress("DEPRECATION") ActivityManager.TaskDescription(label),
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        content = audienceView(this, session, compose = true, shareWindow = true)
        setContentView(content)
    }

    override fun onStart() {
        super.onStart()
        started = true
        // Like Cockpit: visible behind it, this window too would keep the screen on.
        keepScreenOn(window, session.settings.keepScreenOn)
        handler.post(heartbeat)
    }

    override fun onResume() {
        super.onResume()
        inFront = true
        if (!returned || bounce) {
            returned = true
            bounce = false
            backToCockpit()
        }
    }

    override fun onPause() {
        inFront = false
        super.onPause()
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(heartbeat)
        super.onStop()
        onStopped?.invoke()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP) backToCockpit()
        return true
    }

    override fun onDestroy() {
        session.removeListener(listener)
        if (isFinishing) session.shareWindow = false
        super.onDestroy()
    }

    /** Cockpit's task in front (its own, not a new one), or Cockpit started if it is not running. */
    private fun backToCockpit() {
        val task = getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
            runCatching { it.taskInfo?.baseActivity?.className == MainActivity::class.java.name }.getOrDefault(false)
        }
        if (task != null) task.moveToFront()
        else startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        /** The window is started, that is visible (behind a translucent Cockpit or in front). */
        private var started = false

        /**
         * The window is in front: Cockpit, stopped because of it (opening, back behind Cockpit, chosen in the
         * app that shares it), keeps the external displays on the presentation instead of flashing back to the
         * tablet.
         */
        var inFront = false
            private set

        /**
         * Called when the window stops (e.g. home screen from it): Cockpit stops the external displays if it is away
         * too.
         */
        var onStopped: (() -> Unit)? = null

        /** Brought in front only to put it back behind Cockpit ([restackIfHidden]): it goes back to Cockpit at once. */
        private var bounce = false

        /**
         * Cockpit back in front (from the home screen, another app…) with the window open: the window stayed
         * behind the other apps, invisible, and does not draw anymore (the participants see the last image).
         * It is brought in front and goes back to Cockpit, so it is right behind it again.
         */
        fun restackIfHidden(context: Context, session: PresenterSession) {
            if (!session.shareWindow || started) return
            val task = context.getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
                runCatching { it.taskInfo?.baseActivity?.className == AudienceActivity::class.java.name }.getOrDefault(false)
            } ?: return
            bounce = true
            task.moveToFront()
        }

        /** Opens the window to share (in its own task) or, if open, closes it. */
        fun toggle(context: Context, session: PresenterSession) {
            if (session.shareWindow) {
                session.shareWindow = false
            } else {
                session.shareWindow = true
                context.startActivity(Intent(context, AudienceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

private const val HEARTBEAT_MS = 250L
