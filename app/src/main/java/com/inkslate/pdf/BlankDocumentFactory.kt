package com.inkslate.pdf

import android.graphics.Color
import com.inkslate.data.EventLog
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.io.FileOutputStream

/**
 * Creates blank documents to draw on: notebook pages, graph paper, whiteboards.
 *
 * These are written as real PDFs rather than an app-private format on purpose. A whiteboard you
 * cannot hand to anyone is much less useful, and it means blank pages travel through the same
 * sync, export and annotation paths as an assignment you were given.
 */
object BlankDocumentFactory {

    /** Page background pattern. Drawn into the PDF, so it survives export and sharing. */
    enum class Background(val label: String) {
        PLAIN("Plain"),
        RULED("Ruled"),
        GRID("Grid"),
        DOTS("Dot grid"),
        GRAPH("Fine graph"),
        CORNELL("Cornell notes"),
        MUSIC("Music staves"),
        ISOMETRIC("Isometric")
    }

    /** Page geometry, in PDF points (72 per inch). */
    enum class PageSize(val label: String, val width: Float, val height: Float) {
        LETTER("Letter", 612f, 792f),
        LETTER_LANDSCAPE("Letter, landscape", 792f, 612f),
        A4("A4", 595f, 842f),
        A4_LANDSCAPE("A4, landscape", 842f, 595f),
        LEGAL("Legal", 612f, 1008f),
        TABLOID("Tabloid", 792f, 1224f),
        SQUARE("Square", 792f, 792f),
        WHITEBOARD("Whiteboard", 2160f, 1215f),
        WHITEBOARD_XL("Whiteboard XL", 4320f, 2430f)
    }

    data class Spec(
        val name: String,
        val pageSize: PageSize = PageSize.LETTER,
        val background: Background = Background.PLAIN,
        val pageCount: Int = 1,
        val paperColor: Int = Color.WHITE,
        val lineColor: Int = Color.parseColor("#8FA8C8"),
        /** Spacing of the ruling in points; ignored by PLAIN. */
        val spacing: Float = 24f,
        /**
         * Make this a canvas that grows to fit what is drawn on it, rather than a fixed page.
         *
         * Only meaningful for a single page - a document that grows in every direction has
         * nowhere to put a second one.
         */
        val autoGrow: Boolean = false
    )

    /**
     * Write a new document into [dir]. Returns the created file.
     *
     * Refuses to clobber an existing file: a "new" action that silently replaces last week's
     * notes would be indefensible.
     */
    fun create(dir: File, spec: Spec): Result<File> = runCatching {
        require(dir.isDirectory || dir.mkdirs()) { "Cannot write to that folder" }
        val safe = spec.name.trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .ifEmpty { "Untitled" }

        var target = File(dir, "$safe.pdf")
        var n = 2
        while (target.exists()) {
            target = File(dir, "$safe ($n).pdf")
            n++
        }

        PDDocument().use { doc ->
            repeat(spec.pageCount.coerceIn(1, 200)) {
                val page = PDPage(PDRectangle(spec.pageSize.width, spec.pageSize.height))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    // flip into PDF user space: display y runs down, user space runs up
                    cs.transform(
                        com.tom_roush.pdfbox.util.Matrix(
                            1f, 0f, 0f, -1f, 0f, spec.pageSize.height
                        )
                    )
                    paintBackground(
                        cs, spec.background, spec.pageSize.width, spec.pageSize.height,
                        spec.paperColor, spec.lineColor, spec.spacing
                    )
                }
            }
            EventLog.info(
                "create",
                "${target.name}: ${spec.background.name} ${spec.pageSize.name} " +
                    "x${spec.pageCount}, spacing ${spec.spacing.toInt()}"
            )
            val tmp = File(dir, ".${target.name}.tmp")
            try {
                FileOutputStream(tmp).use { doc.save(it) }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true); tmp.delete()
                }
            } catch (t: Throwable) {
                tmp.delete(); throw t
            }
        }
        EventLog.info("create", "${target.name} written (${target.length() / 1024}KB)")
        target
    }.onFailure { EventLog.error("create", "Could not create document: ${it.message}") }

    // ---- background painting -------------------------------------------------

    /**
     * Paint one page's paper and ruling.
     *
     * The geometry comes from [PaperPattern], shared with the drawing surface so that a canvas
     * which has grown past its page keeps its lines in step with the ones printed on it. This end
     * only knows how to put a line into a content stream.
     *
     * Coordinates go through unchanged, in the same y-downwards space the ink is stored in. The
     * caller supplies the one transform that flips into PDF user space, so a canvas whose page
     * has a shifted origin needs no second set of formulae here.
     */
    fun paintBackground(
        cs: PDPageContentStream,
        background: Background,
        w: Float,
        h: Float,
        paperColor: Int = Color.WHITE,
        lineColor: Int = Color.parseColor("#8FA8C8"),
        spacing: Float = 24f,
        left: Float = 0f,
        top: Float = 0f,
        anchorX: Float = 0f,
        anchorY: Float = 0f
    ) {
        val sink = object : PaperPattern.PaperSink {
            override fun paper(left: Float, top: Float, right: Float, bottom: Float) {
                cs.setNonStrokingColor(
                    Color.red(paperColor), Color.green(paperColor), Color.blue(paperColor)
                )
                cs.addRect(left, top, right - left, bottom - top)
                cs.fill()
                cs.setStrokingColor(
                    Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)
                )
            }

            override fun lineWidth(width: Float) = cs.setLineWidth(width)

            override fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
                cs.moveTo(x0, y0); cs.lineTo(x1, y1); cs.stroke()
            }

            override fun dot(x: Float, y: Float, r: Float) {
                // a filled square: cheaper than a circle and indistinguishable at this size
                cs.setNonStrokingColor(
                    Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)
                )
                cs.addRect(x - r, y - r, r * 2, r * 2)
                cs.fill()
            }
        }
        PaperPattern.emit(
            background, left, top, left + w, top + h, spacing, anchorX, anchorY, sink
        )
    }

    /**
     * Suggested paper tints. Starting points, not a limit - any colour can be picked.
     * Chosen to stay easy on the eyes without ruining contrast when printed.
     */
    val PAPER_COLORS = listOf(
        "#FFFFFF", "#FDFCF7", "#FBF7EC", "#F7F1DE", "#F5F5F0", "#F1F3F5",
        "#EEF7F1", "#E8F4EC", "#EEF3FB", "#E7EEF9", "#FBEFEF", "#F7EEF7",
        "#FFF8E1", "#EFEFEF", "#2A2E35", "#1E2126", "#14171B", "#0C0E11"
    ).map { Color.parseColor(it) }

    /** Suggested ruling colours, from crisp to barely there. */
    val LINE_COLORS = listOf(
        "#8FA8C8", "#6E8BB0", "#A9BFDA", "#C3D2E6", "#DCE1E7", "#B9BFC7",
        "#9BC4A6", "#7FB08E", "#C8B79B", "#D8A9A9", "#B6A9D8", "#9AA0A6",
        "#4A5568", "#2F3640", "#E0C48F", "#8C8C8C"
    ).map { Color.parseColor(it) }
}
