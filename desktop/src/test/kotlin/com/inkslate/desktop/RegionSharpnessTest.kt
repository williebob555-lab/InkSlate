package com.inkslate.desktop

import androidx.compose.ui.graphics.toPixelMap
import com.inkslate.core.Box
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs

/**
 * A piece of a page must get sharper as the view comes in, not softer.
 *
 * Reported as the background disappearing at high magnification. A ruled page drawn at a fixed
 * magnification and then stretched has its ruling washed out towards the paper colour - and on a
 * whiteboard that washed-out picture is drawn on top of ruling that is painted underneath it
 * perfectly sharply, so the ruling appears to fade away as the view comes in.
 *
 * Measured as contrast: how far the darkest parts of the piece are from the lightest. Ruling that
 * has washed out has almost none.
 */
class RegionSharpnessTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun ruled(): DesktopSource {
        val dir = temp.newFolder()
        val file = BlankDocumentFactory.create(
            dir,
            BlankDocumentFactory.Spec(
                name = "Ruled",
                pageCount = 1,
                background = BlankDocumentFactory.Background.GRID
            )
        ).getOrThrow()
        return DesktopSources.open(file)!!
    }

    /** How far apart the lightest and darkest samples are. */
    private fun contrast(bmp: androidx.compose.ui.graphics.ImageBitmap): Float {
        val px = bmp.toPixelMap()
        var lightest = 0f
        var darkest = 1f
        var y = 0
        while (y < px.height) {
            var x = 0
            while (x < px.width) {
                val c = px[x, y]
                val light = (c.red + c.green + c.blue) / 3f
                if (light > lightest) lightest = light
                if (light < darkest) darkest = light
                x += 2
            }
            y += 2
        }
        return lightest - darkest
    }

    @Test
    fun `ruling survives being looked at closely`() {
        ruled().use { source ->
            val dim = source.pageDim(0)

            // The whole page, as it looks when it fits the window.
            val wide = source.renderRegion(0, Box(0f, 0f, dim.width, dim.height), 1_200)!!
            val far = contrast(wide)
            assertTrue("the test page should have ruling on it: $far", far > 0.05f)

            // A small part of it, as it looks magnified - the same pixels across a fortieth of
            // the page, which is what the window asks for once the view has come in.
            val close = source.renderRegion(
                0,
                Box(dim.width * 0.40f, dim.height * 0.40f, dim.width * 0.425f, dim.height * 0.425f),
                1_200
            )!!
            val near = contrast(close)

            assertTrue(
                "ruling faded away when looked at closely: $far far against $near near",
                near > far * 0.6f
            )
            assertTrue("and the piece should be the size asked for", abs(close.width - 1_200) < 4)
        }
    }
}
