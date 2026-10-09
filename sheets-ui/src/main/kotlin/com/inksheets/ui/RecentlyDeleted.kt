package com.inksheets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What was just removed, with an Undo, along the foot of the screen for a few seconds - for
 * every remove and delete: a song, a part, a setlist, a folder, a song taken out of a set.
 */
@Composable
fun BoxScope.UndoBar(state: SheetsState) {
    val offer = state.undoOffer ?: return
    LaunchedEffect(offer) {
        delay(UNDO_MS)
        if (state.undoOffer === offer) state.undoOffer = null
    }
    Row(
        Modifier.align(Alignment.BottomCenter).padding(16.dp).widthIn(max = 560.dp)
            .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(8.dp))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            offer.text, color = MaterialTheme.colorScheme.inverseOnSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false).padding(vertical = 12.dp)
        )
        TextButton(onClick = { state.undoOffer = null; offer.undo() }) {
            Text("Undo", color = MaterialTheme.colorScheme.inversePrimary)
        }
    }
}

private const val UNDO_MS = 8_000L

/**
 * Everything removed in the last 30 days, on any device - songs, parts, old copies of files
 * that something replaced, and setlists - each with Restore. Reached from Home's menu.
 */
@Composable
internal fun RecentlyDeletedDialog(state: SheetsState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var trash by remember { mutableStateOf<List<com.inksheets.core.LibraryTrash.Entry>>(emptyList()) }
    var setlists by remember { mutableStateOf<List<Pair<com.inksheets.core.Setlist, Long>>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(state.version) {
        trash = withContext(Dispatchers.IO) { state.trash()?.entries().orEmpty() }
        setlists = state.library?.deletedSetlists().orEmpty()
    }
    SheetDialog(title = "Recently deleted", onDismiss = onClose) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "Kept ${com.inksheets.core.LibraryTrash.KEEP_DAYS} days, on all your devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ListSearch(trash.size + setlists.size, query, { query = it }, "Find")
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (trash.isEmpty() && setlists.isEmpty()) {
                    Text("Nothing here.", modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val rows = trash.map { e -> Triple(e.title, e.removedAt, { scope.launch { withContext(Dispatchers.IO) { state.restore(e) } }; Unit }) } +
                    setlists.map { (s, at) -> Triple("Setlist: ${s.name}", at, { state.change { restoreSetlist(s.id) }; Unit }) }
                rows.sortedByDescending { it.second }.filter { matches(query, it.first) }.forEach { (title, at, restore) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(howLongAgo(at, "Just now"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = restore) { Text("Restore") }
                    }
                }
            }
        }
    }
}
