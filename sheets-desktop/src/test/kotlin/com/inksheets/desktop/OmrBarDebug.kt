package com.inksheets.desktop

import com.inksheets.core.omr.Recognizer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * One bar of a real part, looked at closely: every head the reader finds in it, its stem and
 * what it counted at the stem's end. -Dinksheets.omr=bar -Dinksheets.omr.file=... -Dinksheets.omr.bar=6
 */
class OmrBarDebug {
    @Test
    fun `one bar closely`() {
        assumeTrue(System.getProperty("inksheets.omr") == "bar")
        val file = File(System.getProperty("inksheets.omr.file")!!)
        val bar = System.getProperty("inksheets.omr.bar")!!.toInt()
        val ink = OmrRealPagesTest().render(file, 0)!!
        val r = Recognizer()
        val reading = r.read(ink)
        val m = reading.measures.first { it.number == bar }
        val s = reading.staves[m.staff]
        val (t, _) = r.metrics(ink)!!
        val clean = r.withoutLines(ink, reading.staves, t)
        println("bar $bar: ${m.events} ${m.doubts}; space ${s.space}, line $t, box ${m.box}")
        System.getProperty("inksheets.shots")?.let { dir ->
            val x0 = m.box.left; val x1 = m.box.right; val y0 = m.box.top - (s.space * 4).toInt(); val y1 = m.box.bottom + (s.space * 4).toInt()
            val k = 4
            val img = java.awt.image.BufferedImage((x1 - x0) * k, (y1 - y0) * k, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in y0 until y1) for (x in x0 until x1) {
                val c = when { clean[x, y] -> 0; ink[x, y] -> 0xBBBBBB; else -> 0xFFFFFF }
                for (dy in 0 until k) for (dx in 0 until k) img.setRGB((x - x0) * k + dx, (y - y0) * k + dy, c)
            }
            javax.imageio.ImageIO.write(img, "png", File(dir, "bar-debug.png"))
            println("picture: x from $x0, y from $y0, x4")
        }
        val loud = Recognizer(debug = true)
        for (h in r.heads(clean, s, m.box.left, m.box.right, ink)) {
            loud.debugStem(clean, s, h, t)
            println("  head x=${h.x} step=${h.step} ${h.kind} score=${"%.2f".format(h.score)} stemX=${h.stemX} end=${h.stemEnd} up=${h.up} flags=${h.flags}")
        }
    }
}
