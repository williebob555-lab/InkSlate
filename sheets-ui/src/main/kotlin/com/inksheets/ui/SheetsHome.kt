package com.inksheets.ui

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Minimize
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    var showDeleted by remember { mutableStateOf(false) }
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
        // The folder is the library: what is in it now, before anything else.
        withContext(Dispatchers.IO) { state.scanFolder() }
        withContext(Dispatchers.IO) {
            runCatching {
                state.readUnknownParts { done, of -> reading = if (done < of) done to of else null }
            }
        }
        reading = null
    }

    // Edits from other devices arrive through the synced folder; look for them now and then.
    // Other devices' changes and the folder itself are watched by the state, all the time -
    // see SheetsState.startWatching - not only while Home is on screen.

    // Under the remote (a screen of its own over this one): out of reach - not read out, not focused.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()
        .then(if (state.remote.remoteOpen) Modifier.underCover() else Modifier)) {
    // On a phone the buttons need the whole bar; the name goes.
    val narrow = maxWidth < 520.dp
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { if (!narrow) Text("InkSheets", maxLines = 1) },
            actions = {
                InstrumentChooser(state)
                // Playback mode, from a song left behind: paused or played on from here.
                if (Recording.session) {
                    IconButton(
                        onClick = { Recording.playPause(state) },
                        modifier = if (Recording.playing) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, androidx.compose.foundation.shape.CircleShape) else Modifier
                    ) {
                        if (Recording.playing) Icon(Icons.Default.Pause, "Pause the recording")
                        else Icon(Icons.Default.PlayArrow, "Play the recording")
                    }
                }
                // Each opens its window and closes it again; lit while it is open.
                val tunerShown = showTuner || state.tunerOpen
                IconButton(
                    onClick = { showMetronome = !showMetronome },
                    modifier = if (showMetronome) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, androidx.compose.foundation.shape.CircleShape) else Modifier
                ) { Icon(Icons.Default.Timer, "Metronome") }
                IconButton(
                    onClick = { if (tunerShown) { showTuner = false; state.tunerOpen = false } else showTuner = true },
                    modifier = if (tunerShown) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, androidx.compose.foundation.shape.CircleShape) else Modifier
                ) { Icon(Icons.Default.GraphicEq, "Tuner") }
                // Named, not an icon among icons: the first thing a new library needs.
                if (state.library != null) {
                    if (narrow) IconButton(onClick = { showImport = true }) { Icon(Icons.Default.LibraryAdd, "Add music") }
                    else TextButton(onClick = { showImport = true }) {
                        Icon(Icons.Default.LibraryAdd, null)
                        Spacer(Modifier.size(6.dp))
                        Text("Add music")
                    }
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
                        DropdownMenuItem(
                            text = { Text("Remote...") },
                            leadingIcon = { Icon(Icons.Default.Gamepad, null) },
                            onClick = { more = false; state.remote.remoteOpen = true }
                        )
                        if (state.library != null) {
                            DropdownMenuItem(
                                text = { Text("Open a shared setlist...") },
                                leadingIcon = { Icon(Icons.Default.LibraryAdd, null) },
                                onClick = { more = false; openShared = true }
                            )
                        }
                        if (state.library != null) {
                            DropdownMenuItem(
                                text = { Text("Recently deleted...") },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = { more = false; showDeleted = true }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = { more = false; onOpenSettings() }
                        )
                        // The window covers the whole screen, title bar and all, so it closes from here.
                        if (state.platform.canWindow) {
                            HorizontalDivider()
                            val windowed = state.platform.windowed
                            DropdownMenuItem(
                                text = { Text(if (windowed) "Cover the whole screen" else "Show in a window") },
                                leadingIcon = { Icon(if (windowed) Icons.Default.Fullscreen else Icons.Default.FullscreenExit, null) },
                                onClick = { more = false; state.platform.setWindowed(!windowed) }
                            )
                            // Another screen, where there is one: covered, or the window put there.
                            val screens = state.platform.screens()
                            if (screens.size > 1) screens.forEachIndexed { i, name ->
                                DropdownMenuItem(
                                    text = { Text("Move to $name") },
                                    leadingIcon = { Icon(Icons.Default.Monitor, null) },
                                    onClick = { more = false; state.platform.moveToScreen(i) }
                                )
                            }
                        }
                        if (state.platform.canQuit) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Minimise") },
                                leadingIcon = { Icon(Icons.Default.Minimize, null) },
                                onClick = { more = false; state.platform.minimise() }
                            )
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
        if (state.library == null && state.opening) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Opening your library...", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (state.library == null) {
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
            // Bookmarks is a tab only while there are some.
            val marked = remember(state.version) { state.library?.songs.orEmpty().filter { it.bookmarks.isNotEmpty() } }
            if (tab == 2 && marked.isEmpty()) tab = 0
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Songs") })
                Tab(selected = tab == 1, onClick = {
                    // Also the way back to the top of the setlists, from inside a folder or a setlist.
                    tab = 1; state.setlistFolder = null; state.setlistShown = null
                }, text = { Text("Setlists") })
                if (marked.isNotEmpty()) Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Bookmarks") })
            }
            when (tab) {
                0 -> SongsPane(state)
                2 -> BookmarksPane(state, marked)
                else -> SetlistsPane(state)
            }
        }
    }

    }
    if (state.pickingOneOff) OneOffInstrumentDialog(state)
    if (showMetronome) MetronomeDialog(state, onClose = { showMetronome = false })
    if (showTuner || state.tunerOpen) TunerDialog(state, onClose = { showTuner = false; state.tunerOpen = false })
    if (showImport) AddMusicDialog(state, onClose = { showImport = false })
    SaveTabsDialog(state)
    IncomingDialog(state)
    PageViewer(state)
    // A zip shared or dropped from outside: straight to the bulk import's review.
    state.downloadWaiting?.let { zip -> BulkImportDialog(state, onClose = { state.downloadWaiting = null }, start = zip) }
    backupToImport?.let { msb -> MobileSheetsDialog(state, onClose = { backupToImport = null; backupsLookedAt++ }, backup = msb) }
    if (openShared) OpenSharedDialog(state, onClose = { openShared = false })
    if (showDeleted) RecentlyDeletedDialog(state, onClose = { showDeleted = false })
    if (state.homeInFront) UndoBar(state)
    if (state.companionOpen) CompanionDialog(state, onClose = { state.companionOpen = false })
    if (state.homeInFront) state.notesFor?.let { song -> NotesDialog(state, song, onClose = { state.notesFor = null }) }
    if (state.remote.remoteOpen) RemoteScreen(state, onClose = { state.remote.remoteOpen = false })
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
            state.oneOff?.let { _ ->
                DropdownMenuItem(text = { Text(state.profile?.name ?: "") }, leadingIcon = { Icon(Icons.Default.Check, null) }, onClick = { open = false })
            }
            DropdownMenuItem(text = { Text("All instruments") }, onClick = { state.chooseProfile(null); open = false })
            state.profiles.forEach { p ->
                DropdownMenuItem(text = { Text(p.name) }, onClick = { state.chooseProfile(p.id); open = false })
            }
            DropdownMenuItem(text = { Text("Select instrument...") }, onClick = { open = false; state.oneOffReshows = false; state.pickingOneOff = true })
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
    var noInstrument by rememberSaveable { mutableStateOf(false) }
    var noTempo by rememberSaveable { mutableStateOf(false) }
    var missingOnly by rememberSaveable { mutableStateOf(false) }
    var merging by remember { mutableStateOf<Song?>(null) }
    var colouring by remember { mutableStateOf<Song?>(null) }

    val version = state.version
    // What each filter would pick out of the whole library - a filter that would show nothing,
    // or everything, is not offered.
    val onDisk = state.lastScan?.onDisk
    val counts = remember(version, onDisk) {
        val lib = state.library
        val all = lib?.songs.orEmpty()
        val inSets = lib?.setlists.orEmpty().flatMap { l -> l.entries.map { it.songId } }.toSet()
        FilterCounts(
            total = all.size,
            recording = all.count { it.audio.isNotEmpty() },
            notInSet = all.count { it.id !in inSets },
            noInstrument = all.count { s -> s.parts.any { it.instrument == null } },
            noTempo = all.count { it.tempo == null },
            // From what the last folder scan found, not a look at the disk for every part - that
            // was a thousand and more file checks on the screen's thread each time anything changed.
            missing = if (onDisk == null) emptySet() else all.filter { s -> s.parts.any { it.file !in onDisk } }.map { it.id }.toSet()
        )
    }
    // What a search looks through, per song, worked out once per change to the library - not on
    // every key: the title and who wrote it, tags, notes, and its parts' instruments and names.
    val searchText = remember(version) {
        state.library?.songs.orEmpty().associate { s ->
            s.id to fold((listOf(s.title, s.notes.orEmpty()) + s.composers + s.arrangers + s.artists + s.genres + s.tags +
                s.parts.map { p -> com.inksheets.core.Instruments.partName(p) + " " + (p.label ?: "") + " " + p.file.substringAfterLast('/') }).joinToString(" "))
        }
    }
    val songs = remember(version, state.profileId, query, sort, withRecording, notInSet, noInstrument, noTempo, missingOnly) {
        val lib = state.library
        val all = lib?.songs.orEmpty()
        val inSets = if (notInSet) lib?.setlists.orEmpty().flatMap { l -> l.entries.map { it.songId } }.toSet() else emptySet()
        // Every word, in any order, in any of them: "liberty sousa", "trombone 2", "dvorak".
        val words = fold(query).split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val matching = all.filter { s ->
            (words.isEmpty() || searchText[s.id].orEmpty().let { hay -> words.all { it in hay } }) &&
                (!withRecording || s.audio.isNotEmpty()) &&
                (!notInSet || s.id !in inSets) &&
                (!noInstrument || s.parts.any { it.instrument == null }) &&
                (!noTempo || s.tempo == null) &&
                (!missingOnly || s.id in counts.missing)
        }
        // Filling in details is for every song, not only the chosen instrument's.
        val listed = if (noInstrument || noTempo || missingOnly) matching.map { it to PartChoice.Fit.YES }
            else PartChoice.songsFor(matching, state.profile)
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
            placeholder = { Text("Title, composer, instrument...") },
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
            fun useful(n: Int, on: Boolean) = on || (n in 1 until counts.total)
            if (useful(counts.recording, withRecording)) {
                androidx.compose.material3.FilterChip(selected = withRecording, onClick = { withRecording = !withRecording }, label = { Text("Has a recording") })
            }
            if (useful(counts.notInSet, notInSet)) {
                androidx.compose.material3.FilterChip(selected = notInSet, onClick = { notInSet = !notInSet }, label = { Text("In no setlist") })
            }
            // Details worth filling in, offered only while some are missing.
            // Only while some - not all - are missing it: "No tempo" on every song says nothing.
            if (useful(counts.noInstrument, noInstrument)) {
                androidx.compose.material3.FilterChip(selected = noInstrument, onClick = { noInstrument = !noInstrument }, label = { Text("No instrument (${counts.noInstrument})") })
            }
            if (useful(counts.noTempo, noTempo)) {
                androidx.compose.material3.FilterChip(selected = noTempo, onClick = { noTempo = !noTempo }, label = { Text("No tempo (${counts.noTempo})") })
            }
            if (counts.missing.isNotEmpty() || missingOnly) {
                androidx.compose.material3.FilterChip(selected = missingOnly, onClick = { missingOnly = !missingOnly }, label = { Text("File missing (${counts.missing.size})") })
            }
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
                            standIn = if (fit == PartChoice.Fit.CLOSE) PartChoice.standIn(song, state.profile)?.let { Instruments.partName(it) } else null,
                            chosenPart = state.partFor(song),
                            onPart = { p -> state.stopPlaying(); state.pickPart(song, p); openSong(state, song) },
                            missing = song.id in counts.missing,
                            onOpen = { state.stopPlaying(); openSong(state, song) },
                            onEdit = { editing = song },
                            onAddToSetlist = { addingToSetlist = song },
                            onRecordings = { recordingsFor = song },
                            onNotes = { state.notesFor = song },
                            onColour = { colouring = song },
                            // Filling in instruments: the part that has none.
                            onLook = {
                                val part = (if (noInstrument) song.parts.firstOrNull { it.instrument == null } else null) ?: state.partFor(song)
                                part?.let { state.peeking = song to it }
                            },
                            onMerge = { merging = song },
                            onDelete = { state.removeSong(song) },
                            trailing = if (noInstrument || noTempo) {
                                { TextButton(onClick = { editing = song }) { Text("Fill in") } }
                            } else null
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
    colouring?.let { song ->
        ColourDialog("Colour for ${song.title}", song.color, onChosen = { state.setSongColor(song.id, it); colouring = null }, onDismiss = { colouring = null })
    }
    merging?.let { song ->
        PickSongDialog(
            state, title = "Put \u201C${song.title}\u201D into which song?", exclude = song.id, near = song.title,
            onChosen = { into -> state.mergeSongs(song.id, into.id); merging = null },
            onDismiss = { merging = null }
        )
    }
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
    val part = state.partFor(song) ?: return
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
    /** Write the song's notes; its notes, shown beside its name, open them too. */
    onNotes: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onColour: (() -> Unit)? = null,
    onMerge: (() -> Unit)? = null,
    onLook: (() -> Unit)? = null,
    /** In a setlist: "Colour..." is the song's colour there, and this its colour everywhere. */
    colourLabel: String = "Colour...",
    onColourEverywhere: (() -> Unit)? = null,
    missing: Boolean = false,
    /** The part opened in place of the player's own, which the song has none of: "Tuba". */
    standIn: String? = null,
    /** A line of its own under the title: which page of which part, for a bookmark. */
    note: String? = null,
    /** The part this device opens, marked in the list of parts. */
    chosenPart: com.inksheets.core.Part? = null,
    /** Open the song at one of its parts, chosen from the list under its name. */
    onPart: ((com.inksheets.core.Part) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val tint = song.color?.let { androidx.compose.ui.graphics.Color(it).copy(alpha = 0.10f) } ?: androidx.compose.ui.graphics.Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(tint).clickable(onClick = onOpen).padding(start = 6.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ColourBar(song.color)
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
            val partsLine = onPart != null && song.parts.size > 1
            if (note != null) {
                Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (standIn != null && !partsLine) {
                Text("Opens the $standIn part - there is none for yours", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (missing) {
                Text("A file is not on this device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            val instruments = Instruments.all.filter { it.id in song.instruments }.map { it.name }
            if (onPart != null && song.parts.size > 1) {
                // Every part in plain sight: one tap shows them, one more opens one.
                var open by remember(song.id) { mutableStateOf(false) }
                val opens = chosenPart?.let { Instruments.partName(it) }
                Text(
                    (if (open) "\u25BE " else "\u25B8 ") + "${song.parts.size} parts" + (opens?.let { "  \u00B7  opens $it" + if (standIn != null) " instead" else "" } ?: "") +
                        if (unsure) "  \u00B7  some not yet named" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (unsure) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp)
                )
                if (open) {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        // In score order, flutes to tubas, each instrument's chairs in turn.
                        val order = Instruments.all.withIndex().associate { (i, inst) -> inst.id to i }
                        val sorted = song.parts.sortedWith(compareBy({ order[it.instrument] ?: Int.MAX_VALUE }, { it.chair ?: 0 }))
                        val names = sorted.map { Instruments.partName(it) }
                        sorted.forEachIndexed { i, p ->
                            // Two parts for one instrument are told apart by their file.
                            val name = if (names.count { it == names[i] } > 1) names[i] + " (" + p.file.substringAfterLast('/').substringBeforeLast('.').take(24) + ")" else names[i]
                            androidx.compose.material3.FilterChip(
                                selected = p.id == chosenPart?.id,
                                onClick = { onPart(p) },
                                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                        onEdit?.let { TextButton(onClick = it) { Text("Edit parts") } }
                    }
                }
            } else if (instruments.isNotEmpty() || unsure) {
                Text(
                    (instruments + if (unsure) listOf("parts not yet named") else emptyList()).joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unsure) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        song.notes?.let { notes ->
            Text(
                notes,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier
                    .weight(0.8f)
                    .padding(start = 8.dp)
                    .then(if (onNotes != null) Modifier.clickable(onClick = onNotes) else Modifier)
            )
        }
        trailing?.invoke()
        if (onEdit != null || onAddToSetlist != null || onDelete != null || onColour != null || onMerge != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Song options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    onLook?.let { DropdownMenuItem(text = { Text("Look at it") }, onClick = { menu = false; it() }) }
                    onEdit?.let { DropdownMenuItem(text = { Text("Details and parts") }, onClick = { menu = false; it() }) }
                    onAddToSetlist?.let { DropdownMenuItem(text = { Text("Add to setlist...") }, onClick = { menu = false; it() }) }
                    onRecordings?.let {
                        DropdownMenuItem(
                            text = { Text(if (song.audio.isEmpty()) "Recordings..." else "Recordings (${song.audio.size})") },
                            onClick = { menu = false; it() }
                        )
                    }
                    onNotes?.let { DropdownMenuItem(text = { Text(if (song.notes == null) "Add notes..." else "Notes...") }, onClick = { menu = false; it() }) }
                    onColour?.let { DropdownMenuItem(text = { Text(colourLabel) }, onClick = { menu = false; it() }) }
                    onColourEverywhere?.let { DropdownMenuItem(text = { Text("Colour everywhere...") }, onClick = { menu = false; it() }) }
                    onMerge?.let { DropdownMenuItem(text = { Text("Put into another song...") }, onClick = { menu = false; it() }) }
                    onDelete?.let {
                        var confirm by remember { mutableStateOf(false) }
                        DropdownMenuItem(
                            text = { Text(if (confirm) "Tap again: it goes to the Trash for 30 days" else "Remove from library", fontWeight = if (confirm) FontWeight.Bold else null) },
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
    /** The buttons along the foot; by default a Close (a movable window has its X instead). */
    buttons: (@Composable () -> Unit)? = null,
    wide: Boolean = false,
    /**
     * A window over the music rather than a dialog in front of it: moved by its title bar, and
     * the page underneath still turns and takes the pen while it is open.
     */
    movable: Boolean = false,
    content: @Composable () -> Unit
) {
    if (movable) {
        FloatingPanel(title = title, onClose = onDismiss, width = if (wide) 440.dp else 360.dp, footer = buttons, scroll = false, content = content)
        return
    }
    val footer: @Composable () -> Unit = buttons ?: { TextButton(onClick = onDismiss) { Text("Close") } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            // As wide as it wants on a tablet or laptop, and no wider than a phone's screen.
            modifier = Modifier.widthIn(max = if (wide) 640.dp else 440.dp).fillMaxWidth().padding(8.dp)
        ) {
            Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 20.dp)) {
                // Like every panel: an X at the top right, and the title swiped down closes it.
                var pulled by remember { mutableStateOf(0f) }
                Row(
                    Modifier.fillMaxWidth().pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { pulled = 0f },
                            onVerticalDrag = { _, dy -> pulled += dy; if (pulled > 80.dp.toPx()) { pulled = Float.NEGATIVE_INFINITY; onDismiss() } }
                        )
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                }
                Spacer(Modifier.size(4.dp))
                Box(Modifier.weight(1f, fill = false).padding(end = 12.dp)) { content() }
                Spacer(Modifier.size(12.dp))
                // Wrapping, so a phone puts a third button on a line of its own rather than
                // squeezing "Close" into a column one letter wide.
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { footer() }
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

/** How many songs each filter would pick out, to offer only the filters that would do something. */
private data class FilterCounts(
    val total: Int, val recording: Int, val notInSet: Int, val noInstrument: Int, val noTempo: Int, val missing: Set<String>
)

/** How the song list is ordered. */
internal enum class SongSort(val label: String) {
    AZ("A to Z"), OPENED("Recently opened"), ADDED("Recently added"), COMPOSER("Composer")
}

/** Not there for accessibility or focus: under a screen laid over it. */
private fun Modifier.underCover(): Modifier = this.then(Modifier.clearAndSetSemantics { })
