package com.inksheets.android

import android.graphics.Path
import android.graphics.PointF
import com.inksheets.core.omr.Printed
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDVectorFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/** A page's printed music as its PDF states it ([Printed]), read with PDFBox's Android port. */
object AndroidPrinted {
    /** What a character code of [font] stands for, by the font's own map (see the desktop's PdfPrinted). */
    private fun charOf(font: com.tom_roush.pdfbox.pdmodel.font.PDFont, code: Int): String? = runCatching { font.toUnicode(code) }.getOrNull()

    fun read(context: android.content.Context, file: File, index: Int): Printed? = runCatching {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(file).use { read(it, index) }
    }.getOrNull()

    fun read(doc: PDDocument, index: Int): Printed? {
        if (index >= doc.numberOfPages) return null
        val page = doc.getPage(index)
        // The page as shown - turned, where it says so (a Mac's print to PDF stores a page sideways,
        // turned 270 to show it): the text stripper's places are as shown already, drawn lines are not.
        val box = page.cropBox
        val rotation = ((page.rotation % 360) + 360) % 360
        val sideways = rotation == 90 || rotation == 270
        fun shownX(x: Float, y: Float): Float { val u = x - box.lowerLeftX; val v = y - box.lowerLeftY; return when (rotation) { 90 -> v; 180 -> box.width - u; 270 -> box.height - v; else -> u } }
        fun shownY(x: Float, y: Float): Float { val u = x - box.lowerLeftX; val v = y - box.lowerLeftY; return when (rotation) { 90 -> u; 180 -> v; 270 -> box.width - u; else -> box.height - v } }
        val b = Printed.Builder(if (sideways) box.height else box.width, if (sideways) box.width else box.height)
        // A character's outline, once per font and code: in ems, y down, from its origin.
        val outlines = HashMap<Pair<String, Int>, List<FloatArray>?>()
        fun outline(font: com.tom_roush.pdfbox.pdmodel.font.PDFont, code: Int): List<FloatArray>? = outlines.getOrPut((font.name ?: "") to code) {
            runCatching {
                val path = (font as? PDVectorFont)?.getPath(code) ?: return@runCatching null
                val em = font.fontMatrix.scaleX
                // Points along the outline (fraction, x, y); where the fraction does not move on, a new contour begins.
                val a = path.approximate(0.5f)
                val out = ArrayList<FloatArray>(); var cur = ArrayList<Float>()
                var last = -1f
                var k = 0
                while (k + 2 < a.size) {
                    val f = a[k]; val x = a[k + 1] * em; val y = -a[k + 2] * em
                    if (f == last && cur.size >= 2) { if (cur.size >= 6) out += cur.toFloatArray(); cur = ArrayList() }
                    cur += x; cur += y; last = f
                    k += 3
                }
                if (cur.size >= 6) out += cur.toFloatArray()
                out.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
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
        object : PDFGraphicsStreamEngine(page) {
            val path = ArrayList<ArrayList<PointF>>()
            val curved = HashSet<Int>()
            var at = PointF()
            fun pt(x: Float, y: Float) = PointF(shownX(x, y), shownY(x, y))
            override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
                path += arrayListOf(pt(p0.x, p0.y), pt(p1.x, p1.y), pt(p2.x, p2.y), pt(p3.x, p3.y))
            }
            override fun moveTo(x: Float, y: Float) { path += arrayListOf(pt(x, y)); at = PointF(x, y) }
            override fun lineTo(x: Float, y: Float) { (path.lastOrNull() ?: arrayListOf<PointF>().also { path += it }) += pt(x, y); at = PointF(x, y) }
            override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                val seg = path.lastOrNull() ?: arrayListOf<PointF>().also { path += it }
                // Points along the curve itself (not its control points, which stand off it): a tie's
                // or slur's middle is where its bulge is read from.
                val x0 = at.x; val y0 = at.y
                for (k in 1..8) {
                    val t = k / 8f; val u = 1 - t
                    val bx = u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3
                    val by = u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3
                    seg += pt(bx, by)
                }
                at = PointF(x3, y3)
                curved += path.size - 1
            }
            override fun getCurrentPoint(): PointF = at
            override fun closePath() {}
            override fun endPath() { path.clear(); curved.clear() }
            override fun clip(fillType: Path.FillType) {}
            override fun drawImage(pdImage: PDImage) {}
            override fun shadingFill(shadingName: COSName) {}
            override fun strokePath() {
                // A curve drawn as a line (some engravers stroke their slurs) is a tie or slur as much as a filled one.
                for ((si, seg) in path.withIndex()) if (si in curved) b.curve(FloatArray(seg.size) { seg[it].x }, FloatArray(seg.size) { seg[it].y })
                    else b.polyline(FloatArray(seg.size) { seg[it].x }, FloatArray(seg.size) { seg[it].y })
                path.clear(); curved.clear()
            }
            override fun fillPath(fillType: Path.FillType) {
                for ((si, seg) in path.withIndex()) {
                    val xs = FloatArray(seg.size) { seg[it].x }; val ys = FloatArray(seg.size) { seg[it].y }
                    if (si in curved) b.curve(xs, ys) else b.fill(xs, ys)
                }
                path.clear(); curved.clear()
            }
            override fun fillAndStrokePath(fillType: Path.FillType) = fillPath(fillType)
        }.processPage(page)
        return b.build()
    }
}
