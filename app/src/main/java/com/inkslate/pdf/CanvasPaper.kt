package com.inkslate.pdf

import com.inkslate.core.InkCanvas
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream

/**
 * Enlarges a document's page to cover a canvas that has outgrown it.
 *
 * While drawing, growth costs nothing: the extra room is a rectangle in memory and the drawing
 * surface paints the paper outside the page itself. This is where that becomes real - once, when
 * the document is written.
 *
 * ## Nothing already on the page moves
 *
 * The page is not rebuilt and its content stream is not touched. Only the media box changes, and
 * it changes by moving its corners outwards in the page's own coordinates, so every mark, image
 * and glyph already on it keeps the user-space position it always had. The new paper is
 * *prepended*, so it lands underneath rather than over the top - a page turned into a canvas
 * keeps whatever was printed on it.
 */
object CanvasPaper {

    /**
     * Materialise one bounded view of an otherwise unbounded whiteboard for export only.
     * The editable document keeps world coordinates and no permanent page size; this temporary
     * PDF is merely the envelope another PDF reader needs.
     */
    fun writeSnapshot(target: File, canvas: InkCanvas) {
        PDDocument().use { pdf ->
            val width = canvas.width.coerceAtLeast(72f)
            val height = canvas.height.coerceAtLeast(72f)
            val page = com.tom_roush.pdfbox.pdmodel.PDPage(PDRectangle(width, height))
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { cs ->
                cs.transform(Matrix(1f, 0f, 0f, -1f, 0f, height))
                val background = runCatching {
                    BlankDocumentFactory.Background.valueOf(canvas.background)
                }.getOrDefault(BlankDocumentFactory.Background.PLAIN)
                BlankDocumentFactory.paintBackground(
                    cs = cs,
                    background = background,
                    w = width,
                    h = height,
                    paperColor = canvas.paperColor,
                    lineColor = canvas.lineColor,
                    spacing = canvas.spacing,
                    // Keep ruling phase stable when the export window begins at a negative world
                    // coordinate; the content is shifted to local page coordinates below.
                    anchorX = canvas.paperLeft - canvas.left,
                    anchorY = canvas.paperTop - canvas.top
                )
                canvas.noteGrid?.let { grid ->
                    cs.setStrokingColor(
                        android.graphics.Color.red(canvas.lineColor),
                        android.graphics.Color.green(canvas.lineColor),
                        android.graphics.Color.blue(canvas.lineColor)
                    )
                    cs.setLineWidth(1.15f)
                    grid.segments(com.inkslate.core.Box(canvas.left, canvas.top, canvas.right, canvas.bottom))
                        .forEach { segment ->
                            cs.moveTo(segment.x0 - canvas.left, segment.y0 - canvas.top)
                            cs.lineTo(segment.x1 - canvas.left, segment.y1 - canvas.top)
                            cs.stroke()
                        }
                }
            }
            FileOutputStream(target).use { pdf.save(it) }
        }
    }

    /**
     * Grow page 0 of [pdf] to cover [canvas], and return the canvas with its paper recorded as
     * caught up.
     *
     * Returns [canvas] unchanged when the page already covers it, which is the usual case: a
     * document is only reshaped when somebody has actually written past its edge.
     */
    fun grow(pdf: PDDocument, canvas: InkCanvas): InkCanvas {
        if (!canvas.paperIsBehind || pdf.numberOfPages == 0) return canvas.withPaperMatched()
        val page = pdf.getPage(0)
        val box = page.mediaBox ?: PDRectangle.LETTER

        // The current box's top-left is the point the canvas calls (paperLeft, paperTop). Move
        // each corner by the difference between that and where the canvas now starts, remembering
        // that user space counts y upwards while the canvas counts it down.
        val llx = box.lowerLeftX + (canvas.left - canvas.paperLeft)
        val ury = box.upperRightY - (canvas.top - canvas.paperTop)
        val urx = llx + canvas.width
        val lly = ury - canvas.height

        val grown = PDRectangle(llx, lly, urx - llx, ury - lly)
        page.mediaBox = grown
        // A crop box left at the old size would crop the new room straight back off again.
        page.cropBox = grown

        runCatching {
            PDPageContentStream(
                pdf, page, PDPageContentStream.AppendMode.PREPEND, true, true
            ).use { cs ->
                // display space -> user space for the grown page: x shifts, y flips
                cs.transform(Matrix(1f, 0f, 0f, -1f, llx - canvas.left, ury + canvas.top))
                val background = runCatching {
                    BlankDocumentFactory.Background.valueOf(canvas.background)
                }.getOrDefault(BlankDocumentFactory.Background.PLAIN)
                BlankDocumentFactory.paintBackground(
                    cs = cs,
                    background = background,
                    w = canvas.width,
                    h = canvas.height,
                    paperColor = canvas.paperColor,
                    lineColor = canvas.lineColor,
                    spacing = canvas.spacing,
                    left = canvas.left,
                    top = canvas.top,
                    // Rule from where the page's own lines start, so the join is invisible.
                    anchorX = canvas.paperLeft,
                    anchorY = canvas.paperTop
                )
            }
        }

        return canvas.withPaperMatched()
    }
}
