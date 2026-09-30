package com.inksheets.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The music-font characters the reader does not name yet (articulations, dynamics, fermatas,
 * ornaments...): each font family's codes, how often each is printed and a picture of it, to tell
 * them by what they are rather than by guessing at a font's layout.
 * -Dinksheets.omr=marks, -Dinksheets.shots=dir (a sheet of each character's first few prints).
 */
class MarkSurvey {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    data class Seen(val family: String, val text: String, var count: Int = 0, val pictures: MutableList<BufferedImage> = ArrayList())

    @Test
    fun `characters not named yet`() {
        assumeTrue(System.getProperty("inksheets.omr") == "marks")
        val parts = music.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) && !it.path.contains(".inksheets") && !it.name.contains("score", true) }.toList()
            .sortedBy { java.util.zip.CRC32().apply { update(it.path.toByteArray()) }.value }.take(120)
        val seen = LinkedHashMap<String, Seen>()
        for (f in parts) runCatching {
            Loader.loadPDF(f).use { doc ->
                if (doc.numberOfPages == 0) return@use
                val dpi = 150f; val k = dpi / 72f
                var picture: BufferedImage? = null
                object : PDFTextStripper() {
                    override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
                        for (p in positions.orEmpty()) {
                            val font = p.font ?: continue
                            val name = font.name ?: continue
                            val code = p.characterCodes?.firstOrNull() ?: continue
                            val uni = runCatching { font.toUnicode(code) }.getOrNull() ?: continue
                            if (uni.codePointCount(0, uni.length) != 1) continue
                            val special = com.inksheets.core.omr.Printed.isSpecialFont(name)
                            if (!com.inksheets.core.omr.Printed.isMusicFont(name) && !special) continue
                            val kind = com.inksheets.core.omr.Printed.kindOf(uni.codePointAt(0))
                            if (!special && kind != com.inksheets.core.omr.Printed.Kind.OTHER) continue
                            val family = name.substringAfter('+').replace(Regex("(Std|-?Regular|Pro)$"), "")
                            // A special font's codes are renumbered per document: keyed by the file too.
                            val key = if (special) "$family:${f.name}:$code" else "$family:${uni.codePointAt(0)}"
                            val s = seen.getOrPut(key) { Seen(family, if (special) "code $code (${f.name.take(20)})" else "U+%04X '%s'".format(uni.codePointAt(0), uni)) }
                            s.count++
                            if (s.pictures.size < 4) {
                                val img = picture ?: PDFRenderer(doc).renderImageWithDPI(0, dpi, ImageType.RGB).also { picture = it }
                                if (p.pageIndex() == 0) s.pictures += crop(img, p.xDirAdj * k, p.yDirAdj * k, p.textMatrix.scalingFactorX * k)
                            }
                        }
                    }
                    private fun TextPosition.pageIndex() = currentPageNo - 1
                }.apply { startPage = 1; endPage = 1; sortByPosition = false }.getText(doc)
            }
        }
        val list = seen.values.sortedByDescending { it.count }
        list.take(80).forEach { println("MARK ${it.family} ${it.text} x${it.count}") }
        System.getProperty("inksheets.shots")?.let { dir ->
            val rows = list.filter { it.pictures.isNotEmpty() }.take(60)
            val cell = 90
            val img = BufferedImage(cell * 5 + 260, rows.size * cell, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color.WHITE; g.fillRect(0, 0, img.width, img.height)
            rows.forEachIndexed { r, s ->
                g.color = Color.BLACK; g.font = g.font.deriveFont(11f)
                g.drawString("${s.family} ${s.text} x${s.count}".take(40), 4, r * cell + 20)
                s.pictures.forEachIndexed { i, p -> g.drawImage(p, 260 + i * cell, r * cell, cell - 4, cell - 4, null) }
            }
            g.dispose()
            File(dir).mkdirs(); ImageIO.write(img, "png", File(dir, "marks.png"))
        }
    }

    private fun crop(img: BufferedImage, x: Float, y: Float, size: Float): BufferedImage {
        val half = (size * 0.9f).toInt().coerceIn(12, 80)
        val out = BufferedImage(half * 2, half * 2, BufferedImage.TYPE_INT_RGB)
        for (yy in 0 until half * 2) for (xx in 0 until half * 2) {
            val sx = (x - half / 2 + xx).toInt(); val sy = (y - half * 1.3f + yy).toInt()
            out.setRGB(xx, yy, if (sx in 0 until img.width && sy in 0 until img.height) img.getRGB(sx, sy) else 0xFFFFFF)
        }
        return out
    }
}
