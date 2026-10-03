package com.inksheets.desktop

import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Learned
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Strips
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * What the reader sees and makes of one bar of a real scan, for finding why notes far off the staff
 * are lost: every symbol the trained reader finds there (and how likely), and the bar as read.
 * -Dinksheets.debug.file=<library path> -Dinksheets.debug.page=0 -Dinksheets.debug.x=1274..1498 -Dinksheets.debug.staff=n
 */
class LedgerNotesDebug {
    @Test
    fun `one bar looked into`() {
        val rel = System.getProperty("inksheets.debug.file") ?: return assumeTrue(false)
        val file = File(System.getenv("USERPROFILE"), "Music/Sheet Music/InkSheets/$rel")
        val p = System.getProperty("inksheets.debug.page")!!.toInt()
        val (x0, x1) = System.getProperty("inksheets.debug.x")!!.split("..").map { it.toInt() }
        val staff = System.getProperty("inksheets.debug.staff")!!.toInt()
        val source = com.inkslate.desktop.DesktopSources.open(file, detached = true)!!
        fun draw(width: Int): Pair<IntArray, Ink> {
            val img = source.render(p, width)!!
            val px = IntArray(img.width * img.height); img.readPixels(px)
            return Strips.grey(px) to Ink.fromArgb(img.width, img.height, px)
        }
        val space = Recognizer().metrics(draw(1600).second)?.second
        val width = if (space == null || space <= 0f) 1600 else (1600 * 18f / space).toInt().coerceIn(1000, 5000)
        val (grey, ink) = draw(width)
        val net = com.inksheets.core.omr.Net.shipped!!
        val rec = Recognizer()
        val (t, sp) = rec.metrics(ink)!!
        val staves = rec.staves(ink, t, sp)
        for ((i, st) in staves.withIndex()) println("DEBUG staff $i lines ${st.lineY(0, (st.left + st.right) / 2).toInt()}..${st.lineY(4, (st.left + st.right) / 2).toInt()} x ${st.left}..${st.right}")
        val s = staves[staff]
        println("DEBUG width $width space ${s.space} staff $staff top ${s.lineY(0, (x0 + x1) / 2)} bottom ${s.lineY(4, (x0 + x1) / 2)}")
        val found = Learned.symbols(grey, ink.width, ink.height, staves, net)
        for (sym in found.symbols.filter { it.x + it.width / 2 in x0.toFloat()..x1.toFloat() && it.y > s.lineY(0, x0) - s.space * 8 && it.y < s.lineY(4, x0) + s.space * 8 })
            println("DEBUG  ${sym.kind} at ${sym.x.toInt()},${sym.y.toInt()} step ${((sym.y - s.lineY(0, sym.x.toInt())) / (s.space / 2)).let { "%.1f".format(it) }} conf ${"%.2f".format(sym.confidence)} odds ${sym.odds.map { "%.2f".format(it) }}")
        if (System.getProperty("inksheets.debug.printed") != null) {
            val pr = PdfPrinted.read(file, p)!!.scaled(ink.width)
            println("DEBUG printed width ${pr.width} ink ${ink.width}")
            for (sym in pr.heads.filter { it.x + it.width / 2 in x0.toFloat()..x1.toFloat() }.sortedBy { it.x })
                println("DEBUG  printed ${sym.kind} at ${sym.x.toInt()},${sym.y.toInt()} size ${"%.1f".format(sym.size)}")
            for ((i, st) in staves.withIndex()) println("DEBUG staff $i lines ${st.lineY(0, x0).toInt()}..${st.lineY(4, x0).toInt()} x ${st.left}..${st.right}")
            val r2 = Recognizer().read(ink, p, 1, Recognizer.Carry(), PdfPrinted.read(file, p), grey = grey, net = null)
            for (m in r2.measures.filter { it.staff == staff && it.box.right > x0 && it.box.left < x1 })
                println("DEBUG printed-path bar ${m.number} ${m.box.left}-${m.box.right}: ${m.events.map { e -> if (e is com.inksheets.core.omr.Note) "${e.steps}/${e.duration.base}" else "r${e.duration.base}" }} doubts ${m.doubts}")
            return
        }
        val r = Recognizer().read(ink, p, 1, Recognizer.Carry(), null, grey = grey, net = net)
        for (m in r.measures.filter { it.staff == staff && it.box.right > x0 && it.box.left < x1 })
            println("DEBUG bar ${m.number} ${m.box.left}-${m.box.right}: ${m.events.map { e -> if (e is com.inksheets.core.omr.Note) "${e.steps}/${e.duration.base}" else "r${e.duration.base}" }} maybe ${m.maybe.map { e -> if (e is com.inksheets.core.omr.Note) "${e.steps}/${e.duration.base}" else "r" }} doubts ${m.doubts}")
    }
}
