package com.inksheets.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Matching the recording and knowing its page turns are separate kinds of confidence. */
internal data class ListeningStatus(val title: String, val detail: String, val warning: Boolean,
                                    val confidence: Float? = null, val countdown: Long? = null) {
    companion object {
        fun of(f: Listener.Follow?, deaf: Boolean): ListeningStatus = when {
            deaf -> ListeningStatus("Microphone unavailable", "Check input or microphone permission", true)
            f == null -> ListeningStatus("Preparing to listen", "Building a reference for this music", false)
            !f.music -> ListeningStatus("Waiting for music", "Holding this page during the pause", false)
            !f.found -> ListeningStatus(if (f.previouslyFound) "Lost your place" else "Finding your place", "Holding this page until the match is reliable", true)
            f.suspect -> ListeningStatus("Unsure · holding page", "Listening for a clearer match", true, f.sure)
            !f.turnSafe -> ListeningStatus("Following · checking turn", "This passage matches more than one place", true, f.sure)
            f.source == "guessed" -> ListeningStatus("Following recording", "Page turns are estimated", true, f.sure)
            else -> ListeningStatus("Following confidently", "Page ${f.page + 1} of ${f.pages}" +
                (f.turnMs?.let { " · next page in ${((it - f.atMs) / 1000).coerceAtLeast(0)}s" } ?: " · last page"), false,
                f.sure, f.turnMs?.let { ((it - f.atMs) / 1000).coerceAtLeast(0) })
        }
    }
}

/** A compact status in the reserved shelf, never a tall card beside the notation. */
@Composable
internal fun ListenGauge(state: SheetsState) {
    if (!Listener.active) return
    val status = ListeningStatus.of(Listener.follow, Ears.deaf)
    val color = if (status.warning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Row(Modifier.width(270.dp).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (Ears.deaf) Icons.Default.MicOff else Icons.Default.Hearing, "Listening status", Modifier.size(20.dp), tint = color)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(status.title, style = MaterialTheme.typography.labelMedium, color = color)
            Text(status.detail, style = MaterialTheme.typography.labelSmall)
            status.confidence?.let { confidence ->
                LinearProgressIndicator(progress = { confidence.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = color)
            }
        }
    }
}
