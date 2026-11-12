package it.presenter.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.presenter.app.R
import it.presenter.app.session.Hint

/** First-use hint: a line with the light bulb and the icon to close it (it does not come back). */
@Composable
fun HintCard(hint: Hint, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 4.dp,
        modifier = modifier.widthIn(max = 640.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Icon(Icons.Rounded.Lightbulb, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(hint.text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp).weight(1f, fill = false))
            SmallIconButton(Icons.Rounded.Close, stringResource(R.string.hint_close), onClick = onClose)
        }
    }
}
