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
        val folder: String = "",
        /** Files kept with no part of their own: an old copy something replaced. */
        val files: List<String> = emptyList()
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
        // The list first, then the files: stopped halfway, the trash still knows what it holds.
        val entry = Entry(song.id, song.title, System.currentTimeMillis(), song.parts.map { it.id to it.file }, song.audio.map { it.file })
        File(folder, MANIFEST).writeText(json.encodeToString(Entry.serializer(), entry))
        song.parts.forEach { stash(it.file) }
        song.audio.forEach { stash(it.file) }
        song.parts.forEach { library.deletePart(it.id) }
        library.deleteSong(song.id)
        return entry.copy(folder = folder.name)
    }

    /**
     * Take one part out of [song], its file into the trash - kept, like a removed song, for
     * [KEEP_DAYS]. A file another part still uses (a pack of several parts) stays where it is;
     * only this part goes.
     */
    fun removePart(song: Song, part: Part): Entry {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "${song.title} - ${Instruments.partName(part)}"
        val safe = name.map { if (it.isLetterOrDigit() || it in " -_") it else '_' }.joinToString("").trim().take(40)
        val folder = File(dir, "$stamp $safe").apply { mkdirs() }
        val othersUse = library.songs.flatMap { s -> (s.parts + s.duplicates).filter { it.id != part.id }.map { it.file } + s.audio.map { it.file } }.toSet()
        val entry = Entry(song.id, name, System.currentTimeMillis(), listOf(part.id to part.file), emptyList())
        File(folder, MANIFEST).writeText(json.encodeToString(Entry.serializer(), entry))
        if (part.file !in othersUse) {
            val from = File(root, part.file)
            if (from.isFile) {
                val to = File(folder, part.file)
                to.parentFile?.mkdirs()
                if (!from.renameTo(to)) { from.copyTo(to, overwrite = true); from.delete() }
            }
        }
        library.deletePart(part.id)
        return entry.copy(folder = folder.name)
    }

    /**
     * Keep the file at [rel] in the trash before something is written over it (a part replaced,
     * a setlist or download brought in again) - so a player's marks in the old copy are never
     * lost by one wrong tap. Restoring it puts it back beside whatever is there then.
     */
    fun keepFile(rel: String, title: String): Entry? {
        val from = File(root, rel)
        if (!from.isFile) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = title.map { if (it.isLetterOrDigit() || it in " -_") it else '_' }.joinToString("").trim().take(40)
        var folder = File(dir, "$stamp $safe")
        var n = 2
        while (folder.exists()) folder = File(dir, "$stamp $safe $n").also { n++ }
        folder.mkdirs()
        val entry = Entry("", title, System.currentTimeMillis(), emptyList(), emptyList(), files = listOf(rel))
        File(folder, MANIFEST).writeText(json.encodeToString(Entry.serializer(), entry))
        val to = File(folder, rel)
        to.parentFile?.mkdirs()
        from.copyTo(to, overwrite = true)
        return entry.copy(folder = folder.name)
    }

    /** Put a removed song back: its files where they were, and the song and its parts as they were. */
    fun restore(entry: Entry): Boolean {
        val folder = File(dir, entry.folder)
        if (!folder.isDirectory) return false
        // Where each file goes back to: its own place, or - when another file has been put
        // there since - beside it, "(restored)", so neither is lost.
        val placed = HashMap<String, String>()
        for (rel in entry.parts.map { it.second } + entry.audio + entry.files) {
            val from = File(folder, rel)
            if (!from.isFile || rel in placed) continue
            var target = rel
            if (File(root, rel).exists()) {
                val base = rel.substringBeforeLast('.'); val ext = rel.substringAfterLast('.', "")
                var n = 1
                do { target = "$base (restored${if (n > 1) " $n" else ""})" + (if (ext.isNotEmpty()) ".$ext" else ""); n++ } while (File(root, target).exists())
            }
            val to = File(root, target)
            to.parentFile?.mkdirs()
            if (!from.renameTo(to)) { from.copyTo(to); from.delete() }
            placed[rel] = target
        }
        if (entry.songId.isNotEmpty()) library.restoreSong(entry.songId)
        entry.parts.forEach { (id, _) -> library.restorePart(id) }
        // Parts and recordings put back beside where they were point at where they are now.
        for ((id, rel) in entry.parts) {
            val now = placed[rel]?.takeIf { it != rel } ?: continue
            val (songId, _) = library.partHome(id)
            val part = songId?.let { library.song(it) }?.all?.firstOrNull { it.id == id } ?: continue
            library.writePart(songId, part.copy(file = now))
        }
        if (entry.audio.any { placed[it] != null && placed[it] != it }) library.song(entry.songId)?.let { s ->
            library.editSong(s.id) { audio = s.audio.map { t -> placed[t.file]?.let { t.copy(file = it) } ?: t } }
        }
        // Anything that could not be put back stays in the trash rather than going for good.
        if (folder.walkBottomUp().none { it.isFile && it.name != MANIFEST }) folder.deleteRecursively()
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
