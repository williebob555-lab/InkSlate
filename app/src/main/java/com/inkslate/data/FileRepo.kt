package com.inkslate.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import android.util.LruCache
import com.inkslate.ink.*
import com.inkslate.ink.StrokeRasteriser
import com.inkslate.pdf.PageSources
import java.io.File

/** One row in the browser. */
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
    val isPdf: Boolean get() = !isDirectory && PageSources.isPdf(file)
    val isImage: Boolean get() = !isDirectory && PageSources.isImage(file)
}

enum class SortBy(val label: String) {
    NAME("Name"), MODIFIED("Last modified"), SIZE("Size"), KIND("Kind")
}

/** A top-level place to start browsing. */
data class StorageRoot(val label: String, val path: File)

/**
 * Filesystem browsing for the file picker.
 *
 * This is a sideloaded app holding `MANAGE_EXTERNAL_STORAGE`, so it walks `java.io.File`
 * directly rather than going through the document picker. That is the whole reason the browser
 * can show real thumbnails and remember arbitrary folders without a permission dance.
 */
class FileRepo(private val context: Context) {

    private val prefs = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private val journal = InkJournal(context)

    // ---- where the handwriting is -------------------------------------------

    /**
     * The handwriting for a document, from wherever it happens to live.
     *
     * Since the ink moved inside the documents themselves there are three possible homes, and
     * this is the single place that knows about all of them:
     *  - the app's working store, which is local, tiny and instant;
     *  - the document itself, which is where anything synced from another device arrives;
     *  - a leftover `.inkdoc` companion, for documents last touched by an older build.
     *
     * [maxEmbeddedScanBytes] keeps the middle case honest: parsing a two-hundred-megabyte
     * textbook to decide whether to draw a badge on a list row is not a trade worth making, and
     * the badge appears anyway the moment the document is opened.
     */
    fun inkFor(file: File, maxEmbeddedScanBytes: Long = 25L * 1024 * 1024): InkDocument? {
        journal.load(file)?.let { return it }
        if (InkEmbedder.supports(file) && file.length() <= maxEmbeddedScanBytes) {
            InkEmbedder.read(file)?.let { return it }
        }
        val legacy = File(InkDocument.sidecarPathFor(file.absolutePath))
        if (legacy.isFile) {
            return runCatching { InkDocument.parse(legacy.readText()) }.getOrNull()
        }
        return null
    }

    /**
     * Whether to badge a document as annotated.
     *
     * Deliberately only asks the cheap sources. Parsing every PDF in the library to decide
     * whether to draw a small label made opening the home screen take seconds; the working store
     * already knows about everything written on this device, and anything synced in from another
     * gets recorded there the first time it is opened.
     */
    private fun hasInk(file: File): Boolean =
        journal.hasInk(file) ||
            File(InkDocument.sidecarPathFor(file.absolutePath)).isFile

    // ---- roots ---------------------------------------------------------------

    fun storageRoots(): List<StorageRoot> = buildList {
        val ext = Environment.getExternalStorageDirectory()
        if (ext != null && ext.isDirectory) {
            add(StorageRoot("Internal storage", ext))
            listOf(
                Environment.DIRECTORY_DOWNLOADS to "Downloads",
                Environment.DIRECTORY_DOCUMENTS to "Documents",
                Environment.DIRECTORY_PICTURES to "Pictures",
                Environment.DIRECTORY_DCIM to "Camera"
            ).forEach { (dir, label) ->
                val f = File(ext, dir)
                if (f.isDirectory) add(StorageRoot(label, f))
            }
        }
        // removable volumes, when present
        context.getExternalFilesDirs(null)
            .filterNotNull()
            .mapNotNull { volumeRootOf(it) }
            .filter { it.isDirectory && it.absolutePath != ext?.absolutePath }
            .forEachIndexed { i, f -> add(StorageRoot(if (i == 0) "SD card" else "Storage ${i + 1}", f)) }
    }

    /** Walk up from /storage/XXXX/Android/data/<pkg>/files to /storage/XXXX. */
    private fun volumeRootOf(appDir: File): File? {
        var f: File? = appDir
        repeat(4) { f = f?.parentFile }
        return f
    }

    // ---- listing -------------------------------------------------------------

    fun list(dir: File, sortBy: SortBy = SortBy.NAME, ascending: Boolean = true,
             showHidden: Boolean = false): List<FileEntry> {
        val children = dir.listFiles() ?: return emptyList()
        val entries = children.mapNotNull { f ->
            if (!showHidden && f.name.startsWith(".")) return@mapNotNull null
            // our own bookkeeping should never show up as content
            if (f.isDirectory && f.name == DocumentRepo.BACKUP_DIR) return@mapNotNull null
            if (!f.isDirectory && f.extension.equals(InkDocument.EXTENSION, true)) return@mapNotNull null
            if (!f.isDirectory && !PageSources.isSupported(f)) return@mapNotNull null

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
        dir.listFiles()?.count { it.isDirectory || PageSources.isSupported(it) } ?: 0

    fun createFolder(parent: File, name: String): Result<File> = runCatching {
        val safe = name.trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
        require(safe.isNotEmpty()) { "Folder name cannot be empty" }
        val f = File(parent, safe)
        require(!f.exists()) { "\"$safe\" already exists" }
        require(f.mkdirs()) { "Could not create the folder" }
        f
    }

    /**
     * Rename a document or folder.
     *
     * The handwriting needs no special handling any more - it is inside the document, so it is
     * carried by the rename itself. What still has to follow are the things that live outside:
     * the version history and the per-file save rules. A leftover companion from an older build
     * is dragged along too, for as long as any remain.
     */
    fun rename(entry: File, newName: String, savePrefs: SavePrefs): Result<File> = runCatching {
        val safe = sanitise(newName)
        val target = File(entry.parentFile, safe)
        require(!target.exists()) { "\"$safe\" already exists" }
        require(entry.renameTo(target)) { "Could not rename" }
        carryOver(entry, target, savePrefs)
        target
    }

    /**
     * Move a document or folder into [destination].
     *
     * Tries a rename first, which is instant and atomic within a volume, and falls back to a
     * copy-then-delete across volumes. The copy is verified before the original goes.
     */
    fun move(entry: File, destination: File, savePrefs: SavePrefs): Result<File> = runCatching {
        require(destination.isDirectory) { "That destination is not a folder" }
        require(entry.absolutePath != destination.absolutePath) { "That is where it already is" }
        require(!destination.absolutePath.startsWith(entry.absolutePath + File.separator)) {
            "A folder cannot be moved inside itself"
        }
        val target = File(destination, entry.name)
        require(!target.exists()) { "\"${entry.name}\" is already in that folder" }

        if (!entry.renameTo(target)) {
            // Different volume. Copy across, confirm it arrived, and only then remove the source.
            if (entry.isDirectory) {
                require(entry.copyRecursively(target, overwrite = false)) { "Could not copy" }
                require(entry.deleteRecursively()) { "Copied, but could not remove the original" }
            } else {
                entry.copyTo(target, overwrite = false)
                require(target.length() == entry.length()) { "The copy came out the wrong size" }
                require(entry.delete()) { "Copied, but could not remove the original" }
            }
        }
        carryOver(entry, target, savePrefs)
        target
    }

    /** Move everything that lives outside a document across with it. */
    private fun carryOver(from: File, to: File, savePrefs: SavePrefs) {
        if (!to.isDirectory) {
            journal.relocate(from, to)
            val oldSidecar = File(InkDocument.sidecarPathFor(from.absolutePath))
            if (oldSidecar.isFile) {
                oldSidecar.renameTo(File(InkDocument.sidecarPathFor(to.absolutePath)))
            }
        }
        savePrefs.moveOverride(from.absolutePath, to.absolutePath)
        renamePinned(from, to)
        renameRecent(from, to)
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
     * The version history is deliberately left behind. It is a few kilobytes in app-private
     * storage, and keeping it means deleting the wrong file is not automatically the end of the
     * handwriting that was in it.
     */
    fun delete(file: File, savePrefs: SavePrefs): Result<Unit> = runCatching {
        if (file.isDirectory) {
            require(file.deleteRecursively()) { "Could not delete the folder" }
        } else {
            require(file.delete()) { "Could not delete the file" }
            File(InkDocument.sidecarPathFor(file.absolutePath)).delete()
            savePrefs.clearOverride(file.absolutePath)
        }
        unpin(file)
        Unit
    }

    // ---- recents and pins ----------------------------------------------------

    fun recents(limit: Int = 12): List<File> =
        (prefs.getString(K_RECENTS, "") ?: "").split('\n')
            .filter { it.isNotBlank() }
            .map(::File)
            .filter { it.isFile }
            .take(limit)

    fun noteOpened(file: File) {
        val current = recents(40).map { it.absolutePath }
        val updated = (listOf(file.absolutePath) + current.filter { it != file.absolutePath }).take(40)
        prefs.edit().putString(K_RECENTS, updated.joinToString("\n")).apply()
    }

    fun pinned(): List<File> =
        (prefs.getString(K_PINS, "") ?: "").split('\n')
            .filter { it.isNotBlank() }.map(::File).filter { it.exists() }

    fun isPinned(f: File) = pinned().any { it.absolutePath == f.absolutePath }

    fun togglePin(f: File) {
        val cur = pinned().map { it.absolutePath }.toMutableList()
        if (!cur.remove(f.absolutePath)) cur.add(f.absolutePath)
        prefs.edit().putString(K_PINS, cur.joinToString("\n")).apply()
    }

    private fun unpin(f: File) {
        val cur = pinned().map { it.absolutePath }.filterNot { it == f.absolutePath }
        prefs.edit().putString(K_PINS, cur.joinToString("\n")).apply()
    }

    private fun renamePinned(from: File, to: File) {
        val cur = pinned().map { it.absolutePath }
        if (from.absolutePath !in cur) return
        val updated = cur.map { if (it == from.absolutePath) to.absolutePath else it }
        prefs.edit().putString(K_PINS, updated.joinToString("\n")).apply()
    }

    private fun renameRecent(from: File, to: File) {
        val cur = (prefs.getString(K_RECENTS, "") ?: "").split('\n').filter { it.isNotBlank() }
        if (from.absolutePath !in cur) return
        val updated = cur.map { if (it == from.absolutePath) to.absolutePath else it }
        prefs.edit().putString(K_RECENTS, updated.joinToString("\n")).apply()
    }

    // ---- library folders -----------------------------------------------------

    /**
     * Folders the user has explicitly added. The home screen is built from these rather than from
     * the whole filesystem, so it stays about coursework instead of every stray PDF on the device.
     */
    fun libraryFolders(): List<File> =
        (prefs.getString(K_LIBRARY, "") ?: "").lines()
            .filter { it.isNotBlank() }
            .map(::File)
            .filter { it.isDirectory }

    fun addLibraryFolder(dir: File) {
        if (!dir.isDirectory) return
        val current = libraryFolders().map { it.absolutePath }
        if (dir.absolutePath in current) return
        val updated = current + dir.absolutePath
        prefs.edit().putString(K_LIBRARY, updated.joinToString(LINE_SEP)).apply()
    }

    fun removeLibraryFolder(dir: File) {
        val updated = libraryFolders().map { it.absolutePath }
            .filterNot { it == dir.absolutePath }
        prefs.edit().putString(K_LIBRARY, updated.joinToString(LINE_SEP)).apply()
    }

    fun isInLibrary(dir: File): Boolean =
        libraryFolders().any { it.absolutePath == dir.absolutePath }

    /**
     * Everything across the added folders, newest first.
     *
     * Scans one level deep by default: deep recursion over a synced Documents tree is slow and
     * usually surfaces noise rather than the assignment you were looking for.
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
                    if (f.name != DocumentRepo.BACKUP_DIR) walk(f, remaining - 1)
                } else if (PageSources.isSupported(f) && seen.add(f.absolutePath)) {
                    out.add(
                        FileEntry(
                            file = f,
                            isDirectory = false,
                            sizeBytes = f.length(),
                            modified = f.lastModified(),
                            hasInk = hasInk(f),
                            childCount = 0
                        )
                    )
                }
            }
        }

        libraryFolders().forEach { walk(it, depth) }
        return out.sortedByDescending { it.modified }.take(limit)
    }

    /**
     * Where new documents go.
     *
     * Set once, from a long press on any folder, rather than asked on every new document. Falls
     * back to the first folder in the library so "New" always works without a decision.
     */
    var defaultNewFolder: File?
        get() = prefs.getString(K_NEW_DIR, null)?.let(::File)?.takeIf { it.isDirectory }
            ?: libraryFolders().firstOrNull()
        set(v) { prefs.edit().putString(K_NEW_DIR, v?.absolutePath).apply() }

    fun isDefaultNewFolder(dir: File) =
        prefs.getString(K_NEW_DIR, null) == dir.absolutePath

    /**
     * Find documents by name across the library.
     *
     * Matches on the whole relative path, so "phys sheet 3" finds `PHYS161/Weekly Sheet 3.pdf`
     * without the words having to be adjacent or in order - which is how people actually remember
     * where they put something.
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
                    if (f.name != DocumentRepo.BACKUP_DIR) walk(f, root, remaining - 1)
                    continue
                }
                if (!PageSources.isSupported(f)) continue
                val hay = f.absolutePath.removePrefix(root.absolutePath).lowercase()
                if (!terms.all { hay.contains(it) }) continue
                if (!seen.add(f.absolutePath)) continue
                out.add(
                    FileEntry(f, false, f.length(), f.lastModified(), hasInk(f), 0)
                )
            }
        }

        libraryFolders().forEach { walk(it, it, depth) }

        // Best match first: a hit in the filename beats one that only matched a parent folder.
        return out.sortedWith(
            compareByDescending<FileEntry> { e ->
                terms.count { e.name.lowercase().contains(it) }
            }.thenByDescending { it.modified }
        )
    }

    /**
     * A few documents from inside a folder, for the preview on its tile.
     *
     * Shallow and capped: this runs for every folder on screen, and a folder tile is a hint about
     * what is inside, not an inventory.
     */
    fun folderPreview(dir: File, count: Int = 4): List<File> {
        val direct = dir.listFiles()?.asSequence()
            ?.filterNot { it.name.startsWith(".") }
            ?.filter { !it.isDirectory && PageSources.isSupported(it) }
            ?.sortedByDescending { it.lastModified() }
            ?.take(count)
            ?.toList().orEmpty()
        if (direct.size >= count) return direct

        // not enough at this level, so look one deeper before giving up
        val deeper = dir.listFiles()?.asSequence()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.flatMap { sub ->
                sub.listFiles()?.asSequence()
                    ?.filter { !it.isDirectory && PageSources.isSupported(it) } ?: emptySequence()
            }
            ?.sortedByDescending { it.lastModified() }
            ?.take(count - direct.size)
            ?.toList().orEmpty()
        return direct + deeper
    }

    /** Immediate subfolders, for navigating the library without leaving the home screen. */
    fun subfolders(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != DocumentRepo.BACKUP_DIR }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    /** Documents directly inside a folder. */
    fun documentsIn(dir: File): List<FileEntry> =
        dir.listFiles()
            ?.filter { !it.isDirectory && !it.name.startsWith(".") && PageSources.isSupported(it) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { FileEntry(it, false, it.length(), it.lastModified(), hasInk(it), 0) }
            .orEmpty()

    var lastFolder: String?
        get() = prefs.getString(K_LAST_DIR, null)
        set(v) { prefs.edit().putString(K_LAST_DIR, v).apply() }

    // ---- thumbnails ----------------------------------------------------------

    /**
     * Render a thumbnail with the user's ink composited on top.
     *
     * Showing the annotated state is what makes the grid usable: an untouched worksheet and one
     * you already finished look identical otherwise.
     */
    fun thumbnail(file: File, maxPx: Int = THUMB_PX): Bitmap? {
        val key = "${file.absolutePath}|${file.lastModified()}|$maxPx"
        memCache.get(key)?.let { return it }

        val source = PageSources.open(file, context) ?: return null
        try {
            val dim = source.pageDim(0)
            val w = PageSources.thumbWidthFor(dim, maxPx)
            var bmp = source.renderPage(0, w) ?: return null
            bmp = PageSources.clampBitmap(bmp, maxPx)

            runCatching {
                inkFor(file)?.let { ink ->
                    val strokes = ink.strokesOn(0)
                    if (strokes.isNotEmpty()) {
                        val mutable = if (bmp.isMutable) bmp
                        else bmp.copy(Bitmap.Config.ARGB_8888, true).also { bmp.recycle() }
                        val canvas = Canvas(mutable)
                        canvas.scale(mutable.width / dim.width, mutable.height / dim.height)
                        StrokeRasteriser.drawAll(canvas, strokes)
                        bmp = mutable
                    }
                }
            }
            memCache.put(key, bmp)
            return bmp
        } finally {
            source.close()
        }
    }

    fun invalidateThumb(file: File) {
        // keys embed mtime, so a changed file misses naturally; this is for in-place edits
        memCache.snapshot().keys
            .filter { it.startsWith(file.absolutePath + "|") }
            .forEach { memCache.remove(it) }
    }

    companion object {
        private const val K_RECENTS = "recents"
        private const val K_PINS = "pins"
        private const val K_LAST_DIR = "last_dir"
        private const val K_LIBRARY = "library_folders"
        private const val K_NEW_DIR = "new_document_folder"

        /** Path lists are stored newline-delimited; paths cannot contain one. */
        private val LINE_SEP = System.lineSeparator().takeIf { it == "\n" } ?: "\n"
        const val THUMB_PX = 320

        /** Bounded by bytes rather than count, since page sizes vary wildly. */
        private val memCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }
}
