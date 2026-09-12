package com.inkslate.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.inkslate.core.InkDocument
import java.io.File
import kotlin.math.roundToInt

/** One row in the browser. The Android [com.inkslate.data.FileEntry], field for field. */
data class FileEntry(
    val file: File,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modified: Long,
    /** True when this document carries handwriting, so the browser can badge it. */
    val hasInk: Boolean,
    /** Number of supported children, for folders. */
    val childCount: Int
) {
    val name: String get() = file.name
    val isPdf: Boolean get() = !isDirectory && DesktopSources.isPdf(file)
    val isImage: Boolean get() = !isDirectory && DesktopSources.isImage(file)
}

enum class SortBy(val label: String) {
    NAME("Name"), MODIFIED("Last modified"), SIZE("Size"), KIND("Kind")
}

/** A top-level place to start browsing. */
data class StorageRoot(val label: String, val path: File)

/**
 * Filesystem browsing, recents, starred items and thumbnails.
 *
 * The mirror of Android's `FileRepo`, with the same method names on purpose: the two home screens
 * are meant to be the same screen, and a screen is easiest to keep identical when the thing
 * feeding it answers the same questions. What differs is only what Windows makes available -
 * drive letters and the shell's user folders in place of `Environment.getExternalStorageDirectory`.
 *
 * No permission gate here. Windows already lets an application read the user's own files, so the
 * Android build's "All files access" screen has no counterpart and the app opens straight on Home.
 */
class FileRepo {

    // ---- where the handwriting is -------------------------------------------

    /**
     * The handwriting for a document, from wherever it happens to live: the local working copy,
     * the document itself, or a leftover `.inkdoc` companion from an older build.
     */
    fun inkFor(file: File, maxEmbeddedScanBytes: Long = 25L * 1024 * 1024): InkDocument? {
        DocumentIO.loadWorking(file)?.let { return it }
        if (DesktopEmbedder.supports(file) && file.length() <= maxEmbeddedScanBytes) {
            DesktopEmbedder.read(file)?.let { return it }
        }
        val legacy = DocumentIO.sidecarFor(file)
        if (legacy.isFile) return runCatching { InkDocument.parse(legacy.readText()) }.getOrNull()
        return null
    }

    /**
     * Whether to badge a document as annotated.
     *
     * Only the cheap sources are asked, exactly as on Android. Opening a PDF to decide whether to
     * draw a small label would make Home take seconds on a folder of textbooks; anything synced
     * in from the tablet earns its badge the first time it is opened here.
     */
    private fun hasInk(file: File): Boolean =
        DocumentIO.hasWorking(file) || DocumentIO.sidecarFor(file).isFile

    // ---- roots ---------------------------------------------------------------

    /**
     * Where browsing can start.
     *
     * The shell folders first, because that is where coursework actually lands, then the drives.
     * OneDrive gets its own entry when it is present - on a school laptop the real Documents
     * folder is often redirected into it, and the two look identical from here.
     */
    fun storageRoots(): List<StorageRoot> = buildList {
        val home = File(System.getProperty("user.home"))
        listOf(
            "Documents" to "Documents",
            "Downloads" to "Downloads",
            "Desktop" to "Desktop",
            "Pictures" to "Pictures"
        ).forEach { (dir, label) ->
            val f = File(home, dir)
            if (f.isDirectory) add(StorageRoot(label, f))
        }
        System.getenv("OneDrive")?.let { path ->
            val f = File(path)
            if (f.isDirectory) add(StorageRoot("OneDrive", f))
        }
        if (home.isDirectory) add(StorageRoot(home.name.ifEmpty { "Home" }, home))
        File.listRoots()?.filter { it.isDirectory }?.forEach {
            add(StorageRoot(it.absolutePath.trimEnd('\\', '/').ifEmpty { it.absolutePath }, it))
        }
    }.distinctBy { it.path.absolutePath }

    // ---- listing -------------------------------------------------------------

    fun list(
        dir: File,
        sortBy: SortBy = SortBy.NAME,
        ascending: Boolean = true,
        showHidden: Boolean = false
    ): List<FileEntry> {
        val children = dir.listFiles() ?: return emptyList()
        val entries = children.mapNotNull { f ->
            if (!showHidden && (f.name.startsWith(".") || f.isHidden)) return@mapNotNull null
            if (f.isDirectory && f.name == BACKUP_DIR) return@mapNotNull null
            if (!f.isDirectory && f.extension.equals(InkDocument.EXTENSION, true)) {
                return@mapNotNull null
            }
            if (!f.isDirectory && !DesktopSources.isSupported(f)) return@mapNotNull null

            FileEntry(
                file = f,
                isDirectory = f.isDirectory,
                sizeBytes = if (f.isDirectory) 0 else f.length(),
                modified = f.lastModified(),
                hasInk = !f.isDirectory && hasInk(f),
                childCount = if (f.isDirectory) countSupported(f) else 0
            )
        }
        val cmp = when (sortBy) {
            SortBy.NAME -> compareBy<FileEntry> { it.name.lowercase() }
            SortBy.MODIFIED -> compareBy { it.modified }
            SortBy.SIZE -> compareBy { it.sizeBytes }
            SortBy.KIND -> compareBy<FileEntry> { it.file.extension.lowercase() }
                .thenBy { it.name.lowercase() }
        }
        val sorted = entries.sortedWith(if (ascending) cmp else cmp.reversed())
        // folders always lead, regardless of sort - matches every file manager people know
        return sorted.sortedByDescending { it.isDirectory }
    }

    private fun countSupported(dir: File): Int =
        runCatching {
            dir.listFiles()?.count { it.isDirectory || DesktopSources.isSupported(it) } ?: 0
        }.getOrDefault(0)

    fun createFolder(parent: File, name: String): Result<File> = runCatching {
        DesktopPeers.announceLibraryChanged()
        val safe = sanitise(name)
        val f = File(parent, safe)
        require(!f.exists()) { "\"$safe\" already exists" }
        require(f.mkdirs()) { "Could not create the folder" }
        f
    }

    /**
     * Rename a document or folder.
     *
     * The handwriting needs no special handling - it is inside the document, so the rename
     * carries it. What has to follow are the things that live outside: the local working copy,
     * a leftover companion file, and this machine's own lists.
     */
    fun rename(entry: File, newName: String): Result<File> = runCatching {
        DesktopPeers.announceLibraryChanged()
        val safe = sanitise(newName)
        val target = File(entry.parentFile, safe)
        require(!target.exists()) { "\"$safe\" already exists" }
        require(entry.renameTo(target)) { "Could not rename" }
        carryOver(entry, target)
        target
    }

    /**
     * Move a document or folder into [destination].
     *
     * A rename first, which is instant within a volume, falling back to copy-then-delete across
     * drives. The copy is verified before the original goes.
     */
    fun move(entry: File, destination: File): Result<File> = runCatching {
        require(destination.isDirectory) { "That destination is not a folder" }
        require(entry.absolutePath != destination.absolutePath) { "That is where it already is" }
        require(!destination.absolutePath.startsWith(entry.absolutePath + File.separator)) {
            "A folder cannot be moved inside itself"
        }
        val target = File(destination, entry.name)
        require(!target.exists()) { "\"${entry.name}\" is already in that folder" }

        if (!entry.renameTo(target)) {
            if (entry.isDirectory) {
                require(entry.copyRecursively(target, overwrite = false)) { "Could not copy" }
                require(entry.deleteRecursively()) { "Copied, but could not remove the original" }
            } else {
                entry.copyTo(target, overwrite = false)
                require(target.length() == entry.length()) { "The copy came out the wrong size" }
                require(entry.delete()) { "Copied, but could not remove the original" }
            }
        }
        carryOver(entry, target)
        target
    }

    /** Move everything that lives outside a document across with it. */
    private fun carryOver(from: File, to: File) {
        if (!to.isDirectory) {
            DocumentIO.relocateWorking(from, to)
            val oldSidecar = DocumentIO.sidecarFor(from)
            if (oldSidecar.isFile) oldSidecar.renameTo(DocumentIO.sidecarFor(to))
        }
        renameInList(K_PINS, from, to)
        renameInList(K_RECENTS, from, to)
        renameInList(K_LIBRARY, from, to)
        if (DesktopPrefs.get(K_NEW_DIR) == from.absolutePath) {
            DesktopPrefs.put(K_NEW_DIR, to.absolutePath)
        }
        invalidateThumb(from)
    }

    private fun sanitise(name: String): String {
        val safe = name.trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
        require(safe.isNotEmpty()) { "Name cannot be empty" }
        require(safe != "." && safe != "..") { "That name cannot be used" }
        return safe
    }

    /**
     * Delete a document or folder.
     *
     * The local working copy is deliberately dropped with it: it is keyed by path, so anything
     * left behind would be inherited by the next document to take this name - which is exactly
     * the bug the Android side had to fix.
     */
    fun delete(file: File): Result<Unit> = runCatching {
        DesktopPeers.announceLibraryChanged()
        if (file.isDirectory) {
            require(file.deleteRecursively()) { "Could not delete the folder" }
        } else {
            require(file.delete()) { "Could not delete the file" }
            DocumentIO.sidecarFor(file).delete()
            DocumentIO.forgetWorking(file)
        }
        removeFromList(K_PINS, file)
        removeFromList(K_RECENTS, file)
        removeFromList(K_LIBRARY, file)
        invalidateThumb(file)
    }

    // ---- recents and pins ----------------------------------------------------

    fun recents(limit: Int = 12): List<File> =
        DesktopPrefs.getList(K_RECENTS).map(::File).filter { it.isFile }.take(limit)

    fun noteOpened(file: File) {
        val current = DesktopPrefs.getList(K_RECENTS)
        val updated = (listOf(file.absolutePath) + current.filter { it != file.absolutePath })
            .take(40)
        DesktopPrefs.putList(K_RECENTS, updated)
    }

    fun pinned(): List<File> = DesktopPrefs.getList(K_PINS).map(::File).filter { it.exists() }

    fun isPinned(f: File) = DesktopPrefs.getList(K_PINS).any { it == f.absolutePath }

    fun togglePin(f: File) {
        val cur = DesktopPrefs.getList(K_PINS).toMutableList()
        if (!cur.remove(f.absolutePath)) cur.add(f.absolutePath)
        DesktopPrefs.putList(K_PINS, cur)
    }

    private fun removeFromList(key: String, f: File) {
        DesktopPrefs.putList(key, DesktopPrefs.getList(key).filterNot { it == f.absolutePath })
    }

    private fun renameInList(key: String, from: File, to: File) {
        val cur = DesktopPrefs.getList(key)
        if (from.absolutePath !in cur) return
        DesktopPrefs.putList(key, cur.map { if (it == from.absolutePath) to.absolutePath else it })
    }

    // ---- library folders -----------------------------------------------------

    /**
     * Folders the user has explicitly added. Home is built from these rather than from the whole
     * disk, so it stays about coursework instead of every PDF that ever reached Downloads.
     */
    fun libraryFolders(): List<File> =
        DesktopPrefs.getList(K_LIBRARY).map(::File).filter { it.isDirectory }

    fun addLibraryFolder(dir: File) {
        if (!dir.isDirectory) return
        val current = DesktopPrefs.getList(K_LIBRARY)
        if (dir.absolutePath in current) return
        DesktopPrefs.putList(K_LIBRARY, current + dir.absolutePath)
    }

    fun removeLibraryFolder(dir: File) = removeFromList(K_LIBRARY, dir)

    fun isInLibrary(dir: File): Boolean =
        DesktopPrefs.getList(K_LIBRARY).any { it == dir.absolutePath }

    /**
     * Everything across the added folders, newest first. Two levels by default: deeper recursion
     * over a synced tree is slow and usually surfaces noise rather than the assignment.
     */
    fun libraryFiles(limit: Int = 60, depth: Int = 2): List<FileEntry> {
        val seen = HashSet<String>()
        val out = ArrayList<FileEntry>()

        fun walk(dir: File, remaining: Int) {
            if (remaining <= 0) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (f.name != BACKUP_DIR) walk(f, remaining - 1)
                } else if (DesktopSources.isSupported(f) && seen.add(f.absolutePath)) {
                    out.add(FileEntry(f, false, f.length(), f.lastModified(), hasInk(f), 0))
                }
            }
        }

        libraryFolders().forEach { walk(it, depth) }
        return out.sortedByDescending { it.modified }.take(limit)
    }

    /**
     * Where new documents go. Set once, from a right-click on any folder, rather than asked every
     * time. Falls back to the first folder in the library so "New" never has to ask.
     */
    var defaultNewFolder: File?
        get() = DesktopPrefs.get(K_NEW_DIR)?.let(::File)?.takeIf { it.isDirectory }
            ?: libraryFolders().firstOrNull()
        set(v) = DesktopPrefs.put(K_NEW_DIR, v?.absolutePath)

    fun isDefaultNewFolder(dir: File) = DesktopPrefs.get(K_NEW_DIR) == dir.absolutePath

    /**
     * Find documents by name across the library.
     *
     * Matches on the whole relative path, so "phys sheet 3" finds `PHYS161/Weekly Sheet 3.pdf`
     * without the words having to be adjacent or in order.
     */
    fun search(query: String, limit: Int = 80, depth: Int = 6): List<FileEntry> {
        val terms = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()

        val out = ArrayList<FileEntry>()
        val seen = HashSet<String>()

        fun walk(dir: File, root: File, remaining: Int) {
            if (remaining <= 0 || out.size >= limit) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (out.size >= limit) return
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (f.name != BACKUP_DIR) walk(f, root, remaining - 1)
                    continue
                }
                if (!DesktopSources.isSupported(f)) continue
                val hay = f.absolutePath.removePrefix(root.absolutePath).lowercase()
                if (!terms.all { hay.contains(it) }) continue
                if (!seen.add(f.absolutePath)) continue
                out.add(FileEntry(f, false, f.length(), f.lastModified(), hasInk(f), 0))
            }
        }

        libraryFolders().forEach { walk(it, it, depth) }

        // Best match first: a hit in the filename beats one that only matched a parent folder.
        return out.sortedWith(
            compareByDescending<FileEntry> { e -> terms.count { e.name.lowercase().contains(it) } }
                .thenByDescending { it.modified }
        )
    }

    /**
     * A few documents from inside a folder, for the preview on its tile. Shallow and capped: this
     * runs for every folder on screen, and a tile is a hint about what is inside, not an inventory.
     */
    fun folderPreview(dir: File, count: Int = 4): List<File> {
        val direct = dir.listFiles()?.asSequence()
            ?.filterNot { it.name.startsWith(".") }
            ?.filter { !it.isDirectory && DesktopSources.isSupported(it) }
            ?.sortedByDescending { it.lastModified() }
            ?.take(count)
            ?.toList().orEmpty()
        if (direct.size >= count) return direct

        val deeper = dir.listFiles()?.asSequence()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.flatMap { sub ->
                sub.listFiles()?.asSequence()
                    ?.filter { !it.isDirectory && DesktopSources.isSupported(it) } ?: emptySequence()
            }
            ?.sortedByDescending { it.lastModified() }
            ?.take(count - direct.size)
            ?.toList().orEmpty()
        return direct + deeper
    }

    /** Immediate subfolders, for navigating the library without leaving Home. */
    fun subfolders(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != BACKUP_DIR }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    /** Documents directly inside a folder. */
    fun documentsIn(dir: File): List<FileEntry> =
        dir.listFiles()
            ?.filter { !it.isDirectory && !it.name.startsWith(".") && DesktopSources.isSupported(it) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { FileEntry(it, false, it.length(), it.lastModified(), hasInk(it), 0) }
            .orEmpty()

    var lastFolder: String?
        get() = DesktopPrefs.get(K_LAST_DIR)
        set(v) = DesktopPrefs.put(K_LAST_DIR, v)

    // ---- thumbnails ----------------------------------------------------------

    /**
     * Render a first-page thumbnail with the user's ink composited on top.
     *
     * Drawn by [drawStroke] - the same function the editor paints with - for the reason the
     * Android README gives about its rasteriser: a second renderer for previews is how "it looked
     * different in the list" bugs are made. Showing the annotated state is also what makes the
     * grid usable, since a blank worksheet and a finished one are otherwise the same picture.
     */
    fun thumbnail(file: File, maxPx: Int = THUMB_PX): ImageBitmap? {
        val key = "${file.absolutePath}|${file.lastModified()}|$maxPx"
        synchronized(cache) { cache[key] }?.let { return it }

        val source = DesktopSources.open(file) ?: return null
        try {
            val dim = source.pageDim(0)
            if (dim.width <= 0f || dim.height <= 0f) return null
            val targetWidth = if (dim.width >= dim.height) maxPx
            else (maxPx * (dim.width / dim.height)).roundToInt().coerceAtLeast(16)
            val page = source.render(0, targetWidth) ?: return null

            val w = page.width
            val h = page.height
            val strokes = runCatching { inkFor(file)?.strokesOn(0) }.getOrNull().orEmpty()

            val out = ImageBitmap(w, h)
            val canvas = Canvas(out)
            CanvasDrawScope().draw(
                Density(1f), LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())
            ) {
                drawImage(page, dstOffset = IntOffset.Zero, dstSize = IntSize(w, h))
                if (strokes.isNotEmpty()) {
                    scale(w / dim.width, h / dim.height, pivot = Offset.Zero) {
                        strokes.forEach { drawStroke(it) }
                    }
                }
            }
            synchronized(cache) { cache[key] = out }
            return out
        } finally {
            source.close()
        }
    }

    /** Keys embed the modification time, so a changed file misses on its own; this is for
     *  edits that leave the timestamp alone, such as a rename. */
    fun invalidateThumb(file: File) {
        synchronized(cache) {
            cache.keys.filter { it.startsWith(file.absolutePath + "|") }.forEach(cache::remove)
        }
    }

    companion object {
        private const val K_RECENTS = "recents"
        private const val K_PINS = "pins"
        private const val K_LAST_DIR = "last_dir"
        private const val K_LIBRARY = "library_folders"
        private const val K_NEW_DIR = "new_document_folder"

        /** Matches the Android app's backup directory, so a synced folder hides it here too. */
        const val BACKUP_DIR = ".inkslate-backups"
        const val THUMB_PX = 320

        /**
         * Bounded by count rather than bytes. Unlike Android's `LruCache` there is no cheap
         * byte size on a Skia-backed bitmap, and a page thumbnail is a known quantity: 320 px
         * on its long edge, so a hundred of them is tens of megabytes at worst.
         */
        private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>) = size > 120
        }
    }
}
