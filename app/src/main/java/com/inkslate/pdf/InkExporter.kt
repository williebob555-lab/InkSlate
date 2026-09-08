package com.inkslate.pdf

import com.inkslate.core.Stroke.Kind as StrokeKind
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.inkslate.data.InkDocument
import com.inkslate.data.InkFormat
import com.inkslate.ink.*
import com.inkslate.ink.BrushType
import com.inkslate.ink.DashStyle
import com.inkslate.ink.FillStyle
import com.inkslate.ink.Stroke
import com.inkslate.ink.TextFont
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Writes annotated PDFs and images.
 *
 * The `.inkdoc` sidecar is what keeps strokes editable across devices; this exporter produces the
 * artifact you actually hand in. It supports two shapes of output:
 *
 *  - [InkFormat.ANNOTATIONS] - each object becomes a real PDF annotation carrying an appearance
 *    stream. Still selectable and deletable in Acrobat, and because the appearance stream is
 *    always written, it renders identically in viewers that will not synthesise one (Chrome's
 *    PDFium among them).
 *  - [InkFormat.FLATTENED] - drawn straight into the page content. Permanent, and the safest
 *    thing to submit through a portal that strips annotations.
 *
 * ## Coordinates
 *
 * Ink is stored in display space: origin top-left, y increasing downward, matching what the user
 * saw. PDF user space is origin bottom-left, y increasing upward, and a page may additionally
 * carry a `/Rotate`. Rather than converting every point, [displayToUser] builds one `cm` matrix
 * per page and every drawing operator below is emitted in display coordinates.
 */
object InkExporter {

    private var initialised = false

    /**
     * Where the drawing surface's coordinate origin sits, when it is not the page's top-left.
     *
     * A canvas that has grown leftwards or upwards has ink at negative coordinates, because ink
     * coordinates are never rewritten - see [com.inkslate.core.InkCanvas]. The page underneath
     * still starts at its own top-left, so every mark is shifted by this on the way out. Null for
     * an ordinary document, which is nearly all of them.
     */
    var displayOrigin: Pair<Float, Float>? = null

    /** PdfBox-Android needs its resource loader primed before any document is touched. */
    fun init(context: Context) {
        if (initialised) return
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
        initialised = true
    }

    // ---- public API ----------------------------------------------------------

    /**
     * Write [doc]'s ink into [source], producing [target]. Source and target may be the same
     * path; the caller is responsible for having taken a backup first.
     *
     * [embed] is the editable copy of the handwriting, to be carried inside the result. It is
     * attached here rather than by a second pass over the finished file because the two used to
     * be separate: the exporter loaded the document, wrote it out, and then the embedder loaded
     * that result, wrote it out again, and loaded it a third time to check its work. On a
     * marked-up assignment - where the exported PDF is several megabytes of annotation - that
     * was three full parses and two full writes to produce one file.
     */
    /**
     * [rebuild] names the pages whose ink actually changed; null means all of them.
     *
     * A page nobody drew on this session already carries the right annotation from the last
     * save, sitting in [source] as bytes PdfBox will copy straight through. Taking it out and
     * building an identical one costs the geometry, the float formatting and the deflate of
     * every stroke on it, to arrive back where it started. On a notebook of a few thousand
     * marks that is nearly the whole of a save, and it is work that scales with how much has
     * ever been written rather than with what just changed.
     *
     * This only holds when [source] is our own last output. The caller establishes that; when
     * it cannot, it passes null and everything is rebuilt from the pristine document.
     *
     * [appendOnly] writes just the changed objects onto the end of [source] instead of
     * serialising the whole document again. PdfBox copies the original bytes through first -
     * measured at 8ms for 2.2MB against 277ms to write the same document out properly - so this
     * is a large win when few pages changed and a loss when most of them did, because the
     * superseded objects stay in the file. The caller decides.
     */
    fun exportPdf(
        source: File,
        target: File,
        doc: InkDocument,
        format: InkFormat,
        embed: ByteArray? = null,
        rebuild: Set<Int>? = null,
        appendOnly: Boolean = false
    ): Result<Unit> =
        runCatching {
            // Read from where it lies, not into memory.
            //
            // This used to slurp the whole file first, so that writing back over the source was
            // safe. On an eighty-six-megabyte textbook that is an eighty-six-megabyte array on a
            // heap of five hundred, next to everything the renderer is already holding, and it
            // ended in `OutOfMemoryError: Failed to allocate a 24 byte allocation`. It was never
            // needed either: the replacement is built in a temporary file and only renamed over
            // the original once it is complete, so nothing reads and writes the same bytes.
            val inPlace = appendOnly && source.absolutePath == target.absolutePath
            val startedAt = System.currentTimeMillis()
            // An appended save reuses the copy parsed when the document was opened, and rewinds
            // the file to match it. Everything else parses afresh and is closed straight after.
            val session = if (inPlace) PdfEditSession.of(source) else null
            session?.rewind(target)
            val loaded = session?.document ?: PDDocument.load(source)
            val loadedAt = System.currentTimeMillis()
            var builtAt = loadedAt
            var pageCount = 0
            try {
                val pdf = loaded
                pageCount = pdf.numberOfPages
                for (pageIndex in 0 until pdf.numberOfPages) {
                    if (rebuild != null && pageIndex !in rebuild) continue
                    val page = pdf.getPage(pageIndex)
                    val strokes = doc.strokesOn(pageIndex)

                    // Clear our previous annotations from every page being rebuilt, not only the
                    // ones that end up with strokes on them. Overwriting the same file twice
                    // used to leave two copies of the ink stacked on top of each other, and
                    // erasing something never removed the copy already written into the file.
                    if (format == InkFormat.ANNOTATIONS) {
                        (page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray)
                            ?.let { removeOurAnnotations(it) }
                    }
                    if (appendOnly) page.cosObject.setNeedToBeUpdated(true)

                    if (strokes.isEmpty()) continue
                    when (format) {
                        InkFormat.FLATTENED -> flattenOntoPage(pdf, page, strokes)
                        // One annotation per page rather than per stroke. Measured on the
                        // device: writing 865 separate appearance streams took 2525ms and
                        // produced a 3438KB file; writing three took 281ms and produced 2249KB.
                        // The cost is per stream, not per byte, and it buys only the ability to
                        // select one mark at a time in Acrobat.
                        InkFormat.ANNOTATIONS -> annotatePageGrouped(pdf, page, strokes)
                    }
                    if (appendOnly) {
                        (page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray)
                            ?.setNeedToBeUpdated(true)
                    }
                }
                embed?.let { com.inkslate.data.InkEmbedder.attachPayload(pdf, it) }
                builtAt = System.currentTimeMillis()

                if (appendOnly) {
                    // An incremental write only emits what is marked, and the payload hangs off
                    // the catalog's name tree. Leaving the catalog unmarked writes a file whose
                    // handwriting is the previous save's.
                    pdf.documentCatalog.cosObject.setNeedToBeUpdated(true)
                    pdf.document.trailer.setNeedToBeUpdated(true)
                    if (inPlace) appendUpdate(target) { pdf.saveIncremental(it) }
                    // Appending to something other than the file it came from still has to
                    // produce a whole document, so the original is copied through as usual.
                    else writeAtomically(target) { pdf.saveIncremental(it) }
                } else {
                    writeAtomically(target) { pdf.save(it) }
                    // The file is a different document now; anything held about it is stale.
                    PdfEditSession.release(target)
                }
                session?.noteWritten(target)
                com.inkslate.data.EventLog.info(
                    "export",
                    "${target.name}: $pageCount page(s), " +
                        "load ${loadedAt - startedAt}ms, " +
                        "build ${builtAt - loadedAt}ms, " +
                        "write ${System.currentTimeMillis() - builtAt}ms" +
                        (if (inPlace) " (appended in place)" else "")
                )
            } finally {
                // The session keeps its document open on purpose; anything else is done with it.
                if (session == null) runCatching { loaded.close() }
            }
        }

    /**
     * Burn ink into an image and write it back out. Images have no annotation layer, so this is
     * always flattening; the sidecar remains the editable copy.
     */
    fun exportImage(source: File, target: File, doc: InkDocument, quality: Int = 92): Result<Unit> =
        runCatching {
            val src = ImagePageSource(source)
            val dim = src.pageDim(0)
            val base = src.renderPage(0, dim.width.toInt().coerceIn(120, 8192))
                ?: error("Could not decode ${source.name}")

            val out = base.copy(Bitmap.Config.ARGB_8888, true).also {
                if (it !== base) base.recycle()
            }
            val canvas = Canvas(out)
            // ink is stored against the page dimensions, so scale to the raster we decoded
            val scale = out.width / dim.width
            canvas.scale(scale, scale)
            com.inkslate.ink.StrokeRasteriser.drawAll(canvas, doc.strokesOn(0))

            val png = target.extension.lowercase() in setOf("png", "webp")
            writeAtomically(target) { os ->
                if (png) out.compress(Bitmap.CompressFormat.PNG, 100, os)
                else out.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), os)
            }
            out.recycle()
        }

    /** Turn an annotated image into a one-page PDF, sized to the image. */
    fun exportImageAsPdf(source: File, target: File, doc: InkDocument): Result<Unit> =
        runCatching {
            val src = ImagePageSource(source)
            val dim = src.pageDim(0)
            val base = src.renderPage(0, min(dim.width.toInt(), 2400).coerceAtLeast(120))
                ?: error("Could not decode ${source.name}")

            PDDocument().use { pdf ->
                // fit the image onto a page of the same aspect, capped to something sane
                val pageW = min(dim.width, 1224f)
                val pageH = pageW * (dim.height / max(1f, dim.width))
                val page = PDPage(PDRectangle(pageW, pageH))
                pdf.addPage(page)

                val img = if (base.hasAlpha()) LosslessFactory.createFromImage(pdf, base)
                else JPEGFactory.createFromImage(pdf, base, 0.92f)

                PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.APPEND, true, true)
                    .use { cs ->
                        cs.drawImage(img, 0f, 0f, pageW, pageH)
                        cs.transform(Matrix(1f, 0f, 0f, -1f, 0f, pageH))
                        // ink was captured against image pixels; rescale to page points
                        val s = pageW / dim.width
                        cs.transform(Matrix(s, 0f, 0f, s, 0f, 0f))
                        doc.strokesOn(0).forEach { drawStroke(pdf, cs, it) }
                    }
                writeAtomically(target) { pdf.save(it) }
            }
            base.recycle()
        }

    /**
     * Export only pages [from]..[to] into a new document.
     *
     * Pages are removed from a loaded copy rather than copied into a fresh one, which preserves
     * the original page objects intact - fonts, embedded resources and all - instead of trying to
     * reconstruct them.
     */
    fun exportPdfRange(
        source: File,
        target: File,
        doc: InkDocument,
        format: InkFormat,
        from: Int,
        to: Int
    ): Result<Unit> = exportPdfPages(source, target, doc, format, (from..to).toList())

    /**
     * Export exactly [pages] into a new document, in document order.
     *
     * Generalises the range export, because a selection is not always contiguous: the three pages
     * of a worksheet you actually did are as likely to be 2, 5 and 6 as they are to be 2 to 4.
     * Pages are removed from a loaded copy rather than copied into a fresh one, which preserves
     * the original page objects intact - fonts, embedded resources and all - instead of trying to
     * reconstruct them.
     */
    fun exportPdfPages(
        source: File,
        target: File,
        doc: InkDocument,
        format: InkFormat,
        pages: List<Int>
    ): Result<Unit> = runCatching {
        val bytes = source.readBytes()
        PDDocument.load(bytes).use { pdf ->
            val keep = pages.filter { it in 0 until pdf.numberOfPages }.distinct().sorted()
            require(keep.isNotEmpty()) { "No pages were selected" }

            for (pageIndex in keep) {
                val strokes = doc.strokesOn(pageIndex)
                if (strokes.isEmpty()) continue
                val page = pdf.getPage(pageIndex)
                when (format) {
                    InkFormat.FLATTENED -> flattenOntoPage(pdf, page, strokes)
                    InkFormat.ANNOTATIONS -> annotatePage(pdf, page, strokes)
                }
            }

            // drop the unwanted pages from the end first, so earlier indices stay valid
            val wanted = keep.toSet()
            for (i in pdf.numberOfPages - 1 downTo 0) {
                if (i !in wanted) pdf.removePage(i)
            }
            writeAtomically(target) { pdf.save(it) }
        }
    }

    // ---- page-level writing --------------------------------------------------

    internal fun flattenOntoPage(pdf: PDDocument, page: PDPage, strokes: List<Stroke>) {
        PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.saveGraphicsState()
            cs.transform(toUserFor(page))
            strokes.sortedBy { if (it.isHighlighter) 0 else 1 }.forEach { drawStroke(pdf, cs, it) }
            cs.restoreGraphicsState()
        }
    }

    internal fun annotatePage(pdf: PDDocument, page: PDPage, strokes: List<Stroke>) {
        val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray
            ?: COSArray().also { page.cosObject.setItem(COSName.ANNOTS, it) }

        removeOurAnnotations(annots)

        val toUser = toUserFor(page)
        for (s in strokes.sortedBy { if (it.isHighlighter) 0 else 1 }) {
            val dict = buildAnnotation(pdf, page, s, toUser) ?: continue
            annots.add(dict)
        }
    }

    /**
     * Every stroke on a page as a single annotation, rather than one apiece.
     *
     * The per-object form is nicer in Acrobat - each mark can be selected and deleted on its own
     * - and it costs a separate form XObject, resource dictionary and compressed content stream
     * for every stroke on the page. Whether that price is worth paying is a question about how
     * long a save takes on the actual device, which is what [SaveBenchmark] is for.
     */
    internal fun annotatePageGrouped(pdf: PDDocument, page: PDPage, strokes: List<Stroke>) {
        val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray
            ?: COSArray().also { page.cosObject.setItem(COSName.ANNOTS, it) }
        removeOurAnnotations(annots)
        if (strokes.isEmpty()) return

        val box = page.mediaBox
        val toUser = toUserFor(page)
        val res = com.tom_roush.pdfbox.pdmodel.PDResources()
        val ap = PDAppearanceStream(pdf).apply {
            bBox = box
            resources = res
        }
        // Written here when every mark on the page is one this app can emit itself, which on a
        // page of handwriting is all of them. See [InkOps] for what that buys and why it is
        // all-or-nothing per page.
        if (InkOps.canEmitAll(strokes)) {
            val content = InkOps.emitPage(strokes, toUser, res)
            ap.cosObject.createOutputStream(COSName.FLATE_DECODE).use { it.write(content) }
        } else {
            PDPageContentStream(pdf, ap).use { cs ->
                cs.saveGraphicsState()
                cs.transform(toUser)
                strokes.sortedBy { if (it.isHighlighter) 0 else 1 }
                    .forEach { drawStroke(pdf, cs, it) }
                cs.restoreGraphicsState()
            }
        }
        annots.add(
            COSDictionary().apply {
                setItem(COSName.TYPE, COSName.getPDFName("Annot"))
                setItem(COSName.SUBTYPE, COSName.getPDFName("Stamp"))
                setItem(COSName.RECT, box.cosObject)
                setInt(COSName.F, 4)
                setItem(COSName.T, COSString(ANNOT_TAG))
                setItem(COSName.getPDFName(ANNOT_KEY), COSString(ANNOT_TAG))
                setItem(
                    COSName.AP,
                    PDAppearanceDictionary().apply { setNormalAppearance(ap) }.cosObject
                )
            }
        )
    }

    /**
     * One annotation per object. Freehand becomes a genuine `/Ink` annotation with an `/InkList`,
     * so other editors understand it as ink; everything else becomes a `/Stamp`, which is the
     * honest generic carrier for "this object has a custom appearance".
     */
    /**
     * Remove annotations this app wrote, leaving everyone else's alone.
     *
     * Matched on a private key first and the title second. Deleting an annotation a marker or a
     * classmate added would be far worse than leaving a stale one of ours behind, so the match
     * has to be something only this app writes.
     */
    internal fun removeOurAnnotations(annots: COSArray) {
        for (i in annots.size() - 1 downTo 0) {
            val dict = annots.getObject(i) as? COSDictionary ?: continue
            val mine = (dict.getDictionaryObject(COSName.getPDFName(ANNOT_KEY)) as? COSString)
                ?.string == ANNOT_TAG ||
                (dict.getDictionaryObject(COSName.T) as? COSString)?.string == ANNOT_TAG
            if (mine) annots.remove(i)
        }
    }

    private fun buildAnnotation(
        pdf: PDDocument,
        page: PDPage,
        s: Stroke,
        toUser: Matrix
    ): COSDictionary? {
        val b = s.bounds()
        if (b.isEmpty && s.kind != StrokeKind.TEXT) return null

        // pad for line width, then map the display-space box into user space
        val pad = max(2f, s.baseWidth)
        val corners = listOf(
            b.left - pad to b.top - pad, b.right + pad to b.top - pad,
            b.right + pad to b.bottom + pad, b.left - pad to b.bottom + pad
        ).map { (x, y) -> mapPoint(toUser, x, y) }

        val minX = corners.minOf { it[0] }; val maxX = corners.maxOf { it[0] }
        val minY = corners.minOf { it[1] }; val maxY = corners.maxOf { it[1] }
        val rect = PDRectangle(minX, minY, maxX - minX, maxY - minY)

        val ap = PDAppearanceStream(pdf).apply {
            bBox = rect
            resources = com.tom_roush.pdfbox.pdmodel.PDResources()
            // BBox is already in user space, so the form keeps its identity matrix
        }
        PDPageContentStream(pdf, ap).use { cs ->
            cs.saveGraphicsState()
            cs.transform(toUser)
            drawStroke(pdf, cs, s)
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
            setItem(COSName.C, colorArray(s.color))
            setItem(
                COSName.getPDFName("BS"),
                COSDictionary().apply {
                    setFloat(COSName.W, s.baseWidth)
                    setItem(COSName.S, COSName.getPDFName("S"))
                }
            )
            setItem(
                COSName.AP,
                PDAppearanceDictionary().apply { setNormalAppearance(ap) }.cosObject
            )
        }

        if (s.isFreehand) {
            // /InkList: one array of alternating x y per stroke path, in user space
            val inkList = COSArray()
            val path = COSArray()
            for (p in s.points) {
                val u = mapPoint(toUser, p.x, p.y)
                path.add(COSFloat(u[0])); path.add(COSFloat(u[1]))
            }
            inkList.add(path)
            dict.setItem(COSName.getPDFName("InkList"), inkList)
        }
        return dict
    }

    // ---- stroke drawing ------------------------------------------------------

    /** Emit one object as PDF operators, in display coordinates. */
    internal fun drawStroke(pdf: PDDocument, cs: PDPageContentStream, s: Stroke) {
        cs.saveGraphicsState()

        // transparency and blend mode
        //
        // A tinted fill is a wash, and it has to be one here too. The renderer paints it at a
        // quarter opacity behind a full-strength outline; this used to export it solid, so a
        // shaded box on screen came out of the exporter as a filled-in one - which for anything
        // shaded to mean something, a fraction bar or a highlighted cell, inverts what it says.
        // Only the fill is affected: a closed shape's non-stroking colour is not used for
        // anything else, and text and tables are not closed shapes.
        val alpha = s.effectiveAlpha.coerceIn(0f, 1f)
        val tinted = s.isClosedShape && s.fill == FillStyle.TINTED
        val gs = PDExtendedGraphicsState().apply {
            strokingAlphaConstant = alpha
            nonStrokingAlphaConstant = if (tinted) alpha * TINT_ALPHA else alpha
            if (s.usesMultiply) blendMode = BlendMode.MULTIPLY
        }
        cs.setGraphicsStateParameters(gs)

        s.rotationMatrix()?.let { m ->
            val c = s.rawBounds()
            val rad = Math.toRadians(s.rotation.toDouble())
            // rotate about the object centre, in display space
            cs.transform(Matrix(1f, 0f, 0f, 1f, c.centerX(), c.centerY()))
            cs.transform(
                Matrix(
                    cos(rad).toFloat(), sin(rad).toFloat(),
                    (-sin(rad)).toFloat(), cos(rad).toFloat(), 0f, 0f
                )
            )
            cs.transform(Matrix(1f, 0f, 0f, 1f, -c.centerX(), -c.centerY()))
        }

        setColor(cs, s)
        cs.setLineCapStyle(if (s.brush == BrushType.MARKER || s.isHighlighter) 2 else 1)
        cs.setLineJoinStyle(1)
        s.dash.pattern?.let { cs.setLineDashPattern(it, 0f) }

        when (s.kind) {
            StrokeKind.FREEHAND -> drawFreehand(cs, s)
            StrokeKind.LINE, StrokeKind.ARROW -> drawLine(cs, s)
            StrokeKind.RECT -> drawRect(cs, s)
            StrokeKind.ELLIPSE -> drawEllipse(cs, s)
            StrokeKind.TABLE -> drawTable(pdf, cs, s)
            StrokeKind.TEXT -> drawText(pdf, cs, s)
            StrokeKind.IMAGE -> drawEmbeddedImage(pdf, cs, s)
        }

        cs.restoreGraphicsState()
    }

    private fun drawFreehand(cs: PDPageContentStream, s: Stroke) {
        if (s.points.isEmpty()) return
        if (s.points.size == 1) {
            val p = s.points[0]
            cs.setLineWidth(max(0.05f, p.width))
            cs.moveTo(p.x, p.y); cs.lineTo(p.x + 0.01f, p.y); cs.stroke()
            return
        }

        if (s.usesOutlineRender) {
            // The same polygons the screen fills, written straight out as path operators. PDF has
            // no variable-width stroke, so tapered ink has to be a filled shape either way - and
            // taking the geometry from the shared builder rather than re-tracing a rendered path
            // is what makes the exported letter identical to the drawn one. It is also what stops
            // closed letters like 0 and D coming back filled in solid: every contour here is
            // convex and wound the same way, so `f` can only ever union them.
            emitContours(cs, com.inkslate.core.StrokeOutline.contours(s))
            cs.fill()
            return
        }

        cs.setLineWidth(max(0.05f, s.baseWidth))
        cs.moveTo(s.points[0].x, s.points[0].y)
        for (i in 1 until s.points.size) {
            val prev = s.points[i - 1]; val cur = s.points[i]
            cs.curveTo1(prev.x, prev.y, (prev.x + cur.x) / 2f, (prev.y + cur.y) / 2f)
        }
        cs.lineTo(s.points.last().x, s.points.last().y)
        cs.stroke()
    }

    /**
     * Round to a hundredth of a point.
     *
     * Coordinates arrive with whatever precision the arithmetic produced, and every digit past
     * this is a character written into the content stream and then compressed - tens of
     * thousands of times over on a marked-up document. A hundredth of a point is a five-hundredth
     * of a millimetre: below what a printer can put on paper, let alone what an eye can find.
     */
    private fun snap(v: Float): Float = kotlin.math.round(v * 100f) / 100f

    /** Write closed polygons out as PDF path operators, ready for a single fill. */
    private fun emitContours(cs: PDPageContentStream, contours: List<FloatArray>) {
        for (poly in contours) {
            if (poly.size < 6) continue
            cs.moveTo(snap(poly[0]), snap(poly[1]))
            var i = 2
            while (i < poly.size) {
                cs.lineTo(snap(poly[i]), snap(poly[i + 1]))
                i += 2
            }
            cs.closePath()
        }
    }

    private fun drawLine(cs: PDPageContentStream, s: Stroke) {
        val a = s.points.first(); val b = s.points.last()
        cs.setLineWidth(max(0.05f, s.baseWidth))
        cs.moveTo(a.x, a.y); cs.lineTo(b.x, b.y); cs.stroke()
        if (s.kind == StrokeKind.ARROW) {
            val size = max(3f, s.baseWidth * 3.6f)
            val angle = Math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
            val spread = Math.toRadians(26.0)
            for (side in listOf(-spread, spread)) {
                val ang = angle + Math.PI + side
                cs.moveTo(b.x, b.y)
                cs.lineTo(b.x + (cos(ang) * size).toFloat(), b.y + (sin(ang) * size).toFloat())
                cs.stroke()
            }
        }
    }

    /**
     * Draw a pasted image.
     *
     * The content stream is flipped vertically for ink, so the image needs that flip undone
     * around its own rectangle - otherwise a captured diagram exports upside down.
     */
    private fun drawEmbeddedImage(pdf: PDDocument, cs: PDPageContentStream, s: Stroke) {
        val id = s.imageId ?: return
        val bmp = imageResolver?.invoke(id) ?: return
        val r = s.rectOf()
        runCatching {
            val img = if (bmp.hasAlpha()) LosslessFactory.createFromImage(pdf, bmp)
            else JPEGFactory.createFromImage(pdf, bmp, 0.92f)
            cs.saveGraphicsState()
            cs.transform(Matrix(1f, 0f, 0f, -1f, 0f, r.top + r.bottom))
            cs.drawImage(img, r.left, r.top, r.width(), r.height())
            cs.restoreGraphicsState()
        }
    }

    /** Supplies bitmaps for embedded images; set by the caller before exporting. */
    var imageResolver: ((String) -> android.graphics.Bitmap?)? = null

    private fun drawRect(cs: PDPageContentStream, s: Stroke) {
        val r = s.rectOf()
        cs.setLineWidth(max(0.05f, s.baseWidth))
        cs.addRect(r.left, r.top, r.width(), r.height())
        finishShape(cs, s)
    }

    private fun drawEllipse(cs: PDPageContentStream, s: Stroke) {
        val r = s.rectOf()
        val cx = r.centerX(); val cy = r.centerY()
        val rx = r.width() / 2f; val ry = r.height() / 2f
        val k = 0.5523f    // circle-to-bezier constant
        cs.setLineWidth(max(0.05f, s.baseWidth))
        cs.moveTo(cx - rx, cy)
        cs.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
        cs.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
        cs.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
        cs.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
        cs.closePath()
        finishShape(cs, s)
    }

    /** Matches the 64/255 the renderer uses for a tinted fill. */
    private const val TINT_ALPHA = 0.251f

    private fun finishShape(cs: PDPageContentStream, s: Stroke) {
        when (s.fill) {
            FillStyle.NONE -> cs.stroke()
            FillStyle.SOLID, FillStyle.TINTED -> cs.fillAndStroke()
        }
    }

    private fun drawTable(pdf: PDDocument, cs: PDPageContentStream, s: Stroke) {
        val r = s.rectOf()
        cs.setLineWidth(max(0.05f, s.baseWidth))
        if (s.fill != FillStyle.NONE) {
            cs.addRect(r.left, r.top, r.width(), r.height())
            cs.fill()
        }
        cs.addRect(r.left, r.top, r.width(), r.height())
        cs.stroke()
        if (s.cols > 1) {
            val cw = r.width() / s.cols
            for (c in 1 until s.cols) {
                cs.moveTo(r.left + cw * c, r.top); cs.lineTo(r.left + cw * c, r.bottom); cs.stroke()
            }
        }
        if (s.rows > 1) {
            val rh = r.height() / s.rows
            for (rr in 1 until s.rows) {
                cs.moveTo(r.left, r.top + rh * rr); cs.lineTo(r.right, r.top + rh * rr); cs.stroke()
            }
        }
        if (s.cells.isNotEmpty()) {
            val size = min(s.textSize, r.height() / max(1, s.rows) * 0.55f)
            for (row in 0 until s.rows) for (col in 0 until s.cols) {
                val txt = s.cells.getOrNull(row * s.cols + col).orEmpty()
                if (txt.isBlank()) continue
                val cell = s.cellRect(row, col)
                showText(cs, PDType1Font.HELVETICA, size, s.color,
                    cell.left + 3f, cell.centerY() + size * 0.34f, txt)
            }
        }
    }

    /**
     * Text boxes. Line breaking uses the PDF font's own metrics rather than the on-screen
     * typeface, so an exported box wraps at the same words the editor showed.
     */
    private fun drawText(pdf: PDDocument, cs: PDPageContentStream, s: Stroke) {
        val font = fontFor(s)
        val box = s.rawBounds()

        // box background, then border, then the text on top
        if (s.boxFillColor != Color.TRANSPARENT) {
            cs.setNonStrokingColor(
                Color.red(s.boxFillColor), Color.green(s.boxFillColor), Color.blue(s.boxFillColor)
            )
            cs.addRect(box.left, box.top, box.width(), box.height())
            cs.fill()
        }
        if (s.boxBorder) {
            cs.setStrokingColor(Color.red(s.color), Color.green(s.color), Color.blue(s.color))
            cs.setLineWidth(max(0.4f, s.baseWidth))
            cs.addRect(box.left, box.top, box.width(), box.height())
            cs.stroke()
        }

        fun measure(text: String): Float = runCatching {
            font.getStringWidth(sanitise(text)) / 1000f * s.textSize
        }.getOrDefault(text.length * s.textSize * 0.55f)

        val lines = s.wrapLines(::measure)
        val contentWidth = box.width() - s.padding * 2f
        val ascent = runCatching {
            font.fontDescriptor.ascent / 1000f * s.textSize
        }.getOrDefault(s.textSize * 0.75f)

        var y = box.top + s.padding + ascent
        for (line in lines) {
            val offset = s.lineOffsetX(measure(line), contentWidth)
            showText(cs, font, s.textSize, s.color, box.left + s.padding + offset, y, line)
            y += s.textSize * s.lineSpacing
            if (s.boxHeight > 0f && y > box.bottom) break
        }
    }

    /** Map the document's font choice onto one of the standard-14 PDF fonts. */
    private fun fontFor(s: Stroke): PDFont = when (s.font) {
        TextFont.SERIF -> when {
            s.bold && s.italic -> PDType1Font.TIMES_BOLD_ITALIC
            s.bold -> PDType1Font.TIMES_BOLD
            s.italic -> PDType1Font.TIMES_ITALIC
            else -> PDType1Font.TIMES_ROMAN
        }
        TextFont.MONO -> when {
            s.bold && s.italic -> PDType1Font.COURIER_BOLD_OBLIQUE
            s.bold -> PDType1Font.COURIER_BOLD
            s.italic -> PDType1Font.COURIER_OBLIQUE
            else -> PDType1Font.COURIER
        }
        else -> when {
            s.bold && s.italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
            s.bold -> PDType1Font.HELVETICA_BOLD
            s.italic -> PDType1Font.HELVETICA_OBLIQUE
            else -> PDType1Font.HELVETICA
        }
    }

    /**
     * Draw a run of text. The content stream is flipped (y grows downward), so the text matrix
     * gets an extra vertical flip or the glyphs come out mirrored.
     */
    private fun showText(
        cs: PDPageContentStream, font: PDFont, size: Float, color: Int,
        x: Float, y: Float, raw: String
    ) {
        val text = sanitise(raw)
        if (text.isEmpty()) return
        runCatching {
            cs.beginText()
            cs.setNonStrokingColor(Color.red(color), Color.green(color), Color.blue(color))
            cs.setFont(font, size)
            cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, x, y))
            cs.showText(text)
            cs.endText()
        }.onFailure { runCatching { cs.endText() } }
    }

    /** The standard-14 fonts are WinAnsi only, so drop anything they cannot encode. */
    private fun sanitise(s: String): String =
        s.map { if (it.code in 32..255) it else '?' }.joinToString("")

    private fun setColor(cs: PDPageContentStream, s: Stroke) {
        cs.setStrokingColor(Color.red(s.color), Color.green(s.color), Color.blue(s.color))
        val fc = if (s.fill == FillStyle.NONE) s.color else s.fillColor
        cs.setNonStrokingColor(Color.red(fc), Color.green(fc), Color.blue(fc))
    }

    private fun colorArray(color: Int) = COSArray().apply {
        add(COSFloat(Color.red(color) / 255f))
        add(COSFloat(Color.green(color) / 255f))
        add(COSFloat(Color.blue(color) / 255f))
    }

    // ---- coordinate systems --------------------------------------------------

    /**
     * Matrix mapping display coordinates (top-left origin, y down, page rotation applied) into
     * PDF user space. Derived per page from the crop box and `/Rotate`.
     */
    /**
     * Display-to-user for this page, with the canvas origin folded in.
     *
     * Every path here goes through this rather than [displayToUser] directly, so there is one
     * place that knows ink might not start at zero.
     */
    internal fun toUserFor(page: PDPage): Matrix {
        val base = displayToUser(page)
        val origin = displayOrigin ?: return base
        if (origin.first == 0f && origin.second == 0f) return base
        // Points are shifted first and mapped second, which is what multiplying in this order
        // gives with PdfBox's row-vector convention.
        return Matrix.getTranslateInstance(-origin.first, -origin.second).multiply(base)
    }

    private fun displayToUser(page: PDPage): Matrix {
        val box = page.cropBox ?: page.mediaBox ?: PDRectangle.LETTER
        val llx = box.lowerLeftX; val lly = box.lowerLeftY
        val urx = box.upperRightX; val ury = box.upperRightY
        return when (((page.rotation % 360) + 360) % 360) {
            90 -> Matrix(0f, 1f, 1f, 0f, llx, lly)
            180 -> Matrix(-1f, 0f, 0f, 1f, urx, lly)
            270 -> Matrix(0f, -1f, -1f, 0f, urx, ury)
            else -> Matrix(1f, 0f, 0f, -1f, llx, ury)
        }
    }

    private fun mapPoint(m: Matrix, x: Float, y: Float): FloatArray {
        val a = m.createAffineTransform()
        val src = floatArrayOf(x, y)
        val dst = FloatArray(2)
        a.transform(src, 0, dst, 0, 1)
        return dst
    }

    /** Page size in display space, matching what [PageSource] reports. */
    fun displayDim(page: PDPage): PageDim {
        val box = page.cropBox ?: page.mediaBox ?: PDRectangle.LETTER
        val rot = ((page.rotation % 360) + 360) % 360
        return if (rot == 90 || rot == 270) PageDim(box.height, box.width)
        else PageDim(box.width, box.height)
    }

    // ---- safe writing --------------------------------------------------------

    /**
     * Write via a temp file and then rename.
     *
     * Overwriting a PDF in place is the one operation in this app that can destroy work that was
     * never in the app to begin with, so a crash mid-write must never leave a truncated file
     * where the assignment used to be.
     */
    /**
     * Put an incremental update on the end of [target], leaving the bytes already there alone.
     *
     * This is what a PDF incremental update is for, and it is the only way a fifty-megabyte
     * textbook can take one annotation without being rebuilt. The objects that changed are
     * appended, followed by a cross-reference section pointing at them and a trailer whose
     * `/Prev` chains back to the old one. Readers use the last trailer they find, so the result
     * is the document plus the change, and the work is proportional to the change.
     *
     * PdfBox will write the whole original out ahead of the update - its incremental writer
     * expects to be producing a new file - so those bytes are counted and dropped rather than
     * written, and what follows lands exactly where the offsets it computed say it should.
     *
     * A failed append leaves trailing bytes after a perfectly good trailer, which readers would
     * ignore but this app should not: the file is truncated back to the length it was, which is
     * a complete undo because nothing before that point was touched.
     */
    private inline fun appendUpdate(target: File, write: (java.io.OutputStream) -> Unit) {
        val originalLength = target.length()
        val handle = java.io.RandomAccessFile(target, "rw")
        try {
            handle.seek(originalLength)
            val out = object : java.io.OutputStream() {
                private var toDrop = originalLength

                override fun write(b: Int) {
                    if (toDrop > 0) { toDrop--; return }
                    handle.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (toDrop >= len) { toDrop -= len; return }
                    val from = off + toDrop.toInt()
                    val count = len - toDrop.toInt()
                    toDrop = 0
                    handle.write(b, from, count)
                }
            }
            write(out)
            out.flush()
            if (handle.filePointer <= originalLength) {
                error("the incremental update wrote nothing")
            }
        } catch (t: Throwable) {
            runCatching { handle.setLength(originalLength) }
            throw t
        } finally {
            runCatching { handle.fd.sync() }
            handle.close()
        }
    }

    private inline fun writeAtomically(target: File, write: (java.io.OutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        // A leading dot keeps a half-written file out of the way, but some storage providers
        // simply refuse to create one - which turned an ordinary save into
        // "ENOENT: .Weekly Set 1.pdf.tmp". A plain name is tried next rather than losing the save.
        val tmp = sequenceOf(
            File(target.parentFile, ".${target.name}.tmp"),
            File(target.parentFile, "${target.name}.inkslate-tmp")
        ).firstOrNull { candidate ->
            runCatching { FileOutputStream(candidate).close(); true }.getOrDefault(false)
        } ?: File(target.parentFile, "${target.name}.inkslate-tmp")
        try {
            FileOutputStream(tmp).use { os ->
                write(os)
                os.flush()
                // Best-effort durability. On external storage, which is FUSE-backed on modern
                // Android, fsync frequently throws SyncFailedException even though every byte was
                // written correctly. Letting that propagate turned a successful save into a
                // "sync failed" error and threw the finished file away.
                runCatching { os.fd.sync() }
            }
            if (target.exists() && !target.delete()) {
                // fall back to copying bytes when the filesystem refuses a delete
                tmp.inputStream().use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                }
                tmp.delete()
                return
            }
            if (!tmp.renameTo(target)) {
                tmp.inputStream().use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                }
                tmp.delete()
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun abs(f: Float) = kotlin.math.abs(f)

    /** Identifies annotations written by this app, so a rewrite replaces rather than duplicates. */
    private const val ANNOT_TAG = "InkSlate"
    private const val ANNOT_KEY = "InkSlateObject"
}
