package com.inksheets.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inksheets.core.Instruments
import com.inksheets.core.PartChoice
import com.inksheets.core.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * InkSheets' home: the library for the instrument being played, and the setlists.
 *
 * Shown in the Home tab of the same workspace InkSlate uses, so a song opens as a tab beside the
 * others with the full editor - pens, annotations, live sync - behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetsHome(state: SheetsState, onOpenSettings: () -> Unit) = Box(Modifier.fillMaxSize()) {
    var tab by state::homeTab
    var showMetronome by remember { mutableStateOf(false) }
    var showTuner by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var chooseFolder by remember { mutableStateOf(false) }
    var openShared by remember { mutableStateOf(false) }
    var backupToImport by remember { mutableStateOf<java.io.File?>(null) }
    var backupsLookedAt by remember { mutableStateOf(0) }
    // A MobileSheets backup put in the music folder is noticed and offered, once.
    val waitingBackup = remember(state.root, state.version, backupsLookedAt) {
        state.root?.listFiles { f -> f.isFile && f.extension.equals("msb", ignoreCase = true) }
            ?.firstOrNull { state.platform.pref(backupDoneKey(it)) == null }
    }

    // Scans nobody has read yet are read in the background, a page at a time.
    var reading by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(state.library) {
        if (state.library == null) return@LaunchedEffect
        // Files moved around the music folder are found again before anything else.
        withContext(Dispatchers.IO) { runCatching { state.relinkMoved() } }
        if (!state.platform.canRecognise) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                state.readUnknownParts { done, of -> reading = if (done < of) done to of else null }
            }
        }
        reading = null
    }

    // Edits from other devices arrive through the synced folder; look for them now and then.
    LaunchedEffect(state.library) {
        while (true) {
            withContext(Dispatchers.IO) { runCatching { state.refresh() } }
            delay(3_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("InkSheets") },
            actions = {
                InstrumentChooser(state)
                IconButton(onClick = { showMetronome = true }) { Icon(Icons.Default.Timer, "Metronome") }
                IconButton(onClick = { showTuner = true }) { Icon(Icons.Default.GraphicEq, "Tuner") }
                if (state.library != null) {
                    IconButton(onClick = { showImport = true }) { Icon(Icons.Default.LibraryAdd, "Add music") }
                }
                var more by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(
                            text = { Text("Change library...") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                            onClick = { more = false; chooseFolder = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Play together (lead or follow)...") },
                            leadingIcon = { Icon(Icons.Default.Devices, null) },
                            onClick = { more = false; state.companionOpen = true }
                        )
                        if (state.library != null) {
                            DropdownMenuItem(
                                text = { Text("Open a shared setlist...") },
                                leadingIcon = { Icon(Icons.Default.LibraryAdd, null) },
                                onClick = { more = false; openShared = true }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = { more = false; onOpenSettings() }
                        )
                        // The window covers the whole screen, title bar and all, so it closes from here.
                        if (state.platform.canQuit) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Quit InkSheets") },
                                leadingIcon = { Icon(Icons.Default.PowerSettingsNew, null) },
                                onClick = { more = false; state.platform.quit() }
                            )
                        }
                    }
                }
            }
        )

        FollowBanner(state)
        if (state.library == null) {
            Welcome(onChoose = { chooseFolder = true })
        } else {
            reading?.let { (done, of) ->
                Text(
                    "Reading the instrument off scanned parts: ${done + 1} of $of",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            waitingBackup?.let { msb ->
                androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Found a MobileSheets backup: ${msb.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            state.platform.setPref(backupDoneKey(msb), "skipped")
                            backupsLookedAt++
                        }) { Text("Not now") }
                        Button(onClick = { backupToImport = msb }) { Text("Import it") }
                    }
                }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Songs") })
                Tab(selected = tab == 1, onClick = {
                    // Also the way back to the top of the setlists, from inside a folder or a setlist.
                    tab = 1; state.setlistFolder = null; state.setlistShown = null
                }, text = { Text("Setlists") })
            }
            when (tab) {
                0 -> SongsPane(state)
                else -> SetlistsPane(state)
            }
        }
    }

    if (showMetronome) MetronomeDialog(state, onClose = { showMetronome = false })
    if (showTuner || state.tunerOpen) TunerDialog(state, onClose = { showTuner = false; state.tunerOpen = false })
    if (showImport) AddMusicDialog(state, onClose = { showImport = false })
    backupToImport?.let { msb -> MobileSheetsDialog(state, onClose = { backupToImport = null; backupsLookedAt++ }, backup = msb) }
    if (openShared) OpenSharedDialog(state, onClose = { openShared = false })
    if (state.companionOpen) CompanionDialog(state, onClose = { state.companionOpen = false })
    if (chooseFolder) {
        FolderPickerDialog(
            title = "Choose your library folder",
            start = state.root ?: state.platform.startFolder,
            onChosen = { state.open(it); chooseFolder = false },
            onDismiss = { chooseFolder = false }
        )
    }
}

@Composable
private fun Welcome(onChoose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Your music library", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(12.dp))
        Text(
            "Choose the folder your sheet music is in - ideally one Syncthing shares between " +
                "your devices. The library, setlists and annotations live in that folder, so " +
                "every device that has it sees the same music.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(20.dp))
        Button(onClick = onChoose) { Text("Choose folder") }
    }
}

/** The instrument being played: only its parts are shown, and its part opens first. */
@Composable
private fun InstrumentChooser(state: SheetsState) {
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    if (editing) ProfilesDialog(state, onClose = { editing = false })
    Box {
        TextButton(onClick = { open = true }) {
            Text(state.profile?.name ?: "All instruments")
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All instruments") }, onClick = { state.chooseProfile(null); open = false })
            state.profiles.forEach { p ->
                DropdownMenuItem(text = { Text(p.name) }, onClick = { state.chooseProfile(p.id); open = false })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Edit instruments...") }, onClick = { open = false; editing = true })
        }
    }
}

@Composable
private fun SongsPane(state: SheetsState) {
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Song?>(null) }
    var addingToSetlist by remember { mutableStateOf<Song?>(null) }
    var recordingsFor by remember { mutableStateOf<Song?>(null) }
    var sortName by rememberSaveable { mutableStateOf(SongSort.AZ.name) }
    val sort = SongSort.valueOf(sortName)
    var withRecording by rememberSaveable { mutableStateOf(false) }
    var notInSet by rememberSaveable { mutableStateOf(false) }

    val version = state.version
    val songs = remember(version, state.profileId, query, sort, withRecording, notInSet) {
        val lib = state.library
        val all = lib?.songs.orEmpty()
        val inSets = if (notInSet) lib?.setlists.orEmpty().flatMap { l -> l.entries.map { it.songId } }.toSet() else emptySet()
        val q = query.trim().lowercase()
        val matching = all.filter { s ->
            (q.isEmpty() || (listOf(s.title) + s.composers + s.arrangers + s.artists + s.genres + s.tags)
                .any { it.lowercase().contains(q) }) &&
                (!withRecording || s.audio.isNotEmpty()) &&
                (!notInSet || s.id !in inSets)
        }
        val listed = PartChoice.songsFor(matching, state.profile)
        when (sort) {
            SongSort.AZ -> listed
            SongSort.OPENED -> listed.sortedByDescending { it.first.opened }
            SongSort.ADDED -> listed.sortedByDescending { it.first.created }
            SongSort.COMPOSER -> listed.sortedWith(compareBy({ it.first.composers.firstOrNull()?.lowercase() ?: "\uFFFF" }, { com.inksheets.core.Library.sortKey(it.first.title) }))
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Title, composer, tag...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        // How the list is ordered, then what it is narrowed to.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SongSort.entries.forEach { o ->
                androidx.compose.material3.FilterChip(selected = sort == o, onClick = { sortName = o.name }, label = { Text(o.label) })
            }
            androidx.compose.material3.VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
            androidx.compose.material3.FilterChip(selected = withRecording, onClick = { withRecording = !withRecording }, label = { Text("Has a recording") })
            androidx.compose.material3.FilterChip(selected = notInSet, onClick = { notInSet = !notInSet }, label = { Text("In no setlist") })
        }
        if (songs.isEmpty()) {
            Text(
                if (state.library?.songs.isNullOrEmpty()) "No music yet. Add some with the button at the top."
                else "Nothing matches.",
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // In groups, not one long stack: under letters, how long ago, or who wrote it.
        val groups = remember(songs, sort) {
            songs.groupBy { (song, _) ->
                when (sort) {
                    SongSort.AZ -> letterOf(song.title)
                    SongSort.OPENED -> howLongAgo(song.opened, never = "Never opened")
                    SongSort.ADDED -> howLongAgo(song.created, never = "Earlier")
                    SongSort.COMPOSER -> song.composers.firstOrNull() ?: "No composer"
                }
            }.toList()
        }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        // Where each group's heading sits in the list, for the letter strip to jump to.
        val headingAt = remember(groups) {
            var at = 0
            groups.associate { (name, members) -> name to at.also { at += 1 + members.size } }
        }
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxSize(), state = listState) {
                groups.forEach { (name, members) ->
                    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                    stickyHeader(key = "group-$name") {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                name + "  \u00B7  " + members.size,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                    }
                    items(members, key = { it.first.id }) { (song, fit) ->
                        SongRow(
                            song = song,
                            unsure = fit == PartChoice.Fit.UNKNOWN,
                            onOpen = { state.stopPlaying(); openSong(state, song) },
                            onEdit = { editing = song },
                            onAddToSetlist = { addingToSetlist = song },
                            onRecordings = { recordingsFor = song },
                            onDelete = { state.change { deleteSong(song.id) } }
                        )
                        HorizontalDivider()
                    }
                }
            }
            // The letters down the side: tap one to go straight there.
            if (sort == SongSort.AZ && groups.size > 1) {
                Column(
                    Modifier.fillMaxHeight().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    groups.forEach { (name, _) ->
                        Text(
                            name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { scope.launch { listState.scrollToItem(headingAt[name] ?: 0) } }
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }

    editing?.let { song -> SongEditorDialog(state, song, onClose = { editing = null }) }
    recordingsFor?.let { song -> AudioDialog(state, song, onClose = { recordingsFor = null }) }
    addingToSetlist?.let { song ->
        SetlistChooserDialog(
            state,
            onChosen = { setlist -> state.change { addToSetlist(setlist.id, song.id) }; addingToSetlist = null },
            onDismiss = { addingToSetlist = null }
        )
    }
}

/** Open the part of [song] for the instrument being played. */
internal fun openSong(state: SheetsState, song: Song) {
    val part = PartChoice.partFor(song, state.profile) ?: return
    state.current = song
    state.noteOpened(song)
    val file = state.partFile(song, part) ?: return
    // A part partway into a band pack opens at its own first page.
    part.firstPage?.let { com.inkslate.core.Perform.requestPage(file.absolutePath, it - 1) }
    state.platform.openPart(song, part, file)
    // Following tablets change song with this one; the page follows as it is turned.
    state.companion.pageTurned((part.firstPage ?: 1) - 1)
}

@Composable
internal fun SongRow(
    song: Song,
    unsure: Boolean,
    onOpen: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onAddToSetlist: (() -> Unit)? = null,
    onRecordings: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(
                song.composers.joinToString(", ").takeIf { it.isNotEmpty() },
                song.key?.let { "in $it" },
                song.tempo?.let { "♩=$it" }
            ).joinToString("  ·  ")
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val instruments = song.instruments.mapNotNull { Instruments.byId[it]?.name }.sorted()
            if (instruments.isNotEmpty() || unsure) {
                Text(
                    (instruments + if (unsure) listOf("parts not yet named") else emptyList()).joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unsure) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke()
        if (onEdit != null || onAddToSetlist != null || onDelete != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Song options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    onEdit?.let { DropdownMenuItem(text = { Text("Details and parts") }, onClick = { menu = false; it() }) }
                    onAddToSetlist?.let { DropdownMenuItem(text = { Text("Add to setlist...") }, onClick = { menu = false; it() }) }
                    onRecordings?.let {
                        DropdownMenuItem(
                            text = { Text(if (song.audio.isEmpty()) "Pair a recording..." else "Recordings (${song.audio.size})") },
                            onClick = { menu = false; it() }
                        )
                    }
                    onDelete?.let {
                        var confirm by remember { mutableStateOf(false) }
                        DropdownMenuItem(
                            text = { Text(if (confirm) "Tap again to remove" else "Remove from library", fontWeight = if (confirm) FontWeight.Bold else null) },
                            onClick = { if (confirm) { menu = false; it() } else confirm = true }
                        )
                    }
                }
            }
        }
    }
}

/** A dialog's frame: a title, the body, and buttons along the bottom. */
@Composable
internal fun SheetDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit = { TextButton(onClick = onDismiss) { Text("Close") } },
    wide: Boolean = false,
    content: @Composable () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.width(if (wide) 640.dp else 440.dp).padding(8.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(12.dp))
                Box(Modifier.weight(1f, fill = false)) { content() }
                Spacer(Modifier.size(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { buttons() }
            }
        }
    }
}

@Composable
internal fun AddButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Default.Add, null)
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}

/** The letter a title files under, as a printed index has it: "The Liberty Bell" under L, numbers under #. */
internal fun letterOf(title: String): String {
    val c = com.inksheets.core.Library.sortKey(title).firstOrNull { it.isLetterOrDigit() } ?: return "#"
    return if (c.isLetter()) c.uppercaseChar().toString() else "#"
}

/** Which "how long ago" group a moment falls in; [never] for none. */
internal fun howLongAgo(at: Long, never: String): String {
    if (at <= 0L) return never
    val days = (System.currentTimeMillis() - at) / 86_400_000L
    return when {
        days < 1 -> "Today"
        days < 7 -> "This week"
        days < 31 -> "This month"
        days < 365 -> "This year"
        else -> "Longer ago"
    }
}

/** How the song list is ordered. */
internal enum class SongSort(val label: String) {
    AZ("A to Z"), OPENED("Recently opened"), ADDED("Recently added"), COMPOSER("Composer")
}
