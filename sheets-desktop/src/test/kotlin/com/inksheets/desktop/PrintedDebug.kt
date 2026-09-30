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
        println("PRINTED: " + (p?.symbols?.groupingBy { it.kind }?.eachCount() ?: "null") + ", stems ${p?.stems?.size}, beams ${p?.beams?.size}")
        p?.symbols?.filter { it.digit == 3 }?.forEach { println("  THREE ${it.kind} at ${it.x},${it.y} size ${it.size}") }
        val (ink, _) = OmrRealPagesTest().renderAt(f, page)!!
        val r = Recognizer().read(ink, printed = p)
        for (m in r.measures.take(12)) println("  BAR m${m.number} st${m.staff} ${m.time.beats}/${m.time.beatType} ${m.doubts} | " + m.events.joinToString(" ") { e -> when (e) { is com.inksheets.core.omr.Note -> "n${e.duration.base}${".".repeat(e.duration.dots)}${if (e.duration.tuplet) "t" else ""}"; is com.inksheets.core.omr.Rest -> "r${e.duration.base}" } })
    }
}
