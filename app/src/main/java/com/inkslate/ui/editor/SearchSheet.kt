package com.inkslate.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inkslate.pdf.SearchHit

/**
 * Find text anywhere in the document.
 *
 * Results stream in as pages are read rather than appearing all at once: on a long textbook the
 * first useful hit arrives seconds before the last page is scanned, and making someone wait for
 * completion would make a working feature feel broken.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    hits: List<SearchHit>,
    searching: Boolean,
    progressPage: Int,
    pageCount: Int,
    onGoToHit: (SearchHit) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = remember { FocusRequester() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding()) {

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Find in document") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.weight(1f).focusRequester(focus)
                )
                TextButton(enabled = query.isNotBlank() && !searching, onClick = onSearch) {
                    Text("Find")
                }
            }

            if (searching) {
                LinearProgressIndicator(
                    progress = {
                        if (pageCount > 0) (progressPage + 1f) / pageCount else 0f
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
                Text(
                    "Searching page ${progressPage + 1} of $pageCount, ${hits.size} found",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            } else if (hits.isNotEmpty()) {
                Text(
                    "${hits.size} result" + (if (hits.size == 1) "" else "s"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            when {
                hits.isEmpty() && !searching && query.isNotBlank() -> Box(
                    Modifier.fillMaxWidth().height(160.dp), Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No matches", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Scanned PDFs have no text layer to search.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                hits.isEmpty() && !searching -> Box(
                    Modifier.fillMaxWidth().height(160.dp), Alignment.Center
                ) {
                    Text(
                        "Type a word or phrase",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(hits) { _, hit ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onGoToHit(hit) }
                                .padding(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Text(
                                "Page ${hit.page + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                hit.snippet,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}
