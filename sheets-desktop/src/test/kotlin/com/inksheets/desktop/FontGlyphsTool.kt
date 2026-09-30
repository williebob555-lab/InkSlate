package com.inksheets.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The music fonts the library is printed in, learned from the library: every character of Opus,
 * Helsinki, Maestro, Leland and Bravura that a PDF names properly, its outline taken from the font
 * embedded there - written to sheets-core/src/main/resources/omr/fontglyphs.txt, where a PDF whose
 * font does not name its characters (a subset renumbering them) has them told by matching shapes
 * against these. Only the tuning songs are read (the benchmark's split): the held-out ones check it.
 * A picture of every glyph with its code to -Dinksheets.shots, to see what each one is.
 * `-Dinksheets.glyphs=fonts`
 */
class FontGlyphsTool {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    private fun family(name: String) = name.substringAfter('+').lowercase().let { n ->
        listOf("opus", "helsinki", "finalemaestro", "maestro", "leland", "bravura", "petrucci").firstOrNull { n.contains(it) }
    }

    private fun dev(f: File): Boolean {
        val parts = f.relativeTo(music).path.replace('\\', '/').split('/')
        val i = parts.indexOf("Music")
        val song = (if (i >= 0 && i + 2 < parts.size) parts[i + 1] else f.nameWithoutExtension.substringBefore(" - ")).lowercase().replace(Regex("[^a-z0-9]"), "")
        return java.util.zip.CRC32().apply { update(song.toByteArray()) }.value % 3 == 0L
    }

    /**
     * The characters no PDF names - the "special" fonts' (Opus Special, Helsinki Special: dynamics,
     * dots, marks) and the codes a subset renumbered - every distinct shape among the tuning songs,
     * with how often it is printed: to sheets-core/src/main/resources/omr/fontglyphs-unnamed.txt,
     * each with a label ("?" until it is given one by looking at the picture of them), and a picture
     * of them all numbered. Labels already given are kept. `-Dinksheets.glyphs=unnamed`
     */
    @Test
    fun `collect the characters no PDF names`() {
        assumeTrue(System.getProperty("inksheets.glyphs") == "unnamed")
        val shapes = GlyphShapesTest()
        val target = File(System.getProperty("user.dir")).parentFile.resolve("sheets-core/src/main/resources/omr/fontglyphs-unnamed.txt")
        // What was labelled before, by family and outline.
        val labels = HashMap<String, String>()
        if (target.isFile) target.readLines().filter { !it.startsWith("#") && it.isNotBlank() }.forEach { l -> val p = l.split(' '); if (p.size >= 4) labels[p[0] + " " + p[3]] = p[1] }
        val parts = music.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) && !it.path.contains(".inksheets") && !it.name.contains("score", true) && dev(it) }.toList()
        data class Found(val family: String, val outline: String, val contours: List<FloatArray>, var count: Int)
        val found = LinkedHashMap<String, Found>()
        fun n(v: Float) = "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
        for (f in parts) runCatching {
            Loader.loadPDF(f).use { doc ->
                object : PDFTextStripper() {
                    override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
                        for (p in positions.orEmpty()) {
                            val font = p.font ?: continue
                            val name = font.name ?: continue
                            val special = name.contains("special", true)
                            val fam = (family(name) ?: continue) + if (special) "-special" else ""
                            if (name.contains("text", true)) continue
                            val code = p.characterCodes?.firstOrNull() ?: continue
                            if (!special) {
                                // A main font's character named properly is learned elsewhere.
                                val uni = runCatching { font.toUnicode(code) }.getOrNull()
                                if (uni != null && uni.codePointCount(0, uni.length) == 1 && uni.codePointAt(0) >= 0x21 && uni.codePointAt(0) != 0xFFFD) continue
                            }
                            val c = shapes.outline(font, code) ?: continue
                            // The same shape wherever it is printed: its outline, rounded, is its name.
                            val key = c.joinToString("|") { k -> k.joinToString(",") { n(it) } }
                            found.getOrPut("$fam $key") { Found(fam, key, c, 0) }.count++
                        }
                    }
                }.apply { startPage = 1; endPage = doc.numberOfPages.coerceAtMost(3) }.getText(doc)
            }
        }
        val list = found.values.sortedWith(compareBy({ it.family }, { -it.count }))
        val out = StringBuilder("# Characters no PDF names, by shape (FontGlyphsTool): family label count outline (staff spaces, y down). Label ? = not yet told.\n")
        list.forEachIndexed { i, g -> out.append(g.family).append(' ').append(labels[g.family + " " + g.outline] ?: "?").append(' ').append(g.count).append(' ').append(g.outline).append('\n') }
        target.writeText(out.toString())
        println("UNNAMED: ${list.size} shapes (" + list.groupingBy { it.family }.eachCount() + "), ${list.count { labels.containsKey(it.family + " " + it.outline) }} labelled")
        System.getProperty("inksheets.shots")?.let { dir ->
            val cell = 120; val cols = 8
            val img = BufferedImage(cols * cell, ((list.size + cols - 1) / cols) * cell, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color.WHITE; g.fillRect(0, 0, img.width, img.height)
            list.forEachIndexed { i, s ->
                val x0 = (i % cols) * cell; val y0 = (i / cols) * cell
                g.color = Color(225, 225, 225); g.drawRect(x0, y0, cell - 1, cell - 1)
                g.color = Color.RED; g.font = g.font.deriveFont(12f); g.drawString("#$i ${s.family.take(12)} x${s.count}", x0 + 3, y0 + 13)
                val c = s.contours
                val minX = c.minOf { k -> (k.indices step 2).minOf { k[it] } }; val minY = c.minOf { k -> (1 until k.size step 2).minOf { k[it] } }
                val maxX = c.maxOf { k -> (k.indices step 2).maxOf { k[it] } }; val maxY = c.maxOf { k -> (1 until k.size step 2).maxOf { k[it] } }
                val sc = minOf(90f / maxOf(0.3f, maxX - minX), 85f / maxOf(0.3f, maxY - minY), 25f)
                val path = Path2D.Float(Path2D.WIND_NON_ZERO)
                for (k in c) { path.moveTo(x0 + 12 + (k[0] - minX) * sc, y0 + 22 + (k[1] - minY) * sc); var j = 2; while (j + 1 < k.size) { path.lineTo(x0 + 12 + (k[j] - minX) * sc, y0 + 22 + (k[j + 1] - minY) * sc); j += 2 }; path.closePath() }
                g.color = Color.BLACK; g.fill(path)
            }
            g.dispose()
            File(dir).mkdirs(); ImageIO.write(img, "png", File(dir, "unnamed.png"))
        }
    }

    @Test
    fun `learn the library's music fonts`() {
        assumeTrue(System.getProperty("inksheets.glyphs") == "fonts")
        val shapes = GlyphShapesTest()
        val parts = music.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) && !it.path.contains(".inksheets") && !it.name.contains("score", true) && dev(it) }.toList()
        // family -> code point -> outline (staff spaces, y down)
        val found = java.util.TreeMap<String, java.util.TreeMap<Int, List<FloatArray>>>()
        for (f in parts) runCatching {
            Loader.loadPDF(f).use { doc ->
                object : PDFTextStripper() {
                    override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
                        for (p in positions.orEmpty()) {
                            val font = p.font ?: continue
                            val fam = family(font.name ?: continue) ?: continue
                            if (font.name.contains("text", true) || font.name.contains("special", true)) continue
                            val code = p.characterCodes?.firstOrNull() ?: continue
                            val uni = runCatching { font.toUnicode(code) }.getOrNull() ?: continue
                            if (uni.codePointCount(0, uni.length) != 1) continue
                            val cp = uni.codePointAt(0)
                            if (cp < 0x21 || cp == 0xFFFD) continue   // a control code or unknown: what is to be learned, not learned from
                            val byFont = found.getOrPut(fam) { java.util.TreeMap() }
                            if (cp in byFont) continue
                            shapes.outline(font, code)?.let { byFont[cp] = it }
                        }
                    }
                }.apply { startPage = 1; endPage = doc.numberOfPages.coerceAtMost(3) }.getText(doc)
            }
        }
        val out = StringBuilder("# Music-font glyphs learned from the library's own PDFs (FontGlyphsTool): family code outline, in staff spaces, y down\n")
        fun n(v: Float) = "%.3f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
        for ((fam, glyphs) in found) for ((cp, contours) in glyphs) {
            out.append(fam).append(' ').append("%04X".format(cp)).append(' ')
            out.append(contours.joinToString("|") { c -> c.joinToString(",") { n(it) } }).append('\n')
        }
        val target = File(System.getProperty("user.dir")).parentFile.resolve("sheets-core/src/main/resources/omr/fontglyphs.txt")
        target.writeText(out.toString())
        println("FONTS: " + found.entries.joinToString { "${it.key} ${it.value.size}" } + " glyphs; ${target.length() / 1024} KB")
        // Every glyph drawn with its code, to see what each one is.
        System.getProperty("inksheets.shots")?.let { dir ->
            for ((fam, glyphs) in found) {
                val cell = 110; val cols = 10
                val img = BufferedImage(cols * cell, ((glyphs.size + cols - 1) / cols) * cell, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color.WHITE; g.fillRect(0, 0, img.width, img.height)
                glyphs.entries.forEachIndexed { i, (cp, contours) ->
                    val x0 = (i % cols) * cell; val y0 = (i / cols) * cell
                    g.color = Color(230, 230, 230); g.drawRect(x0, y0, cell - 1, cell - 1)
                    g.color = Color.RED; g.font = g.font.deriveFont(11f); g.drawString("%04X %s".format(cp, String(Character.toChars(cp))), x0 + 3, y0 + 12)
                    val minX = contours.minOf { c -> (c.indices step 2).minOf { c[it] } }; val minY = contours.minOf { c -> (1 until c.size step 2).minOf { c[it] } }
                    val maxX = contours.maxOf { c -> (c.indices step 2).maxOf { c[it] } }; val maxY = contours.maxOf { c -> (1 until c.size step 2).maxOf { c[it] } }
                    val k = minOf(80f / maxOf(0.3f, maxX - minX), 80f / maxOf(0.3f, maxY - minY), 20f)
                    val path = Path2D.Float(Path2D.WIND_NON_ZERO)
                    for (c in contours) { path.moveTo(x0 + 15 + (c[0] - minX) * k, y0 + 20 + (c[1] - minY) * k); var j = 2; while (j + 1 < c.size) { path.lineTo(x0 + 15 + (c[j] - minX) * k, y0 + 20 + (c[j + 1] - minY) * k); j += 2 }; path.closePath() }
                    g.color = Color.BLACK; g.fill(path)
                }
                g.dispose()
                File(dir).mkdirs(); ImageIO.write(img, "png", File(dir, "font-$fam.png"))
            }
        }
    }
}
