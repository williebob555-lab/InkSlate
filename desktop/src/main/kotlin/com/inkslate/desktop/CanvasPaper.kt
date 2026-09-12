package com.inkslate.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import com.inkslate.core.InkCanvas
import com.inkslate.core.PaperPattern
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.util.Matrix

/**
 * Enlarging a document's page to cover a canvas that has outgrown it, and painting the paper
 * outside the page while it has not been enlarged yet.
 *
 * While drawing, growth costs nothing: the extra room is a rectangle in memory and this paints
 * the paper beyond the page's own raster. Writing it into the file happens once, on save.
 */
object CanvasPaper {

    /**
     * Grow page 0 of [pdf] to cover [canvas], and return the canvas with its paper recorded as
     * caught up.
     *
     * ## Nothing already on the page moves
     *
     * The page is not rebuilt and its content stream is not touched. Only the media box changes,
     * and it changes by moving its corners outwards in the page's own coordinates, so every mark,
     * image and glyph already on it keeps the user-space position it always had. The new paper is
     * *prepended*, so it lands underneath rather than over the top - a page turned into a canvas
     * keeps whatever was printed on it.
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

    /**
     * Paint the part of the canvas the document's own page does not cover yet.
     *
     * Drawn in canvas coordinates, ruled from where the page's own lines start so the seam
     * between printed paper and painted paper is invisible - which is the whole illusion. The
     * page's raster covers the middle, so only what lies outside it is painted here.
     */
    fun DrawScope.drawCanvasPaper(
        canvas: InkCanvas,
        scale: Float,
        /** What of the canvas is on screen, in canvas coordinates. Null rules all of it. */
        visible: com.inkslate.core.Box? = null
    ) {
        val paper = canvas.paperColor.toComposeColor()
        val ruling = canvas.lineColor.toComposeColor()

        val sink = object : PaperPattern.Sink {
            private var width = 1f
            override fun paper(left: Float, top: Float, right: Float, bottom: Float) {
                drawRect(
                    paper,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top)
                )
            }

            override fun lineWidth(w: Float) {
                width = w
            }

            override fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
                drawLine(ruling, Offset(x0, y0), Offset(x1, y1), strokeWidth = width)
            }

            override fun dot(x: Float, y: Float, r: Float) {
                drawCircle(ruling, r, Offset(x, y))
            }
        }

        // Only the part on screen is ruled. Ruling the whole canvas meant thousands of lines -
        // or, on a dotted pattern, their product in circles - submitted on every frame whatever
        // was being looked at, and each one grows in real pixels as the view zooms in, so the
        // further in you went the more each invisible line cost to throw away. The ticks are laid
        // out from the page's own corner rather than from the region, so ruling a part of the
        // canvas puts the lines exactly where ruling all of it would have.
        val left = maxOf(canvas.left, visible?.left ?: canvas.left)
        val top = maxOf(canvas.top, visible?.top ?: canvas.top)
        val right = minOf(canvas.right, visible?.right ?: canvas.right)
        val bottom = minOf(canvas.bottom, visible?.bottom ?: canvas.bottom)
        if (right > left && bottom > top) {
            PaperPattern.emit(
                background = PaperPattern.patternOf(canvas.background),
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                spacing = canvas.spacing,
                anchorX = canvas.paperLeft,
                anchorY = canvas.paperTop,
                sink = sink
            )
        }

        // A hairline where the document's own page sits, so it is obvious which part of the
        // canvas will still be there for someone opening the file in anything else.
        drawRect(
            color = Color(0x33FFFFFF),
            topLeft = Offset(canvas.paperLeft, canvas.paperTop),
            size = Size(canvas.paperWidth, canvas.paperHeight),
            style = DrawStroke(
                width = 1f / scale,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(8f / scale, 6f / scale), 0f
                )
            )
        )
    }
}
