package com.inksheets.desktop

import com.inksheets.core.omr.Recognizer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** What [PdfPrinted] makes of a page, and the bars read with it. -Dinksheets.omr=printed -Dinksheets.omr.file=... -Dinksheets.omr.bar=page */
class PrintedDebug {
    @Test
    fun `a page as its PDF states it`() {
        assumeTrue(System.getProperty("inksheets.omr") == "printed")
        val f = File(System.getProperty("inksheets.omr.file")!!)
        val page = (System.getProperty("inksheets.omr.bar") ?: "0").toInt()
        val p = PdfPrinted.read(f, page)
        println("PRINTED: " + (p?.symbols?.groupingBy { it.kind }?.eachCount() ?: "null") + ", stems ${p?.stems?.size}, beams ${p?.beams?.size}, arcs ${p?.arcs?.size}, lines ${p?.lines?.size}")
        p?.symbols?.filter { it.digit == 3 }?.forEach { println("  THREE ${it.kind} at ${it.x},${it.y} size ${it.size}") }
        // The special font's characters: their size (ems) and what their shape is most like.
        org.apache.pdfbox.Loader.loadPDF(f).use { doc ->
            object : org.apache.pdfbox.text.PDFTextStripper() {
                override fun writeString(text: String?, positions: MutableList<org.apache.pdfbox.text.TextPosition>?) {
                    for (p in positions.orEmpty()) {
                        val font = p.font ?: continue
                        if (font.name?.contains("Special", true) != true) continue
                        val code = p.characterCodes?.firstOrNull() ?: continue
                        val shape = GlyphShapesTest().outline(font, code) ?: continue
                        val xs = shape.flatMap { c -> (c.indices step 2).map { c[it] } }; val ys = shape.flatMap { c -> (1 until c.size step 2).map { c[it] } }
                        val best = com.inksheets.core.omr.GlyphShapes.classify(shape, 0f)
                        println("  SPECIAL code $code at ${p.xDirAdj.toInt()},${p.yDirAdj.toInt()} size ${"%.1f".format((xs.max() - xs.min()) / 4)}x${"%.1f".format((ys.max() - ys.min()) / 4)} em: ${best?.kind} ${best?.name} ${best?.score}")
                    }
                }
            }.apply { startPage = page + 1; endPage = page + 1 }.getText(doc)
        }
        val (ink, _) = OmrRealPagesTest().renderAt(f, page)!!
        val r = Recognizer().read(ink, printed = p)
        for (m in r.measures.take(12)) println("  BAR m${m.number} st${m.staff} ${m.time.beats}/${m.time.beatType} ${m.doubts} | " + m.events.joinToString(" ") { e -> when (e) { is com.inksheets.core.omr.Note -> "n${e.duration.base}${".".repeat(e.duration.dots)}${if (e.duration.tuplet) "t" else ""}"; is com.inksheets.core.omr.Rest -> "r${e.duration.base}" } })
    }
}
