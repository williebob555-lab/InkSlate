package com.inksheets.desktop

import com.inksheets.core.omr.Digits
import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.MusicGlyphs
import com.inksheets.core.omr.Fill
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File

/**
 * Makes sheets-core's omr/digits.txt: the digits 0-9 in the typefaces parts print their numbers
 * in (serif and sans, plain and bold, italic for tuplets; the music font's own), each cut to its
 * outline and shrunk to [Digits.W] x [Digits.H]. Run by hand: -Dinksheets.digits=make.
 */
class DigitMasksTool {
    @Test
    fun `make the digit masks`() {
        assumeTrue(System.getProperty("inksheets.digits") == "make")
        val out = StringBuilder("# Digits 0-9 as ${Digits.W}x${Digits.H} masks: typeface digit bits (made by DigitMasksTool)\n")
        val faces = listOf("Serif", "SansSerif", "Times New Roman", "Arial", "Georgia", "Palatino Linotype", "Century Schoolbook", "Book Antiqua", "Tahoma", "Verdana")
        for (face in faces) for (style in listOf(Font.PLAIN, Font.BOLD, Font.ITALIC, Font.BOLD or Font.ITALIC)) {
            val font = Font(face, style, 120)
            if (font.family != face && face !in listOf("Serif", "SansSerif")) continue
            for (d in 0..9) {
                val img = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
                g.color = java.awt.Color.WHITE; g.fillRect(0, 0, 200, 200)
                g.color = java.awt.Color.BLACK; g.font = font
                g.drawString(d.toString(), 40, 150)
                g.dispose()
                val px = IntArray(200 * 200); img.getRGB(0, 0, 200, 200, px, 0, 200)
                val ink = Ink(200, 200, BooleanArray(px.size) { (px[it] and 0xFF) < 128 })
                out.append("$face/$style $d ").append(Digits.mask(ink, 0, 0, 199, 199)?.joinToString("") { if (it) "1" else "0" } ?: continue).append('\n')
            }
        }
        // The music font's own time-signature digits.
        for (d in 0..9) {
            val g = MusicGlyphs["timeSig$d"]
            val ink = Ink(200, 200)
            Fill.polygons(ink, g.polygons(50f, 40f, 100f))
            out.append("Bravura $d ").append(Digits.mask(ink, 0, 0, 199, 199)?.joinToString("") { if (it) "1" else "0" } ?: continue).append('\n')
        }
        val file = File("../sheets-core/src/main/resources/omr/digits.txt")
        file.parentFile.mkdirs()
        file.writeText(out.toString())
        println("wrote ${out.lines().size - 2} masks to ${file.canonicalPath}")
    }
}
