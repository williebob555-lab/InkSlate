package com.inkslate.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One category selection, one scrollable body. Shared by desktop and Android InkSheets. */
@Composable
fun SettingsWorkspace(
    categories: List<String>, selected: String, onSelected: (String) -> Unit,
    modifier: Modifier = Modifier, content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(200.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    categories.forEach { category ->
                        TextButton(onClick = { onSelected(category) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(settingsCategoryIcon(category), null,
                                tint = if (category == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp))
                            Text(category, color = if (category == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                style = if (category == selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                key(selected) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { content() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                val navigation = rememberLazyListState()
                LaunchedEffect(selected) { navigation.scrollToItem(categories.indexOf(selected).coerceAtLeast(0)) }
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), state = navigation,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories) { category ->
                        FilterChip(selected = category == selected, onClick = { onSelected(category) }, label = { Text(category) },
                            leadingIcon = { Icon(settingsCategoryIcon(category), null) })
                    }
                }
                HorizontalDivider()
                key(selected) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { content() }
                }
            }
        }
    }
}

private fun settingsCategoryIcon(category: String): ImageVector = when (category) {
    "Music & library" -> Icons.Default.MusicNote
    "Appearance" -> Icons.Default.Palette
    "Saving" -> Icons.Default.Save
    "Reading & annotation" -> Icons.Default.MenuBook
    "Devices" -> Icons.Default.Devices
    "Storage & files" -> Icons.Default.Folder
    else -> Icons.Default.SystemUpdate
}
