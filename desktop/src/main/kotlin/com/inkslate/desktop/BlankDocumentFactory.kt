package com.inkslate.desktop

import com.inkslate.core.PaperPattern
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream

/**
 * Creates blank documents to draw on: notebook pages, graph paper, whiteboards.
 *
 * The Android factory, against full PDFBox instead of the Android port. Everything that decides
 * what the page *looks* like comes from `core/PaperPattern`, so a sheet of Cornell paper made on
 * the laptop and one made on the tablet are the same sheet rather than two drawings of one.
 *
 * Written as real PDFs on purpose, for the same reason as on Android: a whiteboard you cannot
 * hand to anyone is much less useful, and it means blank pages travel through the same sync,
 * export and annotation paths as an assignment you were given.
 */
object BlankDocumentFactory {

    /** Page background pattern, and what to call it in the menu. */
    enum class Background(val label: String, val pattern: PaperPattern.Pattern) {
        PLAIN("Plain", PaperPattern.Pattern.PLAIN),
        RULED("Ruled", PaperPattern.Pattern.RULED),
        GRID("Grid", PaperPattern.Pattern.GRID),
        DOTS("Dot grid", PaperPattern.Pattern.DOTS),
        GRAPH("Fine graph", PaperPattern.Pattern.GRAPH),
        CORNELL("Cornell notes", PaperPattern.Pattern.CORNELL),
        MUSIC("Music staves", PaperPattern.Pattern.MUSIC),
        ISOMETRIC("Isometric", PaperPattern.Pattern.ISOMETRIC)
    }

    /** Page geometry, in PDF points (72 per inch). The Android list, value for value. */
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
        val paperColor: Int = WHITE,
        val lineColor: Int = DEFAULT_RULING,
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
                    cs.transform(Matrix(1f, 0f, 0f, -1f, 0f, spec.pageSize.height))
                    paintBackground(
                        cs, spec.background, spec.pageSize.width, spec.pageSize.height,
                        spec.paperColor, spec.lineColor, spec.spacing
                    )
                }
            }
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
        target
    }

    /**
     * Paint one page's paper and ruling.
     *
     * Coordinates go through unchanged, in the same y-downwards space the ink is stored in. The
     * caller supplies the one transform that flips into PDF user space.
     */
    fun paintBackground(
        cs: PDPageContentStream,
        background: Background,
        w: Float,
        h: Float,
        paperColor: Int = WHITE,
        lineColor: Int = DEFAULT_RULING,
        spacing: Float = 24f,
        left: Float = 0f,
        top: Float = 0f,
        anchorX: Float = 0f,
        anchorY: Float = 0f
    ) {
        // PDFBox 3 takes components as 0..1 floats; the 0..255 integer overloads are gone.
        fun red(c: Int) = ((c shr 16) and 0xFF) / 255f
        fun green(c: Int) = ((c shr 8) and 0xFF) / 255f
        fun blue(c: Int) = (c and 0xFF) / 255f

        val sink = object : PaperPattern.Sink {
            override fun paper(left: Float, top: Float, right: Float, bottom: Float) {
                cs.setNonStrokingColor(red(paperColor), green(paperColor), blue(paperColor))
                cs.addRect(left, top, right - left, bottom - top)
                cs.fill()
                cs.setStrokingColor(red(lineColor), green(lineColor), blue(lineColor))
            }

            override fun lineWidth(width: Float) = cs.setLineWidth(width)

            override fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
                cs.moveTo(x0, y0); cs.lineTo(x1, y1); cs.stroke()
            }

            override fun dot(x: Float, y: Float, r: Float) {
                // a filled square: cheaper than a circle and indistinguishable at this size
                cs.setNonStrokingColor(red(lineColor), green(lineColor), blue(lineColor))
                cs.addRect(x - r, y - r, r * 2, r * 2)
                cs.fill()
            }
        }
        PaperPattern.emit(
            background.pattern, left, top, left + w, top + h, spacing, anchorX, anchorY, sink
        )
    }

    const val WHITE: Int = 0xFFFFFFFF.toInt()
    val DEFAULT_RULING: Int = 0xFF8FA8C8.toInt()

    /**
     * Suggested paper tints. Starting points, not a limit.
     * The Android list, so the same document can be made on either machine.
     */
    val PAPER_COLORS = listOf(
        "FFFFFF", "FDFCF7", "FBF7EC", "F7F1DE", "F5F5F0", "F1F3F5",
        "EEF7F1", "E8F4EC", "EEF3FB", "E7EEF9", "FBEFEF", "F7EEF7",
        "FFF8E1", "EFEFEF", "2A2E35", "1E2126", "14171B", "0C0E11"
    ).map { (0xFF000000L or it.toLong(16)).toInt() }

    /** Suggested ruling colours, from crisp to barely there. */
    val LINE_COLORS = listOf(
        "8FA8C8", "6E8BB0", "A9BFDA", "C3D2E6", "DCE1E7", "B9BFC7",
        "9BC4A6", "7FB08E", "C8B79B", "D8A9A9", "B6A9D8", "9AA0A6",
        "4A5568", "2F3640", "E0C48F", "8C8C8C"
    ).map { (0xFF000000L or it.toLong(16)).toInt() }
}
