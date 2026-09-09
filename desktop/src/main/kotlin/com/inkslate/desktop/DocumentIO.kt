package com.inkslate.desktop

import com.inkslate.core.InkDocument
import com.inkslate.core.Stroke
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.math.max

/**
 * Reading and writing documents, using exactly the format the Android app writes.
 *
 * The handwriting lives **inside** the document - a PDF attachment, or a private chunk in an
 * image - not in a companion file beside it. That changed on the Android side while this module
 * was parked, and it is the reason a document dropped into a synced folder by the tablet opens
 * here with its annotations already on it and nothing else to copy across.
 *
 * Companion `.inkdoc` files are still read when they turn up, because documents last saved by an
 * older build will have them, and Syncthing may still be carrying some around. They are merged in
 * and then left alone: deleting a user's file from the desktop, where the tablet may still be
 * mid-sync, is not this module's decision to make.
 */
object DocumentIO {

    private val prefs: Preferences = Preferences.userRoot().node("com/inkslate/desktop")

    /** Stable per-installation tag, so stroke ids from this machine cannot collide with others. */
    fun deviceTag(): String {
        prefs.get("deviceTag", null)?.let { return it }
        val tag = UUID.randomUUID().toString().replace("-", "").take(8)
        prefs.put("deviceTag", tag)
        return tag
    }

    fun sidecarFor(file: File) = File(InkDocument.sidecarPathFor(file.absolutePath))

    data class Loaded(
        val ink: InkDocument,
        val mergedConflicts: Int,
        val sourceChanged: Boolean
    )

    fun load(file: File, pageCount: Int): Loaded {
        val sidecar = sidecarFor(file)

        // Everything we can find, merged. The merge is commutative and drops nothing, so where
        // the sources disagree the union is a safer answer than picking a winner.
        val embedded = if (DesktopEmbedder.supports(file)) DesktopEmbedder.read(file) else null
        val legacy = read(sidecar)

        var doc = embedded ?: legacy ?: InkDocument.create(
            sourceName = file.name,
            kind = if (DesktopSources.isPdf(file)) "pdf" else "image",
            pageCount = pageCount,
            sizeBytes = file.length(),
            fingerprint = DesktopSources.fingerprint(file)
        )
        listOfNotNull(embedded, legacy).forEach { if (it !== doc) doc = doc.mergeWith(it) }

        // Syncthing renames one side of a clash rather than merging. Left alone those are
        // invisible lost work, so they are folded in - but nothing is deleted from here.
        val conflicts = conflictFiles(sidecar)
        for (c in conflicts) read(c)?.let { doc = doc.mergeWith(it) }

        val changed = doc.source.fingerprint.isNotEmpty() &&
            doc.source.fingerprint != DesktopSources.fingerprint(file)

        return Loaded(doc, conflicts.size, changed)
    }

    fun conflictFiles(sidecar: File): List<File> {
        val dir = sidecar.parentFile ?: return emptyList()
        val stem = sidecar.name.removeSuffix("." + InkDocument.EXTENSION)
        return dir.listFiles { f ->
            f.isFile && f.name.startsWith(stem) &&
                f.name.contains(".sync-conflict-") &&
                f.name.endsWith("." + InkDocument.EXTENSION)
        }?.toList().orEmpty()
    }

    private fun read(f: File): InkDocument? =
        runCatching { if (f.isFile) InkDocument.parse(f.readText()) else null }.getOrNull()

    fun write(target: File, doc: InkDocument): Boolean = runCatching {
        val tmp = File(target.parentFile, "." + target.name + ".tmp")
        tmp.writeText(doc.compacted().serialize())
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            target.writeText(doc.compacted().serialize())
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    /**
     * Write the handwriting into the document.
     *
     * Same rule as the tablet: the document is the system of record, so this is what makes an
     * edit real and what carries it to the other devices. [autosave] keeps a local scratch copy
     * between those writes.
     */
    fun save(file: File, doc: InkDocument): Boolean =
        if (DesktopEmbedder.supports(file)) {
            DesktopEmbedder.write(file, doc).isSuccess
        } else {
            // Nothing else can carry a payload, so this format keeps a companion file.
            write(sidecarFor(file), doc)
        }

    // ---- the working copy ----------------------------------------------------

    /**
     * A scratch copy kept out of the way, in the user's application data.
     *
     * The document itself is only written on an explicit save, which means a crash in between
     * would otherwise take the work with it. Kept here rather than beside the document so it
     * neither clutters a folder nor gets swept into a sync.
     */
    private val workingDir: File by lazy {
        val base = System.getenv("LOCALAPPDATA")
            ?: System.getProperty("user.home")
        File(base, "InkSlate/working").apply { mkdirs() }
    }

    private fun workingFileFor(file: File): File {
        val key = java.security.MessageDigest.getInstance("SHA-1")
            .digest(file.absolutePath.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return File(workingDir, "$key.${InkDocument.EXTENSION}")
    }

    fun loadWorking(file: File): InkDocument? = read(workingFileFor(file))

    fun saveWorking(file: File, doc: InkDocument): Boolean = write(workingFileFor(file), doc)

    /** Whether there is a working copy at all, without parsing it. Asked once per row on Home. */
    fun hasWorking(file: File): Boolean = workingFileFor(file).isFile

    /**
     * Follow a document that has been renamed or moved.
     *
     * The working copy is keyed by path, so without this a rename would orphan the scratch copy
     * and hand it to whatever file next took the old name.
     */
    fun relocateWorking(from: File, to: File) {
        val old = workingFileFor(from)
        if (!old.isFile) return
        val new = workingFileFor(to)
        if (!old.renameTo(new)) {
            runCatching { old.copyTo(new, overwrite = true) }
            old.delete()
        }
    }

    /** Drop the working copy for a deleted document, for the same keyed-by-path reason. */
    fun forgetWorking(file: File) {
        workingFileFor(file).delete()
    }

    // ---- export ---------------------------------------------------------------

    /**
     * Flatten the ink into a copy of the PDF.
     *
     * Deliberately always a copy on the desktop: overwriting the original from a laptop while the
     * tablet may still be syncing the same file is a good way to lose work.
     */
    fun exportFlattened(source: File, doc: InkDocument): Result<File> = runCatching {
        require(DesktopSources.isPdf(source)) { "Only PDFs can be exported this way" }

        var target = File(source.parentFile, source.nameWithoutExtension + "_annotated.pdf")
        var n = 2
        while (target.exists()) {
            target = File(source.parentFile, source.nameWithoutExtension + "_annotated ($n).pdf")
            n++
        }

        Loader.loadPDF(source).use { pdf ->
            for (index in 0 until pdf.numberOfPages) {
                val strokes = doc.strokesOn(index)
                if (strokes.isEmpty()) continue
                val page = pdf.getPage(index)
                PDPageContentStream(
                    pdf, page, PDPageContentStream.AppendMode.APPEND, true, true
                ).use { cs ->
                    cs.saveGraphicsState()
                    cs.transform(displayToUser(pdf, index))
                    strokes.sortedBy { if (it.isHighlighter) 0 else 1 }.forEach { draw(cs, it) }
                    cs.restoreGraphicsState()
                }
            }
            val tmp = File(target.parentFile, "." + target.name + ".tmp")
            FileOutputStream(tmp).use { pdf.save(it) }
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        }
        target
    }

    /**
     * Ink is stored top-left origin, y down. PDF user space is bottom-left, y up, and pages may
     * carry a rotation - so one matrix per page converts, rather than every point.
     */
    private fun displayToUser(pdf: PDDocument, index: Int): Matrix {
        val page = pdf.getPage(index)
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

    private fun draw(cs: PDPageContentStream, s: Stroke) {
        // PDFBox 3 takes components as 0..1 floats; the 0..255 integer overloads are gone.
        val r = ((s.color shr 16) and 0xFF) / 255f
        val g = ((s.color shr 8) and 0xFF) / 255f
        val b = (s.color and 0xFF) / 255f
        cs.setStrokingColor(r, g, b)
        cs.setNonStrokingColor(r, g, b)
        cs.setLineCapStyle(if (s.brush.flatTip) 0 else 1)
        cs.setLineJoinStyle(1)

        when (s.kind) {
            Stroke.Kind.FREEHAND -> {
                if (s.points.size < 2) return
                if (s.usesOutlineRender) {
                    // The same polygons the screen fills. PDF has no variable-width stroke, so
                    // tapered ink is a filled shape either way; sharing the builder is what keeps
                    // a laptop export identical to a tablet one.
                    for (poly in com.inkslate.core.StrokeOutline.contours(s)) {
                        if (poly.size < 6) continue
                        cs.moveTo(poly[0], poly[1])
                        var i = 2
                        while (i < poly.size) {
                            cs.lineTo(poly[i], poly[i + 1])
                            i += 2
                        }
                        cs.closePath()
                    }
                    cs.fill()
                } else {
                    cs.setLineWidth(max(0.3f, s.baseWidth))
                    cs.moveTo(s.points[0].x, s.points[0].y)
                    for (i in 1 until s.points.size) {
                        cs.lineTo(s.points[i].x, s.points[i].y)
                    }
                    cs.stroke()
                }
            }
            Stroke.Kind.LINE, Stroke.Kind.ARROW -> {
                val a = s.points.first()
                val c = s.points.last()
                cs.setLineWidth(max(0.3f, s.baseWidth))
                cs.moveTo(a.x, a.y); cs.lineTo(c.x, c.y); cs.stroke()
            }
            Stroke.Kind.RECT, Stroke.Kind.TABLE, Stroke.Kind.IMAGE -> {
                val box = s.rectBox()
                cs.setLineWidth(max(0.3f, s.baseWidth))
                cs.addRect(box.left, box.top, box.width, box.height)
                cs.stroke()
            }
            Stroke.Kind.ELLIPSE -> {
                val box = s.rectBox()
                val k = 0.5523f
                val rx = box.width / 2f
                val ry = box.height / 2f
                val cx = box.centerX
                val cy = box.centerY
                cs.setLineWidth(max(0.3f, s.baseWidth))
                cs.moveTo(cx - rx, cy)
                cs.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
                cs.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
                cs.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
                cs.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
                cs.closePath()
                cs.stroke()
            }
            Stroke.Kind.TEXT -> Unit    // handled by the Android exporter; not yet on desktop
        }
    }
}
