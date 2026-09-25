package com.inksheets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inksheets.core.Library
import com.inksheets.core.Song

/** The colours a song or setlist can be given: enough to tell groups apart, all readable. */
internal val MARK_COLOURS = listOf(
    0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF00897B,
    0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFFD81B60, 0xFF6D4C41
).map { it.toInt() }

/** Pick a colour, or none for the theme's own. */
@Composable
internal fun ColourDialog(title: String, current: Int?, onChosen: (Int?) -> Unit, onDismiss: () -> Unit) {
    SheetDialog(title = title, onDismiss = onDismiss) {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(40.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onChosen(null) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Block, "No colour", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            for (c in MARK_COLOURS) {
                Box(
                    Modifier.size(40.dp).background(Color(c), CircleShape)
                        .then(if (c == current) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clickable { onChosen(c) }
                )
            }
        }
    }
}

/** Choose one song - to merge into, or to move a part to. */
@Composable
internal fun PickSongDialog(
    state: SheetsState,
    title: String,
    exclude: String?,
    /** Shown first: songs this one is likely to be, by title. */
    near: String? = null,
    onChosen: (Song) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val all = remember(state.version) { state.library?.songs.orEmpty().filter { it.id != exclude } }
    val key = near?.let { com.inksheets.core.ImportPlan.withoutTrailingInstrument(Library.matchKey(it)) }
    val shown = all.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
        .sortedByDescending { s -> key != null && Library.matchKey(s.title).let { it.startsWith(key) || key.startsWith(it) } }
    SheetDialog(title = title, onDismiss = onDismiss, wide = true) {
        Column {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Find a song") }, modifier = Modifier.fillMaxWidth()
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(shown, key = { it.id }) { s ->
                    Column(Modifier.fillMaxWidth().clickable { onChosen(s) }.padding(vertical = 10.dp, horizontal = 4.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            s.parts.joinToString(", ") { p -> p.instrument?.let { com.inksheets.core.Instruments.byId[it]?.name } ?: "part" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** A thin bar of a song's or setlist's colour, down the start of its row. */
@Composable
internal fun ColourBar(color: Int?) {
    Box(Modifier.padding(end = 10.dp).size(width = 5.dp, height = 36.dp).background(color?.let { Color(it) } ?: Color.Transparent, CircleShape))
}

/** Ask for a name - the new song a part becomes. */
@Composable
internal fun AskName(title: String, initial: String, confirm: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    SheetDialog(title = title, onDismiss = onDismiss, buttons = {
        TextButton(onClick = onDismiss) { Text("Cancel") }
        TextButton(enabled = name.isNotBlank(), onClick = { onDone(name.trim()) }) { Text(confirm) }
    }) {
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}
