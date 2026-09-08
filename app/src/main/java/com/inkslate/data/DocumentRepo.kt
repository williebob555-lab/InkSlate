package com.inkslate.data

import android.content.Context
import com.inkslate.ink.*
import com.inkslate.ink.Stroke
import com.inkslate.pdf.InkExporter
import com.inkslate.pdf.CanvasPaper
import com.inkslate.pdf.PageArrangement
import com.inkslate.pdf.PageSource
import com.inkslate.pdf.PristineStore
import com.inkslate.pdf.PlannedPage
import com.inkslate.pdf.PageSources
import java.io.File

/**
 * A cheap, exact answer to "is this page's handwriting the same as it was".
 *
 * Over stroke identity and edit time rather than over the strokes themselves: the model
 * guarantees that an edit to a stroke keeps its id and moves [Stroke.updatedUtc], so this catches
 * a mark added, removed, restyled, moved or reordered while costing one pass over the ids. The
 * obvious alternative - comparing the strokes - is a pass over every point of every stroke, which
 * is the same order of work as building the page and so would pay for nothing.
 */
internal fun pageSignature(ink: InkDocument, page: Int): Long {
    var hash = 1125899906842597L
    var count = 0
    var newest = 0L
    for (s in ink.strokesOn(page)) {
        hash = hash * 31 + s.id.hashCode()
        if (s.updatedUtc > newest) newest = s.updatedUtc
        count++
    }
    return hash * 31 + count * 1000003L + newest
}

internal fun pageSignatures(ink: InkDocument, pageCount: Int): Map<Int, Long> =
    (0 until pageCount).associateWith { pageSignature(ink, it) }

/** A file opened for editing: the pages to draw on, plus the ink that belongs to them. */
class OpenDocument(
    val file: File,
    val source: PageSource,
    var ink: InkDocument,
    val deviceTag: String,
    /**
     * How many Syncthing conflict files were folded in when this opened, and who wrote them.
     * Surfaced to the user: a silent merge of someone else's work is exactly the kind of thing
     * that should be announced rather than discovered later.
     */
    val mergedConflicts: Int = 0,
    val mergedFrom: List<String> = emptyList(),
    /** True when the source file changed since the annotations were last saved against it. */
    val sourceChanged: Boolean = false
) {
    val pageCount: Int get() = maxOf(1, source.pageCount)

    /**
     * What each page's ink looked like when it was last written to disk.
     *
     * Empty means nothing is known, which makes every page dirty - the safe direction.
     */
    var savedSignatures: Map<Int, Long> = emptyMap()

    /**
     * True when the document was opened carrying none of this app's annotations.
     *
     * Then every page without ink on it is already correct, so a save has only ever to add - and
     * that is what lets the very first save of a large document append to it instead of
     * rebuilding it.
     */
    var pagesHadNoInk: Boolean = false

    /**
     * True once it is known, for every page, whether the annotation on it is current.
     *
     * Without this a save has to assume nothing and rebuild the whole document, which on a
     * nine-hundred-page book is the difference between a second and two minutes.
     */
    var pageStateKnown: Boolean = false

    /**
     * Pages whose ink differs from what is on disk.
     *
     * Derived from the ink each time it is asked for, rather than accumulated as edits arrive.
     * That distinction is the whole point. Writing now happens in the background while the pen is
     * still on the page, and a set that is added to by edits and subtracted from by writes loses
     * anything drawn *during* a write: the page was already in the batch, so finishing the write
     * cleared it, and the strokes added meanwhile were never written and never asked to be again.
     * Comparing signatures cannot go wrong that way - whatever was written is recorded exactly,
     * and anything else is still different.
     */
    fun dirtyPages(): Set<Int> = (0 until pageCount)
        .filterTo(mutableSetOf()) { pageSignature(ink, it) != savedSignatures[it] }

    /**
     * The handwriting as it was when the document on disk was last written.
     *
     * Compared by identity, which is exact and free: the model is immutable, so any edit at all -
     * a stroke, a bookmark, a canvas setting - produces a different object. When this is still
     * the current ink there is provably nothing to write.
     */
    var savedInk: InkDocument? = null

    fun strokesOn(page: Int): List<Stroke> = ink.strokesOn(page)

    fun updatePage(page: Int, strokes: List<Stroke>) {
        ink = ink.withPage(page, strokes, deviceTag)
    }

    /**
     * Replace every page at once from a flat list tagged with [Stroke.pageIndex].
     *
     * The editor holds all pages simultaneously when they are laid out continuously, so it
     * cannot know which single page changed; writing them all keeps the sidecar consistent.
     *
     * [callerHoldsWholeDocument] is not ceremony. A view that has not finished loading is
     * legitimately empty, and writing that emptiness back marks every existing stroke as
     * deleted - tombstones that then travel over Syncthing and erase the work on every other
     * device too. That is precisely how a term's annotations disappeared. The caller must
     * assert it really has the document; refusing is always cheaper than guessing.
     */
    fun updateAll(all: List<Stroke>, callerHoldsWholeDocument: Boolean) {
        if (!callerHoldsWholeDocument) {
            EventLog.warn("sidecar", "Refused a save of ${file.name}: editor had not loaded it")
            return
        }

        val byPage = all.groupBy { it.pageIndex }
        val before = ink.totalStrokes

        var next = ink
        for (page in 0 until pageCount) {
            val existing = next.strokesOn(page)
            val updated = byPage[page].orEmpty()
            // Skip pages that did not change, so untouched pages keep their tombstone history.
            //
            // Compared by identity rather than by value: strokes are immutable and an edit
            // replaces the object, so an unchanged page holds the very same instances that were
            // stored last time. Comparing by value instead walks every point of every stroke on
            // every page on every autosave, which on a marked-up chapter is real work for an
            // answer identity already gave. A false "changed" only costs a rewrite of a page
            // whose contents are the same, which is harmless.
            val same = existing.size == updated.size &&
                existing.indices.all { existing[it] === updated[it] }
            if (!same) next = next.withPage(page, updated, deviceTag)
        }

        // A single save that removes most of the document is either a deliberate mass erase or
        // a bug. Worth a line either way: after the fact there is no other way to tell which.
        val after = next.totalStrokes
        if (before > 4 && after * 2 < before) {
            EventLog.warn(
                "sidecar",
                "${file.name}: strokes fell from $before to $after in one save"
            )
        }
        ink = next
    }

    /** Every stroke in the document, each tagged with the page it belongs to. */
    fun allStrokes(): List<Stroke> =
        (0 until pageCount).flatMap { page ->
            ink.strokesOn(page).map { if (it.pageIndex == page) it else it.copy(pageIndex = page) }
        }

    fun close() {
        source.close()
        // The parsed copy is held for as long as the document is open, and no longer.
        com.inkslate.pdf.PdfEditSession.release(file)
    }
}

/** Result of a save, so the UI can say exactly what happened and where it went. */
sealed interface SaveResult {
    data class Written(val target: File, val wasCopy: Boolean, val backup: File?) : SaveResult
    data class Failed(val error: Throwable) : SaveResult
    data object NothingToDo : SaveResult
}

/**
 * Owns reading and writing documents on disk.
 *
 * Three separate concerns live here deliberately:
 *  - the **working store** ([InkJournal]), written constantly and cheaply into app-private
 *    storage, which is the crash-safe scratch copy and the version history;
 *  - the **document itself**, which carries the handwriting embedded inside it and is therefore
 *    the thing Syncthing moves between devices - no companion file, nothing to keep in step;
 *  - the **export** (PDF or image), written when asked, which is the artifact you submit.
 */
class DocumentRepo(private val context: Context) {

    /** App-private working copies and history. Never visible in the file explorer. */
    val journal = InkJournal(context)

    private val prefs = SavePrefs(context)
    private val deviceTag = DeviceId.get(context)

    /**
     * The handwriting this app last put inside each document, by digest.
     *
     * What makes it possible to tell our own save from a copy arriving off the network. The
     * file's length and timestamp cannot: embedding the handwriting rewrites the file, so those
     * change on every save whether or not anything came in from elsewhere - which is what had
     * the editor announcing its own saves as changes made on another device.
     */
    private val lastEmbedded = mutableMapOf<String, String>()

    /**
     * Only one write to a document at a time.
     *
     * A write replaces the file by deleting it and renaming the new one over the top, so there is
     * a moment when it does not exist. Two writes overlapping - the background write-through and
     * a save, or a save and the write on the way to the background - meant one of them opening
     * the document during the other's moment, which is the `ENOENT (No such file or directory)`
     * on a file that was sitting there the whole time.
     */
    private val writeLock = java.util.concurrent.locks.ReentrantLock()

    private fun <T> writing(block: () -> T): T {
        writeLock.lock()
        try { return block() } finally { writeLock.unlock() }
    }

    fun lastEmbeddedInk(file: File): String? = lastEmbedded[file.absolutePath]

    /** Whether [file] still carries the handwriting this app last wrote into it. */
    fun documentStillOurs(file: File): Boolean {
        val ours = lastEmbedded[file.absolutePath] ?: return false
        return InkEmbedder.inkDigest(file) == ours
    }

    // ---- opening -------------------------------------------------------------

    fun open(file: File): OpenDocument? {
        val started = System.currentTimeMillis()
        InkExporter.init(context)
        var readAt = started
        var sourceAt = started

        // Read the document once, before anything else, and get both answers out of it: the
        // handwriting stored inside, and whether any of it has already been painted onto the
        // pages by a previous overwrite.
        // Whether this file is still byte for byte what this app last wrote.
        val ourOwnOutput = PristineStore.savedPageState(context, file) != null
        val inspected = when {
            !InkEmbedder.supports(file) -> InkEmbedder.Inspection(null, false)
            // Nothing to learn from reading it. The payload inside was written from the working
            // copy, which is written first and so is never behind - so the handwriting comes off
            // app storage instead, and the document is not parsed at all. That is a PDF load and
            // an inflate removed from every open of a document this device last saved.
            ourOwnOutput -> InkEmbedder.Inspection(null, bakedVisible = true)
            else -> InkEmbedder.inspect(file)
        }
        // What the file arrived carrying. Anything different later came from somewhere else.
        inspected.inkStamp?.let { lastEmbedded[file.absolutePath] = it }

        readAt = System.currentTimeMillis()
        val source = PageSources.open(file, context, strokesBakedIn = inspected.bakedVisible)
        sourceAt = System.currentTimeMillis()
        if (source == null) {
            EventLog.error("open", "Unsupported or unreadable: ${file.name}")
            return null
        }

        val sidecar = File(InkDocument.sidecarPathFor(file.absolutePath))
        val conflictsBefore = findConflictFiles(sidecar)
        val conflictDevices = conflictsBefore.mapNotNull { describeConflict(it) }

        val ink = loadInk(file, source, inspected.ink, inspected.inkStamp)

        // Has the page layout underneath the annotations actually changed?
        //
        // Deliberately not file length and timestamp. Handwriting is embedded in the document
        // itself, so every save rewrites the file - which meant the old byte fingerprint never
        // matched and every open, including one seconds after closing, announced that the PDF
        // had changed. Page count and page sizes are what "the ink may no longer line up" really
        // depends on, and embedding ink leaves them alone.
        val geometry = PageSources.geometryFingerprint(source)
        val changed = ink.source.geometry.isNotEmpty() && ink.source.geometry != geometry
        if (changed) {
            EventLog.warn("open", "${file.name}: page layout changed since annotations were saved")
        }
        // Record it either way, so documents written before this existed adopt it silently
        // instead of warning once for no reason.
        val stamped =
            if (ink.source.geometry == geometry) ink
            else ink.copy(source = ink.source.copy(geometry = geometry))

        // Keep a local copy of whatever the document brought with it. Anything written on
        // another device and synced over has, until this moment, existed in exactly one place.
        journal.seed(file, stamped)

        EventLog.info(
            "open",
            "${file.name}: ${source.pageCount}p, ${ink.totalStrokes} strokes " +
                "(${inspected.ink?.totalStrokes ?: 0} in the file, " +
                "${ink.deleted.size} tombstone(s)), " +
                (if (inspected.bakedVisible) "strokes baked into the pages, " else "") +
                "${System.currentTimeMillis() - started}ms " +
                "[read ${readAt - started}ms, pages ${sourceAt - readAt}ms, " +
                "ink ${System.currentTimeMillis() - sourceAt}ms]"
        )
        val opened = OpenDocument(
            file, source, stamped, deviceTag,
            mergedConflicts = conflictsBefore.size,
            mergedFrom = conflictDevices,
            sourceChanged = changed
        )

        // Which pages already carry the right annotation. Only knowable when the file on disk is
        // still exactly what this app last wrote - anything else having touched it means nothing
        // can be assumed about any page, and every one is rebuilt.
        val saved = if (ourOwnOutput) PristineStore.savedPageState(context, file) else null
        val annotated = inspected.annotatedPages
        if (saved == null && annotated != null) {
            // Nothing recorded about this document, but it was just read, so what it carries is
            // known: a page with none of our marks and no ink on it is already right, whatever
            // the rest of the book is doing. Only the pages that carry a mark, or want one, need
            // touching - which is what stops the first save of a textbook rebuilding all of it.
            val blank = pageSignature(stamped.copy(pages = emptyMap()), 0)
            opened.savedSignatures = (0 until opened.pageCount)
                .filter { it !in annotated }
                .associateWith { blank }
            opened.pagesHadNoInk = annotated.isEmpty()
            opened.pageStateKnown = true
            EventLog.info(
                "open",
                "${file.name}: ${annotated.size} page(s) carry our marks; " +
                    "${opened.dirtyPages().size} to build on the next save"
            )
        } else if (saved != null) {
            val now = pageSignatures(stamped, opened.pageCount)
            if (saved.keys == now.keys) {
                opened.savedSignatures = saved
                opened.pageStateKnown = true
                // When every page already matches, the file *is* this document. Saying so means
                // opening something and closing it again writes nothing - and, just as much,
                // that the editor can honestly show it as saved rather than assuming.
                if (opened.dirtyPages().isEmpty()) opened.savedInk = stamped
                val stale = opened.dirtyPages()
                EventLog.info(
                    "open",
                    "${file.name}: ${stale.size} of ${now.size} page(s) need rewriting" +
                        if (stale.isEmpty()) "" else stale.joinToString(
                            prefix = " - ", separator = ", "
                        ) { p ->
                            "p$p has ${stamped.strokesOn(p).size} stroke(s), " +
                                "sig ${now[p]} vs ${saved[p]} recorded"
                        }
                )
            }
        }
        return opened
    }

    /**
     * Load the sidecar, folding in anything Syncthing left behind.
     *
     * When two devices edit the same file while apart, Syncthing does not merge - it keeps one
     * copy and renames the other to `*.sync-conflict-<date>-<time>-<device>.inkdoc`. Left alone
     * those are invisible lost work, so they are merged in and removed on open.
     */
    private fun loadInk(
        file: File,
        source: PageSource,
        embeddedRaw: InkDocument?,
        embeddedStamp: String?
    ): InkDocument {
        val embedded = embeddedRaw?.withoutSelfContradiction()
        val sidecar = File(InkDocument.sidecarPathFor(file.absolutePath))

        // Everything we can find, merged. Merging is commutative and drops nothing, so when the
        // sources disagree the union is always the safer answer than picking a winner:
        //   1. the document itself   - what arrived from the other devices, read by the caller
        //   2. the working store     - anything autosaved here but not yet written out
        //   3. a legacy companion    - documents from before the handwriting moved inside
        // Each source is made self-consistent before anything is merged. A document that says a
        // stroke is both on the page and deleted is describing damage, not a decision, and the
        // merge would resolve it by throwing the stroke away. See [withoutSelfContradiction] for
        // why this has to happen per source rather than to the result.
        // The working copy is nearly always the same handwriting that just came out of the
        // document - it was written from it, moments before, by the save that produced it.
        // Parsing it again to merge it with itself is the single largest thing an open does.
        val inSync = embedded != null && embeddedStamp != null &&
            journal.isInSync(file, embeddedStamp)
        val working =
            if (inSync) null else journal.load(file)?.withoutSelfContradiction()
        val legacy = readSidecar(sidecar)?.withoutSelfContradiction()

        var doc = embedded ?: working ?: legacy ?: InkDocument.create(
            sourceName = file.name,
            kind = source.kind,
            pageCount = source.pageCount,
            sizeBytes = file.length(),
            fingerprint = PageSources.fingerprint(file)
        )
        listOfNotNull(embedded, working, legacy).forEach { if (it !== doc) doc = doc.mergeWith(it) }

        if (legacy != null) {
            EventLog.info(
                "open",
                "${file.name} still has a companion .inkdoc; it will be folded in and removed " +
                    "on the next save"
            )
        }

        val conflicts = findConflictFiles(sidecar)
        if (conflicts.isNotEmpty()) {
            for (c in conflicts) {
                readSidecar(c)?.let { doc = doc.mergeWith(it.withoutSelfContradiction()) }
            }
            // only remove a conflict file once its content is safely folded in and written back
            EventLog.warn(
                "sync",
                "Merged ${conflicts.size} sync-conflict file(s) into ${file.name}"
            )
            if (writeSidecar(sidecar, doc)) conflicts.forEach { it.delete() }
        }

        // record page geometry so a device that cannot open the source still lays ink out right
        if (doc.pageSizes.size != source.pageCount) {
            doc = doc.copy(
                pageSizes = (0 until source.pageCount).map {
                    val d = source.pageDim(it)
                    InkPageSize(d.width, d.height)
                }
            )
        }
        return doc
    }

    fun findConflictFiles(sidecar: File): List<File> {
        val dir = sidecar.parentFile ?: return emptyList()
        val stem = sidecar.name.removeSuffix(".${InkDocument.EXTENSION}")
        return dir.listFiles { f ->
            f.isFile && f.name.startsWith(stem) &&
                f.name.contains(".sync-conflict-") &&
                f.name.endsWith(".${InkDocument.EXTENSION}")
        }?.toList().orEmpty()
    }

    /** Pull the device name out of a Syncthing conflict filename, when it carries one. */
    private fun describeConflict(f: File): String? {
        val marker = ".sync-conflict-"
        val at = f.name.indexOf(marker)
        if (at < 0) return null
        val tail = f.name.substring(at + marker.length)
        // format is <date>-<time>-<deviceId>.<ext>
        val parts = tail.split("-")
        return parts.getOrNull(2)?.substringBefore(".")?.take(7)
    }

    private fun readSidecar(f: File): InkDocument? =
        runCatching { if (f.isFile) InkDocument.parse(f.readText()) else null }.getOrNull()

    // ---- the working store ---------------------------------------------------

    /**
     * Autosave target.
     *
     * Deliberately *not* the document. Writing a two-hundred-megabyte textbook every thirty
     * seconds is not something a tablet should be asked to do, and it would keep Syncthing busy
     * re-sending a file nobody has finished editing. This goes to app-private storage instead:
     * invisible in the file explorer, nothing to copy alongside a document, nothing to rename.
     * The document itself is written when the user saves or leaves.
     */
    fun saveWorking(doc: OpenDocument): Boolean = journal.save(doc.file, doc.ink)

    /**
     * Update the working copy without adding to the version history.
     *
     * For the writes nobody asked for. The document is now written every time the pen pauses, and
     * a snapshot per pause would push a morning's worth of real save points out of a forty-deep
     * history within a couple of minutes - turning the one thing that stands between a bad merge
     * and a lost afternoon into a log of the last three minutes.
     */
    fun saveWorkingQuietly(doc: OpenDocument): Boolean = journal.setWorking(doc.file, doc.ink)

    /**
     * Put this version into the history deliberately.
     *
     * The automatic writes stay out of it - see [saveWorkingQuietly] - so this is what a version
     * history is now made of.
     */
    fun checkpoint(doc: OpenDocument) = journal.record(doc.file, doc.ink)

    /**
     * Put the handwriting inside the document, where it travels with the file.
     *
     * This is what replaces the companion `.inkdoc`. After this returns successfully the document
     * carries everything needed to edit it again - on this device, on the laptop, or on a machine
     * that has only just installed the app - with no second file to keep track of.
     *
     * The working copy is left in place afterwards rather than cleared. It costs a few kilobytes
     * and it is the thing that survives a document being deleted, replaced by a sync, or written
     * to by another program.
     */
    /**
     * Mark a freshly created document as a canvas that grows.
     *
     * Written into the document rather than kept in a local setting: it has to travel with the
     * file, or the same whiteboard would be a fixed page on the laptop.
     */
    fun markAsCanvas(
        file: File,
        width: Float,
        height: Float,
        background: String,
        paperColor: Int,
        lineColor: Int,
        spacing: Float
    ): Result<Unit> {
        val ink = InkDocument.create(
            sourceName = file.name,
            kind = "pdf",
            pageCount = 1,
            sizeBytes = file.length(),
            fingerprint = PageSources.fingerprint(file)
        ).copy(
            canvas = com.inkslate.core.InkCanvas.startingAt(
                width, height, background, paperColor, lineColor, spacing
            )
        )
        journal.setWorking(file, ink)
        return InkEmbedder.write(file, ink)
            .onSuccess { lastEmbedded[file.absolutePath] = it }
            .onFailure { EventLog.error("canvas", "${file.name}: ${it.message}") }
            .map { }
    }

    /**
     * Turn a document that already exists into a canvas, or back into a fixed page.
     *
     * Offered for a single-page document only. Everything already on the page stays where it is;
     * the canvas simply starts out exactly the size of that page and grows from there.
     */
    fun setCanvasEnabled(
        doc: OpenDocument,
        enabled: Boolean,
        background: String,
        paperColor: Int,
        lineColor: Int,
        spacing: Float
    ) {
        doc.ink = if (!enabled) {
            doc.ink.copy(canvas = null)
        } else {
            val dim = runCatching { doc.source.pageDim(0) }.getOrNull()
            val existing = doc.ink.canvas
            val base = existing ?: com.inkslate.core.InkCanvas.startingAt(
                dim?.width ?: 612f, dim?.height ?: 792f,
                background, paperColor, lineColor, spacing
            )
            doc.ink.copy(
                canvas = base.copy(
                    background = background, paperColor = paperColor,
                    lineColor = lineColor, spacing = spacing
                )
            )
        }
        journal.setWorking(doc.file, doc.ink)
    }

    /**
     * Write the document's page out at the size the canvas has grown to.
     *
     * Only for canvas documents, and only when they have actually outgrown their page. Growth is
     * free while drawing precisely because it is deferred to here.
     */
    private fun matchCanvasPaper(doc: OpenDocument): Result<Unit> {
        val canvas = doc.ink.canvas ?: return Result.success(Unit)
        if (!canvas.paperIsBehind) return Result.success(Unit)
        if (!PageSources.isPdf(doc.file)) return Result.success(Unit)

        var grown = canvas
        val result = InkEmbedder.rewritePdf(doc.file, doc.ink.copy(canvas = canvas.withPaperMatched()), 1) { pdf ->
            grown = CanvasPaper.grow(pdf, canvas)
            null
        }
        if (result.isSuccess) {
            PristineStore.invalidate(context, doc.file)
            com.inkslate.pdf.PdfEditSession.release(doc.file)
            doc.ink = doc.ink.copy(canvas = grown)
            journal.setWorking(doc.file, doc.ink)
            EventLog.info(
                "canvas",
                "${doc.file.name}: page grown to ${canvas.width.toInt()}x${canvas.height.toInt()}"
            )
        }
        return result
    }

    fun bakeInto(doc: OpenDocument): Result<Unit> = writing { bakeIntoLocked(doc) }

    private fun bakeIntoLocked(doc: OpenDocument): Result<Unit> {
        if (!InkEmbedder.supports(doc.file)) {
            return Result.failure(
                UnsupportedOperationException(
                    "${doc.file.extension.uppercase()} files cannot carry handwriting inside them"
                )
            )
        }
        val started = System.currentTimeMillis()
        journal.record(doc.file, doc.ink)
        // The page has to be the right size before the handwriting goes into it, or ink drawn
        // past the old edge is ink outside the page.
        matchCanvasPaper(doc).onFailure { return Result.failure(it) }
        val result = InkEmbedder.write(doc.file, doc.ink)
        result.getOrNull()?.let {
            lastEmbedded[doc.file.absolutePath] = it
            journal.noteInSync(doc.file, it)
        }
        if (result.isSuccess) {
            EventLog.info(
                "embed",
                "Baked ${doc.ink.totalStrokes} stroke(s) into ${doc.file.name} in " +
                    "${System.currentTimeMillis() - started}ms"
            )
            // A legacy companion is only removed once its contents are provably inside the
            // document. Until then it stays exactly where it is.
            val legacy = File(InkDocument.sidecarPathFor(doc.file.absolutePath))
            if (legacy.isFile && legacy.delete()) {
                EventLog.info("embed", "Removed the old ${legacy.name} companion file")
            }
        }
        return result.map { }
    }

    /**
     * Apply a page rearrangement to the document on disk.
     *
     * The page tree and the handwriting are rewritten together in a single staged, verified swap,
     * because a document whose pages moved and whose ink did not is worse than either edit alone.
     * A snapshot of the handwriting goes into the journal first: this is the one operation in the
     * app that deliberately throws work away, so there has to be something to go back to.
     *
     * The caller must reopen the document afterwards. Nothing it is holding - page count, page
     * sizes, the strokes in the view - is true any more.
     */
    fun rearrangePages(doc: OpenDocument, plan: List<PlannedPage>): Result<Unit> {
        if (PageArrangement.isUnchanged(plan, doc.pageCount)) return Result.success(Unit)
        if (plan.isEmpty()) {
            return Result.failure(IllegalArgumentException("A document needs at least one page"))
        }

        journal.record(doc.file, doc.ink)

        val ids = StrokeIdGen(deviceTag).also {
            it.seedFrom(doc.ink.allStrokeIds() + doc.ink.deleted.keys)
        }
        // Real page sizes from the source, not the recorded ones: a turn computed against a
        // stale size puts every mark on the page in the wrong place.
        val sizeOf: (Int) -> Pair<Float, Float> = { i ->
            runCatching { doc.source.pageDim(i).let { it.width to it.height } }
                .getOrDefault(612f to 792f)
        }
        val remapped = PageArrangement.remapInk(doc.ink, plan, { ids.next() }, sizeOf)

        val result = InkEmbedder.rewritePdf(doc.file, remapped, plan.size) { pdf ->
            PageArrangement.applyToPdf(pdf, plan)
        }
        if (result.isSuccess) {
            // The pages themselves moved, so the clean copy really is a different document now.
            PristineStore.invalidate(context, doc.file)
            com.inkslate.pdf.PdfEditSession.release(doc.file)
            doc.ink = remapped
            // The working copy describes the pages as they were, down to which index every mark
            // sat on, so it is replaced rather than added to. Leaving it would have the next open
            // merge a description of a document that no longer exists.
            journal.setWorking(doc.file, remapped)
            journal.record(doc.file, remapped)
            journal.noteRestructured(doc.file)
            EventLog.info(
                "pages",
                "${doc.file.name}: ${doc.pageCount} page(s) rearranged into ${plan.size}"
            )
        }
        return result
    }

    // ---- tidying away the old companion files --------------------------------

    /**
     * Find documents that still have a `.inkdoc` companion beside them.
     *
     * These only disappear on their own as each document happens to be opened and saved, which
     * for a library built up over a term could take months. This is what makes the clutter
     * actually go away.
     */
    fun legacySidecars(roots: List<File>, depth: Int = 6): List<File> {
        val out = ArrayList<File>()
        val seen = HashSet<String>()

        fun walk(dir: File, remaining: Int) {
            if (remaining <= 0) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.isDirectory) {
                    if (!f.name.startsWith(".")) walk(f, remaining - 1)
                    continue
                }
                if (!f.name.endsWith(".${InkDocument.EXTENSION}")) continue
                if (f.name.contains(".sync-conflict-")) continue
                // the companion is named "<document>.inkdoc", so the parent is the name minus it
                val parent = File(f.absolutePath.removeSuffix(".${InkDocument.EXTENSION}"))
                if (parent.isFile && seen.add(parent.absolutePath)) out.add(parent)
            }
        }

        roots.forEach { walk(it, depth) }
        return out
    }

    /**
     * Move one document's companion inside the document itself.
     *
     * Merges rather than replaces, so a document that has been edited on another device since
     * does not lose those edits. The companion is deleted only once its contents are provably
     * inside the parent - if anything at all goes wrong, the file stays exactly where it is.
     */
    fun absorbSidecar(file: File): Result<Int> = runCatching {
        val sidecar = File(InkDocument.sidecarPathFor(file.absolutePath))
        require(sidecar.isFile) { "No companion file for ${file.name}" }
        require(InkEmbedder.supports(file)) {
            "${file.extension.uppercase()} files cannot carry handwriting inside them"
        }

        val legacy = readSidecar(sidecar) ?: error("Could not read ${sidecar.name}")
        val embedded = InkEmbedder.read(file)
        val merged = if (embedded == null) legacy else embedded.mergeWith(legacy)

        journal.record(file, merged)
        InkEmbedder.write(file, merged).getOrThrow()

        if (!sidecar.delete()) {
            EventLog.warn("migrate", "${file.name}: absorbed, but ${sidecar.name} would not delete")
        }
        EventLog.info("migrate", "${file.name}: took in ${merged.totalStrokes} stroke(s)")
        merged.totalStrokes
    }.onFailure { EventLog.error("migrate", "${file.name}: ${it.message}") }

    /** True when this document already carries its handwriting internally. */
    fun isSelfContained(file: File): Boolean =
        InkEmbedder.supports(file) && InkEmbedder.read(file) != null

    // ---- history -------------------------------------------------------------

    /** Earlier versions of a document's handwriting, newest first. */
    fun inkBackupsFor(file: File): List<File> = journal.history(file)

    fun peekInk(version: File): InkDocument? = journal.peek(version)

    /**
     * A version worth offering back, or null.
     *
     * Only speaks up when what is on screen is markedly poorer than something already saved, so
     * opening a document normally never nags.
     */
    fun inkRecoveryCandidate(file: File, live: InkDocument): Pair<File, Int>? {
        val liveCount = live.totalStrokes
        // Snapshots from before the pages were last rearranged describe a document with different
        // pages. They hold real work and stay in version history, but they cannot be *compared*
        // to what is on screen - a page that was deleted takes its marks with it, and counting
        // that as handwriting having gone missing turned every deliberate page change into an
        // alarm. Turning a page raised it even though nothing had been lost at all.
        val since = journal.restructuredAt(file)
        // Counts come off the filenames. Parsing every snapshot to find the fullest one meant
        // opening a marked-up document read its entire history first, which is work that scaled
        // with how much had been written on it and answered a question the names already hold.
        val best = journal.historyCounts(file)
            .filter { (f, _) -> f.lastModified() >= since }
            .maxByOrNull { it.second } ?: return null
        if (best.second <= liveCount || best.second - liveCount < 3) return null

        // A count can say a snapshot is fuller. It cannot say whether what is missing was erased
        // on purpose, and rubbing out a wrong working line is the most ordinary thing anyone does
        // here - being told afterwards that handwriting has gone missing is both wrong and
        // alarming. Tombstones are the record of a deliberate delete, so anything explained by
        // one is not a loss.
        //
        // Only parsed at this point, and only the one snapshot: this is the branch that was
        // about to raise an alarm, so it can afford to be sure before it does.
        val snapshot = journal.peek(best.first) ?: return best
        val present = HashSet<String>(liveCount * 2)
        for (page in live.pages.values) for (stroke in page) present.add(stroke.id)
        var unexplained = 0
        for (page in snapshot.pages.values) {
            for (stroke in page) {
                if (stroke.id !in present && stroke.id !in live.deleted) unexplained++
            }
        }
        if (unexplained < 3) {
            EventLog.info(
                "sidecar",
                "${file.name}: a snapshot has ${best.second} to ${liveCount} strokes, but the " +
                    "difference was erased deliberately"
            )
            return null
        }
        return best.first to best.second
    }

    private fun liveInk(file: File): InkDocument? =
        journal.load(file) ?: InkEmbedder.read(file)
            ?: readSidecar(File(InkDocument.sidecarPathFor(file.absolutePath)))

    /**
     * Put a previous version of the handwriting back.
     *
     * Restored strokes are re-issued under fresh ids from this device. That looks heavy-handed
     * until you consider what a tombstone is: a permanent, commutative record that a stroke was
     * deleted. Restore a stroke under its old id and the next device to sync its copy of that
     * deletion removes it again. A new id has never been deleted anywhere, so it survives.
     *
     * Nothing currently in the document is removed, which is what makes this safe to try.
     */
    fun restoreInk(file: File, version: File): Result<Int> = runCatching {
        val restored = journal.peek(version) ?: error("That version could not be read")
        val current = liveInk(file) ?: restored.copy(pages = emptyMap())

        val presentIds = current.pages.values.flatten().map { it.id }.toSet()
        val ids = StrokeIdGen(deviceTag).also { it.seedFrom(presentIds + current.deleted.keys) }
        val now = System.currentTimeMillis()

        var recovered = 0
        val pages = current.pages.toMutableMap()
        for ((key, strokes) in restored.pages) {
            val add = strokes.mapNotNull { s ->
                when {
                    s.id in presentIds -> null                       // still there; leave it
                    s.id in current.deleted ->                       // tombstoned: re-issue
                        s.copy(id = ids.next(), updatedUtc = now)
                    else -> s
                }
            }
            if (add.isEmpty()) continue
            recovered += add.size
            pages[key] = pages[key].orEmpty() + add
        }

        val rebuilt = current.copy(
            pages = pages,
            clocks = current.clocks + (deviceTag to now),
            modifiedUtc = now,
            modifiedBy = deviceTag
        )
        if (!journal.save(file, rebuilt)) error("Could not write the recovered handwriting")
        EventLog.info("journal", "Recovered $recovered stroke(s) into ${file.name}")
        recovered
    }.onFailure { EventLog.error("journal", "Recovery failed: ${it.message}") }

    private fun writeSidecar(target: File, doc: InkDocument): Boolean = runCatching {
        // Serialise once and reuse it. This used to build the whole JSON up to three times for a
        // single write: once to measure it for the log, once for the file, once for the fallback.
        val text = doc.compacted().serialize()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(text)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
        EventLog.info("sidecar", "Wrote ${target.name} (${text.length / 1024}KB)")
        true
    }.onFailure { EventLog.error("sidecar", "Failed writing ${target.name}: ${it.message}") }
        .getOrDefault(false)

    // ---- exporting -----------------------------------------------------------

    /**
     * Write the annotated file according to [settings]. Callers must resolve
     * [SaveMode.ASK] before calling; this treats it as COPY, which is the safe reading.
     */
    /**
     * [recordHistory] is false for the background writes that keep the document current. Those
     * are not save points; see [saveWorkingQuietly].
     */
    fun export(
        doc: OpenDocument,
        settings: SaveSettings,
        recordHistory: Boolean = true
    ): SaveResult = writing { exportLocked(doc, settings, recordHistory) }

    private fun exportLocked(
        doc: OpenDocument,
        settings: SaveSettings,
        recordHistory: Boolean
    ): SaveResult {
        val started = System.currentTimeMillis()
        // One snapshot, used throughout. Writing now happens in the background while the pen is
        // still on the page, so reading ink again part-way would mix two versions of the
        // document into one file - and worse, could mark strokes drawn during the write as
        // already saved.
        val ink = doc.ink
        // pasted images live beside the document; the exporter needs a way to find them
        val store = ImageStore(doc.file)
        InkExporter.imageResolver = { id -> store.load(id) }
        if (ink.totalStrokes == 0) {
            EventLog.warn("export", "${doc.file.name}: nothing drawn, export skipped")
            return SaveResult.NothingToDo
        }
        EventLog.info(
            "export",
            "${doc.file.name}: mode=${settings.mode.name} format=${settings.inkFormat.name} " +
                "strokes=${ink.totalStrokes}"
        )

        matchCanvasPaper(doc)
        // A canvas stores ink where it was written, which for a page that grew leftwards means
        // negative coordinates. The page itself still starts at its own corner.
        InkExporter.displayOrigin = ink.canvas?.let { it.left to it.top }

        val overwrite = settings.mode == SaveMode.OVERWRITE
        val target = if (overwrite) doc.file else copyTargetFor(doc.file, settings)

        // The document on disk already is this document, so everything below would rebuild it
        // byte for byte. Checked before the backup as well as before the write: a backup taken
        // here would be a copy of a file nothing was about to change, and twenty of those would
        // push the real ones out of the history.
        if (overwrite && ink === doc.savedInk &&
            PristineStore.savedPageState(context, doc.file) != null
        ) {
            EventLog.info("export", "${doc.file.name}: already current, nothing to write")
            return SaveResult.Written(target, wasCopy = false, backup = null)
        }


        // The editable copy of the handwriting, built once. For a PDF it rides along with the
        // export's own write; for the other formats it is attached afterwards, below.
        val isPdf = PageSources.isPdf(doc.file)
        // The autosave that runs before every save has already turned this document into text.
        // Building it again is a quarter of a second of repeating work on a marked-up document.
        val payload = when {
            !isPdf -> null
            // The autosave that runs before every save has already turned this document into
            // text. Building it again is a quarter of a second of repeating work.
            else -> journal.textFor(ink)?.let { com.inkslate.core.InkPayload.encodeText(it) }
                ?: com.inkslate.core.InkPayload.encode(ink)
        }
        val encodedAt = System.currentTimeMillis()

        // How much of this document actually has to be built again.
        //
        // Three strategies, cheapest first. A page nobody touched already carries the right
        // annotation in the file we wrote last time, so the file itself is the best source to
        // build on - but only while it is still ours, and only for the annotation format, where
        // ink lives in an annotation that can be replaced page by page. Flattening writes into
        // the page content, which cannot be unpicked, so it always starts from the pristine copy.
        val annotated = settings.inkFormat == InkFormat.ANNOTATIONS
        val saved = if (overwrite && isPdf && annotated) {
            PristineStore.savedPageState(context, doc.file)
        } else null
        val pageCount = doc.pageCount
        // Signatures of the document being written, captured with it. What gets recorded
        // afterwards is these, never "whatever the document looks like once the write finished" -
        // by then the pen may have moved on, and claiming those strokes were written would lose
        // them silently.
        val signatures = pageSignatures(ink, pageCount)
        val dirty = (0 until pageCount)
            .filterTo(mutableSetOf()) { signatures[it] != doc.savedSignatures[it] }
        // Rebuilding fewer pages is worth it whenever there is a page to skip.
        //
        // This briefly had a threshold on it, from comparing a real save against a benchmark row
        // - two things measured under different conditions, which is the mistake this exercise
        // keeps punishing. Like for like on the device: a partial rebuild of one page in three
        // took 1094ms, a full rebuild of the same document took 1554ms. Starting from the
        // finished file costs more to load and write than the pristine copy does, and skipping
        // even one page's geometry more than pays for it.
        // Patching needs to know that the pages it is *not* rebuilding already carry the right
        // annotation. Two ways to know that: this app wrote the file last, or the file has none
        // of our annotations at all - in which case every page without ink is already correct and
        // the save has only ever been adding. The second is what makes the first save of a
        // textbook an append rather than a rewrite of the whole book.
        val knowPages = (saved != null && saved.size == pageCount) ||
            doc.pagesHadNoInk || doc.pageStateKnown
        val patch = knowPages && dirty.size < pageCount

        // Appending leaves what it supersedes behind, so it is right only while it is writing
        // less than a rewrite would, and only until the dead weight has built up.
        val compact = PristineStore.lastCompactSize(context, doc.file)
        val append = APPEND_ENABLED && patch &&
            (compact == 0L || doc.file.length() < compact * 3 / 2 + 256 * 1024)

        // What this write will actually bring up to date. A partial rewrite covers the pages it
        // rebuilds; a full one covers everything, whatever gets drawn while it runs.
        val written = if (patch) dirty else (0 until pageCount).toSet()

        val from = when {
            patch -> doc.file
            else -> PristineStore.forDocument(context, doc.file) ?: doc.file
        }
        if (patch) {
            EventLog.info(
                "export",
                "${doc.file.name}: rebuilding ${dirty.size} of $pageCount page(s)" +
                    (if (append) ", appended" else "") + "; " +
                    // Only the pages being written. Naming all of them put nine hundred
                    // signatures into a log meant to be read.
                    dirty.sorted().take(12).joinToString(" ") { p ->
                        "p$p=${ink.strokesOn(p).size} strokes"
                    }
            )
        } else if (overwrite && isPdf && annotated) {
            // Every page rebuilt. Worth saying why, because the answer is either "first save of
            // this document" - fine, once - or "the file is not what we last wrote", which would
            // mean something is touching it after every save and this never gets to be cheap.
            EventLog.info(
                "export",
                "${doc.file.name}: full rebuild, " + when {
                    saved == null -> "no page state for the file as it is on disk"
                    saved.size != pageCount ->
                        "page count changed (${saved.size} recorded, $pageCount now)"
                    else -> "every page changed"
                }
            )
        }

        var appended = append
        var verified = false

        var backup: File? = null
        // Not for an appended save. A backup exists to answer "I have overwritten the original",
        // and an append does not overwrite anything - every byte that was there is still there,
        // and undoing it is a truncation. Copying a fifty-megabyte textbook to protect against
        // a write that cannot damage it is the largest thing left in closing one.
        if (overwrite && settings.backupOnOverwrite && !append) {
            backup = makeBackup(doc.file).getOrNull()
            // Refuse to overwrite when the backup failed: losing the only copy of a blank
            // assignment template is not recoverable, and silence here would be the worst option.
            if (backup == null) {
                EventLog.error("export", "Backup failed for ${doc.file.name}; overwrite refused")
                return SaveResult.Failed(
                    IllegalStateException("Could not create a backup, so the original was left untouched")
                )
            }
        }

        val backupAt = System.currentTimeMillis()
        val sourcedAt = backupAt

        var result = when {
            isPdf -> InkExporter.exportPdf(
                from, target, ink, settings.inkFormat, payload,
                rebuild = if (patch) dirty else null,
                appendOnly = append
            )
            PageSources.isImage(doc.file) && target.extension.equals("pdf", true) ->
                InkExporter.exportImageAsPdf(doc.file, target, ink)
            PageSources.isImage(doc.file) ->
                InkExporter.exportImage(doc.file, target, ink)
            else -> Result.failure(IllegalArgumentException("Unsupported file type"))
        }
        val builtAt = System.currentTimeMillis()
        // A partial rebuild is checked before it is allowed to stand. It cannot fail loudly:
        // writing the wrong objects produces a valid PDF carrying the previous save's
        // handwriting, and nothing downstream would ever notice.
        val lengthBeforeAppend = if (append) doc.file.length() else -1L
        if (isPdf && payload != null && result.isSuccess && (patch || append)) {
            val expectAnnotated = written.filter { ink.strokesOn(it).isNotEmpty() }.toSet()
            // An append is checked by reading the end of the file, which is where it just put the
            // handwriting. Opening the document instead cost as much as the save did, and it
            // answers a narrower question than it appears to: what an append can get wrong is
            // leaving the document pointing at the previous payload, and that is exactly what
            // this sees. A full rewrite still gets the full check.
            val why = if (append) {
                if (InkEmbedder.appendedPayloadIntact(target, payload)) null
                else "the appended handwriting is not in the file"
            } else {
                InkEmbedder.verifyOutput(target, payload, pageCount, expectAnnotated)
            }
            verified = why == null
            if (!verified) {
                EventLog.warn(
                    "export",
                    "${target.name}: partial rewrite rejected ($why) - " +
                        "rebuilt ${dirty.size}/$pageCount, appended=$append; writing it in full"
                )
                appended = false
                // Undo the append before replacing the file, so that a full rewrite failing too
                // leaves the document exactly as it was rather than as it was plus a bad update.
                if (lengthBeforeAppend >= 0 && doc.file.length() > lengthBeforeAppend) {
                    runCatching {
                        java.io.RandomAccessFile(doc.file, "rw").use {
                            it.setLength(lengthBeforeAppend)
                        }
                    }
                }
                result = InkExporter.exportPdf(
                    PristineStore.forDocument(context, doc.file) ?: doc.file,
                    target, ink, settings.inkFormat, payload
                )
                verified = result.isSuccess &&
                    InkEmbedder.verifyOutput(target, payload, pageCount, expectAnnotated) == null
            }
        }
        val writtenAt = System.currentTimeMillis()

        return result.fold(
            onSuccess = {
                val now = System.currentTimeMillis()
                EventLog.info(
                    "export",
                    "Wrote ${target.name} (${target.length() / 1024}KB) in ${now - started}ms " +
                        "[payload ${encodedAt - started}ms, " +
                        "prepare ${backupAt - encodedAt}ms, pdf ${builtAt - sourcedAt}ms, " +
                        "check ${writtenAt - builtAt}ms, " +
                        "journal ${now - writtenAt}ms]" +
                        (backup?.let { b -> ", backup ${b.name}" } ?: "")
                )
                // The exported file carries the handwriting inside it as well as on the page,
                // so a copy handed to someone else - or synced to the laptop - is still a
                // document this app can edit, not a flat picture of one.
                //
                // A PDF already had it attached during the write above; all that is left is to
                // confirm it reads back. Anything else needs the separate pass.
                if (InkEmbedder.supports(target)) {
                    val carried = payload != null &&
                        (verified || InkEmbedder.carriesPayload(target, payload))
                    val stamp = if (carried) InkEmbedder.digestOf(payload!!) else {
                        InkEmbedder.write(target, ink).onFailure { e ->
                            EventLog.warn(
                                "export",
                                "${target.name} was written, but the editable copy of the " +
                                    "handwriting could not be stored inside it: ${e.message}"
                            )
                        }.getOrNull()
                    }
                    stamp?.let { lastEmbedded[target.absolutePath] = it }
                    if (overwrite) {
                        // Update what was written *before* recording it. These two lines used
                        // to be the other way round, so every save persisted the signatures of
                        // the save before it. Nothing looked wrong - the document was correct -
                        // but the page just drawn on was permanently one version behind on disk,
                        // so opening it always found that page stale, wrote it again two seconds
                        // later, and left every close with work to do.
                        doc.savedSignatures =
                            if (written.size == pageCount) signatures
                            else doc.savedSignatures + written.associateWith { signatures[it]!! }
                        doc.savedInk = ink
                        doc.pagesHadNoInk = false
                        stamp?.let { journal.noteInSync(doc.file, it) }
                        PristineStore.noteRewritten(
                            context, doc.file, doc.savedSignatures,
                            wasFullRewrite = !appended
                        )
                        if (recordHistory) journal.record(doc.file, ink)
                    }
                }

                // a copy carries the original's per-file rules forward; that is almost always
                // what someone means when they duplicate an assignment
                if (!overwrite && prefs.hasOverride(doc.file.absolutePath)) {
                    prefs.overrideFor(doc.file.absolutePath)
                        ?.let { prefs.setOverride(target.absolutePath, it) }
                }
                SaveResult.Written(target, wasCopy = !overwrite, backup = backup)
            },
            onFailure = {
                EventLog.error("export", "${doc.file.name} failed: ${it::class.simpleName}: ${it.message}")
                SaveResult.Failed(it)
            }
        )
    }

    /**
     * Write the annotated document to a file you name, whole or in part.
     *
     * The difference from [export] is that nothing here is decided by the save rules: the caller
     * has already asked the user where it should go and what should be in it, which is what an
     * export is for. Exporting had quietly become "write a copy next to the original and say so
     * in a snackbar", which is a save, not a send - there was no way to hand the finished thing
     * to anyone without going and finding it in a file manager afterwards.
     *
     * [pages] null means the whole document. The target is written atomically by the exporter, so
     * a failure part-way leaves nothing behind.
     */
    fun exportTo(
        doc: OpenDocument,
        target: File,
        pages: List<Int>?,
        flatten: Boolean
    ): Result<File> = writing {
        runCatching {
            val store = ImageStore(doc.file)
            InkExporter.imageResolver = { id -> store.load(id) }
            InkExporter.displayOrigin = doc.ink.canvas?.let { it.left to it.top }
            matchCanvasPaper(doc)
            target.parentFile?.mkdirs()

            val format = if (flatten) InkFormat.FLATTENED else InkFormat.ANNOTATIONS
            if (!PageSources.isPdf(doc.file)) {
                // An image has no annotation layer, so the only question is which container the
                // result goes in - and asking for a .pdf of a photograph is a reasonable thing
                // to want when the other end only accepts PDFs.
                if (target.extension.lowercase() == "pdf") {
                    InkExporter.exportImageAsPdf(doc.file, target, doc.ink).getOrThrow()
                } else {
                    InkExporter.exportImage(doc.file, target, doc.ink).getOrThrow()
                }
            } else if (pages == null) {
                // The editable copy rides along, so a document sent and sent back is still one
                // this app can pick the handwriting out of. Flattening deliberately omits it:
                // that mode exists to produce something that cannot be edited.
                val payload = if (flatten) null else {
                    journal.textFor(doc.ink)?.let { com.inkslate.core.InkPayload.encodeText(it) }
                        ?: com.inkslate.core.InkPayload.encode(doc.ink)
                }
                InkExporter.exportPdf(doc.file, target, doc.ink, format, embed = payload)
                    .getOrThrow()
            } else {
                val wanted = pages.filter { it in 0 until doc.pageCount }.distinct().sorted()
                require(wanted.isNotEmpty()) { "No pages were selected" }
                InkExporter.exportPdfPages(doc.file, target, doc.ink, format, wanted).getOrThrow()
            }
            EventLog.info("export", "Wrote ${target.name} (${target.length() / 1024}KB)")
            target
        }.onFailure { EventLog.error("export", "Export to ${target.name} failed: ${it.message}") }
    }

    /**
     * Export a page range to a new file beside the original. Always a copy: exporting a slice
     * over the top of the whole document would destroy the rest of it.
     */
    fun exportRange(doc: OpenDocument, from: Int, to: Int, flatten: Boolean): Result<File> =
        runCatching {
            require(PageSources.isPdf(doc.file)) { "Page ranges apply to PDFs only" }
            val label = if (from == to) "p${from + 1}" else "p${from + 1}-${to + 1}"
            val dir = doc.file.parentFile!!
            var target = File(dir, "${doc.file.nameWithoutExtension} ($label).pdf")
            var n = 2
            while (target.exists()) {
                target = File(dir, "${doc.file.nameWithoutExtension} ($label) ($n).pdf")
                n++
            }
            val store = ImageStore(doc.file)
            InkExporter.imageResolver = { id -> store.load(id) }
            InkExporter.displayOrigin = doc.ink.canvas?.let { it.left to it.top }
            InkExporter.exportPdfRange(
                doc.file, target, doc.ink,
                if (flatten) InkFormat.FLATTENED else InkFormat.ANNOTATIONS,
                from, to
            ).getOrThrow()
            EventLog.info("export", "Range $label to ${target.name}")
            target
        }.onFailure { EventLog.error("export", "Range export failed: ${it.message}") }

    /**
     * Write the chosen pages out as their own document, beside the original.
     *
     * Named for what it contains rather than with a running number: "Homework (p2, p5-6).pdf"
     * says what it is in a file listing, which "Homework (2).pdf" does not.
     */
    fun exportPages(doc: OpenDocument, pages: List<Int>, flatten: Boolean): Result<File> =
        runCatching {
            require(PageSources.isPdf(doc.file)) { "Only PDFs can be split by page" }
            val wanted = pages.filter { it in 0 until doc.pageCount }.distinct().sorted()
            require(wanted.isNotEmpty()) { "No pages were selected" }

            val dir = doc.file.parentFile!!
            var target = File(dir, "${doc.file.nameWithoutExtension} (${describePages(wanted)}).pdf")
            var n = 2
            while (target.exists()) {
                target = File(
                    dir,
                    "${doc.file.nameWithoutExtension} (${describePages(wanted)}) ($n).pdf"
                )
                n++
            }

            val store = ImageStore(doc.file)
            InkExporter.imageResolver = { id -> store.load(id) }
            InkExporter.displayOrigin = doc.ink.canvas?.let { it.left to it.top }
            InkExporter.exportPdfPages(
                doc.file, target, doc.ink,
                if (flatten) InkFormat.FLATTENED else InkFormat.ANNOTATIONS,
                wanted
            ).getOrThrow()
            EventLog.info("export", "${wanted.size} page(s) to ${target.name}")
            target
        }.onFailure { EventLog.error("export", "Page export failed: ${it.message}") }

    /** "p2, p5-6": runs collapsed, so a long selection still fits in a filename. */
    private fun describePages(pages: List<Int>): String {
        val runs = ArrayList<IntRange>()
        var start = pages.first()
        var prev = start
        for (p in pages.drop(1)) {
            if (p == prev + 1) { prev = p; continue }
            runs.add(start..prev); start = p; prev = p
        }
        runs.add(start..prev)
        val text = runs.joinToString(", ") { r ->
            if (r.first == r.last) "p${r.first + 1}" else "p${r.first + 1}-${r.last + 1}"
        }
        // Filenames have limits and page lists do not.
        return if (text.length <= 40) text else "${pages.size} pages"
    }

    fun copyTargetFor(source: File, settings: SaveSettings): File {
        val dir = when (settings.copyLocation) {
            CopyLocation.SAME_FOLDER -> source.parentFile
            CopyLocation.FIXED_FOLDER ->
                settings.copyFolder?.let { File(it) }?.takeIf { it.isDirectory || it.mkdirs() }
                    ?: source.parentFile
        } ?: source.parentFile!!
        val name = SavePrefs.copyTargetName(source.name, settings) { File(dir, it).exists() }
        return File(dir, name)
    }

    // ---- backups / version history -------------------------------------------

    /**
     * Snapshot the original before overwriting it.
     *
     * These accumulate into a usable version history, which is also the answer to "I overwrote
     * the blank worksheet by accident".
     */
    fun makeBackup(file: File): Result<File> = runCatching {
        val dir = backupDirFor(file)
        dir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val target = File(dir, "${file.nameWithoutExtension}_$stamp.${file.extension}")
        file.copyTo(target, overwrite = true)
        pruneBackups(dir, file.nameWithoutExtension, keep = MAX_BACKUPS)
        target
    }

    fun backupDirFor(file: File) = File(file.parentFile, BACKUP_DIR)

    fun backupsFor(file: File): List<File> =
        backupDirFor(file).listFiles { f ->
            f.isFile && f.name.startsWith("${file.nameWithoutExtension}_")
        }?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun pruneBackups(dir: File, stem: String, keep: Int) {
        val all = dir.listFiles { f -> f.isFile && f.name.startsWith("${stem}_") }
            ?.sortedByDescending { it.lastModified() } ?: return
        all.drop(keep).forEach { it.delete() }
    }

    fun restoreBackup(backup: File, target: File): Result<Unit> = runCatching {
        // snapshot what is there now, so restoring is itself undoable. A failure here is not
        // fatal - the backup being restored is still intact - so the result is deliberately
        // dropped rather than aborting a recovery the user has already confirmed.
        if (target.exists()) makeBackup(target).getOrNull()
        backup.copyTo(target, overwrite = true)
        Unit
    }

    companion object {
        const val BACKUP_DIR = ".inkslate-backups"
        const val MAX_BACKUPS = 20

        /**
         * Appending writes only what changed, onto the end of the file that is already there.
         *
         * The alternative is rebuilding the whole document, which costs the same whether one
         * stroke changed or a thousand and scales with the size of the book rather than the size
         * of the edit. Every append is checked afterwards and the file is put back exactly as it
         * was if the check fails - see [InkEmbedder.verifyOutput], which exists because an
         * incremental write that goes wrong produces a valid document carrying the previous
         * save's handwriting rather than an error.
         */
        private const val APPEND_ENABLED = true
    }
}
