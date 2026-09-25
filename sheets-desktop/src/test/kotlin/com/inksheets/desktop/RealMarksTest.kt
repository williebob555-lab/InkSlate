package com.inksheets.desktop

import com.inksheets.core.MobileSheetsImport
import com.inksheets.core.MobileSheetsMarks
import com.inksheets.ui.ImportedInkForTests
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.io.File
import javax.imageio.ImageIO

/**
 * A real MobileSheets database's markings, read and drawn over the real pages - read only:
 *
 *     ./gradlew :sheets-desktop:test --tests '*RealMarks*' -Dinksheets.msdb="C:/.../mobilesheets.db"
 *         -Dinksheets.lib="C:/.../InkSheets" -Dinksheets.shots=C:/somewhere
 *
 * Pictures go to the shots folder. Skipped unless a database and a library are named.
 */
class RealMarksTest {

    @Test
    fun `real markings land on the real pages`() {
        val db = System.getProperty("inksheets.msdb")?.let(::File)
        val lib = System.getProperty("inksheets.lib")?.let(::File)
        assumeTrue(db != null && db.isFile && lib != null && lib.isDirectory)
        val tables = DesktopSheetsPlatform {}.openMobileSheets(db!!)!!
        val byName = lib!!.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile }.groupBy { it.name.lowercase() }
        val unresolved = HashSet<String>()
        val marks = MobileSheetsMarks.read(tables) { path ->
            val found = byName[path.substringAfterLast('/').lowercase()]?.firstOrNull()
            if (found == null) unresolved += path
            found?.relativeTo(lib)?.invariantSeparatorsPath
        }
        val all = marks.values.flatten()
        println("Markings: ${all.size} on ${marks.size} files; by kind ${all.groupingBy { it.kind }.eachCount()}; files not found ${unresolved.size}")
        unresolved.take(5).forEach { println("  not found: $it") }
        val outside = all.count { m -> m.points.any { it < -0.05f || it > 1.05f } }
        println("Markings reaching off their page: $outside")

        val shots = System.getProperty("inksheets.shots")?.let(::File) ?: return
        shots.mkdirs()
        marks.entries.take(4).forEach { (rel, list) ->
            val file = File(lib, rel)
            org.apache.pdfbox.Loader.loadPDF(file).use { pdf ->
                val renderer = org.apache.pdfbox.rendering.PDFRenderer(pdf)
                list.groupBy { it.page }.entries.take(2).forEach { (page, onPage) ->
                    if (page >= pdf.numberOfPages) { println("  $rel: page ${page + 1} of ${pdf.numberOfPages}?"); return@forEach }
                    val box = pdf.getPage(page).cropBox
                    val scale = 1.5f
                    val image = renderer.renderImage(page, scale)
                    val g = image.createGraphics()
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val strokes = ImportedInkForTests.strokes(onPage) { box.width to box.height }
                    for (s in strokes) {
                        g.color = Color(s.color, true).let { if (it.alpha == 0) Color.BLACK else it }
                        g.stroke = BasicStroke(maxOf(1.5f, s.baseWidth * scale))
                        if (s.text != null) {
                            g.color = Color(200, 0, 0)
                            g.font = g.font.deriveFont(s.textSize * scale)
                            g.drawString(s.text, s.points[0].x * scale, (s.points[0].y + s.textSize) * scale)
                        } else {
                            s.points.zipWithNext().forEach { (a, b) ->
                                g.drawLine((a.x * scale).toInt(), (a.y * scale).toInt(), (b.x * scale).toInt(), (b.y * scale).toInt())
                            }
                        }
                    }
                    g.dispose()
                    val name = rel.substringAfterLast('/').substringBeforeLast('.') + "-p${page + 1}.png"
                    ImageIO.write(image, "png", File(shots, name))
                    println("  drew ${strokes.size} on $name (page ${box.width}x${box.height})")
                }
            }
        }
    }
}
