package com.inksheets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inksheets.core.Song
import com.inksheets.core.Instruments

/** Home keeps its navigation steady; the existing list panes still own all list operations. */
@Composable
internal fun HomeNavigation(
    state: SheetsState,
    hasBookmarks: Boolean,
    onOpenSettings: () -> Unit,
    onOpenDeleted: () -> Unit
) {
    val library = state.library ?: return
    val version = state.version
    val lists = remember(version) { library.setlistsIn(null) }
    val folders = remember(version) { library.foldersIn(null) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(220.dp).fillMaxHeight()) {
        Column(Modifier.padding(8.dp)) {
            HomeNavRow("All music", Icons.Default.LibraryMusic, state.homeTab == 0) { state.homeTab = 0 }
            HomeNavRow("Setlists", Icons.AutoMirrored.Filled.QueueMusic, state.homeTab == 1 && state.setlistShown == null && state.setlistFolder == null) {
                state.homeTab = 1; state.setlistShown = null; state.setlistFolder = null
            }
            if (hasBookmarks) HomeNavRow("Bookmarks", Icons.Default.BookmarkBorder, state.homeTab == 2) { state.homeTab = 2 }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text("Your setlists", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                lists.forEach { set ->
                    val action = { state.homeTab = 1; state.setlistFolder = null; state.setlistShown = set.id }
                    HomeNavRow(set.name, Icons.AutoMirrored.Filled.QueueMusic, state.homeTab == 1 && state.setlistShown == set.id,
                        detail = "${set.entries.size} songs", colour = set.color,
                        // The pane also names this set. Distinct accessible quick-link names make
                        // keyboard and screen-reader users know which copy they are invoking.
                        accessibleName = "Open setlist ${set.name}", onClick = action)
                }
                if (folders.isNotEmpty()) {
                    Text("Folders", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp))
                }
                folders.forEach { folder ->
                    HomeNavRow(folder.name, Icons.Default.Folder,
                        state.homeTab == 1 && state.setlistFolder == folder.id && state.setlistShown == null,
                        colour = folder.color, accessibleName = "Open setlist folder ${folder.name}") {
                        state.homeTab = 1; state.setlistFolder = folder.id; state.setlistShown = null
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            HomeNavRow("Recently deleted", Icons.Default.DeleteOutline, false, onClick = onOpenDeleted)
            HomeNavRow("Library settings", Icons.Default.Settings, false, onClick = onOpenSettings)
        }
    }
}

@Composable
private fun HomeNavRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    detail: String? = null,
    colour: Int? = null,
    accessibleName: String? = null,
    onClick: () -> Unit
) {
    val shape = MaterialTheme.shapes.small
    Row(
        Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .then(if (accessibleName == null) Modifier else Modifier.clearAndSetSemantics {
                contentDescription = accessibleName
                this.onClick { onClick(); true }
            }).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = colour?.let(::Color) ?: if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** The same destinations remain direct taps on a phone; no navigation drawer to open first. */
@Composable
internal fun HomeCompactNavigation(state: SheetsState, hasBookmarks: Boolean) {
    TabRow(selectedTabIndex = state.homeTab) {
        Tab(selected = state.homeTab == 0, onClick = { state.homeTab = 0 }, text = { Text("All music") })
        Tab(selected = state.homeTab == 1, onClick = {
            state.homeTab = 1; state.setlistFolder = null; state.setlistShown = null
        }, text = { Text("Setlists") })
        if (hasBookmarks) Tab(selected = state.homeTab == 2, onClick = { state.homeTab = 2 }, text = { Text("Bookmarks") })
    }
    if (state.homeTab == 0) {
        val library = state.library ?: return
        val version = state.version
        val lists = remember(version) { library.setlistsIn(null) }
        val folders = remember(version) { library.foldersIn(null) }
        if (lists.isNotEmpty() || folders.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            lists.forEach { set ->
                TextButton(onClick = { state.homeTab = 1; state.setlistFolder = null; state.setlistShown = set.id }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = set.color?.let(::Color) ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(set.name, maxLines = 1)
                }
            }
            folders.forEach { folder ->
                TextButton(onClick = { state.homeTab = 1; state.setlistFolder = folder.id; state.setlistShown = null }) {
                    Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(folder.name, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun HomeContinue(state: SheetsState) {
    val version = state.version
    val song = state.current?.let { current -> state.library?.song(current.id) }
        ?: remember(version) { state.library?.songs.orEmpty().filter { it.opened > 0 && it.parts.isNotEmpty() }.maxByOrNull { it.opened } }
        ?: return
    val part = state.partFor(song) ?: return
    val samePart = state.fileOf(part.file)?.absolutePath == state.currentPath
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().clickable { resumeHomeSong(state, song) }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Continue reading", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(Instruments.partName(part) + if (samePart && state.pageShown.second > 0) " · page ${state.pageShown.first + 1} of ${state.pageShown.second}" else "",
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { resumeHomeSong(state, song) }) { Text("Resume"); Spacer(Modifier.width(5.dp)); Icon(Icons.Default.ArrowForward, null) }
        }
    }
}

/** Resume the selected instrument without resetting a currently open part's page range. */
internal fun resumeHomeSong(state: SheetsState, song: Song) {
    val part = state.partFor(song) ?: return
    val file = state.partFile(song, part) ?: return
    state.stopPlaying()
    if (file.absolutePath == state.currentPath && state.pageShown.second > 0) {
        com.inkslate.core.Perform.requestPage(file.absolutePath, state.pageShown.first)
    }
    state.current = song
    state.noteOpened(song)
    state.platform.openPart(song, part, file)
}

@Composable
internal fun HomeDevicesButton(state: SheetsState) {
    var open by remember { mutableStateOf(false) }
    val active = state.companion.leading || state.companion.following != null || state.remote.connected
    androidx.compose.foundation.layout.Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Default.Devices, null, tint = if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(5.dp))
            Text("Devices")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.companion.status?.let { status ->
                DropdownMenuItem(text = { Text(status) }, leadingIcon = { Icon(Icons.Default.Check, null) }, onClick = { open = false; state.companionOpen = true })
                HorizontalDivider()
            }
            DropdownMenuItem(text = { Text("Play together (lead or follow)...") }, leadingIcon = { Icon(Icons.Default.Devices, null) }, onClick = { open = false; state.companionOpen = true })
            DropdownMenuItem(text = { Text("Remote...") }, leadingIcon = { Icon(Icons.Default.Gamepad, null) }, onClick = { open = false; state.remote.remoteOpen = true })
        }
    }
}
