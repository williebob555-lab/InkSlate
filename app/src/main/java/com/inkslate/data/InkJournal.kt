package com.inkslate.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * The app's own copy of every document's handwriting.
 *
 * Two jobs, both of which used to be done by a `.inkdoc` file sitting next to the document:
 *
 *  - **Working store.** Autosave writes here every few seconds. It lives in app-private storage,
 *    so it never appears in the file explorer, never has to be copied alongside a document, and
 *    never has to be renamed to follow one. The document itself only gets written when the user
 *    saves or leaves, which is what they asked for and also what makes annotating a thousand-page
 *    textbook practical.
 *  - **History.** A rolling set of earlier versions, which is the only thing standing between a
 *    bad merge and a lost afternoon. Keeping it here rather than in a dot-folder beside the
 *    document means it survives the document being moved, and cannot be swept up by a sync tool.
 *
 * The document is still the system of record - it is what travels between devices. This is the
 * safety net under it.
 */
class InkJournal(root: File) {

    /**
     * The ordinary way in: the store lives in app-private storage.
     *
     * The [File] constructor above is what the app uses everywhere else, and exists so the rules
     * about which working copy belongs to which document can be tested against a temporary
     * directory. They are worth testing precisely because getting them wrong is silent - a
     * document opens blank and nothing reports an error.
     */
    constructor(context: Context) : this(File(context.filesDir, "ink"))

    private val root = root.apply { mkdirs() }

    /**
     * Documents are keyed by path, not by content.
     *
     * A hash keeps the name filesystem-safe and bounded. The original path is written alongside
     * it so the store stays inspectable and so a document that moves can still be matched up.
     */
    private fun dirFor(file: File): File {
        val key = sha1(file.absolutePath).take(24)
        return File(root, key).apply { mkdirs() }
    }

    private fun currentFile(file: File) = File(dirFor(file), CURRENT)
    private fun historyDir(file: File) = File(dirFor(file), "history").apply { mkdirs() }

    // ---- working store -------------------------------------------------------

    fun load(file: File): InkDocument? = read(currentFile(file))

    /**
     * Record that the working copy and the document's own payload hold the same handwriting.
     *
     * [payloadStamp] identifies what went into the document; the timestamp pins which version of
     * the working copy that was. Both have to match again later, because the working copy is
     * written *before* the document and an interrupted save leaves it ahead - which is precisely
     * the case where it must not be skipped.
     */
    fun noteInSync(file: File, payloadStamp: String) {
        runCatching {
            val note = StringBuilder(payloadStamp).appendLine()
                .append(currentFile(file).lastModified())
            File(dirFor(file), SYNC).writeText(note.toString())
        }
    }

    /**
     * Whether the working copy is still exactly the handwriting [payloadStamp] describes.
     *
     * Two file stats against a full parse of every stroke, which is what opening a document was
     * spending to merge a copy identical to the one it had just read out of the file.
     */
    fun isInSync(file: File, payloadStamp: String): Boolean = runCatching {
        val lines = File(dirFor(file), SYNC).takeIf { it.isFile }?.readLines() ?: return false
        lines.getOrNull(0)?.trim() == payloadStamp &&
            lines.getOrNull(1)?.trim()?.toLongOrNull() == currentFile(file).lastModified()
    }.getOrDefault(false)

    /**
     * Save the working copy, snapshotting what was there first.
     *
     * Unconditional snapshots rather than clever ones: the writes worth having a copy of are
     * precisely the ones nobody saw coming.
     */
    fun save(file: File, doc: InkDocument): Boolean {
        val target = currentFile(file)
        // The count noted alongside the working copy describes what is about to be replaced,
        // which is exactly what the snapshot will contain.
        snapshot(file, target, strokeCount(file))
        writeMeta(file, doc.totalStrokes)
        noteOwnership(file, doc)
        return write(target, doc)
    }

    /**
     * Record handwriting that came from the document itself, without making a history entry.
     *
     * Called when a document is opened. Two reasons: it gives anything synced in from another
     * device an immediate local copy, and it means the browser can tell at a glance which
     * documents have been written on without parsing every PDF in the library to find out.
     */
    fun seed(file: File, doc: InkDocument) {
        if (doc.totalStrokes == 0) return
        val target = currentFile(file)
        // Compare against the recorded count rather than reading the stored document back.
        if (strokeCount(file) >= doc.totalStrokes && currentFile(file).isFile) return
        writeMeta(file, doc.totalStrokes)
        noteOwnership(file, doc)
        write(target, doc)
    }

    /**
     * Whether this document has handwriting we already know about locally.
     *
     * Reads a two-line note rather than the document. The browser asks this for every row it
     * draws, and parsing a few megabytes of stroke data to decide whether to render the word
     * "annotated" is not a trade worth making.
     */
    fun hasInk(file: File): Boolean = strokeCount(file) > 0

    /**
     * How much handwriting this store last recorded for [file], or 0 if it knows of none.
     *
     * Read off a two-line note rather than out of the document, so it costs nothing and can be
     * asked on every open. Its use is a sanity check: if the store says a document had forty
     * marks and it has just been loaded with none, something went wrong on the way in, and the
     * app should stop writing before it makes that permanent.
     */
    fun recordedStrokeCount(file: File): Int = strokeCount(file)

    private fun strokeCount(file: File): Int = runCatching {
        val meta = File(dirFor(file), META)
        if (!meta.isFile) return 0
        meta.readLines().getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    }.getOrDefault(0)

    /**
     * Replace the working copy outright, without snapshotting what was there.
     *
     * For the one case where the previous working copy is not an earlier version of the same
     * thing but a description of a document that no longer exists: after pages have been added,
     * removed, reordered or turned, every page index in it means something different.
     */
    fun setWorking(file: File, doc: InkDocument): Boolean {
        writeMeta(file, doc.totalStrokes)
        noteOwnership(file, doc)
        return write(currentFile(file), doc)
    }

    /**
     * Note that the document's pages were deliberately restructured, just now.
     *
     * Snapshots taken before this moment describe a different arrangement of pages. They are
     * still worth keeping - a rearrangement is exactly the kind of thing someone regrets - but
     * they are no longer *comparable* to what is on screen, and comparing them anyway is what
     * made turning a page announce that handwriting had gone missing.
     */
    fun noteRestructured(file: File) {
        runCatching {
            File(dirFor(file), RESTRUCTURED).writeText(System.currentTimeMillis().toString())
        }
    }

    /** When the pages were last restructured, or 0 if they never were. */
    fun restructuredAt(file: File): Long = runCatching {
        File(dirFor(file), RESTRUCTURED).takeIf { it.isFile }?.readText()?.trim()?.toLong() ?: 0L
    }.getOrDefault(0L)

    /** Forget a document's working copy once it is safely inside the document itself. */
    fun clearCurrent(file: File) {
        runCatching { currentFile(file).delete() }
    }

    // ---- history -------------------------------------------------------------

    private fun snapshot(source: File, current: File, strokes: Int) {
        if (!current.isFile || current.length() < 2L) return
        runCatching {
            val dir = historyDir(source)
            val copy = File(dir, versionName(strokes))
            if (!copy.exists()) current.copyTo(copy)
            prune(dir)
        }.onFailure { EventLog.warn("journal", "Could not snapshot: ${it.message}") }
    }

    /** Explicitly record a version - used just before writing into the document itself. */
    fun record(file: File, doc: InkDocument) {
        runCatching {
            val dir = historyDir(file)
            val copy = File(dir, versionName(doc.totalStrokes))
            val current = currentFile(file)
            // Almost every call arrives moments after an autosave wrote this very document to
            // the working copy. Copying those bytes is a file copy; building them again is a
            // full serialisation of every stroke in the document, which on a marked-up
            // assignment is most of what made saving slow.
            if (current.isFile && strokeCount(file) == doc.totalStrokes) {
                current.copyTo(copy, overwrite = true)
            } else {
                copy.writeText(doc.compacted().serialize())
            }
            writeMeta(file, doc.totalStrokes)
            prune(dir)
        }
    }

    /**
     * Keep the recent versions, and always keep the fullest one.
     *
     * Pruning purely by age or count would throw away the only complete copy exactly when a wipe
     * is discovered late, which is the common case: handwriting going missing is not obvious, and
     * a page can look plausibly blank for a week.
     *
     * "Fullest" is read off the filename rather than out of the file. Parsing every version to
     * count its strokes meant that once the history was full - which takes about twenty minutes
     * of autosaves - every subsequent save re-read the entire history first. On a heavily
     * annotated document that was the whole of the delay before a save or an exit.
     */
    private fun prune(dir: File) {
        val all = dir.listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() }
            ?: return
        if (all.size <= MAX_HISTORY) return
        val fullest = all.maxByOrNull { countOf(it) }
        all.drop(MAX_HISTORY).filter { it != fullest }.forEach { it.delete() }
    }

    /** Earlier versions of a document's handwriting, newest first. */
    fun history(file: File): List<File> =
        historyDir(file).listFiles { f -> f.isFile }
            ?.sortedByDescending { it.lastModified() }.orEmpty()

    /**
     * Every version with how much handwriting is in it, newest first.
     *
     * The count comes from the filename, so asking "is anything on screen poorer than something
     * already saved" costs a directory listing rather than a parse of the entire history.
     */
    fun historyCounts(file: File): List<Pair<File, Int>> =
        history(file).map { it to countOf(it) }

    /** `<when>-<strokes>.inkdoc`: the stroke count is part of the name so nothing has to read it. */
    private fun versionName(strokes: Int) =
        "${System.currentTimeMillis()}-$strokes.${InkDocument.EXTENSION}"

    /**
     * How many strokes a version holds.
     *
     * Versions written before the count moved into the filename are parsed once and renamed, so
     * a store that predates this pays for the migration a single time instead of on every save.
     */
    private fun countOf(f: File): Int {
        NAMED_VERSION.matchEntire(f.name)?.let { return it.groupValues[2].toInt() }
        val counted = read(f)?.totalStrokes ?: return -1
        runCatching {
            val renamed = File(f.parentFile, "${f.nameWithoutExtension}-$counted.${InkDocument.EXTENSION}")
            if (!renamed.exists()) f.renameTo(renamed)
        }
        return counted
    }

    fun peek(version: File): InkDocument? = read(version)

    // ---- moving with the document -------------------------------------------

    /**
     * Follow a document that has been renamed or moved, so its history is not orphaned.
     *
     * Called when the app performs the rename, and also when a rename made outside the app is
     * detected on open.
     */
    fun relocate(from: File, to: File) {
        runCatching {
            val src = dirFor(from)
            val dst = dirFor(to)
            if (src.absolutePath == dst.absolutePath) return
            if (!src.isDirectory) { writeMeta(to); return }
            src.listFiles()?.forEach { child ->
                val target = File(dst, child.name)
                if (child.isDirectory) {
                    target.mkdirs()
                    child.listFiles()?.forEach { it.copyTo(File(target, it.name), overwrite = true) }
                } else {
                    child.copyTo(target, overwrite = true)
                }
            }
            src.deleteRecursively()
            writeMeta(to)
            EventLog.info("journal", "Moved handwriting history to ${to.name}")
        }
    }

    /** Every document this store knows about, as absolute paths. */
    fun knownPaths(): List<String> =
        root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { runCatching { File(it, META).readLines().firstOrNull() }.getOrNull() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /**
     * A tiny note beside each working copy: the document's path, then how much is in it.
     *
     * Two lines rather than a format, because the only thing that ever reads it is this class and
     * a person trying to work out what is in the store.
     */
    /**
     * Record which document the working copy belongs to.
     *
     * The store is keyed by path, and a path is not an identity: delete a document and make a new
     * one with the same name and it hashes to the same folder. Without this note the new document
     * would open carrying the deleted one's handwriting, because the working copy is merged in
     * whenever the document itself has none of its own.
     *
     * The document's own size and modification time are recorded alongside the id, because a
     * brand-new document has no id to compare against - it has no handwriting in it yet.
     *
     * **This has to be called again after the app itself rewrites the document.** Saving changes
     * the file's size and timestamp, so a note taken before the write describes a file that no
     * longer exists - and the working copy it vouches for would then be refused on the next open,
     * as belonging to some other document that once had this name. That is not a hypothetical:
     * a document this app last saved is deliberately not parsed when it is reopened, so the
     * working copy is the *only* source of its handwriting, and refusing it opens the document
     * blank.
     */
    fun noteOwnership(file: File, doc: InkDocument) {
        runCatching {
            val note = StringBuilder(doc.docId).appendLine()
                .append(file.length()).appendLine()
                .append(file.lastModified()).appendLine()
            File(dirFor(file), OWNER).writeText(note.toString())
        }
    }

    /**
     * Whether [working] is handwriting for the document now at [file], rather than for a
     * different document that once had the same name.
     *
     * [embeddedDocId] is the id carried by the document's own handwriting, or null when it has
     * none. When the document names an id, that settles it outright.
     *
     * [isOurLastOutput] is true when the file on disk is still byte for byte what this app wrote
     * the last time it saved this path. Nothing else can be that, so it is proof of identity on
     * its own - and it is the case that matters most, because a document this app last saved is
     * deliberately not parsed when reopened, which leaves the working copy as the only source of
     * its handwriting. It is also what rescues a store written before ownership was re-stamped
     * after each save, where the note on disk describes the document as it was before its last
     * write and can never match again.
     *
     * Failing both, the only evidence left is whether the document is still the file we wrote the
     * working copy against; anything else is a different document wearing the same name.
     */
    fun belongsTo(
        file: File,
        embeddedDocId: String?,
        working: InkDocument,
        isOurLastOutput: Boolean = false
    ): Boolean {
        if (embeddedDocId != null) return embeddedDocId == working.docId
        if (isOurLastOutput) return true
        val owner = File(dirFor(file), OWNER).takeIf { it.isFile }?.readLines()
            // An entry written before this note existed cannot prove it belongs here. It is left
            // on disk rather than deleted - Settings can still recover it - but it is not merged
            // into a document that never asked for it.
            ?: return false
        return owner.getOrNull(1)?.trim()?.toLongOrNull() == file.length() &&
            owner.getOrNull(2)?.trim()?.toLongOrNull() == file.lastModified()
    }

    /**
     * Forget everything stored for [file], working copy and history alike.
     *
     * Called when the document is deleted. Left behind, this is both dead weight and a trap: the
     * next document to take that name would inherit it.
     */
    fun forget(file: File) {
        runCatching { dirFor(file).deleteRecursively() }
    }

    private fun writeMeta(file: File, strokes: Int = -1) {
        runCatching {
            val count = if (strokes >= 0) strokes else strokeCount(file)
            File(dirFor(file), META).writeText("${file.absolutePath}\n$count\n")
        }
    }

    // ---- io ------------------------------------------------------------------

    private fun read(f: File): InkDocument? =
        runCatching { if (f.isFile) InkDocument.parse(f.readText()) else null }.getOrNull()

    /**
     * The exact text last written for [doc], if it is still that document.
     *
     * Compared by identity, which makes this impossible to get wrong: the model is immutable, so
     * the only way this matches is if the very object that was serialised is the one being asked
     * about. Held for one document at a time - the editor has one open.
     */
    fun textFor(doc: InkDocument): String? =
        if (lastSerialised?.first === doc) lastSerialised?.second else null

    private var lastSerialised: Pair<InkDocument, String>? = null

    private fun write(target: File, doc: InkDocument): Boolean = runCatching {
        // Once. Serialising a document with a term of handwriting in it is the expensive part of
        // an autosave, and this used to do it again for the fallback path.
        val text = doc.compacted().serialize()
        lastSerialised = doc to text
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
        true
    }.onFailure { EventLog.error("journal", "Failed writing working copy: ${it.message}") }
        .getOrDefault(false)

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val CURRENT = "current.inkdoc"
        private const val META = "path.txt"
        private const val RESTRUCTURED = "restructured.txt"
        private const val SYNC = "insync.txt"
        private const val OWNER = "owner.txt"
        private const val MAX_HISTORY = 40
        private val NAMED_VERSION =
            Regex("""(\d+)-(\d+)\.${InkDocument.EXTENSION}""")
    }
}
