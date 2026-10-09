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
 *
 * [openLater]: the library last used is read in off the UI thread, Home showing it is on its way
 * (the desktop app, whose first frame waited on it); otherwise read in as this is made.
 */
class SheetsState(val platform: SheetsPlatform, openLater: Boolean = false) {

    /** The thread watching the library; declared first, as [open] runs during construction. */
    @Volatile private var watcher: Thread? = null

    var root by mutableStateOf<File?>(null)
        private set

    var library by mutableStateOf<Library?>(null)
        private set

    /** The library being read in at start, before Home has it to show (see [openInBackground]). */
    var opening by mutableStateOf(false)
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
        get() = oneOff?.let { id -> InstrumentProfile(ONE_OFF, (Instruments.byId[id]?.name ?: id) + " (for now)", listOf(id)) }
            ?: profiles.firstOrNull { it.id == profileId }

    /**
     * An instrument picked for now only, from every instrument there is - to read the tuba part
     * once without adding a tuba to the instruments you play. Not remembered past this session,
     * and gone as soon as one of your own is chosen again.
     */
    var oneOff by mutableStateOf<String?>(null)
        private set

    /** The bookmark on the page in front, if it has one. */
    fun bookmarkHere(): com.inksheets.core.Bookmark? {
        version   // read, so the Bookmark button lights up and goes out as soon as it is pressed
        val song = current?.let { s -> library?.song(s.id) } ?: return null
        val part = partShown() ?: return null
        val page = pageShown.first + 1
        return song.bookmarks.firstOrNull { it.part == part.id && it.page == page }
    }

    /**
     * Bookmark the page in front, or take its bookmark off: the song goes under Bookmarks on Home
     * and opens there at this page, in this part. Kept with the song, so every device has it.
     */
    fun toggleBookmark(): Boolean {
        val song = current?.let { s -> library?.song(s.id) } ?: return false
        val part = partShown() ?: return false
        val page = pageShown.first + 1
        val here = song.bookmarks.firstOrNull { it.part == part.id && it.page == page }
        val next = if (here != null) song.bookmarks - here
            else song.bookmarks + com.inksheets.core.Bookmark(label = "Page $page", part = part.id, page = page, at = System.currentTimeMillis())
        change { editSong(song.id) { bookmarks = next.sortedBy { it.page } } }
        return true
    }

    /** Open [song] at [mark]: the whole song, in the bookmarked part, at its page. */
    fun openBookmark(song: com.inksheets.core.Song, mark: com.inksheets.core.Bookmark) {
        val part = song.parts.firstOrNull { it.id == mark.part } ?: partFor(song) ?: return
        val file = partFile(song, part) ?: return
        stopPlaying()
        current = song
        noteOpened(song)
        com.inkslate.core.Perform.requestPage(file.absolutePath, mark.page - 1)
        platform.openPart(song, part, file)
        companion.pageTurned(mark.page - 1)
    }

    /** Take [mark] off [song]. */
    fun removeBookmark(song: com.inksheets.core.Song, mark: com.inksheets.core.Bookmark) =
        change { editSong(song.id) { bookmarks = song.bookmarks.filterNot { it.samePlace(mark) } } }

    /** Colour [mark] in the Bookmarks list; null for the song's own colour. */
    fun setBookmarkColour(songId: String, mark: com.inksheets.core.Bookmark, color: Int?) = change {
        val song = song(songId) ?: return@change
        editSong(songId) { bookmarks = song.bookmarks.map { if (it.samePlace(mark)) it.copy(color = color) else it } }
    }

    /** The Bookmarks list put in this order by hand: each bookmark given its place, on every device. */
    fun orderBookmarks(order: List<Pair<String, com.inksheets.core.Bookmark>>) = change {
        val rank = HashMap<String, MutableList<Pair<com.inksheets.core.Bookmark, Double>>>()
        order.forEachIndexed { i, (songId, mark) -> rank.getOrPut(songId) { ArrayList() } += mark to i.toDouble() }
        for ((songId, marks) in rank) {
            val song = song(songId) ?: continue
            val next = song.bookmarks.map { b -> marks.firstOrNull { it.first.samePlace(b) }?.let { b.copy(rank = it.second) } ?: b }
            if (next != song.bookmarks) editSong(songId) { bookmarks = next }
        }
    }

    /** How the Bookmarks tab is put in order. Remembered on this device. */
    var bookmarkSort: BookmarkSort
        get() = bookmarkSortState
        set(v) { bookmarkSortState = v; platform.setPref(K_BOOKMARK_SORT, v.name) }
    private var bookmarkSortState by mutableStateOf(BookmarkSort.entries.firstOrNull { it.name == platform.pref(K_BOOKMARK_SORT) } ?: BookmarkSort.MANUAL)

    /** A song's notes: what you want to remember about it, shown beside its name in lists. Synced, private. */
    fun setNotes(songId: String, text: String) = change { editSong(songId) { notes = text.trim().ifEmpty { null } } }

    /** The song whose notes are being written, from a list or from the strip. */
    var notesFor by mutableStateOf<com.inksheets.core.Song?>(null)

    // ---- the leader's one-tap messages ---------------------------------------------

    /** Messages a leader sends in one tap, from the strip and from a remote. Kept on this device. */
    var presets by mutableStateOf(loadPresets())
        private set

    private fun loadPresets(): List<com.inksheets.core.MessagePreset> =
        platform.pref(K_PRESETS)?.let { saved ->
            runCatching { PRESET_JSON.decodeFromString(PRESET_LIST, saved) }.getOrNull()
        } ?: com.inksheets.core.MessagePreset.DEFAULTS

    fun savePresets(list: List<com.inksheets.core.MessagePreset>) {
        presets = list
        platform.setPref(K_PRESETS, PRESET_JSON.encodeToString(PRESET_LIST, list))
    }

    /** Send [preset] to the band; false when not leading. */
    fun sendPreset(preset: com.inksheets.core.MessagePreset): Boolean {
        if (!companion.leading) return false
        companion.sendNote(preset.text, preset.instruments, preset.urgent, preset.color)
        return true
    }

    // ---- the strip, and a tap on the middle of the page -------------------------------

    // One page colour (Night, Sepia...) for every song, remembered - whichever menu set it.
    init {
        platform.pref(K_PAGE_COLOUR)?.let { n -> com.inkslate.core.ReadingMode.entries.firstOrNull { it.name == n } }
            ?.let { platform.readingMode = it }
    }

    /** Keep the page colour for next time (ActionStrip watches it change). */
    fun keepReadingMode(mode: com.inkslate.core.ReadingMode) {
        if (platform.pref(K_PAGE_COLOUR) != mode.name) platform.setPref(K_PAGE_COLOUR, mode.name)
    }

    /** The strip folded down to its one button in the corner. Remembered. */
    var stripCollapsed: Boolean
        get() = stripCollapsedState
        set(v) {
            stripCollapsedState = v
            platform.setPref(K_COLLAPSED, v.toString())
            platform.setStripLane(!v)
        }
    // Folded until first asked for: the music is the whole screen, its corner button the way in.
    private var stripCollapsedState by mutableStateOf(platform.pref(K_COLLAPSED)?.let { it == "true" } ?: true)

    /**
     * A tap on the middle third of the page. With everything put away it brings up the strip -
     * and near the bottom, the tools as well - leaving the page where it is. With anything up,
     * it puts it all away and fits the page back to the screen; except that a tap near the bottom
     * with only the strip up brings the tools up too.
     */
    fun centreTap(bottom: Boolean): Boolean {
        if (homeInFront) return false
        val fullscreen = com.inkslate.core.PerformAction.FULLSCREEN
        val toolsShown = !com.inkslate.core.Perform.on(fullscreen)
        when {
            bottom && !toolsShown -> {
                if (stripCollapsed) stripCollapsed = false
                com.inkslate.core.Perform.run(fullscreen)
            }
            toolsShown || !stripCollapsed -> {
                stripCollapsed = true
                if (toolsShown) com.inkslate.core.Perform.run(fullscreen)
                com.inkslate.core.Perform.recentre?.invoke()
            }
            else -> stripCollapsed = false
        }
        return true
    }

    /** Writing a reminder for the song in front; and the song whose reminder is up. */
    var writingReminder by mutableStateOf(false)
    var reminderShown by mutableStateOf<String?>(null)

    /** Showing the list of every instrument, to pick one for now. */
    var pickingOneOff by mutableStateOf(false)

    /** Whether the song in front is shown again with the pick - from the Part button, not Home. */
    var oneOffReshows = false

    /** Show [instrumentId]'s parts, for now, in every song - the one in front changing at once. */
    fun chooseOneOff(instrumentId: String) {
        oneOff = instrumentId
        savePicks(emptyMap())
        // From Home, only which parts are listed changes; nothing opens.
        if (oneOffReshows) current?.let { showAgain(it) }
    }

    /** The tuner and metronome panels, which a pedal or a button over the page can open. */
    var tunerOpen by mutableStateOf(false)

    /** The song opened last - what the strip's recording button plays. */
    var current by mutableStateOf<com.inksheets.core.Song?>(null)

    /** The file of the part in front, as the editor last reported it. */
    var currentPath by mutableStateOf<String?>(null)
        internal set

    /** Whether Home is what is on screen, rather than a song. Set by the workspace. */
    var homeInFront by mutableStateOf(true)

    /** Leading or following other tablets. */
    val companion = Companion(this)

    /** Remotes: this device controlled by others, and this device as a remote for another. */
    val remote = RemoteControl(this)

    /** Pedals, switches and faders plugged into this device, and what they do. */
    val controllers = ControllerHub(this)

    /** A watch on the wrist: flick it to turn the page (experimental). */
    val watch = WatchFlicks(this)
    var companionOpen by mutableStateOf(false)

    /** The recordings panel, for the song opened last. */
    var audioOpen by mutableStateOf(false)
    var metronomeOpen by mutableStateOf(false)

    /** The window [action]'s button opens is open now: its button is lit, and closes it. */
    fun windowOpen(action: com.inkslate.core.PerformAction): Boolean = when (action) {
        com.inkslate.core.PerformAction.TUNER -> tunerOpen
        com.inkslate.core.PerformAction.RECORDINGS -> audioOpen
        com.inkslate.core.PerformAction.PLAY_TOGETHER -> companionOpen
        com.inkslate.core.PerformAction.SWITCH_PART -> partPicker
        else -> false
    }

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
            ?.let { saved ->
                // Recordings, the tuner, Play together and switching part were tucked into More
                // for a while and asked back onto the strip: added once to a strip saved then.
                if (platform.pref(K_STRIP_RETURNED) == "true") saved
                else (saved.filter { it != com.inkslate.core.PerformAction.FULLSCREEN } + RETURNED.filter { it !in saved } +
                    saved.filter { it == com.inkslate.core.PerformAction.FULLSCREEN }).also {
                    platform.setPref(K_STRIP_RETURNED, "true")
                    platform.setPref(K_STRIP, it.joinToString(",") { a -> a.name })
                }
            }
            ?.let { saved ->
                if (platform.pref(K_STRIP_ADDED) == "true") saved
                else (saved.filter { it != com.inkslate.core.PerformAction.FULLSCREEN } + ADDED_LATER.filter { it !in saved } +
                    saved.filter { it == com.inkslate.core.PerformAction.FULLSCREEN }).also {
                    platform.setPref(K_STRIP_ADDED, "true")
                    platform.setPref(K_STRIP, it.joinToString(",") { a -> a.name })
                }
            }
            ?: DEFAULT_STRIP
    )
        private set

    /** The part picker, opened from the strip's Part button or a pedal. */
    var partPicker by mutableStateOf(false)

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
        controllers.start()
        platform.pref(K_LIBRARY)?.let(::File)?.takeIf { it.isDirectory }?.let { if (openLater) openInBackground(it) else open(it) }
        // Song turns and the metronome from a pedal, whatever screen is in front.
        com.inkslate.core.Perform.app = { action ->
            when (action) {
                com.inkslate.core.PerformAction.NEXT_SONG -> step(1)
                com.inkslate.core.PerformAction.PREVIOUS_SONG -> step(-1)
                com.inkslate.core.PerformAction.METRONOME -> { toggleMetronome(); true }
                // A button for a window opens it, and closes it again.
                com.inkslate.core.PerformAction.TUNER -> { tunerOpen = !tunerOpen; true }
                com.inkslate.core.PerformAction.PLAY_AUDIO -> { Recording.toggle(this); true }
                com.inkslate.core.PerformAction.RECORDINGS -> { if (current != null) audioOpen = !audioOpen; current != null }
                com.inkslate.core.PerformAction.PLAY_TOGETHER -> { companionOpen = !companionOpen; true }
                com.inkslate.core.PerformAction.SWITCH_PART -> { if (current != null) partPicker = !partPicker; current != null }
                com.inkslate.core.PerformAction.BOOKMARK -> toggleBookmark()
                else -> false
            }
        }
        com.inkslate.core.Perform.centreTap = { bottom -> centreTap(bottom) }
        ScoreTools.install(this)
        // Whichever song is in front is "the song": the one the play button plays and the one a
        // leading tablet tells its followers about.
        com.inkslate.core.Perform.onPosition = { page, count ->
            pageShown = page to count
            companion.applyPendingInk()
        }
        // Markings brought across from MobileSheets, handed to a part when it is opened.
        com.inkslate.core.Perform.importedInk = { path, pageSize -> ImportedInk.strokes(importedMarksFor(path), pageSize) }
        com.inkslate.core.Perform.onPage = { path, page ->
            Listener.pageChanged(this, path, page)
            // A whole read of this part cut off part way (the app closed) carries on.
            if (currentPath != path) Transcriber.resume(this, File(path))
            currentPath = path
            if (pagesWanted == path) { pagesWanted = null; com.inkslate.core.Perform.openPages?.invoke() }
            songAt(path)?.let { song ->
                if (current?.id != song.id) current = song
                followInSet(song)
            }
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
                // Every song of a set opens at its start - not wherever it was last read, which put
                // a song turn on page 3 of a part last left there.
                com.inkslate.core.Perform.requestPage(file.absolutePath, (part.firstPage ?: 1) - 1)
                tabs += file to s.title
            } else if (i == index) {
                focus = tabs.indexOfFirst { it.first == file }
            }
        }
        // Opened to play, for "Recently opened" - once per set, not on every song turned to.
        if (playing?.first != setlistId) background { lib.markSetlistOpened(setlistId) }
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

    /** How the setlists are listed, and how a setlist's songs are shown; kept on this device. */
    var setlistSort: SetlistSort
        get() = setlistSortState
        set(v) { setlistSortState = v; platform.setPref(K_SETLIST_SORT, v.name) }
    private var setlistSortState by mutableStateOf(SetlistSort.entries.firstOrNull { it.name == platform.pref(K_SETLIST_SORT) } ?: SetlistSort.AZ)

    var entrySort: EntrySort
        get() = entrySortState
        set(v) { entrySortState = v; platform.setPref(K_ENTRY_SORT, v.name) }
    private var entrySortState by mutableStateOf(EntrySort.entries.firstOrNull { it.name == platform.pref(K_ENTRY_SORT) } ?: EntrySort.SET)

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

    private var listenTurnsState by mutableStateOf(platform.pref(K_LISTEN) == "true")

    /** Experimental: the Listen button, which follows a recording by ear and turns the pages. */
    var listenTurns: Boolean
        get() = listenTurnsState
        set(on) {
            listenTurnsState = on
            platform.setPref(K_LISTEN, on.toString())
            if (!on) { Listener.stop(this); TempoFollow.stop(this) }
        }

    private var readMusicState by mutableStateOf(platform.pref(K_READ_MUSIC) == "true")

    /** Experimental: reading a part's notes off its pages - checked, redrawn, exported as MIDI, and turning pages by them. */
    var readMusic: Boolean
        get() = readMusicState
        set(on) { readMusicState = on; platform.setPref(K_READ_MUSIC, on.toString()) }

    /** The music-reading panel is up. */
    var readMusicOpen by mutableStateOf(false)

    /** Show [file]'s notes as read - reading them first if this device has not yet. */
    fun openReadMusic(file: File) {
        val known = Transcriber.cached(this, file)
        if (known != null) Transcriber.shown = file to known
        else Transcriber.read(this, file) { }
        readMusicOpen = true
    }

    /** The files of the open tabs, as last told. */
    var openFiles: List<File> = emptyList()
        private set

    /** Whether one of [songId]'s parts is open in a tab. */
    fun hasTab(songId: String): Boolean = openFiles.any { songAt(it.absolutePath)?.id == songId }

    /**
     * A tab opened or closed. A recording plays on whatever is in front - Home, another song -
     * until the tab of the song it belongs to is closed: then it stops, and playback mode ends.
     */
    fun openTabs(files: List<File>) {
        openFiles = files
        val id = Recording.songId ?: return
        if (!Recording.session) return
        if (hasTab(id)) Recording.hadTab = true
        else if (Recording.hadTab) Recording.end(this)
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
        // Written to disk off the screen's thread: it happens on every turn to a song, and a
        // turn must not wait for a file to be appended to.
        val lib = library ?: return
        background { lib.markOpened(song.id) }
    }

    /** A small library write, on a thread of its own, the screens told when it is done. */
    private fun background(write: () -> Unit) {
        Thread {
            runCatching(write)
            platform.onMain { library?.let { version = it.version } }
        }.apply { isDaemon = true; name = "library-write" }.start()
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
        // The set's songs are open as tabs already: turn to the next one, rather than working out
        // every song's part and file again and handing the whole set over anew - which is what
        // made each turn to another song stop the screen.
        val lib = library ?: return false
        val entry = lib.setlist(setlistId)?.entries?.getOrNull(next)
        val song = entry?.let { lib.song(it.songId) }
        val part = song?.let { partFor(it) }
        val file = song?.let { s -> part?.let { partFile(s, it) } }
        // A song turned to starts at its start, whatever page its tab was left on.
        if (file != null) com.inkslate.core.Perform.requestPage(file.absolutePath, (part?.firstPage ?: 1) - 1)
        if (entry != null && song != null && file != null && platform.focusSetTab(file)) {
            playing = setlistId to next
            frontEntry = entry.id
            current = song
            noteOpened(song)
            companion.pageTurned(0)
            return true
        }
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
        // Each part tried is noted with its file's size: a file still arriving, or replaced,
        // is tried again once it has changed.
        val tried = platform.pref(K_TRIED).orEmpty().split(',').filter { it.isNotEmpty() }.toMutableSet()
        fun key(part: com.inksheets.core.Part, file: File?) = part.id + ":" + (file?.length() ?: 0L)
        val todo = lib.songs.flatMap { song ->
            song.parts.filter { it.instrument == null }.map { song to it }
        }.filter { (song, part) -> key(part, partFile(song, part)) !in tried && part.id !in tried }
        var unsaved = 0
        todo.forEachIndexed { i, (song, part) ->
            onProgress(i, todo.size)
            val file = partFile(song, part) ?: return@forEachIndexed
            if (!file.isFile || file.length() == 0L) return@forEachIndexed
            // The page's own words where it has them; a scan read where it does not.
            val text = runCatching { platform.pageText(file, part.firstPage ?: 1) }.getOrNull()
                ?.takeIf { com.inksheets.core.InstrumentReader.read(it) != null }
                ?: if (platform.canRecognise) runCatching { platform.recognise(file, part.firstPage ?: 1) }.getOrNull() else null
            val match = text?.let { com.inksheets.core.InstrumentReader.read(it) }
            if (match != null) {
                // Read the part again at the moment of writing: it may have been set meanwhile.
                change {
                    val current = song(song.id)?.parts?.firstOrNull { it.id == part.id }
                    if (current != null && current.instrument == null) writePart(song.id, current.copy(
                        instrument = match.instrument.id,
                        source = com.inksheets.core.InstrumentSource.OCR,
                        label = match.label
                    ))
                }
            }
            tried.remove(part.id)
            tried += key(part, file)
            if (++unsaved >= 25) { platform.setPref(K_TRIED, tried.joinToString(",")); unsaved = 0 }
        }
        if (unsaved > 0) platform.setPref(K_TRIED, tried.joinToString(","))
        onProgress(todo.size, todo.size)
    }

    /**
     * Read the tempo off the first page of every song that has none - a metronome mark, or a
     * tempo word and the middle of its range. Songs read before are not read again, unless
     * [again], which also reads again every tempo that was read rather than set. A tempo a person
     * set is never touched. Off the UI thread.
     */
    fun readTempos(again: Boolean = false) {
        val lib = library ?: return
        val tried = if (again) mutableSetOf() else platform.pref(K_TEMPO_TRIED).orEmpty().split(',').filter { it.isNotEmpty() }.toMutableSet()
        val todo = lib.songs.filter { s -> (s.tempo == null || (again && s.tempoRead)) && s.id !in tried && s.parts.isNotEmpty() }
        for (song in todo) {
            val part = partFor(song) ?: song.parts.first()
            val file = partFile(song, part)
            if (file != null && file.isFile) {
                val page = part.firstPage ?: 1
                val reading = runCatching { platform.pageText(file, page) }.getOrNull()?.let { com.inksheets.core.TempoReader.read(it) }
                    ?: if (platform.canRecognise) runCatching { platform.recognise(file, page) }.getOrNull()?.let { com.inksheets.core.TempoReader.read(it) } else null
                if (reading != null) {
                    platform.onMain {
                        val now = lib.song(song.id)
                        // Set by a person in the meantime: theirs stands.
                        if (now != null && (now.tempo == null || now.tempoRead)) change {
                            editSong(song.id) {
                                tempo = reading.bpm
                                tempoMark = reading.mark
                                tempoRead = true
                            }
                        }
                    }
                }
            }
            tried += song.id
        }
        platform.setPref(K_TEMPO_TRIED, tried.joinToString(","))
    }

    fun toggleMetronome() = Click.toggle(this)

    fun open(folder: File) {
        val lib = runCatching { Library(LibraryLog(folder, platform.deviceId)) }.getOrNull() ?: return
        adopt(folder, lib)
    }

    /**
     * The library last used, read in off the UI thread: its log is megabytes, half a second and
     * more on a laptop, and the first frame waited on it.
     */
    private fun openInBackground(folder: File) {
        opening = true
        Thread({
            val lib = runCatching { Library(LibraryLog(folder, platform.deviceId)).also { it.songs; it.instruments() } }.getOrNull()
            platform.onMain {
                opening = false
                if (lib != null && library == null) adopt(folder, lib)
            }
        }, "library-open").apply { isDaemon = true; start() }
    }

    private fun adopt(folder: File, lib: Library) {
        root = folder
        library = lib
        platform.setPref(K_LIBRARY, folder.absolutePath)
        runCatching { com.inksheets.core.Instruments.use(lib.instruments()) }
        version = lib.version
        runCatching { lib.noteDevice(platform.deviceName) }
        startWatching()
        // Songs already here get a tempo read for them once, in the background.
        Thread({ runCatching { readTempos() } }, "tempos").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
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
                    if (report?.added?.isNotEmpty() == true) { runCatching { readUnknownParts() }; runCatching { readTempos() } }
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
        oneOff = null
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

    /**
     * Make [part] the one [song] opens on this device - or, when it is the one the instrument
     * would open anyway, go back to that. Chosen straight from the song's list of parts.
     */
    fun pickPart(song: com.inksheets.core.Song, part: com.inksheets.core.Part) {
        val natural = com.inksheets.core.PartChoice.partFor(song, profile)
        savePicks(if (natural?.id == part.id) partPicks - song.id else partPicks + (song.id to part.id))
    }

    /** The song in front back to the instrument's part. */
    fun clearThisSong() {
        val song = current ?: return
        savePicks(partPicks - song.id)
        showAgain(song)
    }

    /** Play [profileId]'s parts in every song, from now on - picks made for single songs go. */
    fun switchAllSongs(profileId: String?) {
        chooseProfile(profileId)
        savePicks(emptyMap())
        current?.let { showAgain(it) }
    }

    /** Show [song] again with the part it now gets: the whole set rebuilt, or the one tab swapped. */
    private fun showAgain(song: com.inksheets.core.Song) {
        val (setlistId, index) = playing ?: run {
            val part = partFor(song) ?: return
            val file = partFile(song, part) ?: return
            com.inkslate.core.Perform.requestPage(file.absolutePath, (part.firstPage ?: 1) - 1)
            val old = currentPath?.let(::File)
            if (old != null && old.absolutePath != file.absolutePath) platform.swapPart(old, file) else platform.openPart(song, part, file)
            companion.pageTurned((part.firstPage ?: 1) - 1)
            return
        }
        // Where the song in front is in the set now - reached by a swipe or a tab as well as by
        // Next song - not where the last Next song left it, which reopened an old song.
        val at = placeInSet(setlistId, song.id, index)
        playSetlist(setlistId, at)
    }

    /**
     * Keep the set's place on the song in front, however it got there: a swipe past the last
     * page and a tap on a tab move to another song without going through [step].
     */
    private fun followInSet(song: com.inksheets.core.Song) {
        val (setlistId, index) = playing ?: return
        val at = placeInSet(setlistId, song.id, index)
        if (at != index) {
            playing = setlistId to at
            frontEntry = library?.setlist(setlistId)?.entries?.getOrNull(at)?.id
        }
    }

    /** The place of [songId] in the set nearest [near] (a song can be in a set twice); [near] if absent. */
    private fun placeInSet(setlistId: String, songId: String, near: Int): Int {
        val entries = library?.setlist(setlistId)?.entries ?: return near
        return entries.indices.filter { entries[it].songId == songId }.minByOrNull { kotlin.math.abs(it - near) } ?: near
    }

    /** Take in edits from other devices. Called off the UI thread on a timer. */
    fun refresh() {
        val lib = library ?: return
        if (lib.refresh()) {
            runCatching { com.inksheets.core.Instruments.use(lib.instruments()) }
            version = lib.version
        }
    }

    // ---- keeping the library in step with the folder ------------------------------------

    /** What the last look at the music folder found and did. */
    var lastScan by mutableStateOf<com.inksheets.core.LibraryScan.Report?>(null)
        private set

    /** Recent things the folder scan did, newest first, for Library health. This device's only. */
    val scanHistory = androidx.compose.runtime.mutableStateListOf<String>()

    /** Imports in progress: the scan waits, so it does not file half-copied music its own way. */
    @Volatile var importing = 0

    /** One scan at a time: Home, the watcher, "Check now" and Library health all start them. */
    private val scanLock = Any()
    private var purgedAt = 0L

    private fun scanner(): com.inksheets.core.LibraryScan? {
        val base = root ?: return null
        val lib = library ?: return null
        val name = "scan-" + Integer.toHexString(base.absolutePath.hashCode()) + ".json"
        return com.inksheets.core.LibraryScan(base, lib, File(platform.localFolder, name), pages = { f -> runCatching { platform.pageCount(f) }.getOrNull() })
    }

    /**
     * Look at the music folder and bring the library into line: new files added, moved ones
     * followed, deleted ones removed, doubles put together. Slow-ish: off the UI thread.
     * [allowMassRemoval] is the person confirming a removal that was held back.
     */
    fun scanFolder(allowMassRemoval: Boolean = false): com.inksheets.core.LibraryScan.Report? = synchronized(scanLock) {
        if (importing > 0) return null
        // Old trash cleared now and then - not on every scan, which read every entry each time.
        if (System.currentTimeMillis() - purgedAt > 3_600_000L) { purgedAt = System.currentTimeMillis(); runCatching { trash()?.purge() } }
        val report = runCatching { scanner()?.run(allowMassRemoval) }
            .onFailure { platform.log("Library scan failed: ${it.message}") }
            .getOrNull() ?: return null
        root?.let { base -> byName = report.onDisk.groupBy { it.substringAfterLast('/').lowercase() }.mapValues { (_, l) -> l.map { File(base, it) } } }
        platform.onMain {
            lastScan = report
            if (report.changed) {
                val stamp = java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                val lines = report.added.map { "Added $it" } + report.moved.map { (a, b) -> "Followed $a to $b" } +
                    report.removed.map { "Removed $it" } + report.merged.map { "Put together $it" } + report.sorted.map { "Sorted: $it" }
                lines.forEach { scanHistory.add(0, "$stamp  $it") }
                while (scanHistory.size > 200) scanHistory.removeAt(scanHistory.lastIndex)
                version = library?.version ?: version
            }
        }
        if (report.changed) {
            platform.log("Library scan: ${report.added.size} added, ${report.moved.size} moved, ${report.removed.size} removed, ${report.merged.size + report.sorted.size} put together")
        }
        if (report.heldBack > 0) platform.log("Library scan: held back removing ${report.heldBack} parts - too many at once")
        return report
    }

    // ---- removing, merging, splitting ---------------------------------------------------

    fun trash(): com.inksheets.core.LibraryTrash? = root?.let { r -> library?.let { com.inksheets.core.LibraryTrash(r, it) } }

    /** Take a song out of the library: its files go to the library's Trash for 30 days. */
    fun removeSong(song: com.inksheets.core.Song) {
        importing++
        try {
            runCatching { trash()?.remove(song) }
                .onSuccess { e -> e?.let { offerUndo("Removed ${song.title}") { restore(it) } } }
                .onFailure { platform.log("Could not remove ${song.title}: ${it.message}") }
        }
        finally { importing-- }
        change { }
    }

    /** Take one part out of its song, its file to the library's Trash for 30 days. */
    fun removePart(song: com.inksheets.core.Song, part: com.inksheets.core.Part) {
        importing++
        try {
            runCatching { trash()?.removePart(song, part) }
                .onSuccess { e -> e?.let { offerUndo("Removed ${song.title} - ${com.inksheets.core.Instruments.partName(part)}") { restore(it) } } }
                .onFailure { platform.log("Could not remove ${part.file}: ${it.message}") }
        }
        finally { importing-- }
        change { }
    }

    // ---- undo -------------------------------------------------------------------------

    /** Something just removed or deleted, and how to put it back: offered for a few seconds. */
    class UndoOffer(val text: String, val undo: () -> Unit, val at: Long = System.currentTimeMillis())

    var undoOffer by mutableStateOf<UndoOffer?>(null)

    /** Say what was just removed, with an Undo beside it (Home's foot, or over the music). */
    fun offerUndo(text: String, undo: () -> Unit) {
        val offer = UndoOffer(text, undo)
        platform.onMain { undoOffer = offer }
    }

    /** Take a song out of a setlist, with an Undo that puts it back in its place. */
    fun takeOut(setlistId: String, entryId: String) {
        val lib = library ?: return
        val before = lib.setlist(setlistId)?.entries ?: return
        val title = before.firstOrNull { it.id == entryId }?.let { lib.song(it.songId)?.title } ?: "it"
        change { removeFromSetlist(setlistId, entryId) }
        offerUndo("Took $title out") {
            change {
                val now = setlist(setlistId)?.entries.orEmpty()
                if (now.none { it.id == entryId }) {
                    val i = before.indexOfFirst { it.id == entryId }.coerceIn(0, now.size)
                    editSetlist(setlistId) { entries = now.toMutableList().apply { add(i, before.first { it.id == entryId }) } }
                }
            }
        }
    }

    /** Delete a setlist - with an Undo, and in Recently deleted for 30 days. */
    fun deleteSetlist(id: String) {
        val name = library?.setlist(id)?.name ?: return
        change { deleteSetlist(id) }
        offerUndo("Deleted $name") { change { restoreSetlist(id) } }
    }

    /**
     * Delete a folder. With [withSetlists], the setlists and folders inside go too; otherwise
     * they move up to where it was. Either way one Undo puts everything back as it was.
     */
    fun deleteFolder(id: String, withSetlists: Boolean) {
        val lib = library ?: return
        val folder = lib.folders.firstOrNull { it.id == id } ?: return
        // Everything under it, and where each thing was, for the Undo.
        val folders = ArrayList<com.inksheets.core.Folder>()
        fun walk(f: String) { lib.foldersIn(f).forEach { folders += it; walk(it.id) } }
        walk(id)
        val inside = (folders.map { it.id } + id).toSet()
        val lists = lib.setlists.filter { it.folderId in inside }
        change {
            if (withSetlists) {
                lists.forEach { deleteSetlist(it.id) }
                folders.reversed().forEach { deleteFolder(it.id) }
            }
            deleteFolder(id)
        }
        offerUndo("Deleted ${folder.name}" + if (withSetlists && lists.isNotEmpty()) " and ${lists.size} setlist${if (lists.size == 1) "" else "s"}" else "") {
            change {
                restoreFolder(id)
                folders.forEach { f -> restoreFolder(f.id); moveFolder(f.id, f.parentId) }
                lists.forEach { l -> restoreSetlist(l.id); editSetlist(l.id) { folderId = l.folderId } }
            }
        }
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

    /** A part being looked at in the page viewer over Home - read, not opened as a song. */
    var peeking by mutableStateOf<Pair<com.inksheets.core.Song, com.inksheets.core.Part>?>(null)

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
                        // The old file - and the marks in it - kept in the trash first, for 30 days.
                        runCatching { trash()?.keepFile(part.file, "${lib.song(fate.songId)?.title ?: ""} - ${com.inksheets.core.Instruments.partName(part)} (replaced)") }
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
        if (touched.isNotEmpty()) { runCatching { readUnknownParts() }; runCatching { readTempos() } }
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
        runCatching { com.inksheets.core.Instruments.use(lib.instruments()) }
        version = lib.version
    }

    /** Where "Redo automatic assignment" has got to: parts read, of how many; null when not running. */
    var reassigning by mutableStateOf<Pair<Int, Int>?>(null)

    /** What the last "Redo automatic assignment" changed, for saying so. */
    var reassigned by mutableStateOf<String?>(null)

    /**
     * Read the instrument off every part again - its name, then its first page's words, then a
     * scan of it - with every instrument now known, the ones taught in this library included.
     * Parts a person set by hand are left alone. What changes is written to the library, so every
     * device gets it. Off the UI thread.
     */
    fun reassignInstruments() {
        val lib = library ?: return
        val todo = lib.songs.flatMap { song -> song.parts.filter { it.source != com.inksheets.core.InstrumentSource.PERSON }.map { song to it } }
        var changed = 0
        platform.onMain { reassigning = 0 to todo.size; reassigned = null }
        todo.forEachIndexed { i, (song, part) ->
            platform.onMain { reassigning = i to todo.size }
            val file = partFile(song, part)
            val page = part.firstPage ?: 1
            val read = com.inksheets.core.ImportPlan.readPart(
                part.file,
                textOf = { if (file != null && file.isFile) runCatching { platform.pageText(file, page) }.getOrNull() else null },
                recognise = { if (file != null && file.isFile && platform.canRecognise) runCatching { platform.recognise(file, page) }.getOrNull() else null }
            )
            // Nothing found this time is no reason to forget what was found before.
            if (read.instrument == null) return@forEachIndexed
            if (read.instrument == part.instrument && read.also == part.also && read.chair == part.chair) return@forEachIndexed
            val current = lib.song(song.id) ?: return@forEachIndexed
            platform.onMain {
                change {
                    editSong(song.id) {
                        parts = current.parts.map { p ->
                            if (p.id == part.id && p.source != com.inksheets.core.InstrumentSource.PERSON) {
                                p.copy(instrument = read.instrument, source = read.source, label = read.label, also = read.also, chair = read.chair)
                            } else p
                        }
                    }
                }
            }
            changed++
        }
        runCatching { readTempos(again = true) }
        // Parts that now read as different ones - Trumpet 2 beside Trumpet 1 - can be one song.
        runCatching { scanFolder() }
        platform.onMain {
            reassigning = null
            reassigned = if (changed == 0) "Every part already had the instrument it reads as." else "$changed part${if (changed == 1) "" else "s"} given a different instrument."
        }
    }

    /** The song one of whose parts is the file at [path]. */
    fun songAt(path: String): com.inksheets.core.Song? {
        val rel = relative(File(path)) ?: return null
        return library?.songWithFile(rel)
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
        // Kept up to date by the folder scan, off the screen's thread; asked for on it, this
        // never walks the folder itself.
        // Only before the first scan has built it is the folder walked here.
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
        // Only what is already known about moved files. Walking the whole music folder for it
        // here, on the screen's thread, stopped the screen each time; the folder scan follows a
        // moved file anyway, and the next look finds it where it went.
        val found = findMoved(part.file, fresh = false)?.takeIf { it.isFile } ?: return null
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
        // The usual case, a file under the library folder as given, needs no trip to the disk:
        // asked on every page turned, the resolving below was a system call each time.
        root?.absolutePath?.let { r ->
            val p = file.absolutePath
            if (p.startsWith(r + File.separator) && !p.contains("..")) return p.substring(r.length + 1).replace(File.separatorChar, '/')
        }
        val base = root?.canonicalFile ?: return null
        val canonical = file.canonicalFile
        if (!canonical.path.startsWith(base.path)) return null
        return canonical.path.removePrefix(base.path).trimStart(File.separatorChar).replace(File.separatorChar, '/')
    }

    companion object {
        private const val K_LIBRARY = "sheets_library"
        private const val K_PROFILE = "sheets_profile"
        private const val K_PICKS = "sheets_part_picks"
        private const val K_TEMPO_TRIED = "sheets_tempo_tried"
        private const val K_TRIED = "sheets_ocr_tried"
        private const val K_EDGE_TAPS = "sheets_edge_taps"
        private const val K_TURN = "sheets_turn_style"
        private const val K_STRIP_LABELS = "sheets_strip_labels"
        private const val K_STRIP_LEFT = "sheets_strip_left"
        private const val K_STRIP = "sheets_strip_2"
        private const val K_COLLAPSED = "sheets_strip_collapsed"
        private const val K_BOOKMARK_SORT = "sheets_bookmark_sort"
        private const val K_PRESETS = "sheets_message_presets"
        private val PRESET_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        private val PRESET_LIST = kotlinx.serialization.builtins.ListSerializer(com.inksheets.core.MessagePreset.serializer())

        /**
         * Page turns, then the pen tools - a rehearsal note goes on in one tap and the pen is
         * back in one more - then songs, the metronome and tuner, and fullscreen.
         */
        /**
         * Lean: pages and songs turn by tap and swipe, so the strip is for marking up and the
         * metronome. The rest is in More, or added back under Customise.
         */
        /** Added to the strip by request after it was first laid out, once, for strips saved before. */
        val ADDED_LATER = listOf(com.inkslate.core.PerformAction.BOOKMARK)
        private const val K_STRIP_ADDED = "sheets_strip_added_bookmark"

        /** Back on the strip by request, after a spell in More. */
        val RETURNED = listOf(
            com.inkslate.core.PerformAction.SWITCH_PART,
            com.inkslate.core.PerformAction.RECORDINGS,
            com.inkslate.core.PerformAction.TUNER,
            com.inkslate.core.PerformAction.PLAY_TOGETHER
        )
        val DEFAULT_STRIP = listOf(
            com.inkslate.core.PerformAction.PEN,
            com.inkslate.core.PerformAction.HIGHLIGHTER,
            com.inkslate.core.PerformAction.ERASER,
            com.inkslate.core.PerformAction.UNDO,
            com.inkslate.core.PerformAction.METRONOME
        ) + RETURNED + ADDED_LATER + com.inkslate.core.PerformAction.FULLSCREEN
        private const val K_STRIP_RETURNED = "sheets_strip_returned_1"
        private const val K_SETLIST_SORT = "sheets_setlist_sort"
        const val ONE_OFF = "one-off"
        private const val K_ENTRY_SORT = "sheets_entry_sort"
        private const val K_LISTEN = "sheets_listen_turns"
        private const val K_READ_MUSIC = "sheets_read_music"
        private const val K_PAGE_COLOUR = "sheets_page_colour"
    }
}
