package com.inksheets.desktop

import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Strips
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File

/** Dev aid: dumps zoomed crops of Copprash page 3 (grey and ink side by side). -Dinksheets.dump=x0,y0,x1,y1,zoom,out.png;... */
class CopprashDump {
    @Test
    fun dump() {
        val spec = System.getProperty("inksheets.dump") ?: return
        val file = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/" + (System.getProperty("inksheets.dump.file") ?: "MobileSheets/Trombone Copprash.pdf"))
        assumeTrue(file.isFile)
        val source = com.inkslate.desktop.DesktopSources.open(file, detached = true)!!
        fun draw(width: Int): Pair<IntArray, Ink> {
            val img = source.render((System.getProperty("inksheets.dump.page") ?: "3").toInt(), width)!!
            val px = IntArray(img.width * img.height); img.readPixels(px)
            return Strips.grey(px) to Ink.fromArgb(img.width, img.height, px)
        }
        val space = Recognizer().metrics(draw(1600).second)?.second
        val width = if (space == null || space <= 0f) 1600 else (1600 * 18f / space).toInt().coerceIn(1000, 5000)
        val (grey, ink) = draw(width)
        println("DUMP page ${ink.width}x${ink.height}")
        for (one in spec.split(";")) {
            val p = one.split(",")
            val x0 = p[0].toInt(); val y0 = p[1].toInt(); val x1 = p[2].toInt(); val y1 = p[3].toInt(); val z = p[4].toInt()
            val w = x1 - x0; val h = y1 - y0
            val img = BufferedImage(w * z * 2 + 10, h * z, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until h * z) for (x in 0 until w * z) {
                val sx = x0 + x / z; val sy = y0 + y / z
                val g = grey[sy * ink.width + sx]
                img.setRGB(x, y, g * 0x10101)
                val c = if (ink[sx, sy]) 0 else 255
                img.setRGB(x + w * z + 10, y, c * 0x10101)
            }
            javax.imageio.ImageIO.write(img, "png", File(p[5]))
        }
    }
}
