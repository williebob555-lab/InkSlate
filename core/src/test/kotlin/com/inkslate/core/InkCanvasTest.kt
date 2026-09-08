package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A canvas is the one thing in the document that two devices can enlarge independently, so the
 * property that matters is that folding their answers together gives the same result whichever
 * way round it happens - and that growing never moves anything already written.
 */
class InkCanvasTest {

    private val start = InkCanvas.startingAt(600f, 800f)

    @Test
    fun `a fresh canvas is exactly its page`() {
        assertEquals(600f, start.width, 0.001f)
        assertEquals(800f, start.height, 0.001f)
        assertFalse("nothing to write until something is drawn past the edge", start.paperIsBehind)
    }

    @Test
    fun `content well inside changes nothing`() {
        val same = start.grownTo(Box(300f, 300f, 320f, 320f))
        assertSame(start, same)
    }

    @Test
    fun `writing off the right edge makes room on the right only`() {
        // clear of the other three edges, so only the right one has any reason to move
        val grown = start.grownTo(Box(590f, 300f, 640f, 320f), margin = 200f, chunk = 320f)
        assertEquals("the left edge must not move", 0f, grown.left, 0.001f)
        assertEquals(0f, grown.top, 0.001f)
        assertEquals(800f, grown.bottom, 0.001f)
        assertTrue(grown.right >= 840f)
    }

    @Test
    fun `writing off the left edge gives the canvas a negative origin`() {
        val grown = start.grownTo(Box(-40f, 300f, 10f, 320f), margin = 200f, chunk = 320f)
        assertTrue("the canvas extends backwards rather than shifting", grown.left <= -240f)
        assertEquals("the right edge stays where it was", 600f, grown.right, 0.001f)
        assertTrue(grown.paperIsBehind)
    }

    @Test
    fun `growth is quantised so writing along an edge does not relayout constantly`() {
        var c = start
        // a run of marks creeping rightwards
        for (x in 590..610) {
            c = c.grownTo(Box(x.toFloat(), 300f, x.toFloat(), 320f), margin = 200f, chunk = 320f)
        }
        // one step out, not twenty
        assertEquals(920f, c.right, 0.001f)
    }

    @Test
    fun `a canvas only ever grows`() {
        val grown = start.grownTo(Box(-500f, -500f, 1500f, 1500f))
        val after = grown.grownTo(Box(10f, 10f, 20f, 20f))
        assertEquals(grown.left, after.left, 0.001f)
        assertEquals(grown.right, after.right, 0.001f)
    }

    @Test
    fun `merging two canvases takes the union and does not care about order`() {
        val tablet = start.grownTo(Box(-300f, 0f, 100f, 100f))
        val laptop = start.grownTo(Box(500f, 900f, 700f, 1000f))

        val a = tablet.mergeWith(laptop)
        val b = laptop.mergeWith(tablet)

        assertEquals(a.left, b.left, 0.001f)
        assertEquals(a.top, b.top, 0.001f)
        assertEquals(a.right, b.right, 0.001f)
        assertEquals(a.bottom, b.bottom, 0.001f)

        assertTrue("both devices' room survives", a.left <= tablet.left && a.right >= laptop.right)
        assertTrue(a.bottom >= laptop.bottom)
    }

    @Test
    fun `merging is idempotent`() {
        val grown = start.grownTo(Box(-100f, -100f, 900f, 900f))
        assertEquals(grown, grown.mergeWith(grown))
    }

    @Test
    fun `matching the paper clears the backlog`() {
        val grown = start.grownTo(Box(-40f, 0f, 10f, 10f))
        assertTrue(grown.paperIsBehind)
        val written = grown.withPaperMatched()
        assertFalse(written.paperIsBehind)
        assertEquals(written.left, written.paperLeft, 0.001f)
        assertEquals(written.bottom, written.paperBottom, 0.001f)
    }

    @Test
    fun `a canvas cannot grow past its limit`() {
        val huge = start.grownTo(
            Box(0f, 0f, 90000f, 90000f), margin = 0f, chunk = 256f, maxSpan = 10000f
        )
        assertTrue("width is held to the limit", huge.width <= 10000f + 0.01f)
        assertTrue("height is held to the limit", huge.height <= 10000f + 0.01f)
    }

    @Test
    fun `the limit takes room back from the side that grew, not from the work`() {
        // grown a long way to the left, which is where the writing is
        val leftwards = start.grownTo(Box(-9000f, 0f, 10f, 10f), margin = 0f, chunk = 256f)
        val capped = leftwards.grownTo(
            Box(-9000f, 0f, 700f, 10f), margin = 0f, chunk = 256f, maxSpan = 9700f
        )
        assertTrue(
            "the left-hand side, full of work, must not be trimmed away",
            capped.left <= leftwards.left + 0.01f
        )
        // The canvas was already wider than this limit, so the limit can only refuse the new
        // room - it cannot claw back room that already has work in it. Growth is prevented,
        // never reversed.
        assertEquals("no new room was granted", leftwards.width, capped.width, 0.01f)
    }

    @Test
    fun `an already oversized canvas simply refuses to grow further`() {
        val big = start.grownTo(Box(0f, 0f, 20000f, 100f), margin = 0f, chunk = 256f)
        val again = big.grownTo(
            Box(0f, 0f, 25000f, 100f), margin = 0f, chunk = 256f, maxSpan = 5000f
        )
        assertEquals("nothing is taken away", big.left, again.left, 0.01f)
        assertEquals(big.right, again.right, 0.01f)
    }

    @Test
    fun `growth never pulls an existing edge inwards`() {
        var c = start
        val boxes = listOf(
            Box(-300f, 10f, -280f, 20f),
            Box(700f, 10f, 720f, 20f),
            Box(10f, -300f, 20f, -280f),
            Box(10f, 900f, 20f, 920f)
        )
        for (b in boxes) {
            val before = c
            c = c.grownTo(b)
            assertTrue(c.left <= before.left + 0.001f)
            assertTrue(c.top <= before.top + 0.001f)
            assertTrue(c.right >= before.right - 0.001f)
            assertTrue(c.bottom >= before.bottom - 0.001f)
        }
    }

    @Test
    fun `a document without a canvas keeps one that arrives from another device`() {
        val plain = InkDocument(
            docId = "d",
            source = InkDocument.SourceRef(name = "n.pdf", kind = "pdf")
        )
        val withCanvas = plain.copy(canvas = start)
        assertNull(plain.canvas)
        assertEquals(start, plain.mergeWith(withCanvas).canvas)
        assertEquals(start, withCanvas.mergeWith(plain).canvas)
    }

    @Test
    fun `two devices that both grew merge to the union through the document`() {
        val base = InkDocument(
            docId = "d",
            source = InkDocument.SourceRef(name = "n.pdf", kind = "pdf")
        )
        val tablet = base.copy(canvas = start.grownTo(Box(-400f, 0f, 10f, 10f)))
        val laptop = base.copy(canvas = start.grownTo(Box(590f, 790f, 900f, 1200f)))

        val merged = tablet.mergeWith(laptop).canvas!!
        assertTrue(merged.left <= -400f)
        assertTrue(merged.right >= 900f)
        assertTrue(merged.bottom >= 1200f)
    }
}
