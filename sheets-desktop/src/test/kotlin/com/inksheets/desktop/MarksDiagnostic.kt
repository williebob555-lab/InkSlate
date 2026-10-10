package com.inksheets.desktop

import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Printed
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Strips
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Which articulations, slurs and dynamics the reader gets from a vector PDF, bar by bar, read the way
 * the app does (the PDF's own symbols). Needs -Dinksheets.marks=1; optional
 * -Dinksheets.marks.file=MobileSheets/Trombone Copprash.pdf and -Dinksheets.marks.pages=0,1.
 * Also writes the page pictures to build/marks/ to look at. The library is only read.
 */
class MarksDiagnostic {
    @Test
    fun `marks read per bar`() {
        assumeTrue(System.getProperty("inksheets.marks") != null)
        val rel = System.getProperty("inksheets.marks.file") ?: "MobileSheets/Trombone Copprash.pdf"
        val file = File(System.getenv("USERPROFILE"), "Music/Sheet Music/InkSheets/$rel")
        val source = com.inkslate.desktop.DesktopSources.open(file, detached = true)!!
        val pages = System.getProperty("inksheets.marks.pages")?.split(",")?.map { it.trim().toInt() } ?: (0 until source.pageCount).toList()
        val out = File("build/marks").apply { mkdirs() }
        val carry = Recognizer.Carry(); var number = 1
        for (p in pages) {
            fun draw(width: Int): Pair<IntArray, Ink> {
                val img = source.render(p, width)!!
                val px = IntArray(img.width * img.height); img.readPixels(px)
                return Strips.grey(px) to Ink.fromArgb(img.width, img.height, px)
            }
            val space = Recognizer().metrics(draw(1600).second)?.second
            val width = if (space == null || space <= 0f) 1600 else (1600 * 18f / space).toInt().coerceIn(1000, 5000)
            val (grey, ink) = draw(width)
            val printed = PdfPrinted.read(file, p)
            println("MARKS page $p width $width printed=${printed != null} arcs=${printed?.arcs?.size} lines=${printed?.lines?.size} " +
                "artic=${printed?.symbols?.filter { it.kind == Printed.Kind.ARTICULATION }?.groupingBy { it.name }?.eachCount()} dyn=${printed?.symbols?.filter { it.kind == Printed.Kind.DYNAMIC }?.map { it.name }} " +
                "dots=${printed?.symbols?.count { it.kind == Printed.Kind.DOT }}")
            val r = Recognizer().read(ink, p, number, carry, printed, grey = grey, net = if (printed == null) com.inksheets.core.omr.Net.shipped else null)
            number += r.measures.size
            val img = source.render(p, 1400)!!; val px = IntArray(img.width * img.height); img.readPixels(px)
            val bi = java.awt.image.BufferedImage(img.width, img.height, java.awt.image.BufferedImage.TYPE_INT_ARGB); bi.setRGB(0, 0, img.width, img.height, px, 0, img.width)
            javax.imageio.ImageIO.write(bi, "png", File(out, "${file.nameWithoutExtension.replace(' ', '_')}-p$p.png"))
            for (m in r.measures) {
                val notes = m.events.map { e ->
                    if (e is Note) "${e.steps.joinToString("+")}/${e.duration.base}${if (e.articulations.isEmpty()) "" else e.articulations.joinToString(",", "[", "]")}${if (e.tie) "~" else ""}@${e.x.toInt()}"
                    else "r${e.duration.base}@${e.x.toInt()}"
                }
                println("MARKS p$p staff ${m.staff} bar ${m.number} x ${m.box.left}-${m.box.right}: $notes")
                if (m.directions.isNotEmpty()) println("MARKS    dirs ${m.directions.joinToString { "${it.kind}:${it.text}@${it.x.toInt()}..${it.x2.toInt()}${if (it.kind == "slur") " s${it.step}/${it.step2}" else ""}" }}")
            }
        }
    }
}
