package com.inkslate.pdf

import android.content.Context
import com.inkslate.data.EventLog
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * The document as it would be without this app's marks on it.
 *
 * Saving used to start by reading back the file this app itself wrote last time - a document
 * carrying hundreds of its own annotations, each one a separate compressed stream. Measured on
 * the device, loading that copy of a three-page assignment took 785ms; loading the same document
 * with those annotations taken out took 8ms. Every save paid the first number, and so did every
 * open, to produce a result that depends on neither.
 *
 * Nothing about the pristine copy changes when this app saves - only the marks laid over it do -
 * so it is kept and reused rather than derived again. The stamp beside it records which version
 * of the document it was taken from, and a save updates that stamp: the copy is still correct,
 * it is simply now the pristine form of a newer file. Anything else changing the document leaves
 * a stamp that does not match, and the copy is rebuilt from what actually arrived.
 *
 * This also serves the renderer, which needs exactly the same thing for a different reason: page
 * images have to come from a document without our annotations in it, or every stroke is drawn
 * twice. See [CleanRenderSource].
 */
object PristineStore {

    /** Past this, rewriting the whole file is the wrong trade; the caller uses the original. */
    private const val MAX_BYTES = 80L * 1024 * 1024

    private const val DOC = "doc.pdf"
    private const val STAMP = "stamp.txt"
    private const val OUTPUT = "output.txt"
    private const val PAGES = "pages.txt"
    private const val COMPACT = "compact.txt"

    private fun root(context: Context) = File(context.filesDir, "pristine").apply { mkdirs() }

    private fun dirFor(context: Context, file: File): File {
        val key = MessageDigest.getInstance("SHA-1").digest(file.absolutePath.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        return File(root(context), key).apply { mkdirs() }
    }

    /**
     * The pristine copy of [file], or null when [file] is already pristine and can be used as-is.
     *
     * Null is not a failure. A document with none of our annotations in it *is* the clean copy,
     * and keeping a byte-identical duplicate of it would cost disk to save nothing.
     */
    fun forDocument(context: Context, file: File): File? {
        if (!PageSources.isPdf(file) || !file.isFile) return null
        if (file.length() > MAX_BYTES) return null

        val dir = dirFor(context, file)
        val doc = File(dir, DOC)
        val stamp = File(dir, STAMP)
        val want = PageSources.fingerprint(file)

        if (doc.isFile && doc.length() > 0L && readStamp(stamp) == want) return doc
        return build(context, file, dir, want)
    }

    /**
     * Record that this app rewrote [file], leaving the pristine copy still valid.
     *
     * Called after a save. Without it the very next open or save would find a stamp describing
     * the document as it was before the save, decide the copy was stale, and rebuild it - which
     * is the 780ms this exists to stop paying.
     *
     * [pageSignatures] is what makes the *next* save cheap: it says which handwriting each page
     * was written with. A page whose signature has not moved since already carries the right
     * annotation, and rebuilding it would spend the geometry and compression of every stroke on
     * it to arrive at the bytes already sitting in the file.
     *
     * [wasFullRewrite] records the size of a properly written document, so the appended saves
     * that follow can be stopped once they have added enough dead weight to be worth compacting.
     */
    fun noteRewritten(
        context: Context,
        file: File,
        pageSignatures: Map<Int, Long> = emptyMap(),
        wasFullRewrite: Boolean = true
    ) {
        runCatching {
            val dir = dirFor(context, file)
            if (File(dir, DOC).isFile) File(dir, STAMP).writeText(PageSources.fingerprint(file))
            File(dir, OUTPUT).writeText(PageSources.fingerprint(file))
            val sb = StringBuilder()
            for ((page, sig) in pageSignatures) sb.append(page).append(':').append(sig).appendLine()
            File(dir, PAGES).writeText(sb.toString())
            // Also recorded on the first write of any kind. Without a baseline, the growth
            // guard has nothing to compare against and a run of appends never ends.
            val compact = File(dir, COMPACT)
            if (wasFullRewrite || !compact.isFile) compact.writeText(file.length().toString())
        }
    }

    /**
     * What each page was last written with, or null when [file] is not our last output.
     *
     * Null is the answer whenever anything else has touched the document - another device's copy
     * arriving, an edit in some other app - because then nothing can be assumed about which of
     * its annotations are current, and every page has to be built again.
     */
    fun savedPageState(context: Context, file: File): Map<Int, Long>? {
        val dir = dirFor(context, file)
        val output = readStamp(File(dir, OUTPUT)) ?: return null
        if (output != PageSources.fingerprint(file)) return null
        val text = runCatching { File(dir, PAGES).readText() }.getOrNull() ?: return null
        if (text.isBlank()) return null
        return text.lineSequence().mapNotNull { line ->
            val at = line.indexOf(':')
            if (at <= 0) return@mapNotNull null
            val page = line.substring(0, at).toIntOrNull() ?: return@mapNotNull null
            val sig = line.substring(at + 1).toLongOrNull() ?: return@mapNotNull null
            page to sig
        }.toMap()
    }

    /**
     * The size this document was when it was last written out properly.
     *
     * Appending leaves the objects it supersedes behind, so a run of appended saves grows the
     * file without bound. Comparing against this is how the run is ended.
     */
    fun lastCompactSize(context: Context, file: File): Long =
        readStamp(File(dirFor(context, file), COMPACT))?.toLongOrNull() ?: 0L

    /**
     * Throw the copy away.
     *
     * For the operations that change the pages themselves - rearranging, adding, growing a
     * canvas - where the pristine document genuinely is different afterwards.
     */
    fun invalidate(context: Context, file: File) {
        runCatching { dirFor(context, file).deleteRecursively() }
    }

    private fun build(context: Context, file: File, dir: File, stampValue: String): File? =
        runCatching {
            val doc = File(dir, DOC)
            PDDocument.load(file).use { pdf ->
                var removed = 0
                for (i in 0 until pdf.numberOfPages) {
                    val annots = pdf.getPage(i).cosObject
                        .getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
                    for (j in annots.size() - 1 downTo 0) {
                        val d = annots.getObject(j) as? com.tom_roush.pdfbox.cos.COSDictionary
                            ?: continue
                        if (com.inkslate.data.InkEmbedder.isOurAnnotation(d)) {
                            annots.remove(j); removed++
                        }
                    }
                }
                if (removed == 0) {
                    // Already clean. Say so by keeping nothing, so the caller reads the original.
                    runCatching { dir.deleteRecursively() }
                    return null
                }
                prune(context)
                val tmp = File(dir, "$DOC.tmp")
                FileOutputStream(tmp).use { pdf.save(it) }
                if (!tmp.renameTo(doc)) { tmp.delete(); return null }
                File(dir, STAMP).writeText(stampValue)
                EventLog.info(
                    "pristine",
                    "${file.name}: kept a copy without $removed baked stroke(s), " +
                        "${doc.length() / 1024}KB"
                )
                doc
            }
        }.getOrElse {
            EventLog.warn("pristine", "${file.name}: could not prepare a clean copy: ${it.message}")
            null
        }

    private fun readStamp(f: File): String? =
        runCatching { if (f.isFile) f.readText().trim() else null }.getOrNull()

    /** Bound the store. Every copy here is reproducible, so the oldest can always go. */
    private fun prune(context: Context, keepBytes: Long = 400L * 1024 * 1024) {
        runCatching {
            val dirs = root(context).listFiles { f -> f.isDirectory }
                ?.sortedByDescending { File(it, DOC).lastModified() } ?: return
            var total = 0L
            for (d in dirs) {
                total += File(d, DOC).length()
                if (total > keepBytes) d.deleteRecursively()
            }
        }
    }
}
