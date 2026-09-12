package com.inkslate.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.inkslate.core.Box
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rendering part of a page, against documents nobody here wrote.
 *
 * This exists because the test that was supposed to guard this passed while every page in a real
 * document came out black. It rendered a page this program had generated itself - a few ruled
 * lines - and a piece of it matched the whole of it, which proved only that simple pages work.
 * The library call that fails does so on real documents: for a page using transparency it returns
 * a black rectangle rather than raising anything, so nothing threw and nothing fell back.
 *
 * Point it at a folder of real documents to run it:
 *
 *     ./gradlew :desktop:test -Dinkslate.pdfs="C:/Users/me/Documents"
 *
 * Skipped without one, because a test that needs somebody's homework cannot be a test everyone
 * runs. It is a thing to run before releasing, not a thing the build proves.
 */
class RealDocumentRenderTest {

    private fun brightness(image: ImageBitmap, part: Box? = null): Double {
        val left = ((part?.left ?: 0f) * image.width).roundToInt().coerceIn(0, image.width - 1)
        val top = ((part?.top ?: 0f) * image.height).roundToInt().coerceIn(0, image.height - 1)
        val width = (((part?.width ?: 1f) * image.width).roundToInt())
            .coerceIn(1, image.width - left)
        val height = (((part?.height ?: 1f) * image.height).roundToInt())
            .coerceIn(1, image.height - top)
        val pixels = image.toPixelMap(left, top, width, height)
        var total = 0.0
        var seen = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val c = pixels[x, y]
                total += (c.red + c.green + c.blue) / 3.0
                seen++
                x += 5
            }
            y += 5
        }
        return total / seen
    }

    @Test
    fun `a piece of a real page looks like that part of the whole page`() {
        val where = System.getProperty("inkslate.pdfs")
        assumeTrue("set -Dinkslate.pdfs to a folder of real documents", where != null)
        val pdfs = File(where!!).walkTopDown()
            .filter { it.isFile && it.extension.equals("pdf", ignoreCase = true) }
            .filterNot { it.path.contains(".inkslate-backups") }
            .take(25)
            .toList()
        assumeTrue("no documents found under $where", pdfs.isNotEmpty())

        println("sweeping " + pdfs.size + " documents under " + where)
        val wrong = mutableListOf<String>()
        var checked = 0
        for (pdf in pdfs) {
            val source = runCatching { DesktopSources.open(pdf) }.getOrNull() ?: continue
            source.use {
                val dim = it.pageDim(0)
                val whole = it.render(0, 700) ?: return@use
                // The middle of the page, where the content is.
                val region = Box(
                    dim.width * 0.15f, dim.height * 0.25f,
                    dim.width * 0.85f, dim.height * 0.75f
                )
                val piece = it.renderRegion(0, region, 700) ?: return@use

                val mine = brightness(piece)
                val theirs = brightness(
                    whole,
                    Box(0.15f, 0.25f, 0.85f, 0.75f)
                )
                checked++
                if (abs(mine - theirs) > 0.2) {
                    wrong += "${pdf.name}: piece %.3f against page %.3f".format(mine, theirs)
                }
            }
        }

        println("compared a piece against the whole page in " + checked + " documents")
        assumeTrue("no document could be rendered", checked > 0)
        assert(wrong.isEmpty()) {
            "a rendered piece did not match the page it came from:\n" + wrong.joinToString("\n")
        }
    }
}
