package com.inkslate.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Documents and folders deleted from Home, kept for [KEEP_DAYS] in case - the music library's
 * Trash, for coursework.
 *
 * Kept in `.inkslate-trash/` at the top of the folder on Home they came from: on the same disk, so
 * deleting is a rename and costs nothing; hidden, so nothing lists it; and inside the synced
 * folder, so every device sees the document go and any of them can bring it back. Each deletion
 * is a folder of its own holding the thing itself and a note of where it was.
 */
class DocumentTrash(private val root: File) {

    @Serializable
    data class Entry(
        val name: String,
        /** Where it was, relative to the folder on Home. */
        val from: String,
        val removedAt: Long,
        val isFolder: Boolean,
        /** This entry's folder inside the trash. */
        val folder: String = ""
    )

    private val dir = File(root, DIR)

    /** Everything in the trash, most recently deleted first. */
    fun entries(): List<Entry> = dir.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { d ->
        runCatching { JSON.decodeFromString(Entry.serializer(), File(d, MANIFEST).readText()).copy(folder = d.name) }.getOrNull()
    }.sortedByDescending { it.removedAt }

    /** Put [item] in the trash. It must be inside the folder on Home this trash belongs to. */
    fun put(item: File, now: Long = System.currentTimeMillis()): Entry {
        require(item.exists()) { "\"${item.name}\" is not there any more" }
        val rel = item.absolutePath.removePrefix(root.absolutePath + File.separator)
        require(rel != item.absolutePath) { "\"${item.name}\" is not inside ${root.name}" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(now))
        val safe = item.name.map { if (it.isLetterOrDigit() || it in " -_.") it else '_' }.joinToString("").trim().take(40)
        val folder = File(dir, "$stamp $safe").apply { mkdirs() }
        val entry = Entry(item.name, rel, now, item.isDirectory)
        File(folder, MANIFEST).writeText(JSON.encodeToString(Entry.serializer(), entry))
        val to = File(folder, item.name)
        if (!item.renameTo(to)) {
            // Another disk: copy, and only then remove the original.
            if (item.isDirectory) {
                require(item.copyRecursively(to, overwrite = false)) { "Could not move it to the trash" }
                item.deleteRecursively()
            } else {
                item.copyTo(to, overwrite = false)
                require(to.length() == item.length()) { "Could not move it to the trash" }
                item.delete()
            }
        }
        return entry.copy(folder = folder.name)
    }

    /**
     * Put [entry] back where it was. A folder it was in that has gone is made again; a name taken
     * since gets "(restored)" added. Returns where it went.
     */
    fun restore(entry: Entry): File {
        val folder = File(dir, entry.folder)
        val item = File(folder, entry.name)
        require(item.exists()) { "\"${entry.name}\" is no longer in the trash" }
        var target = File(root, entry.from)
        target.parentFile?.mkdirs()
        if (target.exists()) {
            val base = target.nameWithoutExtension
            val ext = target.extension.let { if (it.isEmpty() || entry.isFolder) "" else ".$it" }
            val stem = if (entry.isFolder) target.name else base
            var n = 1
            do {
                target = File(target.parentFile, if (n == 1) "$stem (restored)$ext" else "$stem (restored $n)$ext")
                n++
            } while (target.exists())
        }
        if (!item.renameTo(target)) {
            if (item.isDirectory) require(item.copyRecursively(target, overwrite = false)) { "Could not restore it" }
            else item.copyTo(target, overwrite = false)
        }
        folder.deleteRecursively()
        return target
    }

    /** Gone for good, now. */
    fun deleteForever(entry: Entry) {
        File(dir, entry.folder).takeIf { entry.folder.isNotEmpty() }?.deleteRecursively()
    }

    /** Clear out whatever has been here longer than [KEEP_DAYS]. */
    fun purge(now: Long = System.currentTimeMillis()) {
        val cutoff = now - KEEP_DAYS * 24L * 60 * 60 * 1000
        entries().filter { it.removedAt < cutoff }.forEach { deleteForever(it) }
        if (dir.isDirectory && dir.listFiles().isNullOrEmpty()) dir.delete()
    }

    companion object {
        const val KEEP_DAYS = 30
        const val DIR = ".inkslate-trash"
        private const val MANIFEST = "deleted.json"
        private val JSON = Json { ignoreUnknownKeys = true }

        /** The folder on Home [item] belongs to, whose trash it goes in. */
        fun rootFor(item: File, roots: List<File>): File? =
            roots.firstOrNull { item.absolutePath.startsWith(it.absolutePath + File.separator) }
    }
}
