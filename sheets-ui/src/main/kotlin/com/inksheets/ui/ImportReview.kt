package com.inksheets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inksheets.core.Library

/** One thing being imported: a file, or one MobileSheets song. [alone] is its title on its own. */
internal data class ReviewItem(val key: String, val label: String, val alone: String)

/**
 * What will become one song. [into] adds it to a song already in the library; [skip] leaves it
 * out; [apart] keeps a new song apart from any other of its name (for groups the person split).
 */
internal data class ReviewGroup(
    val title: String,
    val items: List<ReviewItem>,
    val into: String? = null,
    val skip: Boolean = false,
    val apart: Boolean = false
)

/**
 * The songs an import is about to make, for a look and any correction: put songs together as
 * one song's parts, take one apart, add one to a song already here, rename, or leave it out -
 * and all at once for a big download.
 */
@Composable
internal fun ImportReview(state: SheetsState, groups: SnapshotStateList<ReviewGroup>, automatic: List<ReviewGroup>) {
    val picked = remember { mutableStateListOf<Int>() }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf<Int?>(null) }
    val existing = remember(state.version) { state.library?.songs.orEmpty() }

    fun separate(g: ReviewGroup) = g.items.map { ReviewGroup(it.alone, listOf(it), apart = true) }

    Column {
        // All at once.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = {
                val all = groups.filter { !it.skip }
                if (all.isNotEmpty()) {
                    val one = ReviewGroup(all.first().title, all.flatMap { it.items }, into = all.firstNotNullOfOrNull { it.into })
                    groups.clear(); groups += one
                }
                picked.clear()
            }, label = { Text("Merge all into one song") })
            AssistChip(onClick = {
                val all = groups.flatMap { g -> if (g.skip) listOf(g) else separate(g) }
                groups.clear(); groups += all; picked.clear()
            }, label = { Text("Separate all") })
            AssistChip(onClick = { groups.clear(); groups += automatic; picked.clear() }, label = { Text("Automatic") })
        }
        if (picked.size >= 2) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("${picked.size} picked", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = {
                    val chosen = picked.sorted().map { groups[it] }
                    val merged = ReviewGroup(chosen.first().title, chosen.flatMap { it.items }, into = chosen.firstNotNullOfOrNull { it.into })
                    val keep = groups.filterIndexed { i, _ -> i !in picked }
                    groups.clear(); groups += listOf(merged) + keep
                    picked.clear()
                }) { Text("Make them one song") }
            }
        } else {
            Text(
                "Tick songs to put them together as one song's parts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
            itemsIndexed(groups, key = { i, g -> g.items.first().key + "#" + i }) { i, g ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
                    Checkbox(checked = i in picked, onCheckedChange = { on -> if (on) picked += i else picked -= i })
                    Column(Modifier.weight(1f).padding(top = 4.dp)) {
                        val target = g.into?.let { id -> existing.firstOrNull { it.id == id } }
                        Text(
                            (if (g.skip) "Left out: " else "") + g.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (g.skip) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clickable { renaming = i }
                        )
                        if (target != null) {
                            Text("Adds to “${target.title}”, already in your library", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        g.items.forEach { item ->
                            Text(item.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (g.items.size > 1) TextButton(onClick = {
                                val parts = separate(g)
                                groups.removeAt(i); groups.addAll(i, parts); picked.clear()
                            }) { Text("Separate") }
                            TextButton(onClick = { adding = i }) { Text(if (target == null) "Add to a song..." else "Other song...") }
                            if (target != null) TextButton(onClick = { groups[i] = g.copy(into = null) }) { Text("New song") }
                            TextButton(onClick = { groups[i] = g.copy(skip = !g.skip) }) { Text(if (g.skip) "Include" else "Leave out") }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    renaming?.let { i ->
        AskName("Song title", groups[i].title, "Rename", onDone = { groups[i] = groups[i].copy(title = it); renaming = null }, onDismiss = { renaming = null })
    }
    adding?.let { i ->
        PickSongDialog(
            state, title = "Add as parts of which song?", exclude = null, near = groups[i].title,
            onChosen = { s -> groups[i] = groups[i].copy(into = s.id); adding = null },
            onDismiss = { adding = null }
        )
    }
}

/** The song already in the library an imported title belongs to, if any. */
internal fun existingFor(state: SheetsState, title: String): String? =
    state.library?.songs?.firstOrNull { !it.apart && Library.matchKey(it.title) == Library.matchKey(title) }?.id
