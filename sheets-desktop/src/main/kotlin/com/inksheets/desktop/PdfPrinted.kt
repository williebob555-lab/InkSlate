package com.inksheets.desktop

import com.inksheets.core.omr.Printed
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDVectorFont
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.awt.geom.Point2D
import java.io.File

/** A page's printed music as its PDF states it ([Printed]), read with PDFBox. */
object PdfPrinted {
    /**
     * What a character code of [font] stands for, by the font's own map: the text stripper's
     * merges a dot printed over a note into the note's text as an accent, which would hide it.
     */
    private fun charOf(font: org.apache.pdfbox.pdmodel.font.PDFont, code: Int): String? = runCatching { font.toUnicode(code) }.getOrNull()

    fun read(file: File, index: Int): Printed? = runCatching { Loader.loadPDF(file).use { read(it, index) } }.getOrNull()

    /** A character's outline: in ems, y down, from its origin; null when the font has none to give. */
    internal fun contours(font: org.apache.pdfbox.pdmodel.font.PDFont, code: Int): List<FloatArray>? =
        runCatching {
            val path = (font as? PDVectorFont)?.getNormalizedPath(code) ?: return@runCatching null
            val it = path.getPathIterator(java.awt.geom.AffineTransform(0.001, 0.0, 0.0, -0.001, 0.0, 0.0), 0.0005)
            val out = ArrayList<FloatArray>(); var cur = ArrayList<Float>(); val c = DoubleArray(6)
            while (!it.isDone) {
                when (it.currentSegment(c)) {
                    java.awt.geom.PathIterator.SEG_MOVETO -> { if (cur.size >= 6) out += cur.toFloatArray(); cur = arrayListOf(c[0].toFloat(), c[1].toFloat()) }
                    java.awt.geom.PathIterator.SEG_LINETO -> { cur += c[0].toFloat(); cur += c[1].toFloat() }
                    java.awt.geom.PathIterator.SEG_CLOSE -> { if (cur.size >= 6) out += cur.toFloatArray(); cur = ArrayList() }
                }
                it.next()
            }
            if (cur.size >= 6) out += cur.toFloatArray()
            out.takeIf { it.isNotEmpty() }
        }.getOrNull()

    fun read(doc: PDDocument, index: Int): Printed? {
        if (index >= doc.numberOfPages) return null
        val page = doc.getPage(index)
        // The page as shown - turned, where it says so (the text stripper's places are as shown already).
        val space = PageSpace(page)
        val b = Printed.Builder(space.width, space.height)
        // Characters: where the text stripper puts them (from the page's top left, in points).
        // A character's outline, once per font and code: in ems, y down, from its origin.
        val outlines = HashMap<Pair<String, Int>, List<FloatArray>?>()
        fun outline(font: org.apache.pdfbox.pdmodel.font.PDFont, code: Int): List<FloatArray>? = outlines.getOrPut((font.name ?: "") to code) { contours(font, code) }
        object : PDFTextStripper() {
            // Every glyph as drawn, each once: the stripper's own grouping takes a glyph that looks like an
            // accent (a half head, "˙") for one and merges it into the character before it - an accent
            // mark over a half note lost the note.
            private val glyphs = ArrayList<TextPosition>()
            private val seen = HashSet<String>()
            override fun processTextPosition(text: TextPosition) {
                if (seen.add("${text.font?.name}|${text.characterCodes?.firstOrNull()}|${Math.round(text.xDirAdj * 2)}|${Math.round(text.yDirAdj * 2)}")) glyphs += text
            }
            override fun writePage() = writeString("", glyphs)
            override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
                for (p in positions.orEmpty()) {
                    val font = p.font ?: continue
                    val name = font.name ?: continue
                    val code = p.characterCodes?.firstOrNull() ?: continue
                    val size = p.textMatrix.scalingFactorX
                    if (Printed.isSpecialFont(name)) {
                        val shape = outline(font, code) ?: continue
                        val xs = shape.flatMap { c -> (c.indices step 2).map { c[it] } }; val ys = shape.flatMap { c -> (1 until c.size step 2).map { c[it] } }
                        val w = xs.max() - xs.min(); val h = ys.max() - ys.min()
                        // Small and round: a dot, at its middle. Anything else: told by its outline.
                        if (Printed.specialKind(w, h) != null) b.specialChar(name, w, h, p.xDirAdj + (xs.max() + xs.min()) / 2 * size, p.yDirAdj + (ys.max() + ys.min()) / 2 * size, size)
                        else b.glyph(name, shape, p.xDirAdj, p.yDirAdj, size)
                    } else if (!b.char(name, code, charOf(font, code) ?: p.unicode, p.xDirAdj, p.yDirAdj, p.widthDirAdj, size)) {
                        outline(font, code)?.let { b.glyph(name, it, p.xDirAdj, p.yDirAdj, size) }
                    }
                }
            }
        }.apply { startPage = index + 1; endPage = index + 1; sortByPosition = false }.getText(doc)
        // Lines and shapes: stems and beams.
        object : PDFGraphicsStreamEngine(page) {
            val path = ArrayList<ArrayList<Point2D.Float>>()
            val curved = HashSet<Int>()
            var at = Point2D.Float()
            fun pt(x: Float, y: Float) = Point2D.Float(space.x(x, y), space.y(x, y))
            override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
                path += arrayListOf(pt(p0.x.toFloat(), p0.y.toFloat()), pt(p1.x.toFloat(), p1.y.toFloat()), pt(p2.x.toFloat(), p2.y.toFloat()), pt(p3.x.toFloat(), p3.y.toFloat()))
            }
            override fun moveTo(x: Float, y: Float) { path += arrayListOf(pt(x, y)); at = Point2D.Float(x, y) }
            override fun lineTo(x: Float, y: Float) { (path.lastOrNull() ?: arrayListOf<Point2D.Float>().also { path += it }) += pt(x, y); at = Point2D.Float(x, y) }
            override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                val seg = path.lastOrNull() ?: arrayListOf<Point2D.Float>().also { path += it }
                // Points along the curve itself (not its control points, which stand off it): a tie's
                // or slur's middle is where its bulge is read from.
                val x0 = at.x; val y0 = at.y
                for (k in 1..8) {
                    val t = k / 8f; val u = 1 - t
                    val bx = u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3
                    val by = u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3
                    seg += pt(bx, by)
                }
                at = Point2D.Float(x3, y3)
                curved += path.size - 1
            }
            override fun getCurrentPoint(): Point2D = at
            override fun closePath() {}
            override fun endPath() { path.clear(); curved.clear() }
            override fun clip(windingRule: Int) {}
            override fun drawImage(pdImage: PDImage) {}
            override fun shadingFill(shadingName: COSName) {}
            override fun strokePath() {
                // A curve drawn as a line (some engravers stroke their slurs) is a tie or slur as much as a filled one.
                for ((si, seg) in path.withIndex()) if (si in curved) b.curve(FloatArray(seg.size) { seg[it].x }, FloatArray(seg.size) { seg[it].y })
                    else b.polyline(FloatArray(seg.size) { seg[it].x }, FloatArray(seg.size) { seg[it].y })
                path.clear(); curved.clear()
            }
            override fun fillPath(windingRule: Int) {
                for ((si, seg) in path.withIndex()) {
                    val xs = FloatArray(seg.size) { seg[it].x }; val ys = FloatArray(seg.size) { seg[it].y }
                    if (si in curved) b.curve(xs, ys) else b.fill(xs, ys)
                }
                path.clear(); curved.clear()
            }
            override fun fillAndStrokePath(windingRule: Int) = fillPath(windingRule)
        }.processPage(page)
        return b.build()
    }
}
