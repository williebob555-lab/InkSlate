package com.inksheets.desktop

import com.inksheets.core.omr.Recognizer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

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
        val (ink, dpi) = OmrRealPagesTest().renderAt(file, 0)!!
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
            // The page in grey as drawn, same place and size: what the black and white was made from.
            val grey = org.apache.pdfbox.Loader.loadPDF(file).use { org.apache.pdfbox.rendering.PDFRenderer(it).renderImageWithDPI(0, dpi, org.apache.pdfbox.rendering.ImageType.GRAY) }
            val g2 = java.awt.image.BufferedImage((x1 - x0) * k, (y1 - y0) * k, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in y0 until y1) for (x in x0 until x1) {
                val c = if (x < grey.width && y < grey.height) grey.getRGB(x, y) else -1
                for (dy in 0 until k) for (dx in 0 until k) g2.setRGB((x - x0) * k + dx, (y - y0) * k + dy, c)
            }
            javax.imageio.ImageIO.write(g2, "png", File(dir, "bar-debug-grey.png"))
            // Grey levels (tens) round -Dinksheets.omr.at=x,y, from the colour page the reader is given.
            System.getProperty("inksheets.omr.at")?.split(",")?.map { it.toInt() }?.let { (ax, ay) ->
                val rgb = org.apache.pdfbox.Loader.loadPDF(file).use { org.apache.pdfbox.rendering.PDFRenderer(it).renderImageWithDPI(0, dpi, org.apache.pdfbox.rendering.ImageType.RGB) }
                println("  hollow at $ax,$ay: " + r.explainHollow(clean, s, ax, ay, ink))
                for (y in ay - 14..ay + 14) println("  lum y=$y " + (ax - 16..ax + 16).joinToString("") { x ->
                    val p = rgb.getRGB(x, y); val l = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    (if (ink[x, y]) "#" else " ") + "%2d".format(l / 10)
                })
            }
            println("picture: x from $x0, y from $y0, x4")
        }
        // Every place in the bar something like a hollow head is, and each test it was put to.
        if (System.getProperty("inksheets.omr.hollow") != null) for (step in -2..10) for (x in m.box.left until m.box.right step 8) {
            val y = s.y(step, x).roundToInt()
            val why = r.explainHollow(clean, s, x, y)
            val ring = why.substringAfter("ring ").substringBefore(" ").toFloatOrNull() ?: 0f
            val half = why.substringAfter("half ").substringBefore(" ").toFloatOrNull() ?: 0f
            if (ring > 0.45f || half > 0.45f) println("  hollow? x=$x step=$step: $why")
        }
        val loud = Recognizer(debug = true)
        for (h in r.heads(clean, s, m.box.left, m.box.right, ink)) {
            loud.debugStem(clean, s, h, t)
            println("  head x=${h.x} step=${h.step} ${h.kind} score=${"%.2f".format(h.score)} stemX=${h.stemX} end=${h.stemEnd} up=${h.up} flags=${h.flags}")
        }
        // Every upright line along the staff, and why each was or was not taken for a barline.
        val all = r.heads(clean, s, s.left, s.right, ink)
        for (h in all) Recognizer().debugStem(clean, s, h, t)
        // The traced lines against the page's: for each line, how far off the nearest long run of ink is.
        for (x in s.left until s.right step 60) {
            val offs = (0..4).map { i ->
                val y0 = s.lineY(i, x).roundToInt()
                (-6..6).filter { dy -> (x - 4..x + 4).all { ink[it, y0 + dy] } }.minByOrNull { abs(it) }
            }
            println("  trace x=$x: $offs")
        }
        println("barlines found: ${reading.barlines[m.staff]}")
        for ((h, why) in reading.dropped) if (h.x in m.box.left..m.box.right && abs(h.y - (m.box.top + m.box.bottom) / 2) < s.space * 5) println("  dropped x=${h.x} step=${h.step} ${h.kind} stemX=${h.stemX}: $why")
        println("-- in the reading itself:")
        Recognizer(debug = true).read(ink)
        loud.barlines(ink, clean, s, t, all.filter { it.stemX >= 0 }.map { it.stemX }, all)
    }
}
