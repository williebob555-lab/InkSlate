package com.inkslate.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inkslate.core.peer.LinkSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Whether this device is linked to another right now, kept current.
 *
 * Asked every couple of seconds rather than told: connections change on their own threads, and a
 * label that is a little late is far better than one that is confidently wrong. Null while the
 * link is off or nothing is paired.
 */
@Composable
fun rememberLinkSummary(): LinkSummary? {
    var summary by remember { mutableStateOf<LinkSummary?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            summary = withContext(Dispatchers.IO) { runCatching { DesktopPeers.summary() }.getOrNull() }
            delay(2_000)
        }
    }
    return summary
}

/**
 * "Linked to Tablet", as a filled badge in the bar - and nothing at all when not linked.
 *
 * Using the app on one device is the ordinary case, and a warning about a link nobody is using
 * was noise on every screen. Being linked is the state that changes how the app behaves, so that
 * is the one made hard to miss. Settings still says why a paired device is not linked.
 *
 * [short] drops the device's name, for the editor's crowded bar.
 */
@Composable
fun LinkIndicator(modifier: Modifier = Modifier, short: Boolean = false) {
    val summary = rememberLinkSummary() ?: return
    if (!summary.linked) return
    Row(
        modifier
            .padding(horizontal = 6.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (short) "Linked" else summary.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}
