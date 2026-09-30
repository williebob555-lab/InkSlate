package com.inksheets.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.geom.Point2D
import java.io.File

/**
 * What kind of page each part in the library is, as far as reading its music goes: notes written
 * as music-font characters the PDF names (readable exactly), a music font whose characters cannot
 * be told apart, notes drawn as outlines (vector, no characters), or a picture (a scan).
 * -Dinksheets.omr=kinds
 */
class LibraryKindsSurvey {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    @Test
    fun `kinds of page in the library`() {
        assumeTrue(System.getProperty("inksheets.omr") == "kinds")
        val parts = music.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) && !it.name.contains("score", true) && !it.path.contains(".inksheets") }.toList()
        val kinds = HashMap<String, Int>()
        val examples = HashMap<String, MutableList<String>>()
        for (f in parts) {
            val kind = runCatching { kindOf(f) }.getOrElse { "unreadable" }
            kinds.merge(kind, 1, Int::plus)
            examples.getOrPut(kind) { ArrayList() }.let { if (it.size < 4) it += f.relativeTo(music).path }
        }
        println("PARTS: ${parts.size}")
        kinds.entries.sortedByDescending { it.value }.forEach { (k, v) -> println("  $k: $v (${v * 100 / parts.size}%)  e.g. ${examples[k]}") }
    }

    private fun kindOf(f: File): String {
        if (AnswerKey.read(f, 0, 72f) != null) return "music font, readable"
        Loader.loadPDF(f).use { doc ->
            if (doc.numberOfPages == 0) return "empty"
            val page = doc.getPage(0)
            val area = page.mediaBox.width * page.mediaBox.height
            var imageArea = 0f; var fills = 0; var strokes = 0
            object : PDFGraphicsStreamEngine(page) {
                override fun drawImage(pdImage: PDImage) {
                    val m = graphicsState.currentTransformationMatrix
                    imageArea += kotlin.math.abs(m.scalingFactorX * m.scalingFactorY)
                }
                override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {}
                override fun clip(windingRule: Int) {}
                override fun moveTo(x: Float, y: Float) {}
                override fun lineTo(x: Float, y: Float) {}
                override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {}
                override fun getCurrentPoint(): Point2D = Point2D.Float()
                override fun closePath() {}
                override fun endPath() {}
                override fun strokePath() { strokes++ }
                override fun fillPath(windingRule: Int) { fills++ }
                override fun fillAndStrokePath(windingRule: Int) { fills++ }
                override fun shadingFill(shadingName: COSName) {}
            }.processPage(page)
            val fonts = page.resources.fontNames.mapNotNull { runCatching { page.resources.getFont(it)?.name }.getOrNull() }
            val musicFont = fonts.any { n -> listOf("opus", "helsinki", "leland", "bravura", "petrucci", "maestro", "jazz", "sonata", "finale", "engraver", "broadway", "reprise").any { n.contains(it, true) } }
            return when {
                imageArea > area * 0.5f -> "picture (scan)"
                musicFont -> "music font, not readable (${fonts.filter { n -> n.contains("opus", true) || n.contains("maestro", true) || n.contains("petrucci", true) || n.contains("jazz", true) || n.contains("leland", true) || n.contains("bravura", true) }.map { it.substringAfter('+') }.distinct().take(2)})"
                fills + strokes > 200 -> "outlines (vector, no characters)"
                else -> "other (${fills + strokes} shapes, ${fonts.size} fonts)"
            }
        }
    }
}
