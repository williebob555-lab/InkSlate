package com.inkslate.data

import com.inkslate.core.ImageInkCarrier
import com.inkslate.core.InkPayload
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Carries a document's handwriting inside the document itself.
 *
 * Every supported format already has a standard place to put bytes that viewers ignore: PDF has
 * embedded file attachments, and images have private chunks and application segments. Using those
 * rather than a companion file means the handwriting is copied, moved, renamed and synced by
 * whatever the user happens to use - Files, Syncthing, a USB cable - without this app being
 * involved or even installed.
 *
 * Only the PDF half lives here. Images are handled by [ImageInkCarrier] in the shared module,
 * because the desktop build has to read and write byte-identical files, and a second
 * implementation of the same format is a second implementation to get subtly wrong.
 *
 * Nothing here is the only copy. [InkJournal] holds the same data in app-private storage, so a
 * failed or refused write to someone's original document costs nothing.
 */
object InkEmbedder {

    /** The attachment name inside a PDF. */
    const val NAME = "InkSlate.inkdoc"

    /** Matches what the exporter stamps on every annotation it writes. */
    private const val ANNOT_TAG = "InkSlate"
    private const val ANNOT_KEY = "InkSlateObject"

    /**
     * Above this, the document is left alone.
     *
     * Rewriting a file this large on a tablet risks running out of memory partway through, and
     * that failure is far worse than the inconvenience of the handwriting staying in the working
     * store. The user is told, rather than left to discover it on another device.
     */
    private const val MAX_DOCUMENT_BYTES = 400L * 1024 * 1024

    fun supports(file: File): Boolean = when (file.extension.lowercase()) {
        "pdf", "png", "jpg", "jpeg" -> true
        else -> false
    }

    // ---- reading -------------------------------------------------------------

    /**
     * What a document has to say about itself.
     *
     * [bakedVisible] means the pages already have this app's strokes painted onto them, from a
     * save that overwrote the original. The editor has to know, because it draws those same
     * strokes again on top: two copies of the same handwriting, very slightly apart, looks like a
     * rendering fault and only half of it can be erased.
     */
    data class Inspection(
        val ink: InkDocument?,
        val bakedVisible: Boolean,
        /** Digest of the payload as it sits in the file, for telling our own saves apart later. */
        val inkStamp: String? = null,
        /**
         * Which pages carry marks of ours, when that was actually looked at.
         *
         * Null means nobody asked. An empty set means nobody asked *and got no*, which is a very
         * different thing: it says every page is untouched, and so lets a save add to the
         * document rather than rebuild it.
         */
        val annotatedPages: Set<Int>? = null
    )

    /**
     * Read the handwriting and check for baked strokes in a single pass.
     *
     * These were two separate calls to begin with, which on a thousand-page textbook meant
     * parsing the whole document twice over just to open it.
     */
    fun inspect(file: File): Inspection {
        if (!file.isFile) return Inspection(null, false)
        if (!file.extension.equals("pdf", ignoreCase = true)) {
            return Inspection(read(file), false)
        }
        return runCatching {
            PDDocument.load(file).use { pdf ->
                val payload = pdf.documentCatalog?.names?.embeddedFiles?.let { findInTree(it) }
                // Every page, not just far enough to answer yes. Knowing *which* pages carry
                // our marks is what lets the next save rebuild those and leave the other nine
                // hundred alone; stopping at the first one meant a book had to be rebuilt whole.
                val annotated = HashSet<Int>()
                for (i in 0 until pdf.numberOfPages) {
                    val annots = pdf.getPage(i).cosObject
                        .getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
                    for (j in 0 until annots.size()) {
                        val dict = annots.getObject(j) as? COSDictionary ?: continue
                        if (isOurAnnotation(dict)) { annotated.add(i); break }
                    }
                }
                Inspection(
                    InkPayload.decode(payload), annotated.isNotEmpty(),
                    payload?.let { digestOf(it) }, annotated
                )
            }
        }.getOrElse { Inspection(null, false) }
    }

    /** Annotations this app wrote, identified by a private key rather than an editable title. */
    fun isOurAnnotation(dict: COSDictionary): Boolean =
        (dict.getDictionaryObject(COSName.getPDFName(ANNOT_KEY)) as? COSString)?.string == ANNOT_TAG ||
            (dict.getDictionaryObject(COSName.T) as? COSString)?.string == ANNOT_TAG

    /** Pull the handwriting out of a document, or null when it carries none. */
    fun read(file: File): InkDocument? = readAs(file, file.extension)

    private fun readAs(file: File, format: String): InkDocument? =
        InkPayload.decode(rawPayload(file, format))

    /**
     * The payload bytes [file] carries, treating it as [format], without decoding them.
     *
     * The format is passed in rather than taken from the name because a staged rewrite is
     * verified before it is renamed into place, and at that point it is still called
     * `.something.inkslate-tmp`. Dispatching on its own extension made every good write look
     * unreadable and get thrown away.
     *
     * Verifying a save only needs to know the payload is there and came back intact, and
     * comparing bytes proves that. Decoding it into a document to check meant every save
     * inflated and JSON-parsed the whole of the handwriting a second time, purely to confirm
     * what a byte comparison already settles.
     */
    /**
     * Find the payload by reading the file backwards, without parsing it as a PDF.
     *
     * The payload is stored uncompressed precisely so this works: its eight-byte header is
     * findable in the raw bytes. Checking a saved document used to mean loading the whole PDF a
     * second time purely to fetch one attachment.
     *
     * Read from the end in chunks and stopped at the first hit, because an appended save puts the
     * newest payload last - which is also why the *last* occurrence is the right one, the earlier
     * ones being superseded copies an append leaves behind. What comes back runs to the end of
     * the file, so it carries trailing PDF bytes; both readers of it cope, one because a deflate
     * stream ends where it ends, the other by comparing a prefix.
     */
    private fun scanForPayload(file: File): ByteArray? = runCatching {
        val length = file.length()
        if (length < HEADER_BYTES) return null
        java.io.RandomAccessFile(file, "r").use { raf ->
            val chunk = 256 * 1024
            var end = length
            val overlap = 16L
            while (end > 0) {
                val start = maxOf(0L, end - chunk)
                val size = (end - start).toInt()
                val buf = ByteArray(size)
                raf.seek(start)
                raf.readFully(buf)
                for (i in size - 8 downTo 0) {
                    if (InkPayload.looksLikePayload(buf, i)) {
                        val at = start + i
                        val out = ByteArray((length - at).toInt())
                        raf.seek(at)
                        raf.readFully(out)
                        return out
                    }
                }
                if (start == 0L) break
                end = start + overlap
            }
            null
        }
    }.getOrNull()

    /** Eight bytes of magic is the least that could be recognised. */
    private const val HEADER_BYTES = 20

    private fun rawPayload(file: File, format: String): ByteArray? = runCatching {
        if (!file.isFile) return null
        when (format.lowercase()) {
            // The document's own name tree first. Reading backwards for the magic finds the
            // *last* payload in the file, and an appended save leaves earlier ones behind - so
            // the scan can hand back a previous version of the handwriting, which is the one
            // mistake here that loses work rather than time. It stays as a fallback only.
            "pdf" -> readFromPdf(file) ?: scanForPayload(file)
            "png" -> ImageInkCarrier.readPng(file.readBytes())
            "jpg", "jpeg" -> ImageInkCarrier.readJpeg(file.readBytes())
            else -> null
        }
    }.getOrNull()

    private fun readFromPdf(file: File): ByteArray? =
        PDDocument.load(file).use { pdf ->
            val tree = pdf.documentCatalog?.names?.embeddedFiles ?: return null
            findInTree(tree)
        }

    /**
     * Walk the embedded-file name tree.
     *
     * The tree is only flat in small documents; anything produced by a real PDF toolchain may
     * nest it, and missing an attachment because it sat one level down would look exactly like
     * losing the user's work.
     */
    private fun findInTree(node: PDEmbeddedFilesNameTreeNode): ByteArray? {
        node.names?.get(NAME)?.embeddedFile?.let { return it.toByteArray() }
        node.kids?.forEach { kid ->
            (kid as? PDEmbeddedFilesNameTreeNode)?.let { findInTree(it)?.let { b -> return b } }
        }
        return null
    }

    // ---- writing -------------------------------------------------------------

    /**
     * Put the handwriting into [file], replacing anything already there.
     *
     * The replacement is built beside the original and proven readable before it is swapped in.
     * Verifying afterwards would mean discovering a bad write by finding the user's assignment
     * already replaced by it.
     */
    fun write(file: File, doc: InkDocument): Result<String> = runCatching {
        require(file.length() <= MAX_DOCUMENT_BYTES) {
            "${file.name} is too large to rewrite safely (${file.length() / (1024 * 1024)}MB). " +
                "Your handwriting is saved, but it stays on this device for this document."
        }
        val startedAt = System.currentTimeMillis()
        val payload = InkPayload.encode(doc)
        val isPdf = file.extension.equals("pdf", ignoreCase = true)

        // For PDFs, append the change rather than rebuilding the document. The staged file is
        // still a full copy - that is what makes the swap safe - but the original bytes are
        // copied straight through instead of every object being parsed, re-serialised and written
        // out again. On a thousand-page textbook that is the difference between a moment and a
        // long pause holding the whole document in memory.
        var lastError: Throwable? = null
        val attempts = if (isPdf) listOf(true, false) else listOf(false)

        for (incremental in attempts) {
            val staged = try {
                stage(file) { out ->
                    when {
                        isPdf -> writeToPdf(file, payload, out, incremental)
                        file.extension.equals("png", true) ->
                            out.write(ImageInkCarrier.writePng(file.readBytes(), payload))
                        file.extension.lowercase() in setOf("jpg", "jpeg") ->
                            out.write(ImageInkCarrier.writeJpeg(file.readBytes(), payload))
                        else -> error("${file.extension} cannot carry embedded handwriting")
                    }
                }
            } catch (t: Throwable) {
                lastError = t
                continue
            }

            // Read it straight back out of the staged file. Only a document that is provably
            // readable is allowed to replace the one the user already has. Byte-for-byte against
            // what went in is a stricter test than decoding it and counting strokes, and it does
            // not cost a second parse of the whole document.
            val verified = rawPayload(staged, file.extension)
            if (startsWithPayload(verified, payload)) {
                commit(staged, file)
                EventLog.info(
                    "embed",
                    "${file.name}: ${if (incremental) "appended" else "rewrote"} " +
                        "${payload.size / 1024}KB of handwriting in " +
                        "${System.currentTimeMillis() - startedAt}ms"
                )
                return@runCatching digestOf(payload)
            }
            staged.delete()
            lastError = IllegalStateException(
                "Could not read the handwriting back out of the rewritten ${file.name}"
            )
        }
        throw (lastError ?: IllegalStateException("Could not write into ${file.name}"))
    }.onFailure { EventLog.error("embed", "${file.name}: ${it.message}") }

    /**
     * Rewrite a PDF's structure and its handwriting together, safely.
     *
     * [transform] is handed the loaded document and may do what it likes to the page tree; the
     * handwriting in [doc] is embedded afterwards, and the result is staged, proved readable and
     * only then swapped in. Structural edits cannot be incremental - the page tree is being
     * replaced, not appended to - so this always writes a full document.
     *
     * The verification is stricter than the ordinary save's: the page count has to come back as
     * [expectedPages] as well. A rearrangement that silently produced the wrong number of pages
     * is the one failure that would be both plausible and catastrophic.
     */
    fun rewritePdf(
        file: File,
        doc: InkDocument,
        expectedPages: Int,
        /**
         * May return something to be closed once the save has finished - a page imported from
         * another document still reads out of the file it came from, so closing that file before
         * the save writes an empty page.
         */
        transform: (PDDocument) -> AutoCloseable?
    ): Result<Unit> = runCatching {
        require(file.extension.equals("pdf", ignoreCase = true)) {
            "Only PDFs have pages that can be rearranged"
        }
        require(file.length() <= MAX_DOCUMENT_BYTES) {
            "${file.name} is too large to rewrite safely " +
                "(${file.length() / (1024 * 1024)}MB)"
        }
        val payload = InkPayload.encode(doc)
        val staged = stage(file) { out ->
            PDDocument.load(file).use { pdf ->
                val borrowed = transform(pdf)
                try {
                    attachPayload(pdf, payload)
                    pdf.save(out)
                } finally {
                    borrowed?.close()
                }
            }
        }

        val readBack = rawPayload(staged, "pdf")
        val pages = runCatching { PDDocument.load(staged).use { it.numberOfPages } }.getOrDefault(-1)
        if (!startsWithPayload(readBack, payload) || pages != expectedPages) {
            staged.delete()
            error(
                "The rearranged ${file.name} did not read back correctly " +
                    "($pages pages, expected $expectedPages), so it was discarded"
            )
        }
        commit(staged, file)
        EventLog.info("pages", "${file.name}: rewritten with $pages page(s)")
    }.onFailure { EventLog.error("pages", "${file.name}: ${it.message}") }

    /**
     * A short digest of the handwriting [file] carries, or null when it carries none.
     *
     * Reads the attachment without decoding it, so this is a document load and a hash rather
     * than a parse of every stroke. Used to answer "is the copy on disk still the one we wrote",
     * which is a question the file's length and timestamp cannot answer: every save rewrites the
     * file, so those change even when nothing about the handwriting did.
     */
    fun inkDigest(file: File): String? {
        val bytes = rawPayload(file, file.extension) ?: return null
        return digestOf(bytes)
    }

    fun digestOf(payload: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(payload)
            .joinToString("") { "%02x".format(it) }.take(24)

    /**
     * Whether [file] ends with exactly this payload, without parsing it as a PDF.
     *
     * For checking an append, where the payload has just been put at the end of the file and the
     * question is only whether it arrived intact. Every candidate is compared rather than only
     * the last one, so the superseded copies an append leaves behind are simply skipped.
     *
     * Loading the document to answer this instead cost 6795ms on a 959-page textbook - as much
     * as the save it was checking.
     */
    fun appendedPayloadIntact(file: File, payload: ByteArray, within: Long = 32L * 1024 * 1024)
        : Boolean = runCatching {
        val length = file.length()
        if (length < payload.size) return false
        val floor = maxOf(0L, length - within)
        java.io.RandomAccessFile(file, "r").use { raf ->
            val chunk = 256 * 1024
            var end = length
            val probe = ByteArray(payload.size)
            while (end > floor) {
                val start = maxOf(floor, end - chunk)
                val buf = ByteArray((end - start).toInt())
                raf.seek(start)
                raf.readFully(buf)
                for (i in buf.size - 8 downTo 0) {
                    if (!InkPayload.looksLikePayload(buf, i)) continue
                    val at = start + i
                    if (at + payload.size > length) continue
                    raf.seek(at)
                    raf.readFully(probe)
                    if (probe.contentEquals(payload)) return true
                }
                if (start == floor) break
                end = start + 16
            }
            false
        }
    }.getOrDefault(false)

    /** Whether [file] carries exactly [payload]. The check a save is allowed to trust. */
    fun carriesPayload(file: File, payload: ByteArray): Boolean =
        startsWithPayload(rawPayload(file, file.extension), payload)

    private fun startsWithPayload(found: ByteArray?, payload: ByteArray): Boolean {
        if (found == null || found.size < payload.size) return false
        for (i in payload.indices) if (found[i] != payload[i]) return false
        return true
    }

    /**
     * One load, three questions: the right pages, our marks on the pages that should have them,
     * and our handwriting inside, byte for byte.
     *
     * The check that lets a save append rather than rewrite. An incremental write emits only the
     * objects it was told about, so a mistake there does not fail - it produces a perfectly valid
     * document carrying the previous save's handwriting, which is the worst shape a bug can take
     * here. Nothing else would notice until the work was already gone.
     */
    fun verifyOutput(
        file: File,
        payload: ByteArray,
        expectPages: Int,
        mustCarryInk: Set<Int>
    ): String? = runCatching {
        val openedAt = System.currentTimeMillis()
        PDDocument.load(file).use { pdf ->
            EventLog.info(
                "embed",
                "${file.name}: checked in ${System.currentTimeMillis() - openedAt}ms " +
                    "(${mustCarryInk.size} page(s) looked at)"
            )
            if (pdf.numberOfPages != expectPages) {
                return "${pdf.numberOfPages} pages, expected $expectPages"
            }
            // Only the pages this save was responsible for. Walking all of them meant a save on
            // page nine of an eight-hundred-page book parsed eight hundred page dictionaries to
            // check its work, which is the one part of a save that had no business scaling with
            // the size of the book.
            for (i in mustCarryInk) {
                if (i !in 0 until pdf.numberOfPages) continue
                val annots = pdf.getPage(i).cosObject
                    .getDictionaryObject(COSName.ANNOTS) as? COSArray
                    ?: return "page ${i + 1} carries no annotations at all"
                var mine = false
                for (j in 0 until annots.size()) {
                    val d = annots.getObject(j) as? COSDictionary ?: continue
                    if (isOurAnnotation(d)) { mine = true; break }
                }
                if (!mine) return "page ${i + 1} is missing our ink"
            }
            // Out of the name tree, which is what a reader would follow - not by scanning for
            // the magic, which finds whichever payload sits last in the file and rejected a
            // perfectly good append because an earlier copy was still lying there behind it.
            val payloadInPdf = pdf.documentCatalog?.names?.embeddedFiles?.let { findInTree(it) }
            when {
                payloadInPdf == null -> "no handwriting payload in the result"
                !payloadInPdf.contentEquals(payload) ->
                    "payload is ${payloadInPdf.size}B, wrote ${payload.size}B"
                else -> null
            }
        }
    }.getOrElse { "could not be read back: ${it.javaClass.simpleName}: ${it.message}" }

    /** Put the handwriting into an already-loaded document's embedded-file tree. */
    fun attachPayload(pdf: PDDocument, payload: ByteArray) {
        val catalog = pdf.documentCatalog

        val spec = PDComplexFileSpecification().apply {
            this.file = NAME
            fileDescription = "InkSlate handwriting. Safe to ignore; do not edit by hand."
        }
        // Stored without a filter. The payload is already deflated, so a PDF stream filter
        // would compress it a second time for nothing - and leaving it raw is what lets it be
        // found by reading the file backwards instead of parsing the whole document.
        val embedded = PDEmbeddedFile(pdf, ByteArrayInputStream(payload), null).apply {
            subtype = "application/octet-stream"
            size = payload.size
            creationDate = java.util.Calendar.getInstance()
        }
        spec.embeddedFile = embedded

        // Preserve every other name in the document. Replacing the whole dictionary would
        // quietly drop a PDF's named destinations, which is how internal links break.
        val names = catalog.names ?: PDDocumentNameDictionary(catalog)
        val existing = names.embeddedFiles?.names?.toMutableMap() ?: mutableMapOf()
        existing[NAME] = spec
        names.embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { this.names = existing }
        catalog.names = names

        // Say that this dictionary changed, not only the catalog above it.
        //
        // An incremental save writes the objects it is told about and nothing else. The names
        // dictionary usually has an object number of its own, so changing where it points while
        // marking only the catalog left the old one in the file - and the document went on
        // carrying the previous save's handwriting, which is the failure this whole path is most
        // dangerous for. It showed up as a save being rejected for finding a payload one version
        // behind, and then spending a hundred seconds rewriting the book for nothing.
        names.cosObject.setNeedToBeUpdated(true)
        catalog.cosObject.setNeedToBeUpdated(true)

        // Some viewers only reveal attachments when told the document has them.
        catalog.cosObject.setName(COSName.getPDFName("PageMode"), "UseAttachments")
    }

    private fun writeToPdf(
        file: File,
        payload: ByteArray,
        out: java.io.OutputStream,
        incremental: Boolean
    ) {
        PDDocument.load(file).use { pdf ->
            val catalog = pdf.documentCatalog
            // Payload is already deflated by InkPayload, so it is stored raw rather than
            // compressed twice.
            attachPayload(pdf, payload)

            if (incremental) {
                // Mark the objects we touched, since an incremental save only writes those.
                catalog.cosObject.setNeedToBeUpdated(true)
                pdf.document.trailer.setNeedToBeUpdated(true)
                pdf.saveIncremental(out)
            } else {
                pdf.save(out)
            }
        }
    }

    // ---- safe replacement ----------------------------------------------------

    /**
     * Write the replacement next to the document, without touching the document.
     *
     * The dot-prefixed name keeps a half-written file out of the way, but some storage providers
     * refuse to create one - which is what turned an ordinary save into an ENOENT on a `.tmp`
     * file. A plain name is tried next rather than failing the save.
     */
    private inline fun stage(target: File, write: (java.io.OutputStream) -> Unit): File {
        val dir = target.parentFile
        dir?.mkdirs()
        val candidates = listOf(
            File(dir, ".${target.name}.inkslate-tmp"),
            File(dir, "${target.name}.inkslate-tmp")
        )
        var lastError: Throwable? = null
        for (c in candidates) {
            try {
                FileOutputStream(c).use { os ->
                    write(os)
                    os.flush()
                    // Best effort: fsync throws on FUSE-backed external storage even when every
                    // byte landed, and treating that as failure once threw away good saves.
                    runCatching { os.fd.sync() }
                }
                return c
            } catch (t: Throwable) {
                lastError = t
                runCatching { c.delete() }
            }
        }
        throw (lastError ?: java.io.IOException("Could not stage a rewrite of ${target.name}"))
    }

    /** Swap the verified replacement into place. */
    private fun commit(staged: File, target: File) {
        try {
            if (staged.renameTo(target)) return
            if (target.exists() && target.delete() && staged.renameTo(target)) return
            // Last resort for storage that refuses renames: copy the bytes over. Slower, and
            // briefly non-atomic, but by this point the replacement is known to be good.
            staged.inputStream().use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
        } finally {
            runCatching { staged.delete() }
        }
    }
}
