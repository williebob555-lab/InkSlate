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

    val profiles: List<InstrumentProfile> = Instruments.defaultProfiles

    var profileId by mutableStateOf(platform.pref(K_PROFILE))
        private set

    val profile: InstrumentProfile?
        get() = profiles.firstOrNull { it.id == profileId }

    /** The setlist being played through, and where in it: what "next song" means. */
    var playing by mutableStateOf<Pair<String, Int>?>(null)
        private set

    init {
        platform.pref(K_LIBRARY)?.let(::File)?.takeIf { it.isDirectory }?.let(::open)
        // Song turns and the metronome from a pedal, whatever screen is in front.
        com.inkslate.core.Perform.app = { action ->
            when (action) {
                com.inkslate.core.PerformAction.NEXT_SONG -> step(1)
                com.inkslate.core.PerformAction.PREVIOUS_SONG -> step(-1)
                com.inkslate.core.PerformAction.METRONOME -> { toggleMetronome(); true }
                else -> false
            }
        }
    }

    /** Open entry [index] of a setlist, and remember it for the pedals. */
    fun playSetlist(setlistId: String, index: Int) {
        val lib = library ?: return
        val entries = lib.setlist(setlistId)?.entries ?: return
        val entry = entries.getOrNull(index) ?: return
        val song = lib.song(entry.songId) ?: return
        playing = setlistId to index
        openSong(this, song)
    }

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
            val file = fileOf(part.file) ?: return@forEachIndexed
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
    }
}
