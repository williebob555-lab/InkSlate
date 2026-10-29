package com.inksheets.ui

import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Switch
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.VerticalDivider
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction

/**
 * The action buttons laid over a song while it is being played: a slim column in the corner of
 * the page. Which actions it carries is chosen from its own menu; each one does exactly what the
 * pedal assigned to it would, through [Perform].
 */
@Composable
fun BoxScope.ActionStrip(state: SheetsState) {
    androidx.compose.runtime.CompositionLocalProvider(LocalReaderPanelState provides state) { ReaderActions(state) }
}

@Composable private fun BoxScope.ReaderActions(state: SheetsState) {
    if(state.groupMessageOpen && (state.homeInFront || ScoreTools.checking)) SendNoteDialog(state){state.groupMessageOpen=false}
    // The app's, not this page's: a tap on the middle of the page opens and closes it too.
    val collapsed = state.stripCollapsed
    var menu by remember { mutableStateOf(false) }
    val shown = state.strip
    var customising by remember { mutableStateOf(false) }
    var sendingNote by remember { mutableStateOf(false) }
    var partMenu by remember { mutableStateOf(false) }
    // Folded away, the strip is one button in the corner and the page has the whole width.
    androidx.compose.runtime.LaunchedEffect(collapsed,ScoreTools.checking) {
        state.platform.setStripLane(false)
        state.platform.setMusicLane(false);Overlays.actionOut=0.dp;Overlays.musicOut=0.dp
    }
    val readingMode = state.platform.readingMode
    androidx.compose.runtime.LaunchedEffect(readingMode) { state.keepReadingMode(readingMode) }
    // The music being read: a slim bar up the screen's edge filling as it goes - always in sight,
    // never over the music, whatever is open.
    // (The music tools show it in themselves while they are out.)
    if (ScoreTools.checking && Transcriber.progress != null && !(ScoreTools.open && !state.stripCollapsed)) {
        // Moving all the time while a part is read - the page in hand filling as pages take here.
        var shown by remember { mutableStateOf(0f) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (true) { Transcriber.smoothProgress()?.let { shown = it }; kotlinx.coroutines.delay(100) }
        }
        Column(
            Modifier.align(if (state.stripOnLeft) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.width(10.dp).fillMaxHeight(0.45f).clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
            ) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(shown.coerceIn(0.02f, 1f)).background(MaterialTheme.colorScheme.primary))
            }
            Text("${(shown * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 3.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp))
        }
    }
    // "End of the set": a small pill at the page's foot for two seconds - a turn that does nothing says why.
    state.edgeNotice?.let { text ->
        androidx.compose.runtime.LaunchedEffect(state.edgeNoticeAt) { kotlinx.coroutines.delay(2_000); state.edgeNotice = null }
        if(ScoreTools.checking) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.inverseSurface, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)) {
            Text(text, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
    // Playing along with the song's recording: its pages turn by themselves (PlayAlong).
    // (Only while a recording is loaded: nothing ticks, nothing wakes, when there is none.)
    androidx.compose.runtime.LaunchedEffect(Unit) { PlayAlong.load(state) }
    androidx.compose.runtime.LaunchedEffect(Recording.loadedFile) {
        while (Recording.loadedFile != null && Recording.player != null) {
            PlayAlong.tick(state)
            kotlinx.coroutines.delay(100)
        }
    }

    if (!state.homeInFront) {
        androidx.compose.runtime.SideEffect { Overlays.actionOut=0.dp;Overlays.musicOut=0.dp }
        MusicDock(state) { ReaderFooter(state) }
        BackToLeader(state,Modifier.align(Alignment.TopCenter).padding(top=8.dp))
        BoxWithConstraints(Modifier.matchParentSize()) { NotePopup(state) }
    }
    if (customising) StripEditor(state, onClose = { customising = false })
    if (sendingNote) SendNoteDialog(state, onClose = { sendingNote = false })
    SaveTabsDialog(state)
    IncomingDialog(state)
    if (state.clearingMarks) {
        val title = state.current?.title ?: "this part"
        SheetDialog(
            title = "Clear all markings?",
            onDismiss = { state.clearingMarks = false },
            buttons = {
                TextButton(onClick = { state.clearingMarks = false }) { Text("Keep them") }
                TextButton(onClick = {
                    state.currentPath?.let { Perform.clearInk?.invoke(it) }
                    state.clearingMarks = false
                }) { Text("Clear them", color = MaterialTheme.colorScheme.error) }
            }
        ) {
            Text("Every mark on every page of $title is taken off, on all your devices. This cannot be undone.")
        }
    }
    if (state.pickingOneOff) OneOffInstrumentDialog(state)
    ReminderDialogs(state)
    if (!state.homeInFront) state.notesFor?.let { song -> NotesDialog(state, song, onClose = { state.notesFor = null }) }
    if (state.readMusicOpen && (state.homeInFront || ScoreTools.checking)) ReadMusicPanel(state, onClose = { state.readMusicOpen = false })
    if (state.tunerOpen && (state.homeInFront || ScoreTools.checking)) TunerDialog(state, onClose = { state.tunerOpen = false })
    if (state.metronomeOpen && (state.homeInFront || ScoreTools.checking)) MetronomeDialog(state, onClose = { state.metronomeOpen = false })
    if (state.companionOpen && (state.homeInFront || ScoreTools.checking)) CompanionDialog(state, onClose = { state.companionOpen = false })
    val song = state.current
    if (state.audioOpen && song != null && (state.homeInFront || ScoreTools.checking)) AudioDialog(state, song, movable = true, onClose = { state.audioOpen = false })
    // The music tools' strip, down the other side, in its own lane.
    if (!state.homeInFront) {
        if (state.remote.remoteOpen) RemoteScreen(state, onClose = { state.remote.remoteOpen = false })
    }
}

/** An instrument's name for a part, or what the part calls itself. */
@Composable
private fun ReaderFooter(state:SheetsState) {
    var more by remember { mutableStateOf(false) }
    var excerpt by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow=maxWidth<600.dp
        val toolsOut=Perform.isOn!=null && !Perform.on(PerformAction.FULLSCREEN)
        @Composable fun Mini(icon:ImageVector,label:String,description:String,active:Boolean=false,width:androidx.compose.ui.unit.Dp=38.dp,action:()->Unit) {
            Column(Modifier.width(width).height(40.dp).clip(RoundedCornerShape(7.dp))
                .background(if(active)MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                .clickable(onClick=action),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                Icon(icon,description,Modifier.size(17.dp))
                Text(label,fontSize=9.sp,maxLines=1,lineHeight=11.sp)
            }
        }
        val annotations:@Composable RowScope.()->Unit={
            if(!toolsOut) {
                Mini(Icons.Default.Edit,"Pen","Pen",Perform.on(PerformAction.PEN)){Perform.run(PerformAction.PEN)}
                Mini(Icons.Default.CleaningServices,"Erase","Eraser",Perform.on(PerformAction.ERASER)){Perform.run(PerformAction.ERASER)}
                Mini(Icons.AutoMirrored.Filled.Undo,"Undo","Undo"){Perform.run(PerformAction.UNDO)}
                Mini(Icons.AutoMirrored.Filled.Redo,"Redo","Redo"){Perform.run(PerformAction.REDO)}
            }
            Mini(if(toolsOut)Icons.Default.Close else Icons.Default.Construction,if(toolsOut)"Hide"else"Tools",if(toolsOut)"Hide annotation tools"else"All tools",toolsOut){Perform.run(PerformAction.FULLSCREEN)}
            Mini(Icons.Default.Bookmark,"Excerpt","Bookmark excerpt"){excerpt=true}
            Mini(Icons.Default.GraphicEq,"Tuner","Tuner",width=48.dp){state.tunerOpen=true}
        }
        val navigation:@Composable RowScope.()->Unit={
            IconButton(onClick={Perform.run(PerformAction.PREVIOUS_PAGE)},modifier=Modifier.size(36.dp)){Icon(Icons.AutoMirrored.Filled.NavigateBefore,"Previous page")}
            val (page,count)=state.pageShown
            Column(Modifier.widthIn(min=56.dp,max=150.dp).clickable {Perform.openPages?.invoke()},horizontalAlignment=Alignment.CenterHorizontally) {
                Text(state.edgeNotice ?: "${page+1} / $count",style=MaterialTheme.typography.labelLarge,maxLines=1)
                if(state.readMusic) {
                    val read=ScoreTools.scoreHere(state)?.hasRead(page)==true
                    Text(if(read)"Read"else"NOT READ",fontSize=9.sp,lineHeight=11.sp,color=if(read)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                }
                state.playing?.let { (id,index) ->
                    Text("${state.library?.setlist(id)?.name ?: "Setlist"} · ${index+1}",fontSize=9.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick={Perform.run(PerformAction.NEXT_PAGE)},modifier=Modifier.size(36.dp)){Icon(Icons.AutoMirrored.Filled.NavigateNext,"Next page")}
            state.current?.let {song ->
                Box {
                    TextButton(onClick={state.partPicker=!state.partPicker},contentPadding=androidx.compose.foundation.layout.PaddingValues(horizontal=6.dp),modifier=Modifier.widthIn(max=140.dp)) {
                        Icon(Icons.Default.SwapHoriz,null,Modifier.size(16.dp))
                        Text(state.partShown()?.let(::partName) ?: "Part",modifier=Modifier.padding(start=4.dp),fontSize=10.sp,maxLines=2)
                    }
                    PartMenu(state,song,state.partShown(),state.partPicker){state.partPicker=false}
                }
            }
        }
        @Composable fun RowScope.OutsideControls() {
            Box {
                Mini(Icons.Default.MoreVert,"More","More music actions",more){more=true}
                StripMenu(state,more,listOf(PerformAction.HALF_PAGE_BACK,PerformAction.HALF_PAGE_FORWARD,PerformAction.FIRST_PAGE,PerformAction.LAST_PAGE),
                    {more=false},{state.arrangingShelf=true},{state.partPicker=true},fromDock=true)
            }
            Mini(Icons.Default.UnfoldLess,"Fold","Hide buttons"){state.stripCollapsed=true;Perform.recentre?.invoke()}
            VerticalDivider(Modifier.height(28.dp).padding(horizontal=4.dp))
            Mini(Icons.Default.Tune,"Layout","Arrange music shelf"){state.arrangingShelf=true}
        }
        if(narrow) Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),content=annotations)
            }
            Row(Modifier.fillMaxWidth().heightIn(min=40.dp),verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically,content=navigation)
                OutsideControls()
            }
        } else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),content=annotations)
            Row(verticalAlignment=Alignment.CenterVertically,content=navigation)
            OutsideControls()
        }
    }
    if(excerpt) state.current?.let {BookmarkEditorDialog(state,it,null){excerpt=false}}
}

private fun partName(p: com.inksheets.core.Part): String =
    com.inksheets.core.Instruments.partName(p)

/**
 * Switch part, two ways: for this song only (every other song keeps its part), or for every song
 * - the instrument this device plays.
 */
@Composable
private fun PartMenu(state: SheetsState, song: com.inksheets.core.Song, shown: com.inksheets.core.Part?, open: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        Text(
            "This song only",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
        val names = song.parts.map { partName(it) }
        var query by remember { mutableStateOf("") }
        ListSearch(song.parts.size, query, { query = it }, "Find a part", Modifier.padding(horizontal = 8.dp))
        song.parts.forEachIndexed { i, p ->
            if (!matches(query, names[i], p.file.substringAfterLast('/'))) return@forEachIndexed
            // Two parts for one instrument are told apart by their file.
            val name = if (names.count { it == names[i] } > 1) names[i] + " - " + p.file.substringAfterLast('/').substringBeforeLast('.') else names[i]
            DropdownMenuItem(
                text = { Text(name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                leadingIcon = { if (p.id == shown?.id) Icon(Icons.Default.Check, null) },
                onClick = { onDismiss(); if (p.id != shown?.id) state.switchThisSong(p) }
            )
        }
        if (state.hasOwnPick(song)) {
            DropdownMenuItem(
                text = { Text("Back to ${state.profile?.name ?: "my instrument"}'s part") },
                onClick = { onDismiss(); state.clearThisSong() }
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(
            "All songs",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
        state.profiles.forEach { profile ->
            DropdownMenuItem(
                text = { Text(profile.name) },
                leadingIcon = { if (state.oneOff == null && profile.id == state.profileId) Icon(Icons.Default.Check, null) },
                onClick = { onDismiss(); state.switchAllSongs(profile.id) }
            )
        }
        // Every song's full score (or whatever has every instrument), as Home offers it.
        DropdownMenuItem(
            text = { Text("All instruments") },
            leadingIcon = { if (state.oneOff == null && state.profileId == null) Icon(Icons.Default.Check, null) },
            onClick = { onDismiss(); state.switchAllSongs(null) }
        )
        state.oneOff?.let { _ ->
            DropdownMenuItem(text = { Text(state.profile?.name ?: "") }, leadingIcon = { Icon(Icons.Default.Check, null) }, onClick = onDismiss)
        }
        // Any instrument at all, just for now: not added to the ones you play.
        DropdownMenuItem(text = { Text("Select instrument...") }, onClick = { onDismiss(); state.oneOffReshows = true; state.pickingOneOff = true })
    }
}

@Composable
private fun StripMenu(state: SheetsState, open: Boolean, overflow: List<PerformAction>, onDismiss: () -> Unit, onCustomise: () -> Unit, onPart: () -> Unit, fromDock: Boolean = false) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        // The strip's own buttons that did not fit on this screen, first, as they are on it.
        val fullscreen = Perform.on(PerformAction.FULLSCREEN)
        for (action in overflow) if (action != PerformAction.SWITCH_PART) DropdownMenuItem(
            text = { Text(action.label) },
            leadingIcon = { Icon(iconOf(action, fullscreen), null) },
            onClick = { onDismiss(); Perform.run(action) }
        )
        if (overflow.isNotEmpty()) HorizontalDivider()
        // Only what is not on the strip already: one way to each thing.
        val onStrip = if(fromDock) emptyList() else state.strip - overflow.toSet()
        if (!fromDock && PerformAction.SWITCH_PART !in onStrip) state.current?.let { _ ->
            val part = state.partShown()?.let { partName(it) }
            DropdownMenuItem(
                text = { Text("Switch part" + (part?.let { " (now $it)" } ?: "") + "...") },
                leadingIcon = { Icon(Icons.Default.SwapHoriz, null) },
                onClick = { onDismiss(); onPart() }
            )
        }
        DropdownMenuItem(
            text = { Text("Fit the page to the screen") },
            leadingIcon = { Icon(Icons.Default.CenterFocusStrong, null) },
            onClick = { onDismiss(); Perform.recentre?.invoke() }
        )
        if(!fromDock) DropdownMenuItem(
            text = { Text("Arrange music shelf...") },
            leadingIcon = { Icon(Icons.Default.Tune, null) },
            onClick = { onDismiss(); state.arrangingShelf = true }
        )
        // Night for a dark pit or stage, Sepia for long reading: one choice for every song.
        var colours by remember { mutableStateOf(false) }
        val mode = state.platform.readingMode
        DropdownMenuItem(
            text = { Text("Page colour: " + mode.label + if (colours) "  ▾" else "  ▸") },
            leadingIcon = { Icon(Icons.Default.Contrast, null) },
            onClick = { colours = !colours }
        )
        if (colours) com.inkslate.core.ReadingMode.entries.forEach { m ->
            DropdownMenuItem(
                text = { Text((if (m == mode) "✓  " else "     ") + m.label) },
                onClick = { state.platform.readingMode = m; onDismiss() },
                modifier = Modifier.padding(start = 24.dp)
            )
        }
        if (state.playing != null) {
            DropdownMenuItem(
                text = { Text("Next song") },
                leadingIcon = { Icon(Icons.Default.SkipNext, null) },
                onClick = { onDismiss(); Perform.run(PerformAction.NEXT_SONG) }
            )
            DropdownMenuItem(
                text = { Text("Previous song") },
                leadingIcon = { Icon(Icons.Default.SkipPrevious, null) },
                onClick = { onDismiss(); Perform.run(PerformAction.PREVIOUS_SONG) }
            )
        }
        if (!fromDock && PerformAction.TUNER !in onStrip) DropdownMenuItem(
            text = { Text("Tuner") },
            leadingIcon = { Icon(Icons.Default.GraphicEq, null) },
            onClick = { onDismiss(); Perform.run(PerformAction.TUNER) }
        )
        HorizontalDivider()
        if (!fromDock && PerformAction.RECORDINGS !in onStrip) state.current?.let { song ->
            DropdownMenuItem(
                text = { Text("Recordings...") },
                leadingIcon = { Icon(Icons.Default.PlayCircle, null) },
                onClick = { onDismiss(); state.audioOpen = true }
            )
        }
        if (!fromDock && PerformAction.PLAY_TOGETHER !in onStrip) DropdownMenuItem(
            text = { Text("Play together (lead or follow)...") },
            leadingIcon = { Icon(Icons.Default.Devices, null) },
            onClick = { onDismiss(); state.companionOpen = true }
        )
        state.current?.let { song ->
            val notes = state.library?.song(song.id)?.notes
            DropdownMenuItem(
                text = { Text(if (notes == null) "Notes..." else "Notes: " + notes.lineSequence().first().take(28)) },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { onDismiss(); state.notesFor = state.library?.song(song.id) ?: song }
            )
            DropdownMenuItem(
                text = { Text(if (song.reminder == null) "Reminder..." else "Change the reminder...") },
                leadingIcon = { Icon(Icons.Default.NotificationsActive, null) },
                onClick = { onDismiss(); state.writingReminder = true }
            )
        }
        // Experimental (Settings): the notes read off the page - checked, redrawn, played as MIDI.
        if (!fromDock && state.readMusic) state.currentPath?.let { path ->
            DropdownMenuItem(
                text = { Text("Read the music (experimental)...") },
                leadingIcon = { Icon(Icons.Default.MusicNote, null) },
                onClick = { onDismiss(); state.openReadMusic(java.io.File(path)) }
            )
        }
        state.currentPath?.let {
            DropdownMenuItem(
                text = { Text("Clear all markings on this part...") },
                leadingIcon = { Icon(Icons.Default.CleaningServices, null) },
                onClick = { onDismiss(); state.clearingMarks = true }
            )
        }
        if (state.platform.canWindow) {
            HorizontalDivider()
            val windowed = state.platform.windowed
            DropdownMenuItem(
                text = { Text(if (windowed) "Cover the whole screen" else "Show in a window") },
                onClick = { onDismiss(); state.platform.setWindowed(!windowed) }
            )
            val screens = state.platform.screens()
            if (screens.size > 1) screens.forEachIndexed { i, name ->
                DropdownMenuItem(text = { Text("Move to $name") }, onClick = { onDismiss(); state.platform.moveToScreen(i) })
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Customise buttons...") },
            leadingIcon = { Icon(Icons.Default.Tune, null) },
            onClick = { onDismiss(); onCustomise() }
        )
    }
}

/**
 * The strip, laid out to be changed: its buttons in order, each dragged by its handle to a new
 * place or taken off with its cross, and every other action below, added with a tap.
 */
@Composable
private fun StripEditor(state: SheetsState, onClose: () -> Unit) {
    SheetDialog(title = "Buttons on the strip", onDismiss = onClose) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { state.edgeTaps = !state.edgeTaps }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tap the page to turn it (right half on, left half back)", Modifier.weight(1f))
                Switch(checked = state.edgeTaps, onCheckedChange = { state.edgeTaps = it })
            }
            Row(
                Modifier.fillMaxWidth().clickable { state.stripOnLeft = !state.stripOnLeft }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Buttons on the left side", Modifier.weight(1f))
                Switch(checked = state.stripOnLeft, onCheckedChange = { state.stripOnLeft = it })
            }
            Row(
                Modifier.fillMaxWidth().clickable { state.stripLabels = !state.stripLabels }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Names under the buttons", Modifier.weight(1f))
                Switch(checked = state.stripLabels, onCheckedChange = { state.stripLabels = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text("Show the turn", Modifier.weight(1f))
                for ((style, label) in listOf("slide" to "Slide", "fade" to "Fade", "none" to "None")) {
                    androidx.compose.material3.FilterChip(
                        selected = state.turnStyle == style,
                        onClick = { state.turnStyle = style },
                        label = { Text(label) },
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("On the strip - drag to reorder", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            val order = remember(state.strip) { state.strip.toMutableStateList() }
            var dragging by remember { mutableStateOf<PerformAction?>(null) }
            var dragBy by remember { mutableStateOf(0f) }
            val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                order.forEach { action ->
                    val lifted = dragging == action
                    androidx.compose.runtime.key(action) {
                        Row(
                            (if (lifted) Modifier.zIndex(1f).graphicsLayer { translationY = dragBy }
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                            else Modifier).fillMaxWidth().height(48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.DragHandle, "Drag to reorder",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .pointerInput(action) {
                                        detectDragGestures(
                                            onDragStart = { dragging = action; dragBy = 0f },
                                            onDragEnd = { dragging = null; dragBy = 0f; state.setStripActions(order.toList()) },
                                            onDragCancel = { dragging = null; dragBy = 0f; state.setStripActions(order.toList()) }
                                        ) { change, amount ->
                                            change.consume()
                                            dragBy += amount.y
                                            val from = order.indexOf(action)
                                            val to = (from + (dragBy / rowPx).toInt()).coerceIn(0, order.lastIndex)
                                            if (to != from) {
                                                order.add(to, order.removeAt(from))
                                                dragBy -= (to - from) * rowPx
                                            }
                                        }
                                    }
                                    .padding(12.dp)
                            )
                            Icon(iconOf(action, false), null, Modifier.padding(end = 12.dp))
                            Text(action.label, Modifier.weight(1f))
                            IconButton(onClick = { state.setStripActions(order - action) }) {
                                Icon(Icons.Default.Close, "Take off the strip")
                            }
                        }
                    }
                }
            }
            val rest = PerformAction.entries.filter { it !in order }
            if (rest.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Add a button", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                var query by remember { mutableStateOf("") }
                ListSearch(rest.size, query, { query = it }, "Find a button")
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    rest.filter { matches(query, it.label, shortName(it, false)) }.forEach { action ->
                        AssistChip(
                            onClick = { state.setStripActions(order + action) },
                            label = { Text(action.label) },
                            leadingIcon = { Icon(iconOf(action, false), null, Modifier.size(18.dp)) }
                        )
                    }
                }
            }
        }
    }
}

/** A short screen: the strip's names in small print, so every button still fits with its name. */
internal val LocalSmallNames = androidx.compose.runtime.compositionLocalOf { false }

/**
 * One of the strip's buttons: its icon, and its name under it - so what it does is never a guess.
 * [description] is what a screen reader says.
 */
@Composable
internal fun StripButton(
    icon: ImageVector,
    name: String,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    named: Boolean,
    lit: Boolean = false,
    /** Work in hand (a part being read): the button fills from left to right, 0 to 1. */
    progress: Float? = null,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 2.dp, vertical = 1.dp)
    ) {
        Box(
            Modifier.size(size).then(
                if (lit) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)) else Modifier
            ),
            contentAlignment = Alignment.Center
        ) {
            if (progress != null) Box(
                Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.coerceIn(0.04f, 1f))
                    .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
            )
            Icon(icon, description)
        }
        if (progress != null && named) Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, lineHeight = 10.sp, color = MaterialTheme.colorScheme.primary)
        if (named) {
            val small = LocalSmallNames.current
            Text(name, style = MaterialTheme.typography.labelSmall, fontSize = if (small) 8.sp else 10.sp, maxLines = 1, lineHeight = if (small) 9.sp else 11.sp)
        }
    }
}

/**
 * Playback mode's quick actions, in a column beside the strip while a recording is playing or
 * paused: where it is, play or pause, back and on, from the start again - and put away.
 */
@Composable
private fun PlaybackColumn(state: SheetsState, btn: androidx.compose.ui.unit.Dp, named: Boolean, modifier: Modifier) {
    var position by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            position = Recording.player?.positionMs ?: 0L
            kotlinx.coroutines.delay(250)
        }
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        modifier = modifier
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 4.dp)) {
            val s = position / 1000
            Text("%d:%02d".format(s / 60, s % 60), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(4.dp))
            // What the pages will do: count down to the next turn, learning, or off - tapped, on or off.
            PlayAlong.hint?.let { hint ->
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = if (PlayAlong.on && PlayAlong.learned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(btn + 8.dp).clip(RoundedCornerShape(6.dp)).clickable { PlayAlong.toggle(state) }.padding(2.dp)
                )
            }
            StripButton(Icons.Default.Replay, "Replay", "From the start again", btn, named) { Recording.replay(state) }
            StripButton(Icons.Default.FastRewind, "Back ${SKIP_S}s", "Back $SKIP_S seconds", btn, named) { Recording.skip(-SKIP_S.toDouble()) }
            StripButton(
                if (Recording.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (Recording.playing) "Pause" else "Play", "Pause or play on", btn, named, lit = Recording.playing
            ) { Recording.playPause(state) }
            StripButton(Icons.Default.FastForward, "On ${SKIP_S}s", "On $SKIP_S seconds", btn, named) { Recording.skip(SKIP_S.toDouble()) }
            StripButton(Icons.Default.Close, "Done", "Stop, and put these away", btn, named) { Recording.end(state) }
        }
    }
}

/** How far back and on the playback column's buttons skip. */
private const val SKIP_S = 5

/** A button's name under it: a word or two. */
internal fun shortName(action: PerformAction, fullscreen: Boolean): String = when (action) {
    PerformAction.NEXT_PAGE -> "Next"
    PerformAction.PREVIOUS_PAGE -> "Back"
    PerformAction.HALF_PAGE_FORWARD -> "Half on"
    PerformAction.HALF_PAGE_BACK -> "Half back"
    PerformAction.FIRST_PAGE -> "First"
    PerformAction.LAST_PAGE -> "Last"
    PerformAction.NEXT_SONG -> "Next song"
    PerformAction.PREVIOUS_SONG -> "Last song"
    PerformAction.METRONOME -> "Metronome"
    PerformAction.TUNER -> "Tuner"
    PerformAction.PLAY_AUDIO -> "Play"
    PerformAction.RECORDINGS -> "Recordings"
    PerformAction.PLAY_TOGETHER -> "Together"
    PerformAction.SWITCH_PART -> "Part"
    PerformAction.BOOKMARK -> "Bookmark"
    PerformAction.PEN -> "Pen"
    PerformAction.HIGHLIGHTER -> "Highlight"
    PerformAction.ERASER -> "Eraser"
    PerformAction.UNDO -> "Undo"
    PerformAction.REDO -> "Redo"
    PerformAction.FULLSCREEN -> if (fullscreen) "All tools" else "Hide tools"
}

/** Page turns, pen tools, songs, sound, the screen: a thin rule between each. */
private fun groupOf(action: PerformAction): Int = when (action) {
    PerformAction.PEN, PerformAction.HIGHLIGHTER, PerformAction.ERASER, PerformAction.UNDO, PerformAction.REDO -> 1
    PerformAction.NEXT_SONG, PerformAction.PREVIOUS_SONG -> 2
    PerformAction.METRONOME, PerformAction.TUNER, PerformAction.PLAY_AUDIO, PerformAction.RECORDINGS -> 3
    PerformAction.SWITCH_PART, PerformAction.PLAY_TOGETHER, PerformAction.BOOKMARK -> 5
    PerformAction.FULLSCREEN -> 4
    else -> 0
}

internal fun iconOf(action: PerformAction, fullscreen: Boolean): ImageVector = when (action) {
    PerformAction.NEXT_PAGE -> Icons.AutoMirrored.Filled.NavigateNext
    PerformAction.PREVIOUS_PAGE -> Icons.AutoMirrored.Filled.NavigateBefore
    PerformAction.HALF_PAGE_FORWARD -> Icons.Default.ExpandMore
    PerformAction.HALF_PAGE_BACK -> Icons.Default.ExpandLess
    PerformAction.FIRST_PAGE -> Icons.Default.FirstPage
    PerformAction.LAST_PAGE -> Icons.Default.LastPage
    PerformAction.NEXT_SONG -> Icons.Default.SkipNext
    PerformAction.PREVIOUS_SONG -> Icons.Default.SkipPrevious
    PerformAction.METRONOME -> Icons.Default.Timer
    PerformAction.TUNER -> Icons.Default.GraphicEq
    PerformAction.PLAY_AUDIO -> if (Recording.playing) Icons.Default.Pause else Icons.Default.PlayCircle
    PerformAction.RECORDINGS -> Icons.Default.LibraryMusic
    PerformAction.PLAY_TOGETHER -> Icons.Default.Devices
    PerformAction.SWITCH_PART -> Icons.Default.SwapHoriz
    PerformAction.BOOKMARK -> Icons.Default.Bookmark
    PerformAction.PEN -> Icons.Default.Edit
    PerformAction.HIGHLIGHTER -> Icons.Default.Highlight
    PerformAction.ERASER -> Icons.Default.CleaningServices
    PerformAction.UNDO -> Icons.AutoMirrored.Filled.Undo
    PerformAction.REDO -> Icons.AutoMirrored.Filled.Redo
    PerformAction.FULLSCREEN -> if (fullscreen) Icons.Default.Construction else Icons.Default.Close
}

/**
 * A leader's message kept for one tap: its words on the button, in its colour. Lit for a moment
 * once sent, so the leader knows it went.
 */
@Composable
private fun PresetButton(preset: com.inksheets.core.MessagePreset, onSend: () -> Unit) {
    var sent by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(sent) {
        if (sent > 0) { kotlinx.coroutines.delay(1500); sent = 0 }
    }
    val fill = when {
        sent > 0 -> MaterialTheme.colorScheme.primary
        preset.color != null -> androidx.compose.ui.graphics.Color(preset.color!!)
        preset.urgent -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val ink = if (sent > 0) MaterialTheme.colorScheme.onPrimary
        else if (preset.color != null) (if (androidx.compose.ui.graphics.Color(preset.color!!).luminance() > 0.45f) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White)
        else if (preset.urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = fill,
        modifier = Modifier.padding(vertical = 2.dp).width(56.dp).clip(RoundedCornerShape(10.dp)).clickable { onSend(); sent++ }
    ) {
        Text(
            if (sent > 0) "Sent" else preset.text,
            color = ink,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
        )
    }
}

/**
 * A reminder left on a song: written from More, and shown the next time the song is opened - on
 * any device, since it lives with the song. "Done" clears it; "Keep it" shows it again next time.
 */
@Composable
private fun ReminderDialogs(state: SheetsState) {
    val song = state.current
    // Shown once each time a song with a reminder comes in front.
    var shownFor by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(song?.id) {
        if (song != null && song.reminder != null && shownFor != song.id) {
            shownFor = song.id
            state.reminderShown = song.id
        } else if (song?.id != shownFor) shownFor = null
    }
    val showing = state.reminderShown?.let { id -> state.library?.song(id) }?.takeIf { it.reminder != null }
    if (showing != null) {
        SheetDialog(
            title = "Reminder - ${showing.title}",
            onDismiss = { state.reminderShown = null },
            buttons = {
                TextButton(onClick = { state.reminderShown = null }) { Text("Keep it") }
                TextButton(onClick = {
                    state.change { editSong(showing.id) { reminder = null } }
                    state.reminderShown = null
                }) { Text("Done") }
            }
        ) {
            Text(showing.reminder.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (state.writingReminder && song != null) {
        var text by remember(song.id) { mutableStateOf(song.reminder.orEmpty()) }
        SheetDialog(
            title = "Reminder for next time",
            onDismiss = { state.writingReminder = false },
            buttons = {
                if (song.reminder != null) TextButton(onClick = {
                    state.change { editSong(song.id) { reminder = null } }
                    state.writingReminder = false
                }) { Text("Remove") }
                TextButton(onClick = { state.writingReminder = false }) { Text("Cancel") }
                TextButton(onClick = {
                    state.change { editSong(song.id) { reminder = text.trim() } }
                    state.writingReminder = false
                }) { Text("Save") }
            }
        ) {
            Column {
                Text("Shown the next time ${song.title} is opened.", style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Reminder") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
    }
}
