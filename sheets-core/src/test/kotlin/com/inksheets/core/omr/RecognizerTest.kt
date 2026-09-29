package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Test
import java.util.Random

/**
 * The reader against music whose every note is known: pages engraved here, read back - clean,
 * then roughened like a scan (noise, blur, a slight turn).
 */
class RecognizerTest {

    private fun describe(e: Event) = when (e) {
        is Note -> "N${e.steps}/${e.duration.base}${".".repeat(e.duration.dots)}"
        is Rest -> "R/${e.duration.base}${".".repeat(e.duration.dots)}"
    }

    /** Compares; returns (events right, events in all), printing each measure that differs. */
    private fun compare(truth: List<Measure>, read: List<Measure>, label: String): Pair<Int, Int> {
        var right = 0; var all = 0
        for ((i, t) in truth.withIndex()) {
            val want = t.events.map(::describe)
            val got = read.getOrNull(i)?.events?.map(::describe).orEmpty()
            all += want.size
            // In order, allowing for a missed or an extra event.
            val lcs = Array(want.size + 1) { IntArray(got.size + 1) }
            for (a in want.indices.reversed()) for (b in got.indices.reversed())
                lcs[a][b] = if (want[a] == got[b]) 1 + lcs[a + 1][b + 1] else maxOf(lcs[a + 1][b], lcs[a][b + 1])
            right += lcs[0][0]
            if (want != got) println("$label m${i + 1}: want $want\n$label m${i + 1}:  got $got  ${read.getOrNull(i)?.doubts.orEmpty()}")
        }
        return right to all
    }

    private fun overlay(page: TestPages.Page, reading: Recognizer.PageReading, name: String) {
        val dir = System.getProperty("inksheets.shots") ?: return
        val ink = page.ink
        val img = java.awt.image.BufferedImage(ink.width, ink.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, ink.width, ink.height, ink.argb(), 0, ink.width)
        val g = img.createGraphics()
        g.stroke = java.awt.BasicStroke(2f)
        for ((si, s) in reading.staves.withIndex()) {
            g.color = java.awt.Color(0, 160, 255)
            g.drawRect(s.left, s.top, s.right - s.left, s.bottom - s.top)
            g.color = java.awt.Color.RED
            for (b in reading.barlines[si]) g.drawLine(b, s.top - 10, b, s.bottom + 10)
        }
        for (m in reading.measures) {
            g.color = if (m.sure) java.awt.Color(0, 170, 0) else java.awt.Color(230, 120, 0)
            for (e in m.events) g.drawString(describe(e).substringAfter('/'), e.x.toInt(), m.box.bottom + (m.space * 3).toInt())
        }
        g.dispose()
        java.io.File(dir).mkdirs()
        javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "read-${name.replace('/', '-')}.png"))
    }

    private fun check(page: TestPages.Page, label: String, least: Double, measureSlack: Int = 0) {
        val t0 = System.nanoTime()
        val reading = Recognizer().read(page.ink)
        val ms = (System.nanoTime() - t0) / 1_000_000
        overlay(page, reading, label)
        val truth = page.staves.flatten()
        println("$label: ${reading.staves.size} staves, ${reading.measures.size} measures (want ${truth.size}), space ${"%.1f".format(reading.space)}, $ms ms")
        assertEquals("$label staves", page.staves.size, reading.staves.size)
        assertTrue("$label measures: ${reading.measures.size}, want ${truth.size}", abs(truth.size - reading.measures.size) <= measureSlack)
        // Measure by measure when they line up; the whole page's events in order when one was split.
        val (right, all) = if (truth.size == reading.measures.size) compare(truth, reading.measures, label)
        else compare(listOf(truth.first().copy(events = truth.flatMap { it.events })), listOf(reading.measures.first().copy(events = reading.measures.flatMap { it.events })), label)
        val rate = right.toDouble() / all
        println("$label: $right of $all events read right (${"%.1f".format(rate * 100)}%); ${reading.measures.count { it.sure }} of ${reading.measures.size} measures sure")
        assertTrue("$label: ${"%.3f".format(rate)}", rate >= least)
    }

    /** Scan-like: a slight turn, soft edges, specks and grey paper. */
    private fun roughen(ink: Ink, seed: Long, turnDegrees: Double = 0.4): Ink {
        val r = Random(seed)
        val a = Math.toRadians(turnDegrees)
        val cos = Math.cos(a); val sin = Math.sin(a)
        val cx = ink.width / 2.0; val cy = ink.height / 2.0
        val grey = IntArray(ink.width * ink.height)
        for (y in 0 until ink.height) for (x in 0 until ink.width) {
            // Sample the turned page, averaging a little (blur).
            var dark = 0.0
            for (dy in -1..1) for (dx in -1..1) {
                val sx = cos * (x + dx * 0.5 - cx) + sin * (y + dy * 0.5 - cy) + cx
                val sy = -sin * (x + dx * 0.5 - cx) + cos * (y + dy * 0.5 - cy) + cy
                if (ink[sx.toInt(), sy.toInt()]) dark += 1.0 / 9
            }
            val paper = 225 + (x * 20 / ink.width)   // lighter to the right, like a scan's light
            grey[y * ink.width + x] = (paper - dark * 200 + r.nextGaussian() * 12).toInt().coerceIn(0, 255)
        }
        return Ink.fromGrey(ink.width, ink.height, grey)
    }

    @Test
    fun `reads engraved music back`() {
        check(TestPages.page(1), "clean", 0.9)
    }

    @Test
    fun `reads it back from a rough scan`() {
        val page = TestPages.page(2)
        check(TestPages.Page(roughen(page.ink, 2), page.staves, page.space, page.tops), "rough", 0.8)
    }

    @Test
    fun `reads other keys, clefs and times`() {
        check(TestPages.page(3, clef = Clef.BASS, key = Key(-3), time = TimeSig(3, 4)), "bass-3flats-3/4", 0.85)
        // Small print is the hard case: the app reads pages drawn larger than this.
        check(TestPages.page(4, key = Key(2), time = TimeSig(6, 8), space = 14f), "2sharps-6/8-small", 0.75, measureSlack = 1)
    }
}
