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
    private val memoryFile: File
) {

    /** What one scan did. */
    data class Report(
        val added: List<String> = emptyList(),
        val moved: List<Pair<String, String>> = emptyList(),
        val removed: List<String> = emptyList(),
        val merged: List<String> = emptyList(),
        val migrated: Int = 0,
        /** Set when deletions were held back because too much seemed to be missing at once. */
        val heldBack: Int = 0
    ) {
        val changed: Boolean get() = added.isNotEmpty() || moved.isNotEmpty() || removed.isNotEmpty() || merged.isNotEmpty() || migrated > 0
    }

    @Serializable
    private data class Seen(val size: Long, val hash: String = "")

    private val json = Json { ignoreUnknownKeys = true }
    private val memorySerializer = MapSerializer(String.serializer(), Seen.serializer())

    private fun remember(): MutableMap<String, Seen> =
        runCatching { json.decodeFromString(memorySerializer, memoryFile.readText()).toMutableMap() }.getOrDefault(HashMap())

    private fun keep(memory: Map<String, Seen>) {
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
            val claims = library.songs.flatMap { s -> s.parts.map { s to it } }.groupBy { it.second.file.lowercase() }
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
        library.songs.forEach { s -> s.parts.forEach { referenced += it.file.lowercase() } }
        val unclaimed = disk.filter { it.path.lowercase() !in referenced }.toMutableList()
        val missing = library.songs.flatMap { s -> s.parts.filter { it.file !in onDisk }.map { s to it } }
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
        val partCount = library.songs.sumOf { it.parts.size }
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
            val title = ImportPlan.songTitle(file.path, songFolders[file.path.substringBeforeLast('/', "")] == true)
            val song = home?.let { library.song(it) } ?: library.ensureSong(title)
            library.writePart(song.id, Part(
                id = id, file = file.path, instrument = planned.instrument, source = planned.source,
                label = planned.label, also = planned.also
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

        // 4. One piece, several songs: put together (unless someone split them on purpose). Only
        // songs whose parts are for different instruments: two Euphonium parts under one title
        // are two editions, or two pieces, and stay two songs.
        run {
            val groups = library.songs.filter { !it.apart }.groupBy { Library.matchKey(it.title) }
            for ((_, same) in groups) {
                if (same.size < 2) continue
                // The same choice on every device: the title's own id if one has it, else the smallest.
                val keep = same.firstOrNull { it.id == Library.songIdFor(it.title) } ?: same.minBy { it.id }
                val have = HashSet(library.song(keep.id)?.instruments.orEmpty())
                for (s in same) if (s.id != keep.id) {
                    val theirs = s.instruments
                    if (theirs.any { it in have }) continue
                    library.mergeSongs(s.id, keep.id)
                    have += theirs
                    merged += "${s.title} into ${keep.title}"
                }
            }
        }

        // 5. Songs left with nothing, and setlist entries for songs that are gone.
        for (s in library.songs) {
            if (s.id in touched && s.parts.isEmpty() && s.audio.isEmpty()) {
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

        return Report(added, moved, removed, merged, migrated, heldBack)
    }

    /**
     * Parts whose file is not in the folder on this device - ghosts left by an older version, or
     * files still on their way. Shown for the person to judge; [removeMissing] clears them.
     */
    fun missing(): List<Pair<Song, Part>> {
        val onDisk = listMusic(root).map { it.path }.toSet()
        return library.songs.flatMap { s -> s.parts.filter { it.file !in onDisk }.map { s to it } }
    }

    /** Remove every part whose file is not here, and songs left with nothing. The person asked. */
    @Synchronized
    fun removeMissing(): Int {
        val gone = missing()
        gone.forEach { (_, p) -> library.deletePart(p.id) }
        for (s in library.songs) if (s.parts.isEmpty() && s.audio.isEmpty()) library.deleteSong(s.id)
        return gone.size
    }

    data class Found(val path: String, val size: Long, val modified: Long, val music: Boolean)

    companion object {
        val MUSIC = setOf("pdf", "png", "jpg", "jpeg", "webp")
        val SOUND = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "aif", "aiff")

        /** The music and recordings in [root], library-relative, skipping dot folders and sync debris. */
        fun listMusic(root: File): List<Found> {
            if (!root.isDirectory) return emptyList()
            return root.walkTopDown()
                .onEnter { it == root || !it.name.startsWith(".") }
                .filter { it.isFile && !ignored(it.name) }
                .mapNotNull { f ->
                    val ext = f.extension.lowercase()
                    val music = ext in MUSIC
                    if (!music && ext !in SOUND) return@mapNotNull null
                    Found(f.relativeTo(root).invariantSeparatorsPath, f.length(), f.lastModified(), music)
                }
                .toList()
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
