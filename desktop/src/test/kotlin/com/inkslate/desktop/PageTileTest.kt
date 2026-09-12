package com.inkslate.desktop

import androidx.compose.ui.graphics.toPixelMap
import com.inkslate.core.Box
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A piece of a page has to be a picture of that piece, in the place it says it is.
 *
 * The whole page at the size it is shown when magnified is tens of megabytes, which a graphics
 * card will not hold between frames - it was being sent across again every frame, and that was
 * the entire cost of a frame. Rendering only the part being looked at fixes that, and the way it
 * fails is the worst kind: the page still appears, slightly displaced, and every mark on it then
 * looks like it is in the wrong place.
 *
 * So the piece is compared against the same part of a picture of the whole page. Nothing here
 * knows what a page looks like; it only knows the two must agree.
 */
class PageTileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun ruledPage(): DesktopSource {
        val dir = temp.newFolder()
        val file = BlankDocumentFactory.create(
            dir,
            BlankDocumentFactory.Spec(name = "Ruled", pageCount = 1, background = BlankDocumentFactory.Background.GRAPH)
        ).getOrThrow()
        return DesktopSources.open(file)!!
    }

    @Test
    fun `a piece of a page matches the same part of the whole page`() {
        ruledPage().use { source ->
            val dim = source.pageDim(0)
            val wholeAcross = 900
            val whole = source.render(0, wholeAcross)
            assertNotNull("the whole page should render", whole)

            // A quarter of the page, offset from the corner so a mistake in either direction shows.
            val region = Box(
                dim.width * 0.25f, dim.height * 0.3f,
                dim.width * 0.75f, dim.height * 0.7f
            )
            // The same pixels per point as the whole-page render, so the two are comparable.
            val pieceAcross = (wholeAcross * region.width / dim.width).roundToInt()
            val piece = source.renderRegion(0, region, pieceAcross)
            assertNotNull("this source should render a region", piece)

            val wholePixels = whole!!.toPixelMap()
            val piecePixels = piece!!.toPixelMap()
            val perPoint = wholeAcross / dim.width

            var compared = 0
            var differing = 0
            var y = 4
            while (y < piecePixels.height - 4) {
                var x = 4
                while (x < piecePixels.width - 4) {
                    val wx = ((region.left * perPoint) + x).roundToInt()
                    val wy = ((region.top * perPoint) + y).roundToInt()
                    if (wx in 0 until wholePixels.width && wy in 0 until wholePixels.height) {
                        compared++
                        val a = piecePixels[x, y]
                        val b = wholePixels[wx, wy]
                        val apart = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
                        if (apart > 0.25f) differing++
                    }
                    x += 3
                }
                y += 3
            }

            assertTrue("nothing was compared", compared > 1_000)
            // Not pixel-exact: the two are rasterised separately and a ruled line lands either
            // side of a pixel boundary differently. A displacement would not be a few per cent.
            val wrong = differing * 100.0 / compared
            assertTrue(
                "the piece does not line up with the whole page: %.1f%% of pixels differ"
                    .format(wrong),
                wrong < 6.0
            )
        }
    }

    @Test
    fun `a piece knows what it covers`() {
        val tile = PageTile(Box(100f, 100f, 300f, 300f), androidx.compose.ui.graphics.ImageBitmap(10, 10))

        assertTrue(tile.covers(Box(150f, 150f, 250f, 250f)))
        assertTrue("its own edges count", tile.covers(Box(100f, 100f, 300f, 300f)))
        assertTrue("off the left", !tile.covers(Box(90f, 150f, 250f, 250f)))
        assertTrue("off the bottom", !tile.covers(Box(150f, 150f, 250f, 310f)))
    }

    @Test
    fun `sharpness is measured against the page, not the picture`() {
        // 400 pixels across 200 points is two pixels a point, so a hundred points is 200 pixels.
        val tile = PageTile(Box(0f, 0f, 200f, 200f), androidx.compose.ui.graphics.ImageBitmap(400, 400))

        assertTrue(abs(tile.acrossPx(100f) - 200) <= 1)
        assertTrue(abs(tile.acrossPx(200f) - 400) <= 1)
    }
}
