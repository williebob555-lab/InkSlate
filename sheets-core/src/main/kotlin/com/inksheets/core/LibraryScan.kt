package com.inksheets.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Keeping the library in step with the music folder: the folder is the library.
 *
 * A file that appears is added to the song it belongs to (or a new one); a file that moves is
 * followed; a file that is deleted takes its part with it, and a song left with nothing goes too.
 * Songs that turn out to be one piece are put together, and a file two songs both claim is kept
 * once. It runs on every device, on files that arrive in any order and on any version of the app,
 * so every decision is made so that two devices making it independently agree:
 *
 * - **Ids come from what they name.** A part found in the folder is `Library.partIdFor(path)`; a
 *   song made for it is `Library.songIdFor(title)`. Two devices noticing the same new file write
 *   the same records, which merge into one - never two songs.
 * - **A device deletes only what it watched go.** A part is removed when this device had seen its
 *   file and now does not - Syncthing deleting it here is how a deletion elsewhere arrives. A file
 *   this device has never had (it has not synced yet) is left alone. A scan that finds the folder
 *   empty, or most of it gone at once (an unplugged drive, a folder renamed), removes nothing and
 *   says so instead.
 * - **What was removed stays removed.** A file whose part was deleted after the file was last
 *   changed is one whose deletion has not reached this device yet; it is not added back.
 * - Syncthing's temporary and conflict files, and everything in dot folders, are not music.
 */
class LibraryScan(
    private val root: File,
    private val library: Library,
    /** This device's own memory of the folder: never synced, one per device. */
    private val memoryFile: File,
    /** A file's page count (0 when it will not open), for choosing between copies of a part. */
    private val pages: (File) -> Int? = { null }
) {

    /** What one scan did. */
    data class Report(
        val added: List<String> = emptyList(),
        val moved: List<Pair<String, String>> = emptyList(),
        val removed: List<String> = emptyList(),
        val merged: List<String> = emptyList(),
        val migrated: Int = 0,
        /** What the automatic sort put right. */
        val sorted: List<String> = emptyList(),
        /** Set when deletions were held back because too much seemed to be missing at once. */
        val heldBack: Int = 0,
        /** Every music and recording file found, library-relative: what is on this device now. */
        val onDisk: Set<String> = emptySet()
    ) {
        val changed: Boolean get() = added.isNotEmpty() || moved.isNotEmpty() || removed.isNotEmpty() || merged.isNotEmpty() || migrated > 0 || sorted.isNotEmpty()
    }

    @Serializable
    private data class Seen(val size: Long, val hash: String = "")

    private val json = Json { ignoreUnknownKeys = true }

    /** What the folder and library were when last sorted (kept per folder, across scans). */
    private var lastSorted: Long
        get() = sortedAt[root.absolutePath] ?: 0L
        set(v) { sortedAt[root.absolutePath] = v }
    private val memorySerializer = MapSerializer(String.serializer(), Seen.serializer())

    /** What was last written to [memoryFile], so an unchanged memory is not written again. */
    private var remembered: Map<String, Seen>?
        get() = written[memoryFile.absolutePath]
        set(v) { if (v == null) written.remove(memoryFile.absolutePath) else written[memoryFile.absolutePath] = v }

    private fun remember(): MutableMap<String, Seen> = remembered?.toMutableMap() ?: readMemory()

    private fun readMemory(): MutableMap<String, Seen> =
        runCatching { json.decodeFromString(memorySerializer, memoryFile.readText()).toMutableMap() }.getOrDefault(HashMap())

    private fun keep(memory: Map<String, Seen>) {
        // Unchanged, as it is on nearly every scan: nothing to write.
        if (memory == remembered) return
        remembered = memory
        runCatching {
            memoryFile.parentFile?.mkdirs()
            val temp = File(memoryFile.parentFile, memoryFile.name + ".tmp")
            temp.writeText(json.encodeToString(memorySerializer, memory))
            if (!temp.renameTo(memoryFile)) { memoryFile.delete(); temp.renameTo(memoryFile) }
        }
    }

    /**
     * Look at the folder and bring the library into line. [allowMassRemoval] lets through a
     * removal that was held back for being too big (the person confirmed it).
     */
    @Synchronized
    fun run(allowMassRemoval: Boolean = false): Report {
        val migrated = library.migrateParts()
        val disk = listMusic(root)
        val onDisk = disk.associateBy { it.path }
        val memory = remember()
        val added = ArrayList<String>()
        val moved = ArrayList<Pair<String, String>>()
        val removed = ArrayList<String>()
        val merged = ArrayList<String>()

        // Songs this scan took parts from: the only ones it may remove for being empty. A song
        // another device has only half written (its parts still on the way) is never touched.
        val touched = HashSet<String>()

        // 1. One file listed twice is kept once. The same pages of the same file are the same
        // part; so are the whole file and a part of it that begins on its first page - what one
        // device's scan and another's import make of the same file. Band packs, a page range
        // each, are left alone.
        run {
            val claims = library.songs.flatMap { s -> s.all.map { s to it } }.groupBy { it.second.file.lowercase() }
            for ((_, all) in claims) {
                if (all.size < 2) continue
                val ranged = all.filter { it.second.firstPage != null }
                val whole = all.filter { it.second.firstPage == null }
                val drop = ArrayList<Pair<Song, Part>>()
                // Exact doubles: keep the smallest id.
                for (same in all.groupBy { it.second.firstPage to it.second.lastPage }.values) {
                    if (same.size > 1) drop += same.sortedBy { it.second.id }.drop(1)
                }
                // A whole-file part found by a scan, beside a part of the same file brought in by
                // an import: the import knew more (its title, its pages) and is kept.
                if (ranged.isNotEmpty()) {
                    val single = ranged.map { it.second.firstPage to it.second.lastPage }.distinct().size == 1
                    for (w in whole) {
                        val otherSong = ranged.none { it.first.id == w.first.id }
                        if (w.second.source != InstrumentSource.PERSON && (single || otherSong) && w !in drop) drop += w
                    }
                }
                for ((song, part) in drop) {
                    library.deletePart(part.id)
                    touched += song.id
                    merged += "${song.title}: ${part.file} was listed twice"
                }
            }
        }

        // 2. Parts whose file is not where the library says.
        val referenced = HashSet<String>()
        library.songs.forEach { s -> s.all.forEach { referenced += it.file.lowercase() } }
        val unclaimed = disk.filter { it.path.lowercase() !in referenced }.toMutableList()
        val missing = library.songs.flatMap { s -> s.all.filter { it.file !in onDisk }.map { s to it } }
        val gone = ArrayList<Pair<Song, Part>>()
        // Where each missing file went, decided once per file: every part of a band pack follows it.
        val movedTo = HashMap<String, Found?>()
        for ((song, part) in missing) {
            if (part.file in movedTo) {
                movedTo[part.file]?.let { library.writePart(song.id, part.copy(file = it.path)) }
                    ?: run { if (memory[part.file] != null) gone += song to part }
                continue
            }
            val was = memory[part.file]
            val name = part.file.substringAfterLast('/').lowercase()
            // Moved: the one new file with its name, or failing that its size and content.
            val byName = unclaimed.filter { it.path.substringAfterLast('/').lowercase() == name }
            val found = byName.singleOrNull()
                ?: was?.takeIf { it.hash.isNotEmpty() }?.let { w ->
                    unclaimed.filter { it.size == w.size }.firstOrNull { hashOf(File(root, it.path)) == w.hash }
                }
            movedTo[part.file] = found
            if (found != null) {
                library.writePart(song.id, part.copy(file = found.path))
                unclaimed.remove(found)
                moved += part.file to found.path
                continue
            }
            // Gone: only if this device had it and watched it go.
            if (was != null) gone += song to part
        }
        val partCount = library.songs.sumOf { it.all.size }
        // A folder that is not really there - a card taken out, a drive not mounted - has lost
        // the library's own records too; one that is there but lost most of its music at once
        // more likely lost a folder than had it all deleted.
        val unplugged = !File(root, ".inksheets/log").isDirectory
        val tooMany = unplugged || (gone.size > 10 && gone.size > partCount * 0.3)
        var heldBack = 0
        if (tooMany && !allowMassRemoval) {
            heldBack = gone.size
        } else {
            for ((song, part) in gone) {
                library.deletePart(part.id)
                touched += song.id
                removed += "${song.title}: ${part.file}"
                memory.remove(part.file)
            }
        }

        // 3. New files: into the song they belong to, or a new one.
        val sort = LibrarySort(root, library, pages)
        // Worked out only if a new file needs placing: on a scan that finds nothing new, never.
        val folderTitles by lazy { sort.folderSongs(disk) }
        val songFolders = disk.filter { it.music }.groupBy { it.path.substringBeforeLast('/', "") }
            .mapValues { (_, files) -> ImportPlan.folderIsSong(files.map { it.path }) }
        for (file in unclaimed) {
            if (!file.music) continue
            val id = Library.partIdFor(file.path)
            // Removed on another device, and the file's own deletion not here yet: stay removed.
            val removedAt = library.partDeletedAt(id)
            if (removedAt != null && removedAt >= file.modified) continue
            val (home, _) = library.partHome(id)
            val planned = ImportPlan.readPart(file.path)
            val folder = file.path.substringBeforeLast('/', "")
            val title = folderTitles[folder] ?: ImportPlan.songTitle(file.path, songFolders[folder] == true)
            val song = home?.let { library.song(it) } ?: library.ensureSong(title)
            library.writePart(song.id, Part(
                id = id, file = file.path, instrument = planned.instrument, source = planned.source,
                label = planned.label, also = planned.also, chair = planned.chair
            ))
            added += "${song.title}: ${file.path}"
        }

        // Recordings: paired with the song whose title their name starts with.
        run {
            val songs = library.songs
            val used = songs.flatMap { s -> s.audio.map { it.file.lowercase() } }.toSet()
            for (file in unclaimed) {
                if (file.music || file.path.lowercase() in used) continue
                val key = Library.titleKey(ImportPlan.songTitle(file.path))
                val song = songs.filter { s -> val k = Library.titleKey(s.title); key == k || key.startsWith("$k ") }
                    .maxByOrNull { it.title.length } ?: continue
                library.editSong(song.id) { audio = song.audio + AudioTrack(file = file.path) }
                added += "${song.title}: ${file.path} (recording)"
            }
            // Recordings whose file this device watched go.
            if (!tooMany || allowMassRemoval) for (s in library.songs) {
                val keep = s.audio.filter { it.file in onDisk || memory[it.file] == null }
                if (keep.size != s.audio.size) library.editSong(s.id) { audio = keep }
            }
        }

        // The automatic sort: every part in its song, no strays (see LibrarySort). Only when the
        // folder or the library has changed since it last ran: otherwise it would find nothing.
        // What the sort looks at is the files and which song each part is in - not when a song
        // was last opened, which changes on every turn in a set and used to set off a whole sort
        // (the start of every file read again) while the music was being played.
        fun signature() = library.songs.fold(disk.fold(17L) { h, f -> h * 31 + (f.path.hashCode() + f.size * 7 + f.modified) }) { h, s ->
            s.all.fold(h * 31 + s.id.hashCode() + s.title.hashCode()) { g, p -> g * 31 + p.id.hashCode() + p.file.hashCode() + (if (p.dup) 1 else 0) + (p.instrument?.hashCode() ?: 0) }
        }
        val structureChanged = signature() != lastSorted
        if (structureChanged) sort.run(disk)

        // 4. One piece, several songs: put together (unless someone split them on purpose). Only
        // songs whose parts are for different instruments: two Euphonium parts under one title
        // are two editions, or two pieces, and stay two songs.
        if (structureChanged) run {
            // Grouped by the title with any instrument still stuck to it taken off: a download
            // named "Song-Trumpet_1.pdf" once made a song per part before that was read.
            val groups = library.songs.filter { !it.apart }.groupBy { Library.matchKey(ImportPlan.titleOfTitle(it.title).ifBlank { it.title }) }
            for ((_, same) in groups) {
                if (same.size < 2) continue
                // The same choice on every device: the title's own id if one has it, else the smallest.
                val clean = same.filter { ImportPlan.titleOfTitle(it.title) == it.title.trim() }
                val keep = (clean.ifEmpty { same }).let { c -> c.firstOrNull { it.id == Library.songIdFor(it.title) } ?: c.minBy { it.id } }
                val have = HashSet(library.song(keep.id)?.seats.orEmpty())
                var took = false
                for (s in same) if (s.id != keep.id) {
                    val theirs = s.seats
                    // A second score, or a second drum line or pan part, is no sign of another
                    // piece: "Take On Me - Full Score copy", "We Like to Party - Score and Parts".
                    if (theirs.any { it in have && it.substringBefore('#') !in SHARED_SEATS }) continue
                    library.mergeSongs(s.id, keep.id)
                    have += theirs
                    took = true
                    merged += "${s.title} into ${keep.title}"
                }
                // Kept a title with an instrument on it: it is the song's now, so it goes.
                if (took) {
                    val title = ImportPlan.titleOfTitle(keep.title)
                    if (title.isNotBlank() && title != keep.title) library.editSong(keep.id) { this.title = title }
                }
            }
        }

        if (structureChanged) lastSorted = signature()

        // 5. Songs left with nothing, and setlist entries for songs that are gone.
        for (s in library.songs) {
            if (s.id in touched && s.all.isEmpty() && s.audio.isEmpty()) {
                library.deleteSong(s.id)
                removed += s.title
            }
        }
        // Setlist entries for a removed song are left in place (and not shown): brought back from
        // the trash, the song is back in its setlists too.

        // What this device has now seen, for telling a deletion from a file not yet arrived.
        val next = HashMap<String, Seen>()
        for (f in disk) {
            val before = memory[f.path]
            next[f.path] = if (before != null && before.size == f.size && before.hash.isNotEmpty()) before
            else Seen(f.size, hashOf(File(root, f.path)))
        }
        // Held-back deletions are remembered as seen, so they can still be made once confirmed.
        if (heldBack > 0) gone.forEach { (_, p) -> memory[p.file]?.let { next[p.file] = it } }
        keep(next)

        return Report(added, moved, removed, merged, migrated, sort.done, heldBack, onDisk.keys)
    }

    /**
     * Parts whose file is not in the folder on this device - ghosts left by an older version, or
     * files still on their way. Shown for the person to judge; [removeMissing] clears them.
     */
    /** What still looks out of place after sorting (see [LibrarySort.strays]). */
    fun strays(): List<String> = LibrarySort(root, library, pages).strays(listMusic(root))

    fun missing(): List<Pair<Song, Part>> {
        val onDisk = listMusic(root).map { it.path }.toSet()
        return library.songs.flatMap { s -> s.all.filter { it.file !in onDisk }.map { s to it } }
    }

    /** Remove every part whose file is not here, and songs left with nothing. The person asked. */
    @Synchronized
    fun removeMissing(): Int {
        val gone = missing()
        gone.forEach { (_, p) -> library.deletePart(p.id) }
        for (s in library.songs) if (s.all.isEmpty() && s.audio.isEmpty()) library.deleteSong(s.id)
        return gone.size
    }

    data class Found(val path: String, val size: Long, val modified: Long, val music: Boolean)

    companion object {
        private val SHARED_SEATS = setOf("score", "drumline", "steel-pan", "percussion")
        private val sortedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val written = java.util.concurrent.ConcurrentHashMap<String, Map<String, Seen>>()

        val MUSIC = setOf("pdf", "png", "jpg", "jpeg", "webp")
        val SOUND = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "aif", "aiff")

        /**
         * A folder at the library's top kept out of it: music gathered for teaching the trained
         * reader, not for playing (it never shows among the songs).
         */
        const val TRAINING = "Training"

        /** The music and recordings in [root], library-relative, skipping dot folders, the training folder and sync debris. */
        fun listMusic(root: File): List<Found> {
            if (!root.isDirectory) return emptyList()
            // One walk that is handed each file's size and date with its name, as the system lists
            // them - not three questions per file afterwards (is it a file, how long, how old),
            // which was most of what a scan every few seconds cost.
            val base = root.toPath()
            val out = ArrayList<Found>()
            java.nio.file.Files.walkFileTree(base, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                    if (dir != base && (dir.fileName.toString().startsWith(".") || dir.parent == base && dir.fileName.toString().equals(TRAINING, ignoreCase = true)))
                        java.nio.file.FileVisitResult.SKIP_SUBTREE
                    else java.nio.file.FileVisitResult.CONTINUE

                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    if (!attrs.isRegularFile) return java.nio.file.FileVisitResult.CONTINUE
                    val name = file.fileName.toString()
                    if (ignored(name)) return java.nio.file.FileVisitResult.CONTINUE
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val music = ext in MUSIC
                    if (music || ext in SOUND) {
                        out += Found(base.relativize(file).toString().replace('\\', '/'), attrs.size(), attrs.lastModifiedTime().toMillis(), music)
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                // A file that vanished mid-walk (Syncthing replacing it) is simply not there this time.
                override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException) = java.nio.file.FileVisitResult.CONTINUE
            })
            return out
        }

        /** Syncthing's files in flight and its conflict copies, and other apps' leftovers. */
        fun ignored(name: String): Boolean =
            name.startsWith(".") || name.startsWith("~syncthing~") || name.contains(".syncthing.") ||
                name.contains(".sync-conflict-") || name.endsWith(".tmp") || name.endsWith(".part")

        /** Size-independent fingerprint of a file's start: enough to know it again after a move. */
        fun hashOf(file: File): String = runCatching {
            val md = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                val n = input.read(buffer)
                if (n > 0) md.update(buffer, 0, n)
            }
            md.digest().take(12).joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }
}
