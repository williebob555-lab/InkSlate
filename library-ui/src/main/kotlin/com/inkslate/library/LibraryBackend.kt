package com.inkslate.library

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.inkslate.core.DocumentShelf
import com.inkslate.core.DocumentTrash
import com.inkslate.core.Pictures
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What Home needs from the app it is in: the files, and what is remembered about them.
 *
 * Each app's `FileRepo` answers these already, under the same names; the adapter is a few lines.
 * Everything here may touch the disk and is only ever called off the main thread.
 */
interface LibraryBackend {
    /** The folders added to Home. */
    fun libraryFolders(): List<File>
    fun removeLibraryFolder(dir: File)

    fun recents(limit: Int): List<File>
    fun pinned(): List<File>
    fun isPinned(f: File): Boolean
    fun togglePin(f: File)

    /** A document this app can open. */
    fun isDocument(f: File): Boolean
    /** Whether a document has been written on, from what is cheap to know. */
    fun hasInk(f: File): Boolean

    var defaultNewFolder: File?
    fun isDefaultNewFolder(dir: File): Boolean

    fun createFolder(parent: File, name: String): Result<File>
    fun rename(f: File, name: String): Result<File>
    fun move(f: File, destination: File): Result<File>
    fun delete(f: File): Result<Unit>

    fun thumbnail(f: File): ImageBitmap?

    /** Take [f] off the Recent row, and nothing else. */
    fun removeRecent(f: File)

    /**
     * Let go of what the app keeps about [f] apart from the file - its star, its place in Recent,
     * its save rules, its working copy - once it has gone to the trash.
     */
    fun forget(f: File)

    /** A small remembered setting: the sort, the filters, the automation switch. */
    fun pref(key: String): String?
    fun setPref(key: String, value: String?)

    // ---- built from the above; the same on every platform ------------------------------------

    /**
     * Delete [f] the forgiving way: into the trash of the folder on Home it is in, for
     * [DocumentTrash.KEEP_DAYS] days. Fails for anything not inside a folder on Home.
     */
    fun trash(f: File): Result<Pair<File, DocumentTrash.Entry>> = runCatching {
        val root = DocumentTrash.rootFor(f, libraryFolders()) ?: error("\"${f.name}\" is not inside a folder on Home")
        val entry = DocumentTrash(root).put(f)
        runCatching { forget(f) }
        root to entry
    }

    /** Everything deleted from Home, newest first, with the folder on Home it came from. */
    fun trashed(): List<Pair<File, DocumentTrash.Entry>> =
        libraryFolders().flatMap { root -> DocumentTrash(root).entries().map { root to it } }
            .sortedByDescending { it.second.removedAt }

    fun restore(root: File, entry: DocumentTrash.Entry): Result<File> = runCatching { DocumentTrash(root).restore(entry) }

    fun deleteForever(root: File, entry: DocumentTrash.Entry) = DocumentTrash(root).deleteForever(entry)

    /** Clear out what has been in the trash past its time. */
    fun purgeTrash() = libraryFolders().forEach { runCatching { DocumentTrash(it).purge() } }

    /** Folders of a folder, as a person would see them: no hidden ones, no bookkeeping. */
    fun subfolders(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isDirectory && isShown(it) }
            ?.sortedWith(compareBy(DocumentShelf.NATURAL) { it.name })
            .orEmpty()

    /** The documents in [dir], and in its folders down to [depth] levels when that is above 1. */
    fun documentsUnder(dir: File, depth: Int, limit: Int = 4000): List<DocumentShelf.Item> {
        val out = ArrayList<DocumentShelf.Item>()
        fun walk(at: File, left: Int) {
            if (left <= 0 || out.size >= limit) return
            val children = at.listFiles() ?: return
            for (f in children) {
                if (!isShown(f)) continue
                if (f.isDirectory) walk(f, left - 1)
                else if (isDocument(f)) {
                    out.add(DocumentShelf.Item(f, f.lastModified(), f.length(), hasInk(f)))
                    if (out.size >= limit) return
                }
            }
        }
        walk(dir, depth)
        return out
    }

    /** Every folder in [roots] down to [depth] levels, outermost first. */
    fun allFolders(roots: List<File>, depth: Int = 5): List<File> {
        val out = ArrayList<File>()
        fun walk(at: File, left: Int) {
            if (left <= 0) return
            for (sub in subfolders(at)) {
                out.add(sub)
                walk(sub, left - 1)
            }
        }
        roots.forEach { walk(it, depth) }
        return out
    }

    /** What is in a folder, for the count on its tile: folders and documents only. */
    fun itemCount(dir: File): Int =
        dir.listFiles()?.count { isShown(it) && (it.isDirectory || isDocument(it)) } ?: 0

    /** A few of the newest documents in or just below a folder, for the picture on its tile. */
    fun folderPreview(dir: File, count: Int = 4): List<File> =
        documentsUnder(dir, depth = 2, limit = 60).sortedByDescending { it.modified }.take(count).map { it.file }

    fun isShown(f: File): Boolean =
        !f.name.startsWith(".") && !f.isHidden && !(f.isDirectory && Pictures.isLegacyFolder(f))
}

/** The pieces of Home that differ between a tablet and a computer. */
class LibraryPlatform(
    /** The back button (Android) or Escape (desktop): walk back out before leaving. */
    val backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit,
    /** Shown beside the list on a computer, where a mouse needs something to drag. */
    val verticalScrollbar: @Composable BoxScope.(LazyListState) -> Unit = {},
    /** The link to the other devices, in the top bar. */
    val linkIndicator: @Composable () -> Unit = {},
    /** True with a mouse and a keyboard: wording says "right-click" rather than "long press". */
    val pointerIsMouse: Boolean = false
)

/** Short relative time, the way a document list usually reads. */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0) return ""
    val diff = now - millis
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        days < 365 -> "${days / 7}w ago"
        else -> "${days / 365}y ago"
    }
}
