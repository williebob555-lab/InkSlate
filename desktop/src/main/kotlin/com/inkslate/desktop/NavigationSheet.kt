package com.inkslate.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkslate.core.InkDocument

/**
 * Document navigation: contents, bookmarks, and a direct page jump.
 *
 * A thousand-page textbook is unusable by scrolling alone, so all three live one click from the
 * page indicator rather than behind a menu - the same arrangement as the tablet, because the page
 * indicator is already where you look when you want to know where you are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationSheet(
    pageCount: Int,
    currentPage: Int,
    outline: List<OutlineEntry>?,
    bookmarks: List<InkDocument.Bookmark>,
    onGoToPage: (Int) -> Unit,
    onRemoveBookmark: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableStateOf(if (outline.isNullOrEmpty()) 1 else 0) }
    var jumpText by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {

            // ---- jump to page, always visible ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = jumpText,
                    onValueChange = { v -> jumpText = v.filter { it.isDigit() }.take(6) },
                    label = { Text("Go to page") },
                    singleLine = true,
                    supportingText = { Text("1 to $pageCount") },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = jumpText.toIntOrNull()?.let { it in 1..pageCount } == true,
                    onClick = { jumpText.toIntOrNull()?.let { onGoToPage(it - 1) } }
                ) { Text("Go") }
            }

            HorizontalDivider()

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Contents") })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("Bookmarks (${bookmarks.size})") }
                )
            }

            // Null means "still reading the outline", empty means "this PDF has none". Two plain
            // values rather than a smart cast, which does not hold across independent branches.
            val loadingOutline = outline == null
            val entries = outline.orEmpty()
            when {
                tab == 0 && loadingOutline -> Centered { CircularProgressIndicator() }

                tab == 0 && entries.isEmpty() -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.MenuBook, null,
                            Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "This PDF has no table of contents",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            "Bookmark pages yourself instead.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                tab == 0 -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(entries) { entry ->
                        OutlineRow(entry, currentPage) {
                            if (entry.isResolvable) onGoToPage(entry.pageIndex)
                        }
                    }
                }

                bookmarks.isEmpty() -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.BookmarkBorder, null,
                            Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "No bookmarks yet",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            "Bookmarks are stored in the file, so they follow it to your other " +
                                "devices.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }

                else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(bookmarks) { bm ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onGoToPage(bm.page) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Bookmark, null,
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(
                                    bm.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "Page ${bm.page + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onRemoveBookmark(bm.page) }) {
                                Icon(Icons.Default.Close, "Remove bookmark", Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlineRow(entry: OutlineEntry, currentPage: Int, onClick: () -> Unit) {
    val onThisPage = entry.pageIndex == currentPage
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (onThisPage) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .clickable(enabled = entry.isResolvable, onClick = onClick)
            .padding(start = (16 + entry.depth * 14).dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            entry.title,
            style = if (entry.depth == 0) MaterialTheme.typography.bodyMedium
            else MaterialTheme.typography.bodySmall,
            color = when {
                !entry.isResolvable -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                onThisPage -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (entry.isResolvable) {
            Text(
                "${entry.pageIndex + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(220.dp), Alignment.Center) { content() }
}

/** Prompt for a bookmark label, pre-filled with the page number. */
@Composable
fun BookmarkDialog(page: Int, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var label by remember { mutableStateOf("Page ${page + 1}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmark page ${page + 1}") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = label.isNotBlank(), onClick = { onConfirm(label.trim()) }) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
