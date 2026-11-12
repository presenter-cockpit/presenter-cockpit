package it.presenter.app

import android.app.Application
import android.content.Context
import it.presenter.app.doc.PdfInkStore
import it.presenter.app.doc.PresenterDocument
import it.presenter.app.remote.SavedPasswords
import it.presenter.app.session.PresenterSession
import it.presenter.app.tabs.LocalShell

class PresenterApp : Application() {
    /** Presentation state: it lives with the process, not with the Activity. */
    val session by lazy { PresenterSession(this) }

    /**
     * The saved session has already been reopened (or there was none). While false nothing is saved: it
     * would overwrite the previous session with an empty one.
     */
    var sessionRestored = false

    /** The private start has already been evaluated in this process (see `MainActivity.onStart`). */
    var privateStartChecked = false

    override fun onCreate() {
        super.onCreate()
        context = this
        PdfInkStore.init(this)
        LocalShell.init(this)
        SavedPasswords.init(this)
        // Private copies from a previous session: the documents are no longer open.
        PresenterDocument.clearCache(this)
    }

    companion object {
        /** Application context, for those that have none (e.g. tabs recreated from the session). */
        lateinit var context: Context
            private set
    }
}
