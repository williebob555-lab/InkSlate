package com.inksheets.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.PathIterator
import java.io.File

/**
 * Writes the music symbols' outlines, from the Bravura font (SIL OFL), into
 * sheets-core/src/main/resources/omr/glyphs.txt - which recognition rasterizes as templates and
 * the redrawn measures are drawn from. Run by hand when the list changes:
 * `gradlew :sheets-desktop:test --tests *GlyphOutlinesTool* -Dinksheets.glyphs=write`.
 *
 * Units are staff spaces (a SMuFL em is four), y downwards, origin on the glyph's baseline -
 * which for a notehead is its middle and for a clef is the line it names.
 */
class GlyphOutlinesTool {
    private val glyphs = listOf(
        "gClef" to 0xE050, "fClef" to 0xE062, "cClef" to 0xE05C,
        "noteheadBlack" to 0xE0A4, "noteheadHalf" to 0xE0A3, "noteheadWhole" to 0xE0A2,
        "accidentalFlat" to 0xE260, "accidentalNatural" to 0xE261, "accidentalSharp" to 0xE262,
        "restWhole" to 0xE4E3, "restHalf" to 0xE4E4, "restQuarter" to 0xE4E5, "rest8th" to 0xE4E6, "rest16th" to 0xE4E7,
        "flag8thUp" to 0xE240, "flag8thDown" to 0xE241, "flag16thUp" to 0xE242, "flag16thDown" to 0xE243,
        "augmentationDot" to 0xE1E7,
        "timeSigCommon" to 0xE08A, "timeSigCutCommon" to 0xE08B
    ) + (0..9).map { "timeSig$it" to 0xE080 + it }

    @Test
    fun `write the glyph outlines`() {
        assumeTrue(System.getProperty("inksheets.glyphs") == "write")
        val font = Font.createFont(Font.TRUETYPE_FONT, javaClass.getResourceAsStream("/Bravura.otf")).deriveFont(1000f)
        val frc = FontRenderContext(null, true, true)
        val em = 1000.0
        val perSpace = em / 4
        val out = StringBuilder("# Bravura (SIL Open Font License 1.1, see BRAVURA-LICENSE.txt), outlines in staff spaces\n")
        for ((name, cp) in glyphs) {
            val gv = font.createGlyphVector(frc, String(Character.toChars(cp)))
            val shape = gv.getGlyphOutline(0)
            val advance = gv.getGlyphMetrics(0).advance / perSpace
            val it = shape.getPathIterator(AffineTransform.getScaleInstance(1 / perSpace, 1 / perSpace))
            val c = DoubleArray(6)
            val path = StringBuilder()
            fun n(v: Double) = "%.3f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
            while (!it.isDone) {
                when (it.currentSegment(c)) {
                    PathIterator.SEG_MOVETO -> path.append("M${n(c[0])},${n(c[1])} ")
                    PathIterator.SEG_LINETO -> path.append("L${n(c[0])},${n(c[1])} ")
                    PathIterator.SEG_QUADTO -> path.append("Q${n(c[0])},${n(c[1])},${n(c[2])},${n(c[3])} ")
                    PathIterator.SEG_CUBICTO -> path.append("C${n(c[0])},${n(c[1])},${n(c[2])},${n(c[3])},${n(c[4])},${n(c[5])} ")
                    PathIterator.SEG_CLOSE -> path.append("Z ")
                }
                it.next()
            }
            out.append(name).append(' ').append(n(advance)).append(' ').append(path.toString().trim()).append('\n')
        }
        val target = File(System.getProperty("user.dir")).parentFile.resolve("sheets-core/src/main/resources/omr/glyphs.txt")
        target.writeText(out.toString())
        println("wrote ${glyphs.size} glyphs, ${target.length()} bytes, to $target")
    }
}
