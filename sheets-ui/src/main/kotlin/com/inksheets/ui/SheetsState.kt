package com.inksheets.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inksheets.core.InstrumentProfile
import com.inksheets.core.Instruments
import com.inksheets.core.Library
import com.inksheets.core.LibraryLog
import java.io.File

/**
 * What the InkSheets screens share: the open library, the instrument being played, and a counter
 * that moves whenever the library changes so the screens know to look again.
 */
class SheetsState(val platform: SheetsPlatform) {

    /** The thread watching the library; declared first, as [open] runs during construction. */
    @Volatile private var watcher: Thread? = null

    var root by mutableStateOf<File?>(null)
        private set

    var library by mutableStateOf<Library?>(null)
        private set

    /** Bumped on every change, from this device or from another through the synced folder. */
    var version by mutableStateOf(0L)
        private set

    /** The instruments to choose between: the library's own (synced), or the built-in three. */
    val profiles: List<InstrumentProfile>
        get() {
            version   // read, so screens showing these redraw when they change
            return library?.profiles() ?: Instruments.defaultProfiles
        }

    var profileId by mutableStateOf(platform.pref(K_PROFILE))
        private set

    val profile: InstrumentProfile?
        get() = profiles.firstOrNull { it.id == profileId }

    /** The tuner and metronome panels, which a pedal or a button over the page can open. */
    var tunerOpen by mutableStateOf(false)

    /** The song opened last - what the strip's recording button plays. */
    var current by mutableStateOf<com.inksheets.core.Song?>(null)

    /** The file of the part in front, as the editor last reported it. */
    var currentPath by mutableStateOf<String?>(null)
        private set

    /** Whether Home is what is on screen, rather than a song. Set by the workspace. */
    var homeInFront by mutableStateOf(true)

    /** Leading or following other tablets. */
    val companion = Companion(this)
    var companionOpen by mutableStateOf(false)

    /** The recordings panel, for the song opened last. */
    var audioOpen by mutableStateOf(false)
    var metronomeOpen by mutableStateOf(false)

    /** Open tabs being kept as a new setlist: asking its name and colour. */
    var savingTabs by mutableStateOf<List<File>?>(null)

    /** Make a setlist of the songs these files are parts of, in this order. */
    fun setlistFromFiles(name: String, color: Int?, files: List<File>): com.inksheets.core.Setlist? {
        val lib = library ?: return null
        val songs = files.mapNotNull { songAt(it.absolutePath) }.distinctBy { it.id }
        var made: com.inksheets.core.Setlist? = null
        change {
            val list = addSetlist(name)
            editSetlist(list.id) {
                entries = songs.map { com.inksheets.core.SetlistEntry(songId = it.id) }
                if (color != null) this.color = color
            }
            made = setlist(list.id)
        }
        return made
    }

    /** Asking whether to clear every mark on the part in front. */
    var clearingMarks by mutableStateOf(false)

    /** Whether a finger tap at the side of the page turns it. On unless turned off. */
    var edgeTaps: Boolean
        get() = edgeTapsState
        set(on) {
            edgeTapsState = on
            platform.setPref(K_EDGE_TAPS, on.toString())
            platform.setEdgeTaps(on)
        }
    private var edgeTapsState by mutableStateOf(platform.pref(K_EDGE_TAPS) != "false")

    /** The strip sits down the left side of the page rather than the right. */
    var stripOnLeft: Boolean
        get() = stripOnLeftState
        set(on) { stripOnLeftState = on; platform.setPref(K_STRIP_LEFT, on.toString()); platform.setStripSide(on) }
    private var stripOnLeftState by mutableStateOf(platform.pref(K_STRIP_LEFT) == "true")

    /** Whether the strip's buttons have their names under them. On unless turned off. */
    var stripLabels: Boolean
        get() = stripLabelsState
        set(on) { stripLabelsState = on; platform.setPref(K_STRIP_LABELS, on.toString()) }
    private var stripLabelsState by mutableStateOf(platform.pref(K_STRIP_LABELS) != "false")

    /** How a page turn is shown: "slide" (the default), "fade" or "none". */
    var turnStyle: String
        get() = turnStyleState
        set(style) {
            turnStyleState = style
            platform.setPref(K_TURN, style)
            platform.setTurnStyle(style)
        }
    private var turnStyleState by mutableStateOf(platform.pref(K_TURN) ?: "slide")

    /** The actions on the strip over the page, in order. Held as state so every screen showing it follows a change. */
    var strip by mutableStateOf(
        platform.pref(K_STRIP)?.split(',')?.mapNotNull { n -> com.inkslate.core.PerformAction.entries.firstOrNull { it.name == n } }
            ?: DEFAULT_STRIP
    )
        private set

    fun stripActions(): List<com.inkslate.core.PerformAction> = strip

    fun setStripActions(actions: List<com.inkslate.core.PerformAction>) {
        strip = actions.distinct()
        platform.setPref(K_STRIP, strip.joinToString(",") { it.name })
    }

    /** The setlist being played through, and where in it: what "next song" means. */
    var playing by mutableStateOf<Pair<String, Int>?>(null)
        private set

    init {
        platform.setEdgeTaps(edgeTapsState)
        platform.setTurnStyle(turnStyleState)
        platform.setStripSide(stripOnLeftState)
        platform.pref(K_LIBRARY)?.let(::File)?.takeIf { it.isDirectory }?.let(::open)
        // Song turns and the metronome from a pedal, whatever screen is in front.
        com.inkslate.core.Perform.app = { action ->
            when (action) {
                com.inkslate.core.PerformAction.NEXT_SONG -> step(1)
                com.inkslate.core.PerformAction.PREVIOUS_SONG -> step(-1)
                com.inkslate.core.PerformAction.METRONOME -> { toggleMetronome(); true }
                com.inkslate.core.PerformAction.TUNER -> { tunerOpen = true; true }
                com.inkslate.core.PerformAction.PLAY_AUDIO -> { Recording.toggle(this); true }
                else -> false
            }
        }
        // Whichever song is in front is "the song": the one the play button plays and the one a
        // leading tablet tells its followers about.
        com.inkslate.core.Perform.onPosition = { page, count ->
            pageShown = page to count
            companion.applyPendingInk()
        }
        // Markings brought across from MobileSheets, handed to a part when it is opened.
        com.inkslate.core.Perform.importedInk = { path, pageSize -> ImportedInk.strokes(importedMarksFor(path), pageSize) }
        com.inkslate.core.Perform.onPage = { path, page ->
            currentPath = path
            if (pagesWanted == path) { pagesWanted = null; com.inkslate.core.Perform.openPages?.invoke() }
            songAt(path)?.let { if (current?.id != it.id) current = it }
            companion.pageTurned(page)
        }
    }

    /**
     * Open a setlist as tabs - every song, in order, with your instrument's part - and bring entry
     * [index] to the front. Remembered for the pedals, the strip and companion mode.
     */
    fun playSetlist(setlistId: String, index: Int) {
        val lib = library ?: return
        val entries = lib.setlist(setlistId)?.entries ?: return
        val entry = entries.getOrNull(index) ?: return
        val song = lib.song(entry.songId) ?: return
        val tabs = ArrayList<Pair<File, String>>()
        var focus = 0
        entries.forEachIndexed { i, e ->
            val s = lib.song(e.songId) ?: return@forEachIndexed
            val part = partFor(s) ?: return@forEachIndexed
            val file = partFile(s, part) ?: return@forEachIndexed
            if (i == index) focus = tabs.size
            if (tabs.none { it.first == file }) {
                part.firstPage?.let { com.inkslate.core.Perform.requestPage(file.absolutePath, it - 1) }
                tabs += file to s.title
            } else if (i == index) {
                focus = tabs.indexOfFirst { it.first == file }
            }
        }
        playing = setlistId to index
        frontEntry = entry.id
        current = song
        noteOpened(song)
        platform.openSet(tabs, focus)
        companion.pageTurned(0)
    }

    /** The page in front (0-based) and how many the part has, for the strip. */
    var pageShown by mutableStateOf(0 to 0)

    /** What Home shows: the songs (0) or the setlists (1), and in Setlists the folder and setlist open. */
    var homeTab by mutableStateOf(0)
    var setlistFolder by mutableStateOf<String?>(null)
    var setlistShown by mutableStateOf<String?>(null)

    /**
     * Home, pressed while playing a set: the set is put away as "Close set" would, and Home opens
     * on that setlist, in its folder, rather than on the song list.
     */
    fun backToSetlist() {
        val (id, _) = playing ?: return closeSetlist()
        closeSetlist()
        homeTab = 1
        setlistShown = id
        setlistFolder = library?.setlist(id)?.folderId
    }

    /** The file each entry of [setlistId] opens, with your instrument's part; null where none. */
    private fun entryFiles(setlistId: String): List<Pair<String, File?>> {
        val lib = library ?: return emptyList()
        return lib.setlist(setlistId)?.entries.orEmpty().map { e ->
            e.id to lib.song(e.songId)?.let { s -> partFor(s)?.let { partFile(s, it) } }
        }
    }

    /**
     * The tab row was rearranged while a set is open: the setlist takes the same order. Entries
     * sharing a file keep their order among themselves; ones with no tab stay where they are last.
     */
    fun tabsMoved(files: List<File>) {
        val (id, index) = playing ?: return
        val before = entryFiles(id)
        if (before.isEmpty()) return
        val rank = files.map { it.absolutePath }
        val after = before.withIndex().sortedWith(compareBy(
            { (_, e) -> e.second?.absolutePath?.let(rank::indexOf)?.takeIf { it >= 0 } ?: Int.MAX_VALUE },
            { it.index }
        )).map { it.value.first }
        if (after == before.map { it.first }) return
        val playingEntry = before.getOrNull(index)?.first
        reorderSetlist(id, after)
        playing = id to (after.indexOf(playingEntry).takeIf { it >= 0 } ?: index)
    }

    /**
     * Put a setlist's entries in [entryIds] order - from dragging a song in the list. If the set is
     * open, its tabs follow.
     */
    fun reorderSetlist(setlistId: String, entryIds: List<String>) {
        val lib = library ?: return
        val entries = lib.setlist(setlistId)?.entries ?: return
        val byId = entries.associateBy { it.id }
        val ordered = entryIds.mapNotNull(byId::get) + entries.filter { it.id !in entryIds }
        if (ordered.map { it.id } == entries.map { it.id }) return
        change { editSetlist(setlistId) { this.entries = ordered } }
    }

    /** After the list was dragged: the open set's tabs take the new order, the same song in front. */
    fun setlistReordered(setlistId: String) {
        val (id, index) = playing ?: return
        if (id != setlistId) return
        val front = frontEntry ?: return
        val now = library?.setlist(id)?.entries?.indexOfFirst { it.id == front }?.takeIf { it >= 0 } ?: index
        playSetlist(id, now)
    }
    private var frontEntry: String? = null

    /** For "Recently opened". Never in the way of opening: a failure to note it is only lost. */
    fun noteOpened(song: com.inksheets.core.Song) {
        runCatching { change { markOpened(song.id) } }
    }

    /** Put the setlist away: its tabs are saved and closed, and it is no longer being played. */
    fun closeSetlist() {
        playing = null
        platform.closeSet()
    }

    /** The name of the setlist being played, for the strip. */
    val playingName: String?
        get() = playing?.let { (id, _) -> library?.setlist(id)?.name }

    /** A song opened from the library rather than a setlist ends any setlist being played. */
    fun stopPlaying() {
        playing = null
    }

    /** Move through the setlist being played. False at either end, or with none. */
    fun step(by: Int): Boolean {
        val (setlistId, index) = playing ?: return false
        val size = library?.setlist(setlistId)?.entries?.size ?: return false
        val next = index + by
        if (next !in 0 until size) return false
        playSetlist(setlistId, next)
        return true
    }

    /**
     * Read the instrument off every part nobody has named yet - scans imported on a device that
     * could not recognise text, or before it could. Each part is tried once per device; what is
     * found is written to the library, so it reaches every other device through the synced folder
     * and none of them has to read that page again. Slow: run it off the UI thread.
     */
    fun readUnknownParts(onProgress: (done: Int, of: Int) -> Unit = { _, _ -> }) {
        val lib = library ?: return
        val tried = platform.pref(K_TRIED).orEmpty().split(',').filter { it.isNotEmpty() }.toMutableSet()
        val todo = lib.songs.flatMap { song ->
            song.parts.filter { it.instrument == null && it.id !in tried }.map { song to it }
        }
        todo.forEachIndexed { i, (song, part) ->
            onProgress(i, todo.size)
            val file = partFile(song, part) ?: return@forEachIndexed
            if (!file.isFile) return@forEachIndexed
            // The page's own words where it has them; a scan read where it does not.
            val text = runCatching { platform.pageText(file, part.firstPage ?: 1) }.getOrNull()
                ?.takeIf { com.inksheets.core.InstrumentReader.read(it) != null }
                ?: if (platform.canRecognise) runCatching { platform.recognise(file, part.firstPage ?: 1) }.getOrNull() else null
            val match = text?.let { com.inksheets.core.InstrumentReader.read(it) }
            if (match != null) {
                // Read the song again at the moment of writing: another part may have changed.
                val current = lib.song(song.id) ?: return@forEachIndexed
                change {
                    editSong(song.id) {
                        parts = current.parts.map { p ->
                            if (p.id == part.id && p.instrument == null) {
                                p.copy(
                                    instrument = match.instrument.id,
                                    source = com.inksheets.core.InstrumentSource.OCR,
                                    label = match.label
                                )
                            } else p
                        }
                    }
                }
            }
            tried += part.id
            platform.setPref(K_TRIED, tried.joinToString(","))
        }
        onProgress(todo.size, todo.size)
    }

    fun toggleMetronome() {
        val out = platform.audioOut ?: return
        val engine = SharedMetronome.engine
            ?: com.inksheets.core.Metronome(out.sampleRate).also { SharedMetronome.engine = it }
        if (SharedMetronome.running) {
            out.stop()
            SharedMetronome.running = false
            SharedMetronome.beat = -1
        } else {
            engine.reset()
            engine.onBeat = { SharedMetronome.beat = it }
            out.start { engine.fill(it) }
            SharedMetronome.running = true
        }
    }

    fun open(folder: File) {
        val lib = runCatching { Library(LibraryLog(folder, platform.deviceId)) }.getOrNull() ?: return
        root = folder
        library = lib
        platform.setPref(K_LIBRARY, folder.absolutePath)
        version = lib.version
        runCatching { lib.noteDevice(platform.deviceName) }
        startWatching()
    }

    /**
     * Keep looking - whatever is on screen, Home or a song: other devices' changes every couple of
     * seconds, the music folder itself every ten. Not tied to any screen, so a setlist made on the
     * phone shows up while the tablet is in the middle of a piece.
     */
    private fun startWatching() {
        if (watcher != null) return
        watcher = Thread({
            var ticks = 0
            while (true) {
                runCatching { Thread.sleep(2_000) }
                if (library == null) continue
                val changed = runCatching { library?.refresh() == true }.getOrDefault(false)
                if (changed) platform.onMain { version = library?.version ?: version }
                if (++ticks % 5 == 0) {
                    val report = scanFolder()
                    if (report?.added?.isNotEmpty() == true) runCatching { readUnknownParts() }
                }
            }
        }, "library-watch").apply { isDaemon = true; start() }
    }

    /**
     * Whose changes have reached this device, and when each last changed anything: every device
     * that writes to the library has its own record file, which the file sync carries over.
     */
    fun devicesHeard(): List<Triple<String, Long, Boolean>> {
        val base = root ?: return emptyList()
        val names = runCatching { library?.deviceNames() }.getOrNull().orEmpty()
        val me = platform.deviceId
        return File(base, ".inksheets/log").listFiles { f -> f.isFile && f.name.endsWith(".jsonl") && !f.name.startsWith(".") }
            .orEmpty().map { f ->
                val id = f.name.removeSuffix(".jsonl")
                Triple(names[id] ?: id, f.lastModified(), id == me)
            }.sortedByDescending { it.second }
    }

    fun chooseProfile(id: String?) {
        profileId = id
        platform.setPref(K_PROFILE, id)
    }

    /**
     * Parts picked for one song on this device, over the instrument chosen for all of them: song
     * id to part id. Kept here, like the instrument, since each player reads their own.
     */
    private var partPicks by mutableStateOf(
        platform.pref(K_PICKS).orEmpty().split(';').mapNotNull { pair ->
            pair.split('=').takeIf { it.size == 2 && it[0].isNotEmpty() && it[1].isNotEmpty() }?.let { it[0] to it[1] }
        }.toMap()
    )

    private fun savePicks(picks: Map<String, String>) {
        partPicks = picks
        platform.setPref(K_PICKS, picks.entries.joinToString(";") { "${it.key}=${it.value}" }.ifEmpty { null })
    }

    /** The part of [song] this device plays: the one picked for it, or the instrument's. */
    fun partFor(song: com.inksheets.core.Song): com.inksheets.core.Part? =
        partPicks[song.id]?.let { id -> song.parts.firstOrNull { it.id == id } }
            ?: com.inksheets.core.PartChoice.partFor(song, profile)

    /** Whether [song] has a part picked for it alone. */
    fun hasOwnPick(song: com.inksheets.core.Song): Boolean = partPicks[song.id]?.let { id -> song.parts.any { it.id == id } } == true

    /** The part in front: of the song in front, the one whose file is showing. */
    fun partShown(): com.inksheets.core.Part? {
        val song = current ?: return null
        val path = currentPath ?: return partFor(song)
        val here = song.parts.filter { p -> fileOf(p.file)?.absolutePath == path }
        val page = pageShown.first + 1
        return here.firstOrNull { p -> (p.firstPage ?: 1) <= page && page <= (p.lastPage ?: Int.MAX_VALUE) }
            ?: here.firstOrNull() ?: partFor(song)
    }

    /** Play [part] for the song in front only - every other song keeps the instrument's part. */
    fun switchThisSong(part: com.inksheets.core.Part) {
        val song = current ?: return
        savePicks(partPicks + (song.id to part.id))
        showAgain(song)
    }

    /** The song in front back to the instrument's part. */
    fun clearThisSong() {
        val song = current ?: return
        savePicks(partPicks - song.id)
        showAgain(song)
    }

    /** Play [profileId]'s parts in every song, from now on - picks made for single songs go. */
    fun switchAllSongs(profileId: String) {
        chooseProfile(profileId)
        savePicks(emptyMap())
        current?.let { showAgain(it) }
    }

    /** Show [song] again with the part it now gets: the whole set rebuilt, or the one tab swapped. */
    private fun showAgain(song: com.inksheets.core.Song) {
        val (setlistId, index) = playing ?: run {
            val part = partFor(song) ?: return
            val file = partFile(song, part) ?: return
            part.firstPage?.let { com.inkslate.core.Perform.requestPage(file.absolutePath, it - 1) }
            val old = currentPath?.let(::File)
            if (old != null && old.absolutePath != file.absolutePath) platform.swapPart(old, file) else platform.openPart(song, part, file)
            companion.pageTurned((part.firstPage ?: 1) - 1)
            return
        }
        playSetlist(setlistId, index)
    }

    /** Take in edits from other devices. Called off the UI thread on a timer. */
    fun refresh() {
        val lib = library ?: return
        if (lib.refresh()) version = lib.version
    }

    // ---- keeping the library in step with the folder ------------------------------------

    /** What the last look at the music folder found and did. */
    var lastScan by mutableStateOf<com.inksheets.core.LibraryScan.Report?>(null)
        private set

    /** Recent things the folder scan did, newest first, for Library health. This device's only. */
    val scanHistory = androidx.compose.runtime.mutableStateListOf<String>()

    /** Imports in progress: the scan waits, so it does not file half-copied music its own way. */
    @Volatile var importing = 0

    private fun scanner(): com.inksheets.core.LibraryScan? {
        val base = root ?: return null
        val lib = library ?: return null
        val name = "scan-" + Integer.toHexString(base.absolutePath.hashCode()) + ".json"
        return com.inksheets.core.LibraryScan(base, lib, File(platform.localFolder, name))
    }

    /**
     * Look at the music folder and bring the library into line: new files added, moved ones
     * followed, deleted ones removed, doubles put together. Slow-ish: off the UI thread.
     * [allowMassRemoval] is the person confirming a removal that was held back.
     */
    fun scanFolder(allowMassRemoval: Boolean = false): com.inksheets.core.LibraryScan.Report? {
        if (importing > 0) return null
        runCatching { trash()?.purge() }
        val report = runCatching { scanner()?.run(allowMassRemoval) }
            .onFailure { platform.log("Library scan failed: ${it.message}") }
            .getOrNull() ?: return null
        platform.onMain {
            lastScan = report
            if (report.changed) {
                val stamp = java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                val lines = report.added.map { "Added $it" } + report.moved.map { (a, b) -> "Followed $a to $b" } +
                    report.removed.map { "Removed $it" } + report.merged.map { "Put together $it" }
                lines.forEach { scanHistory.add(0, "$stamp  $it") }
                while (scanHistory.size > 200) scanHistory.removeAt(scanHistory.lastIndex)
                version = library?.version ?: version
            }
        }
        if (report.changed) {
            platform.log("Library scan: ${report.added.size} added, ${report.moved.size} moved, ${report.removed.size} removed, ${report.merged.size} put together")
        }
        if (report.heldBack > 0) platform.log("Library scan: held back removing ${report.heldBack} parts - too many at once")
        return report
    }

    // ---- removing, merging, splitting ---------------------------------------------------

    fun trash(): com.inksheets.core.LibraryTrash? = root?.let { r -> library?.let { com.inksheets.core.LibraryTrash(r, it) } }

    /** Take a song out of the library: its files go to the library's Trash for 30 days. */
    fun removeSong(song: com.inksheets.core.Song) {
        importing++
        try { runCatching { trash()?.remove(song) }.onFailure { platform.log("Could not remove ${song.title}: ${it.message}") } }
        finally { importing-- }
        change { }
    }

    fun restore(entry: com.inksheets.core.LibraryTrash.Entry) {
        importing++
        try { trash()?.restore(entry) } finally { importing-- }
        change { }
    }

    /** Put [from] into [into]: one piece, two entries in the library. */
    fun mergeSongs(from: String, into: String) = change { mergeSongs(from, into) }

    /** Move a part to another song. */
    fun movePart(partId: String, toSong: String) = change { movePart(partId, toSong) }

    /** Make a part a song of its own, called [title], never put back with its namesake. */
    fun splitPart(partId: String, title: String) = change { movePart(partId, null, title) }

    fun setSongColor(songId: String, color: Int?) = change { editSong(songId) { this.color = color } }
    fun setSetlistColor(setlistId: String, color: Int?) = change { editSetlist(setlistId) { this.color = color } }
    fun setFolderColor(folderId: String, color: Int?) = change { setFolderColor(folderId, color) }

    /** A song's colour within one setlist only. */
    fun setEntryColor(setlistId: String, entryId: String, color: Int?) = change {
        val list = setlist(setlistId) ?: return@change
        editSetlist(setlistId) { entries = list.entries.map { if (it.id == entryId) it.copy(color = color) else it } }
    }

    /**
     * Some pages of the file at [path] as a part of their own - one instrument's pages of a band
     * pack - in the song the file belongs to, or in a new song called [newSong].
     */
    fun partFromPages(path: String, pages: List<Int>, instrument: String?, newSong: String?) {
        val lib = library ?: return
        val rel = relative(File(path)) ?: return
        if (pages.isEmpty()) return
        val first = pages.min() + 1
        val last = pages.max() + 1
        val part = com.inksheets.core.Part(
            id = com.inksheets.core.Library.partIdFor("$rel#$first-$last"), file = rel,
            firstPage = first, lastPage = last, instrument = instrument,
            source = if (instrument != null) com.inksheets.core.InstrumentSource.PERSON else com.inksheets.core.InstrumentSource.UNKNOWN,
            label = instrument?.let { com.inksheets.core.Instruments.byId[it]?.name }
        )
        change {
            val home = if (newSong != null) addSong(newSong, emptyList()) { apart = true }.id else songAt(path)?.id ?: ensureSong(com.inksheets.core.ImportPlan.songTitle(rel)).id
            writePart(home, part)
        }
    }

    /** A part to show the pages of once its editor is in front. */
    @Volatile var pagesWanted: String? = null

    /** Open [part]'s file and its page overview. */
    fun showPages(song: com.inksheets.core.Song, part: com.inksheets.core.Part) {
        val file = partFile(song, part) ?: return
        pagesWanted = file.absolutePath
        current = song
        platform.openPart(song, part, file)
    }

    // ---- music arriving from outside ---------------------------------------------------

    /** A zip handed in from outside, waiting for the bulk import to be shown for it. */
    var downloadWaiting by mutableStateOf<File?>(null)

    /** Files handed in from outside, waiting for the person to say what each is. */
    var incoming by mutableStateOf<List<File>?>(null)

    /**
     * Files handed to InkSheets from outside - shared from another app, picked, dropped on the
     * window. A zip opens the bulk import; music and recordings are shown for the person to say
     * what they are ([IncomingDialog]). Any thread.
     */
    fun offer(files: List<File>) {
        val zips = files.filter { it.extension.equals("zip", ignoreCase = true) }
        val music = files.filter { f ->
            val ext = f.extension.lowercase()
            ext in com.inksheets.core.LibraryScan.MUSIC || ext in com.inksheets.core.LibraryScan.SOUND
        }
        platform.onMain {
            zips.firstOrNull()?.let { downloadWaiting = it }
            if (music.isNotEmpty()) incoming = music
        }
    }

    /**
     * Bring [files] in as the person decided ([fates], one each): copied into the music folder's
     * Inbox, and made new songs, parts of songs already here, or new editions of parts; with
     * [setlistId], the songs are added to that setlist too. Off the UI thread.
     */
    internal fun takeIn(files: List<File>, fates: List<Fate>, setlistId: String? = null) {
        val base = root ?: return
        val lib = library ?: return
        val inbox = File(base, "Inbox")
        val made = HashMap<String, String>()
        val touched = ArrayList<String>()
        importing++
        try {
            files.zip(fates).forEach { (f, fate) ->
                when (fate) {
                    Fate.Skip -> Unit
                    is Fate.Replace -> {
                        val part = lib.song(fate.songId)?.parts?.firstOrNull { it.id == fate.partId } ?: return@forEach
                        runCatching { f.copyTo(File(base, part.file), overwrite = true) }
                            .onFailure { platform.log("Could not replace ${part.file}: ${it.message}") }
                        touched += fate.songId
                    }
                    is Fate.NewSong, is Fate.AddTo -> {
                        inbox.mkdirs()
                        var target = File(inbox, f.name)
                        var i = 2
                        while (target.exists()) target = File(inbox, f.nameWithoutExtension + " (" + i++ + ")." + f.extension)
                        if (runCatching { f.copyTo(target) }.isFailure) return@forEach
                        val rel = relative(target) ?: return@forEach
                        val songId = when (fate) {
                            is Fate.AddTo -> fate.songId
                            is Fate.NewSong -> {
                                val key = com.inksheets.core.Library.matchKey(fate.title)
                                made[key] ?: run {
                                    // A new song, even beside one of the same name already here.
                                    val clash = lib.songs.any { com.inksheets.core.Library.matchKey(it.title) == key }
                                    val song = if (clash) lib.addSong(fate.title, emptyList()) { apart = true } else lib.ensureSong(fate.title)
                                    made[key] = song.id
                                    song.id
                                }
                            }
                            else -> return@forEach
                        }
                        if (target.extension.lowercase() in com.inksheets.core.LibraryScan.SOUND) {
                            val song = lib.song(songId) ?: return@forEach
                            lib.editSong(songId) { audio = song.audio + com.inksheets.core.AudioTrack(file = rel) }
                        } else {
                            val planned = com.inksheets.core.ImportPlan.readPart(rel)
                            lib.writePart(songId, planned.toPart().copy(id = com.inksheets.core.Library.partIdFor(rel)))
                        }
                        touched += songId
                    }
                }
            }
            if (setlistId != null) {
                val list = lib.setlist(setlistId)
                if (list != null) {
                    val have = list.entries.map { it.songId }.toSet()
                    val more = touched.distinct().filter { it !in have }.map { com.inksheets.core.SetlistEntry(songId = it) }
                    if (more.isNotEmpty()) lib.editSetlist(setlistId) { entries = list.entries + more }
                }
            }
            platform.log("Took in ${files.size} files")
        } finally {
            importing--
        }
        platform.onMain { version = lib.version }
        if (touched.isNotEmpty()) runCatching { readUnknownParts() }
    }

    /** Parts whose file is not on this device. */
    fun missingParts(): List<Pair<com.inksheets.core.Song, com.inksheets.core.Part>> =
        runCatching { scanner()?.missing() }.getOrNull().orEmpty()

    /** Remove every part whose file is not on this device - the person asked. */
    fun removeMissing(): Int {
        val n = runCatching { scanner()?.removeMissing() }.getOrNull() ?: 0
        platform.onMain { version = library?.version ?: version }
        return n
    }

    /** Make a change and let the screens know. */
    fun change(block: Library.() -> Unit) {
        val lib = library ?: return
        lib.block()
        version = lib.version
    }

    /** The song one of whose parts is the file at [path]. */
    fun songAt(path: String): com.inksheets.core.Song? {
        val rel = relative(File(path)) ?: return null
        return library?.songs?.firstOrNull { s -> s.parts.any { it.file == rel } }
    }

    // ---- markings from MobileSheets ---------------------------------------------------

    @Volatile private var importedMarks: Map<String, List<com.inksheets.core.ImportedMark>>? = null
    @Volatile private var importedStamp = -1L

    /** The kept markings changed (an import just ran): read them again when next wanted. */
    fun importedMarksChanged() { importedMarks = null }

    /**
     * The markings brought across for the file at [path] - found by its place in the library, or,
     * for a file that has since moved, by its name when only one file has it.
     */
    fun importedMarksFor(path: String): List<com.inksheets.core.ImportedMark> {
        val base = root ?: return emptyList()
        val kept = com.inksheets.core.MobileSheetsMarks.fileIn(base)
        val stamp = kept.lastModified()
        if (stamp == 0L) return emptyList()
        val all = importedMarks?.takeIf { importedStamp == stamp }
            ?: com.inksheets.core.MobileSheetsMarks.load(base).also { importedMarks = it; importedStamp = stamp }
        val rel = relative(File(path)) ?: return emptyList()
        all[rel]?.let { return it }
        val name = rel.substringAfterLast('/')
        return all.entries.filter { it.key.substringAfterLast('/').equals(name, ignoreCase = true) }.singleOrNull()?.value.orEmpty()
    }

    // ---- files that have moved ------------------------------------------------------

    /** Every file in the music folder by name, for finding ones that moved; built when needed. */
    @Volatile
    private var byName: Map<String, List<File>>? = null

    private fun filesByName(fresh: Boolean = false): Map<String, List<File>> {
        if (!fresh) byName?.let { return it }
        val base = root ?: return emptyMap()
        return base.walkTopDown().onEnter { !it.name.startsWith(".") }
            .filter { it.isFile }
            .groupBy { it.name.lowercase() }
            .also { byName = it }
    }

    /** A moved file, found by its name - only when exactly one file in the music folder has it. */
    private fun findMoved(relative: String, fresh: Boolean): File? =
        filesByName(fresh)[relative.substringAfterLast('/').lowercase()]?.singleOrNull()

    /**
     * The file for [part] of [song] on this device. Where a file is not where the library says -
     * moved into another folder, say - it is found by name and the library corrected, so every
     * device follows the move.
     */
    fun partFile(song: com.inksheets.core.Song, part: com.inksheets.core.Part): File? {
        fileOf(part.file)?.takeIf { it.isFile }?.let { return it }
        val found = findMoved(part.file, fresh = false)?.takeIf { it.isFile } ?: findMoved(part.file, fresh = true) ?: return null
        val rel = relative(found) ?: return found
        val latest = library?.song(song.id) ?: return found
        change { editSong(song.id) { parts = latest.parts.map { if (it.id == part.id) it.copy(file = rel) else it } } }
        return found
    }

    /**
     * Correct every part and recording whose file has moved within the music folder. Run in the
     * background when the library opens; a no-op when nothing has moved.
     */
    fun relinkMoved(): Int {
        val lib = library ?: return 0
        var fixed = 0
        var index: Map<String, List<File>>? = null
        for (song in lib.songs) {
            val missingParts = song.parts.filter { fileOf(it.file)?.isFile != true }
            val missingAudio = song.audio.filter { fileOf(it.file)?.isFile != true }
            if (missingParts.isEmpty() && missingAudio.isEmpty()) continue
            val names = index ?: filesByName(fresh = true).also { index = it }
            fun moved(path: String): String? =
                names[path.substringAfterLast('/').lowercase()]?.singleOrNull()?.let { relative(it) }
            val parts = song.parts.map { p -> if (p in missingParts) moved(p.file)?.let { p.copy(file = it) } ?: p else p }
            val audio = song.audio.map { a -> if (a in missingAudio) moved(a.file)?.let { a.copy(file = it) } ?: a else a }
            if (parts != song.parts || audio != song.audio) {
                fixed += parts.zip(song.parts).count { (a, b) -> a != b } + audio.zip(song.audio).count { (a, b) -> a != b }
                change {
                    editSong(song.id) {
                        if (parts != song.parts) this.parts = parts
                        if (audio != song.audio) this.audio = audio
                    }
                }
            }
        }
        return fixed
    }

    /** A library-relative path turned into the file on this device. */
    fun fileOf(relative: String): File? = root?.let { File(it, relative) }

    /** A file on this device as the library records it: relative, forward slashes. */
    fun relative(file: File): String? {
        val base = root?.canonicalFile ?: return null
        val canonical = file.canonicalFile
        if (!canonical.path.startsWith(base.path)) return null
        return canonical.path.removePrefix(base.path).trimStart(File.separatorChar).replace(File.separatorChar, '/')
    }

    companion object {
        private const val K_LIBRARY = "sheets_library"
        private const val K_PROFILE = "sheets_profile"
        private const val K_PICKS = "sheets_part_picks"
        private const val K_TRIED = "sheets_ocr_tried"
        private const val K_EDGE_TAPS = "sheets_edge_taps"
        private const val K_TURN = "sheets_turn_style"
        private const val K_STRIP_LABELS = "sheets_strip_labels"
        private const val K_STRIP_LEFT = "sheets_strip_left"
        private const val K_STRIP = "sheets_strip"

        /**
         * Page turns, then the pen tools - a rehearsal note goes on in one tap and the pen is
         * back in one more - then songs, the metronome and tuner, and fullscreen.
         */
        val DEFAULT_STRIP = listOf(
            com.inkslate.core.PerformAction.PREVIOUS_PAGE,
            com.inkslate.core.PerformAction.NEXT_PAGE,
            com.inkslate.core.PerformAction.PEN,
            com.inkslate.core.PerformAction.HIGHLIGHTER,
            com.inkslate.core.PerformAction.ERASER,
            com.inkslate.core.PerformAction.UNDO,
            com.inkslate.core.PerformAction.PREVIOUS_SONG,
            com.inkslate.core.PerformAction.NEXT_SONG,
            com.inkslate.core.PerformAction.PLAY_AUDIO,
            com.inkslate.core.PerformAction.METRONOME,
            com.inkslate.core.PerformAction.TUNER,
            com.inkslate.core.PerformAction.FULLSCREEN
        )
    }
}
