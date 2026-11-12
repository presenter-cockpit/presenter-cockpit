package it.presenter.app

import android.content.Context
import androidx.annotation.StringRes
import java.io.IOException

/**
 * Error with a message meant for the user: the logic only names the string and its arguments, the UI
 * produces the text in the right language with [describe].
 */
class UserFacingException(@param:StringRes val messageRes: Int, vararg val args: Any) : IOException()

/** Message to show to the user for this error. */
fun Throwable.describe(context: Context): String =
    if (this is UserFacingException) context.getString(messageRes, *args) else message ?: javaClass.simpleName
