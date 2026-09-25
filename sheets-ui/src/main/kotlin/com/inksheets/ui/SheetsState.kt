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

    /** Leading or following other tablets. */
    val companion = Companion(this)
    var companionOpen by mutableStateOf(false)

    /** The recordings panel, for the song opened last. */
    var audioOpen by mutableStateOf(false)
    var metronomeOpen by mutableStateOf(false)

    /** Whether a finger tap at the side of the page turns it. On unless turned off. */
    var edgeTaps: Boolean
        get() = edgeTapsState
        set(on) {
            edgeTapsState = on
            platform.setPref(K_EDGE_TAPS, on.toString())
            platform.setEdgeTaps(on)
        }
    private var edgeTapsState by mutableStateOf(platform.pref(K_EDGE_TAPS) != "false")

    /** The actions on the strip over the page. */
    fun stripActions(): List<com.inkslate.core.PerformAction> =
        platform.pref(K_STRIP)?.split(',')?.mapNotNull { n -> com.inkslate.core.PerformAction.entries.firstOrNull { it.name == n } }
            ?: DEFAULT_STRIP

    fun setStripActions(actions: List<com.inkslate.core.PerformAction>) =
        platform.setPref(K_STRIP, actions.joinToString(",") { it.name })

    /** The setlist being played through, and where in it: what "next song" means. */
    var playing by mutableStateOf<Pair<String, Int>?>(null)
        private set

    init {
        platform.setEdgeTaps(edgeTapsState)
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
        com.inkslate.core.Perform.onPage = { path, page ->
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
            val part = com.inksheets.core.PartChoice.partFor(s, profile) ?: return@forEachIndexed
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
        platform.openSet(tabs, focus)
        companion.pageTurned(0)
    }

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
            e.id to lib.song(e.songId)?.let { s -> com.inksheets.core.PartChoice.partFor(s, profile)?.let { partFile(s, it) } }
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
        if (!platform.canRecognise) return
        val lib = library ?: return
        val tried = platform.pref(K_TRIED).orEmpty().split(',').filter { it.isNotEmpty() }.toMutableSet()
        val todo = lib.songs.flatMap { song ->
            song.parts.filter { it.instrument == null && it.id !in tried }.map { song to it }
        }
        todo.forEachIndexed { i, (song, part) ->
            onProgress(i, todo.size)
            val file = partFile(song, part) ?: return@forEachIndexed
            if (!file.isFile) return@forEachIndexed
            val text = runCatching { platform.recognise(file, part.firstPage ?: 1) }.getOrNull()
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
    }

    fun chooseProfile(id: String?) {
        profileId = id
        platform.setPref(K_PROFILE, id)
    }

    /** Take in edits from other devices. Called off the UI thread on a timer. */
    fun refresh() {
        val lib = library ?: return
        if (lib.refresh()) version = lib.version
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
        private const val K_TRIED = "sheets_ocr_tried"
        private const val K_EDGE_TAPS = "sheets_edge_taps"
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
