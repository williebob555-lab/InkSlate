package com.inksheets.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Songs removed from the library, kept for a while in case.
 *
 * The folder is the library, so removing a song has to take its files out of the folder -
 * otherwise the next look at the folder finds them and adds the song straight back. They go to
 * `.inksheets/trash/`, inside the synced folder, so every device loses the song together (each
 * sees the files go) and any of them can bring it back. After [KEEP_DAYS] the files are deleted.
 */
class LibraryTrash(private val root: File, private val library: Library) {

    @Serializable
    data class Entry(
        val songId: String,
        val title: String,
        val removedAt: Long,
        /** Part ids and the library-relative paths their files had. */
        val parts: List<Pair<String, String>>,
        val audio: List<String>,
        /** This entry's folder inside the trash. */
        val folder: String = ""
    )

    private val dir = File(root, ".inksheets/trash")
    private val json = Json { ignoreUnknownKeys = true }

    /** Everything in the trash, most recently removed first. */
    fun entries(): List<Entry> = dir.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { d ->
        runCatching { json.decodeFromString(Entry.serializer(), File(d, MANIFEST).readText()).copy(folder = d.name) }.getOrNull()
    }.sortedByDescending { it.removedAt }

    /** Take [song] out of the library, its files into the trash. */
    fun remove(song: Song): Entry {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = song.title.map { if (it.isLetterOrDigit() || it in " -_") it else '_' }.joinToString("").trim().take(40)
        val folder = File(dir, "$stamp $safe").apply { mkdirs() }
        // Parts sharing a file with another song's part leave that file where it is.
        val othersUse = library.songs.filter { it.id != song.id }.flatMap { s -> s.parts.map { it.file } + s.audio.map { it.file } }.toSet()
        fun stash(rel: String) {
            if (rel in othersUse) return
            val from = File(root, rel)
            if (!from.isFile) return
            val to = File(folder, rel)
            to.parentFile?.mkdirs()
            if (!from.renameTo(to)) { from.copyTo(to, overwrite = true); from.delete() }
        }
        song.parts.forEach { stash(it.file) }
        song.audio.forEach { stash(it.file) }
        val entry = Entry(song.id, song.title, System.currentTimeMillis(), song.parts.map { it.id to it.file }, song.audio.map { it.file })
        File(folder, MANIFEST).writeText(json.encodeToString(Entry.serializer(), entry))
        song.parts.forEach { library.deletePart(it.id) }
        library.deleteSong(song.id)
        return entry.copy(folder = folder.name)
    }

    /** Put a removed song back: its files where they were, and the song and its parts as they were. */
    fun restore(entry: Entry): Boolean {
        val folder = File(dir, entry.folder)
        if (!folder.isDirectory) return false
        for (rel in entry.parts.map { it.second } + entry.audio) {
            val from = File(folder, rel)
            val to = File(root, rel)
            if (!from.isFile || to.exists()) continue
            to.parentFile?.mkdirs()
            if (!from.renameTo(to)) { from.copyTo(to); from.delete() }
        }
        library.restoreSong(entry.songId)
        entry.parts.forEach { (id, _) -> library.restorePart(id) }
        folder.deleteRecursively()
        return true
    }

    /** Delete for good what has been in the trash longer than [KEEP_DAYS]. */
    fun purge(now: Long = System.currentTimeMillis()): Int {
        val old = entries().filter { now - it.removedAt > KEEP_DAYS * 86_400_000L }
        old.forEach { File(dir, it.folder).deleteRecursively() }
        return old.size
    }

    /** Delete one entry for good now. */
    fun forget(entry: Entry) { File(dir, entry.folder).deleteRecursively() }

    companion object {
        const val KEEP_DAYS = 30
        private const val MANIFEST = "removed.json"
    }
}
