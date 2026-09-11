package com.inkslate.desktop

import com.inkslate.core.InkDocument
import com.inkslate.core.Stroke
import com.inkslate.core.TextFont
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
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

        EventLog.info(
            "open",
            "${file.name}: $pageCount page(s), ${doc.totalStrokes} marks" +
                (if (conflicts.isNotEmpty()) ", merged ${conflicts.size} sync conflict(s)" else "") +
                (if (changed) ", the file changed since these were saved" else "")
        )
        if (conflicts.isNotEmpty()) {
            EventLog.warn("sync", "Merged ${conflicts.size} conflict file(s) into ${file.name}")
        }

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
                    strokes.sortedBy { if (it.isHighlighter) 0 else 1 }.forEach { drawInto(cs, it) }
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

    /** Emit one object as PDF operators, in display coordinates. */
    internal fun drawInto(cs: PDPageContentStream, s: Stroke) {
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
            Stroke.Kind.IMAGE -> drawEmbeddedImage(cs, s)
            Stroke.Kind.RECT, Stroke.Kind.TABLE -> {
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
            Stroke.Kind.TEXT -> drawText(cs, s)
        }
    }

    /**
     * Write a text box into the page.
     *
     * Two things make this less obvious than it looks. The content stream is already under the
     * transform that maps display space - y downwards, the way ink is stored - into PDF user
     * space, where y runs up; text drawn under that would come out mirrored, so the text matrix
     * flips it back. And line breaking uses [Stroke.wrapLines] with the widths of the font
     * actually being written, which is what keeps an exported paragraph breaking where the screen
     * broke it.
     */
    private fun drawText(cs: PDPageContentStream, s: Stroke) {
        val p = s.points.firstOrNull() ?: return
        val body = s.text?.takeIf { it.isNotEmpty() } ?: return
        val font = fontFor(s)
        val size = max(1f, s.textSize)

        fun widthOf(line: String): Float =
            runCatching { font.getStringWidth(encodable(line)) / 1000f * size }.getOrDefault(0f)

        val lines = s.wrapLines(::widthOf)
        val contentWidth =
            if (s.boxWidth > 0f) s.boxWidth - s.padding * 2f
            else max(8f, lines.maxOfOrNull(::widthOf) ?: 0f)
        val boxW = if (s.boxWidth > 0f) s.boxWidth else contentWidth + s.padding * 2f
        val boxH =
            if (s.boxHeight > 0f) s.boxHeight
            else max(1, lines.size) * size * s.lineSpacing + s.padding * 2f

        if (s.boxFillColor != 0) {
            val f = s.boxFillColor
            cs.setNonStrokingColor(
                ((f shr 16) and 0xFF) / 255f, ((f shr 8) and 0xFF) / 255f, (f and 0xFF) / 255f
            )
            cs.addRect(p.x, p.y, boxW, boxH)
            cs.fill()
            // The fill just changed the colour; put the text's own back before writing it.
            val r = ((s.color shr 16) and 0xFF) / 255f
            val g = ((s.color shr 8) and 0xFF) / 255f
            val b = (s.color and 0xFF) / 255f
            cs.setNonStrokingColor(r, g, b)
        }
        if (s.boxBorder) {
            cs.setLineWidth(max(0.3f, s.baseWidth))
            cs.addRect(p.x, p.y, boxW, boxH)
            cs.stroke()
        }

        var y = p.y + s.padding
        for (line in lines) {
            if (line.isNotEmpty()) {
                val x = p.x + s.padding + s.lineOffsetX(widthOf(line), contentWidth)
                cs.beginText()
                cs.setFont(font, size)
                // The counter-flip. Its translation is the baseline, which sits one font size
                // below the top of the line in the y-downwards space the box is measured in.
                cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, x, y + size))
                cs.showText(encodable(line))
                cs.endText()
            }
            y += size * s.lineSpacing
        }
    }

    /** The standard-14 face closest to what the document asked for. */
    private fun fontFor(s: Stroke): PDType1Font {
        val name = when (s.font) {
            TextFont.SERIF -> when {
                s.bold && s.italic -> Standard14Fonts.FontName.TIMES_BOLD_ITALIC
                s.bold -> Standard14Fonts.FontName.TIMES_BOLD
                s.italic -> Standard14Fonts.FontName.TIMES_ITALIC
                else -> Standard14Fonts.FontName.TIMES_ROMAN
            }
            TextFont.MONO -> when {
                s.bold && s.italic -> Standard14Fonts.FontName.COURIER_BOLD_OBLIQUE
                s.bold -> Standard14Fonts.FontName.COURIER_BOLD
                s.italic -> Standard14Fonts.FontName.COURIER_OBLIQUE
                else -> Standard14Fonts.FontName.COURIER
            }
            // No script face exists among the standard 14, so the casual font is written as an
            // oblique. Embedding one would carry a font file into every exported document for a
            // difference nobody asked for.
            TextFont.CASUAL -> if (s.bold) Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE
            else Standard14Fonts.FontName.HELVETICA_OBLIQUE
            TextFont.SANS -> when {
                s.bold && s.italic -> Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE
                s.bold -> Standard14Fonts.FontName.HELVETICA_BOLD
                s.italic -> Standard14Fonts.FontName.HELVETICA_OBLIQUE
                else -> Standard14Fonts.FontName.HELVETICA
            }
        }
        return PDType1Font(name)
    }

    /**
     * Supplies pictures for embedded images, set by the caller before exporting.
     *
     * A resolver rather than a store, because the exporter has no business knowing where a
     * document keeps its assets - and a copy being written somewhere else still wants the
     * originals from where they actually are.
     */
    var imageResolver: ((String) -> java.awt.image.BufferedImage?)? = null

    /**
     * The document being written, so an image can be attached to it.
     *
     * Held for the duration of one export rather than threaded through every draw call, because
     * only this one object kind needs it and the drawing interface is otherwise about geometry.
     */
    internal var exportingInto: PDDocument? = null

    /**
     * Draw a pasted picture into the page.
     *
     * A cropped image is trimmed before it is embedded rather than clipped afterwards, so the
     * discarded edges are not carried in the exported file at all - which on a photograph cropped
     * down to one diagram is most of its weight.
     */
    private fun drawEmbeddedImage(cs: PDPageContentStream, s: Stroke) {
        val id = s.imageId ?: return
        val document = exportingInto ?: return
        val source = imageResolver?.invoke(id) ?: return
        val r = s.rectBox()
        if (r.isEmpty) return

        val crop = s.cropPixels(source.width, source.height)
        val picture = if (crop == null) source else runCatching {
            source.getSubimage(crop[0], crop[1], crop[2] - crop[0], crop[3] - crop[1])
        }.getOrDefault(source)

        runCatching {
            val image =
                org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(
                    document, picture
                )
            cs.saveGraphicsState()
            // The page transform has y running down; an image is drawn from its bottom-left, so
            // this flips back for the duration of the draw.
            cs.transform(Matrix(1f, 0f, 0f, -1f, 0f, r.top + r.bottom))
            cs.drawImage(image, r.left, r.top, r.width, r.height)
            cs.restoreGraphicsState()
        }
    }

    /**
     * The standard-14 fonts encode WinAnsi and throw on anything else.
     *
     * A maths symbol dropped into a text box would otherwise abort the whole export with an
     * encoding error, losing the entire document's worth of annotation over one character. A
     * visible placeholder is the better failure: the export succeeds and the gap is obvious.
     */
    private fun encodable(text: String): String =
        text.map { if (it.code in 32..255 || it == '’') it else '?' }.joinToString("")
}
