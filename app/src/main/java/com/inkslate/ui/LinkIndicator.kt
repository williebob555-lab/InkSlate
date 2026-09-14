package com.inkslate.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            summary = withContext(Dispatchers.IO) { runCatching { com.inkslate.data.AppPeers.summary() }.getOrNull() }
            delay(2_000)
        }
    }
    return summary
}

/**
 * "Linked to Tablet", or "Not linked" - in the home screen's bar, where it can be seen before a
 * document is opened. Hidden entirely while the link is off or nothing is paired.
 */
@Composable
fun LinkIndicator(modifier: Modifier = Modifier) {
    val summary = rememberLinkSummary() ?: return
    val tint = if (summary.linked) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.error
    Row(modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (summary.linked) Icons.Default.Link else Icons.Default.LinkOff,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(summary.label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}
