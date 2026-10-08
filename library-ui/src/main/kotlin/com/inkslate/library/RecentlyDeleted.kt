package com.inkslate.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkslate.core.DocumentTrash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings' list of what was deleted from Home, to bring back - the music library's Trash, for
 * documents and folders. Kept [DocumentTrash.KEEP_DAYS] days, on every device the folder syncs to.
 */
@Composable
fun RecentlyDeletedSection(backend: LibraryBackend) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<Pair<File, DocumentTrash.Entry>>>(emptyList()) }
    var open by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { entries = withContext(Dispatchers.IO) { backend.trashed() } }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(
            "Recently deleted",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Documents and folders deleted from Home stay here for ${DocumentTrash.KEEP_DAYS} days, on every device, " +
                "and can be put back where they were.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
        )
        if (entries.isEmpty()) {
            Text("Nothing deleted lately.", style = MaterialTheme.typography.bodyMedium)
        } else {
            // Folded to one line until opened: a long list is not worth the room.
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text((if (open) "▾ " else "▸ ") + "Deleted (${entries.size})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (open) entries.forEach { (root, e) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (e.isFolder) Icons.Default.Folder else Icons.Default.Description, null, Modifier.size(18.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "From " + (File(e.from).parent?.let { "${root.name} / " + it.replace(File.separator, " / ") } ?: root.name) +
                                "  ·  " + relativeTime(e.removedAt) + "  ·  " + daysLeft(e.removedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = {
                        scope.launch {
                            val back = withContext(Dispatchers.IO) { backend.restore(root, e) }
                            message = back.fold({ "Put back: ${it.name}" }, { it.message ?: "Could not put it back" })
                            reload++
                        }
                    }) { Text("Restore") }
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { backend.deleteForever(root, e) }
                            reload++
                        }
                    }) { Text("Delete now", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp)) }
    }
}

private fun daysLeft(removedAt: Long): String {
    val left = DocumentTrash.KEEP_DAYS - ((System.currentTimeMillis() - removedAt) / (24L * 60 * 60 * 1000)).toInt()
    return if (left <= 1) "goes tomorrow" else "$left days left"
}
