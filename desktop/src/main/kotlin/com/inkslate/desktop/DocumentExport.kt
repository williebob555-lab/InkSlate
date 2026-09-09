package com.inkslate.desktop

import com.inkslate.core.CopyLocation
import com.inkslate.core.CopyNames
import com.inkslate.core.InkDocument
import com.inkslate.core.InkFormat
import com.inkslate.core.SaveMode
import com.inkslate.core.SaveSettings
import com.inkslate.core.Stroke
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSFloat
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import org.apache.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** What a save did, so the editor can say so accurately. */
sealed interface SaveResult {
    data class Written(val target: File, val wasCopy: Boolean, val backup: File?) : SaveResult
    data class Failed(val error: Throwable) : SaveResult
    data object NothingToDo : SaveResult
}

/**
 * Writing the annotated document out, under the rules the file is saving by.
 *
 * The Android `InkExporter`, against full PDFBox. The two formats are the same two, and mean the
 * same things: an annotation carrying its own appearance stream, which stays selectable in
 * Acrobat and renders correctly everywhere because the appearance is always written rather than
 * left for the viewer to synthesise; or flattened into the page content, which is permanent and
 * is the safest thing to hand a submission portal that strips annotations.
 */
object DocumentExport {

    /** Written into every annotation this app makes, so its own can be told from anyone else's. */
    private const val ANNOT_TAG = "InkSlate"
    private const val ANNOT_KEY = "InkSlateObj"

    /** Copies of the original, kept beside it before an overwrite. */
    const val BACKUP_DIR = ".inkslate-backups"
    private const val MAX_BACKUPS = 20

    /**
     * Save [doc] into or beside [source], according to [settings].
     *
     * Overwriting is the one action here that can destroy something the app did not create, so a
     * backup is taken first and **the overwrite is refused if the backup fails** rather than
     * proceeding quietly.
     */
    fun save(source: File, doc: InkDocument, settings: SaveSettings): SaveResult {
        if (!DesktopSources.isPdf(source)) {
            return SaveResult.Failed(
                UnsupportedOperationException("Only PDFs can be exported this way")
            )
        }
        if (doc.totalStrokes == 0) return SaveResult.NothingToDo

        val overwrite = settings.mode == SaveMode.OVERWRITE
        val target = if (overwrite) source else copyTargetFor(source, settings)

        var backup: File? = null
        if (overwrite && settings.backupOnOverwrite) {
            backup = makeBackup(source).getOrNull()
                ?: return SaveResult.Failed(
                    IllegalStateException(
                        "Could not create a backup, so the original was left untouched"
                    )
                )
        }

        return runCatching {
            write(source, target, doc, settings.inkFormat)
            // The written file carries the handwriting inside it as well as on the page, so a
            // copy handed to someone else - or synced to the tablet - is still a document this
            // app can edit rather than a flat picture of one.
            if (DesktopEmbedder.supports(target)) DesktopEmbedder.write(target, doc)
            SaveResult.Written(target, wasCopy = !overwrite, backup = backup)
        }.getOrElse { SaveResult.Failed(it) }
    }

    /** Where a copy goes, by the naming and location rules. */
    fun copyTargetFor(source: File, settings: SaveSettings): File {
        val dir = when (settings.copyLocation) {
            CopyLocation.SAME_FOLDER -> source.parentFile
            CopyLocation.FIXED_FOLDER ->
                settings.copyFolder?.let(::File)?.takeIf { it.isDirectory } ?: source.parentFile
        } ?: source.parentFile
        val name = CopyNames.copyTargetName(source.name, settings) { File(dir, it).exists() }
        return File(dir, name)
    }

    /**
     * Snapshot the original before it is overwritten.
     *
     * Kept beside the document rather than in application data, so it survives the app being
     * reinstalled and can be found by hand - it doubles as version history.
     */
    fun makeBackup(source: File): Result<File> = runCatching {
        val dir = File(source.parentFile, BACKUP_DIR)
        require(dir.isDirectory || dir.mkdirs()) { "Could not make a backup folder" }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val target = File(dir, "${source.nameWithoutExtension}_$stamp.${source.extension}")
        source.copyTo(target, overwrite = false)
        require(target.length() == source.length()) { "The backup came out the wrong size" }
        prune(dir, source.nameWithoutExtension)
        target
    }

    /** Keep the most recent copies of one document, so the folder does not grow without end. */
    private fun prune(dir: File, stem: String) {
        val mine = dir.listFiles { f -> f.isFile && f.name.startsWith(stem + "_") }
            ?.sortedByDescending { it.lastModified() } ?: return
        mine.drop(MAX_BACKUPS).forEach { it.delete() }
    }

    fun backupsFor(source: File): List<File> =
        File(source.parentFile, BACKUP_DIR)
            .listFiles { f -> f.isFile && f.name.startsWith(source.nameWithoutExtension + "_") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /** Put a backup back, after snapshotting what is there now so the undo is itself undoable. */
    fun restoreBackup(backup: File, target: File): Result<Unit> = runCatching {
        require(backup.isFile) { "That version is no longer there" }
        makeBackup(target)
        val staged = File(target.parentFile, "." + target.name + ".restore")
        backup.copyTo(staged, overwrite = true)
        require(staged.length() == backup.length()) { "The restored copy came out wrong" }
        if (!staged.renameTo(target)) {
            staged.copyTo(target, overwrite = true)
            staged.delete()
        }
    }

    /**
     * Write a named copy, optionally of only some pages.
     *
     * Separate from [save] because it answers a different question: not "what happens to this
     * document" but "make me a file to hand in". It never touches the original, and a page
     * subset is built by dropping the rest from a copy rather than by assembling a new document,
     * so everything a page carries - its size, its rotation, its fonts - comes with it.
     */
    fun exportTo(
        source: File,
        target: File,
        doc: InkDocument,
        pages: List<Int>?,
        flatten: Boolean
    ): Result<File> = runCatching {
        require(DesktopSources.isPdf(source)) { "Only PDFs can be exported this way" }
        target.parentFile?.mkdirs()

        val format = if (flatten) InkFormat.FLATTENED else InkFormat.ANNOTATIONS
        write(source, target, doc, format)

        if (pages != null) {
            val keep = pages.toSortedSet()
            Loader.loadPDF(target).use { pdf ->
                for (i in pdf.numberOfPages - 1 downTo 0) {
                    if (i !in keep) pdf.removePage(i)
                }
                val tmp = File(target.parentFile, "." + target.name + ".pages")
                FileOutputStream(tmp).use { out -> pdf.save(out) }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
        }

        // Flattening deliberately omits the editable copy: that mode exists to produce something
        // that cannot be edited again, and carrying the strokes along would undo the point of it.
        if (!flatten && DesktopEmbedder.supports(target)) DesktopEmbedder.write(target, doc)
        target
    }

    /** A name that is not already taken, so an export never lands on an earlier one. */
    fun freeTarget(dir: File, stem: String): File {
        var candidate = File(dir, "$stem.pdf")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$stem ($n).pdf")
            n++
        }
        return candidate
    }

    // ---- writing -------------------------------------------------------------

    private fun write(source: File, target: File, doc: InkDocument, format: InkFormat) {
        Loader.loadPDF(source).use { pdf ->
            for (index in 0 until pdf.numberOfPages) {
                val page = pdf.getPage(index)
                // Anything this app wrote last time goes, so a second save does not stack a
                // second copy of every mark on top of the first.
                (page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray)
                    ?.let { removeOurAnnotations(it) }

                val strokes = doc.strokesOn(index)
                if (strokes.isEmpty()) continue
                val ordered = strokes.sortedBy { if (it.isHighlighter) 0 else 1 }
                val toUser = displayToUser(pdf, index)

                when (format) {
                    InkFormat.FLATTENED -> PDPageContentStream(
                        pdf, page, PDPageContentStream.AppendMode.APPEND, true, true
                    ).use { cs ->
                        cs.saveGraphicsState()
                        cs.transform(toUser)
                        ordered.forEach { DocumentIO.drawInto(cs, it) }
                        cs.restoreGraphicsState()
                    }

                    InkFormat.ANNOTATIONS -> {
                        val annots = (page.cosObject.getDictionaryObject(COSName.ANNOTS)
                            as? COSArray) ?: COSArray().also {
                            page.cosObject.setItem(COSName.ANNOTS, it)
                        }
                        ordered.forEach { s ->
                            buildAnnotation(pdf, s, toUser)?.let { annots.add(it) }
                        }
                    }
                }
            }
            val tmp = File(target.parentFile, "." + target.name + ".tmp")
            FileOutputStream(tmp).use { pdf.save(it) }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    /**
     * Remove annotations this app wrote, leaving everyone else's alone.
     *
     * Matched on a private key first and the title second. Deleting an annotation a marker or a
     * classmate added would be far worse than leaving a stale one of ours behind, so the match
     * has to be something only this app writes.
     */
    private fun removeOurAnnotations(annots: COSArray) {
        for (i in annots.size() - 1 downTo 0) {
            val dict = annots.getObject(i) as? COSDictionary ?: continue
            val mine = (dict.getDictionaryObject(COSName.getPDFName(ANNOT_KEY)) as? COSString)
                ?.string == ANNOT_TAG ||
                (dict.getDictionaryObject(COSName.T) as? COSString)?.string == ANNOT_TAG
            if (mine) annots.remove(i)
        }
    }

    /**
     * One annotation per object.
     *
     * Freehand becomes a genuine `/Ink` annotation with an `/InkList`, so other editors
     * understand it as ink; everything else becomes a `/Stamp`, which is the honest generic
     * carrier for "this object has a custom appearance". The appearance stream is always written,
     * which is why these render correctly in Chrome and Canvas rather than only in Acrobat.
     */
    private fun buildAnnotation(pdf: PDDocument, s: Stroke, toUser: Matrix): COSDictionary? {
        val b = s.boundsBox()
        if (b.isEmpty && s.kind != Stroke.Kind.TEXT) return null

        val pad = max(2f, s.baseWidth)
        val corners = listOf(
            b.left - pad to b.top - pad, b.right + pad to b.top - pad,
            b.right + pad to b.bottom + pad, b.left - pad to b.bottom + pad
        ).map { (x, y) -> mapPoint(toUser, x, y) }

        val minX = corners.minOf { it[0] }
        val maxX = corners.maxOf { it[0] }
        val minY = corners.minOf { it[1] }
        val maxY = corners.maxOf { it[1] }
        val rect = PDRectangle(minX, minY, maxX - minX, maxY - minY)

        val appearance = PDAppearanceStream(pdf).apply {
            bBox = rect
            resources = PDResources()
            // The bounding box is already in user space, so the form keeps its identity matrix.
        }
        PDPageContentStream(pdf, appearance).use { cs ->
            cs.saveGraphicsState()
            cs.transform(toUser)
            DocumentIO.drawInto(cs, s)
            cs.restoreGraphicsState()
        }

        val dict = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.getPDFName("Annot"))
            setItem(COSName.SUBTYPE, COSName.getPDFName(if (s.isFreehand) "Ink" else "Stamp"))
            setItem(COSName.RECT, rect.cosObject)
            setInt(COSName.F, 4)                       // Print flag: appears in printed output
            setItem(COSName.T, COSString(ANNOT_TAG))
            // A key of our own, so recognising our annotations does not depend on the title -
            // which any PDF editor is free to rewrite.
            setItem(COSName.getPDFName(ANNOT_KEY), COSString(ANNOT_TAG))
            setFloat(COSName.CA, s.effectiveAlpha.coerceIn(0f, 1f))
            setItem(
                COSName.getPDFName("BS"),
                COSDictionary().apply {
                    setFloat(COSName.W, s.baseWidth)
                    setItem(COSName.S, COSName.getPDFName("S"))
                }
            )
            setItem(
                COSName.AP,
                PDAppearanceDictionary().apply { setNormalAppearance(appearance) }.cosObject
            )
        }

        if (s.isFreehand) {
            // /InkList: one array of alternating x y per stroke path, in user space
            val inkList = COSArray()
            val path = COSArray()
            for (p in s.points) {
                val u = mapPoint(toUser, p.x, p.y)
                path.add(COSFloat(u[0]))
                path.add(COSFloat(u[1]))
            }
            inkList.add(path)
            dict.setItem(COSName.getPDFName("InkList"), inkList)
        }
        return dict
    }

    private fun mapPoint(m: Matrix, x: Float, y: Float): FloatArray {
        val v = m.transformPoint(x, y)
        return floatArrayOf(v.x, v.y)
    }

    /**
     * Ink is stored top-left origin, y down. PDF user space is bottom-left, y up, and pages may
     * carry a rotation - so one matrix per page converts, rather than every point.
     */
    internal fun displayToUser(pdf: PDDocument, index: Int): Matrix {
        val page: PDPage = pdf.getPage(index)
        val box = page.cropBox ?: page.mediaBox
        val llx = box.lowerLeftX
        val lly = box.lowerLeftY
        val urx = box.upperRightX
        val ury = box.upperRightY
        return when (((page.rotation % 360) + 360) % 360) {
            90 -> Matrix(0f, 1f, 1f, 0f, llx, lly)
            180 -> Matrix(-1f, 0f, 0f, 1f, urx, lly)
            270 -> Matrix(0f, -1f, -1f, 0f, urx, ury)
            else -> Matrix(1f, 0f, 0f, -1f, llx, ury)
        }
    }
}
