package com.inksheets.core.omr

import java.io.File
import java.util.Random

/** Pages of engraved music whose every note is known: for checking the reader against. */
object TestPages {
    /** A bar of [time] filled with notes and rests at random, in [key] and [clef]. */
    fun randomMeasure(r: Random, number: Int, clef: Clef, key: Key, time: TimeSig, first: Boolean): Measure {
        val events = ArrayList<Event>()
        var left = time.quarters
        val choices = listOf(Duration(1), Duration(2), Duration(2, 1), Duration(4), Duration(4), Duration(4), Duration(4, 1), Duration(8), Duration(8), Duration(8), Duration(16))
        // Eighths come in pairs and sixteenths in fours, so they beam within a beat.
        while (left > 1e-9) {
            val d = choices.filter { it.quarters <= left + 1e-9 }.let { it[r.nextInt(it.size)] }
            val group = when (d.base) { 8 -> if (d.dots == 0 && left >= 1.0) 2 else 1; 16 -> if (left >= 1.0) 4 else 1; else -> 1 }
            val use = if (group > 1 && (time.quarters - left) % 1.0 < 1e-9) group else 1
            repeat(use) {
                if (r.nextInt(8) == 0 && d.base <= 8) events += Rest(d, 0f)
                else {
                    val step = 1 + r.nextInt(10) - 1        // from above the top line to below the bottom
                    val steps = if (r.nextInt(6) == 0) listOf(step, step + 2) else listOf(step)
                    val acc = if (r.nextInt(7) == 0) mapOf(steps[0] to listOf(-1, 0, 1)[r.nextInt(3)]) else emptyMap()
                    events += Note(steps, emptyList(), d, 0f, acc)
                }
                left -= d.quarters
            }
        }
        return Measure(number, 0, 0, Box(0, 0, 0, 0), 0f, clef, key, time, events, showsClef = first, showsKey = first, showsTime = number == 1)
    }

    class Page(val ink: Ink, val staves: List<List<Measure>>, val space: Float, val tops: List<Float>)

    /** A page of [staves] lines, [perLine] measures each, [space] pixels to the staff space. */
    fun page(seed: Long, staves: Int = 4, perLine: Int = 4, space: Float = 18f, clef: Clef = Clef.TREBLE, key: Key = Key(0), time: TimeSig = TimeSig(4, 4)): Page {
        val r = Random(seed)
        val lines = (0 until staves).map { s -> (0 until perLine).map { i -> randomMeasure(r, s * perLine + i + 1, clef, key, time, i == 0) } }
        val drawings = lines.map { Engraver.line(it) }
        val width = (drawings.maxOf { it.width } * space + 4 * space).toInt()
        val gap = 10f
        val height = ((staves * (4 + gap)) * space + 6 * space).toInt()
        val ink = Ink(width, height)
        val tops = ArrayList<Float>()
        drawings.forEachIndexed { i, d ->
            val top = (4 + i * (4 + gap)) * space
            tops += top
            Engraver.paint(ink, d, space, 2 * space, top)
        }
        return Page(ink, lines, space, tops)
    }

    fun save(ink: Ink, file: File) {
        val img = java.awt.image.BufferedImage(ink.width, ink.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, ink.width, ink.height, ink.argb(), 0, ink.width)
        file.parentFile.mkdirs()
        javax.imageio.ImageIO.write(img, "png", file)
    }

    /** Where pictures from these tests go: -Dinksheets.shots, or nowhere. */
    fun shot(name: String, ink: Ink) {
        val dir = System.getProperty("inksheets.shots") ?: return
        save(ink, File(dir, "$name.png"))
    }
}
