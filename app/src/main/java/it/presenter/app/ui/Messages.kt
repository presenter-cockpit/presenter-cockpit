package it.presenter.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import it.presenter.app.R

/** Plain message. */
class TextMessage(override val message: String) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction = false
    override val duration = SnackbarDuration.Short
}

/** Notice about a feature missing in this distribution; [url] = project page (null: no icon). */
class LimitMessage(override val message: String, val url: String?) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction = false
    override val duration = SnackbarDuration.Long
}

/**
 * Message with a path (e.g. "Path copied: …"): [message] is the full text (accessibility); [pattern] is
 * the same with [MARK] in place of the path, to draw it separately.
 */
class PathMessage(override val message: String, val pattern: String, val path: String) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction = false
    override val duration = SnackbarDuration.Short

    companion object {
        const val MARK = "\u0000"
    }
}

/**
 * Snackbar of the app: a [PathMessage] with its path shortened at the start, a [LimitMessage] with the icon
 * for the project page (opened by [onOpenUrl]), any other message as it is.
 */
@Composable
fun AppSnackbar(data: SnackbarData, onOpenUrl: (String) -> Unit) {
    when (val visuals = data.visuals) {
        is PathMessage -> Snackbar { PathMessageLine(visuals) }
        is LimitMessage -> {
            val url = visuals.url
            Snackbar(
                action = if (url == null) null else {
                    {
                        val label = stringResource(R.string.limit_open_project)
                        ActionIcon(Icons.AutoMirrored.Rounded.OpenInNew, label) {
                            data.dismiss()
                            onOpenUrl(url)
                        }
                    }
                },
            ) { Text(visuals.message) }
        }
        else -> Snackbar(data)
    }
}

/**
 * Message line with the full path if it fits; otherwise the path loses its beginning ("…/folder": the
 * end matters) and the rest of the message stays as it is.
 */
@Composable
private fun PathMessageLine(m: PathMessage) {
    val before = m.pattern.substringBefore(PathMessage.MARK)
    val after = m.pattern.substringAfter(PathMessage.MARK, "")
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (before.isNotEmpty()) Text(before, maxLines = 1)
        Text(
            m.path,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (after.isNotEmpty()) Text(after, maxLines = 1)
    }
}
