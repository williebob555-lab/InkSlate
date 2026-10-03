package com.inksheets.desktop

import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Strips
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The library's own files read as the app reads them - each page drawn so a space is 18 pixels, the
 * trained reader on scans - and what came of it: bars, sure, and the doubts most often had. For
 * seeing a change on the real parts it is meant for. -Dinksheets.survey.files=<path>|<path> (under the library)
 */
class RealFilesSurvey {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    @Test
    fun `real files read`() {
        val files = System.getProperty("inksheets.survey.files")?.split('|')?.map { File(music, it.trim()) } ?: return assumeTrue(false)
        for (file in files) {
            if (!file.isFile) { println("SURVEY ${file.name}: not here"); continue }
            val source = com.inkslate.desktop.DesktopSources.open(file, detached = true) ?: continue
            var bars = 0; var sure = 0
            val doubts = HashMap<String, Int>()
            var number = 1
            val carry = Recognizer.Carry()
            for (p in 0 until source.pageCount) {
                fun draw(width: Int): Pair<IntArray, Ink>? {
                    val img = source.render(p, width) ?: return null
                    val px = IntArray(img.width * img.height); img.readPixels(px)
                    return Strips.grey(px) to Ink.fromArgb(img.width, img.height, px)
                }
                val first = draw(1600) ?: continue
                val space = Recognizer().metrics(first.second)?.second
                val width = if (space == null || space <= 0f) 1600 else (1600 * 18f / space).toInt().coerceIn(1000, 5000)
                val (grey, ink) = draw(width) ?: continue
                val printed = if (System.getProperty("inksheets.survey.scan") != null) null else runCatching { PdfPrinted.read(file, p) }.getOrNull()
                val r = Recognizer().read(ink, p, number, carry, printed, grey = grey, net = if (printed == null) com.inksheets.core.omr.Net.shipped else null)
                r.measures.lastOrNull()?.let { number = it.number + it.bars }
                println("  PAGE ${file.name} p${p + 1}: ${r.staves.size} staves " + r.staves.joinToString(" ") { "${it.lineY(0, (it.left + it.right) / 2).toInt()}[${it.left}..${it.right}]" })
                bars += r.measures.size; sure += r.measures.count { it.sure }
                for (m in r.measures) for (d in m.doubts) doubts.merge(d.replace(Regex("[0-9.]+"), "#"), 1, Int::plus)
                for (m in r.measures) for (d in m.directions) if (d.kind == "breath") {
                    doubts.merge("(breath marks)", 1, Int::plus); println("  BREATH ${file.name} p${p + 1} bar ${m.number} at ${d.x.toInt()}")
                    System.getProperty("inksheets.survey.crops")?.let { dir -> File(dir).mkdirs()
                        val st = r.staves.getOrNull(m.staff) ?: return@let
                        val x0 = (d.x - st.space * 5).toInt().coerceAtLeast(0); val x1 = (d.x + st.space * 5).toInt().coerceAtMost(ink.width - 1)
                        val y0 = (st.lineY(0, d.x.toInt()) - st.space * 4).toInt().coerceAtLeast(0); val y1 = (st.lineY(4, d.x.toInt()) + st.space * 2).toInt().coerceAtMost(ink.height - 1)
                        val img = java.awt.image.BufferedImage(x1 - x0, y1 - y0, java.awt.image.BufferedImage.TYPE_INT_RGB)
                        for (y in y0 until y1) for (x in x0 until x1) img.setRGB(x - x0, y - y0, if (ink[x, y]) 0 else 0xFFFFFF)
                        javax.imageio.ImageIO.write(img, "png", File(dir, "${file.nameWithoutExtension.take(12)}-p${p + 1}-b${m.number}-${d.x.toInt()}.png"))
                    }
                }
            }
            println("SURVEY ${file.name}: $bars bars, $sure sure; " + doubts.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key} x${it.value}" })
        }
    }
}
