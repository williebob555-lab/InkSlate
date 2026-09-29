package com.inksheets.desktop

import com.inksheets.core.omr.Event
import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Rest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The music reader on real parts from the music folder: each page drawn so a staff space is about
 * 18 pixels, read, and drawn over for looking at (-Dinksheets.shots). Run by hand:
 * -Dinksheets.omr=real.
 */
class OmrRealPagesTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    /** [file]'s page [index], drawn so a staff space is about [space] pixels. */
    fun render(file: File, index: Int, space: Float = 18f): Ink? {
        Loader.loadPDF(file).use { doc ->
            if (index >= doc.numberOfPages) return null
            val r = PDFRenderer(doc)
            fun at(dpi: Float): Ink {
                val img = r.renderImageWithDPI(index, dpi, ImageType.RGB)
                val px = IntArray(img.width * img.height)
                img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
                return Ink.fromArgb(img.width, img.height, px)
            }
            val first = at(100f)
            val m = Recognizer().metrics(first) ?: return first
            return at(100f * space / m.second)
        }
    }

    private fun describe(e: Event) = when (e) {
        is Note -> e.pitches.joinToString("+") + "/" + e.duration.base + ".".repeat(e.duration.dots)
        is Rest -> "r/" + e.duration.base
    }

    private fun overlay(ink: Ink, reading: Recognizer.PageReading, out: File) {
        val img = BufferedImage(ink.width, ink.height, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, ink.width, ink.height, ink.argb(), 0, ink.width)
        val g = img.createGraphics()
        g.stroke = BasicStroke(2f)
        g.font = g.font.deriveFont(11f)
        for ((si, s) in reading.staves.withIndex()) {
            g.color = Color(0, 160, 255)
            g.drawRect(s.left, s.top, s.right - s.left, s.bottom - s.top)
            g.color = Color.RED
            for (b in reading.barlines[si]) g.drawLine(b, s.top - 8, b, s.bottom + 8)
        }
        for (m in reading.measures) {
            g.color = if (m.sure) Color(0, 150, 0) else Color(220, 110, 0)
            g.drawString("${m.number}", m.box.left + 3, m.box.top - 4)
            var row = 0
            for (e in m.events) {
                g.drawString(describe(e), e.x.toInt(), m.box.bottom + (m.space * 2.5f).toInt() + 12 * (row++ % 2))
            }
        }
        g.dispose()
        out.parentFile.mkdirs()
        ImageIO.write(img, "png", out)
    }

    private fun read(relative: String, pages: Int = 1) {
        val file = File(music, relative)
        assumeTrue("no $relative", file.isFile)
        val shots = System.getProperty("inksheets.shots")
        for (p in 0 until pages) {
            val ink = runCatching { render(file, p) }.onFailure { println("${file.name}: cannot open - ${it.message}") }.getOrNull() ?: break
            val t0 = System.nanoTime()
            val reading = Recognizer().read(ink, p)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val sure = reading.measures.count { it.sure }
            println("${file.name} p${p + 1}: ${ink.width}x${ink.height}, space ${"%.1f".format(reading.space)}, line ${reading.thickness}, " +
                "${reading.staves.size} staves, ${reading.measures.size} measures, $sure sure, ${reading.measures.sumOf { it.events.size }} events, $ms ms")
            reading.measures.filter { !it.sure }.forEach { println("   m${it.number}: ${it.doubts} ${it.events.map(::describe)}") }
            if (shots != null) overlay(ink, reading, File(shots, "real-${file.nameWithoutExtension}-p${p + 1}.png"))
        }
    }

    @Test
    fun `reads real parts`() {
        assumeTrue(System.getProperty("inksheets.omr") == "real")
        read("Imported/PEP BAND/Music/Sweet Caroline/SweetC - Trumpet 1.pdf", 2)
        read("Imported/PEP BAND/Music/September/September - Alto Sax 1.pdf")
        read("Imported/PEP BAND/Music/September/September - Tuba.pdf")
        music.resolve("MobileSheets").listFiles { f -> f.extension.equals("pdf", true) }?.sortedBy { it.name }?.take(3)?.forEach {
            read("MobileSheets/${it.name}")
        }
    }
}
