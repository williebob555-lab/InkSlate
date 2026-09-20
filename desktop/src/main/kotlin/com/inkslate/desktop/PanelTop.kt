package com.inkslate.desktop

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * The top of a panel: what it is, what it can do, and the way out.
 *
 * One of these on every panel so that closing one is the same action everywhere - the cross is
 * always in the top right, and a drag down from the top always puts the panel away. They were each
 * doing something different before: a Done button here, a cross there, and some with no way out at
 * all but tapping the page behind.
 */
@Composable
fun PanelTop(
    title: String,
    onClose: () -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier
            .fillMaxWidth()
            // A drag down anywhere along the top closes it, which is the gesture a sheet asks for
            // by sitting at the bottom of the screen in the first place.
            .pointerInput(Unit) {
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragCancel = { travelled = 0f },
                    onDragEnd = {
                        if (travelled > 60f) onClose()
                        travelled = 0f
                    }
                ) { change, amount ->
                    travelled += amount
                    change.consume()
                }
            }
            .padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing()
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
    }
}
