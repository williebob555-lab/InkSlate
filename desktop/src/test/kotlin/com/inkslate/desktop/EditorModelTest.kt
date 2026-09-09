package com.inkslate.desktop

import com.inkslate.core.Box
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's undo model and its transforms.
 *
 * All of it is arithmetic over the shared model with no UI attached, which is the point: the
 * things most likely to be wrong here - an undo that resurrects a stroke that never existed, a
 * nudge that silently reorders the page - are invisible on screen until much later.
 */
class EditorModelTest {

    private fun stroke(id: String, x: Float = 0f, y: Float = 0f, n: Int = 4) = Stroke(
        id = id,
        kind = Stroke.Kind.FREEHAND,
        color = 0xFF000000.toInt(),
        baseWidth = 2f,
        points = (0 until n).map { InkPoint(x + it, y + it, 2f) }
    )

    // ---- the undo model ------------------------------------------------------

    @Test
    fun `an edit replaces in place, so the page keeps its z-order`() {
        val list = mutableListOf(stroke("a"), stroke("b"), stroke("c"))
        val moved = list[0].movedBy(10f, 10f, 1L)

        list.applyEdit(listOf(list[0]), listOf(moved))

        assertEquals(listOf("a", "b", "c"), list.map { it.id })
        assertEquals(10f, list[0].points[0].x, 0.001f)
    }

    @Test
    fun `adding appends and undoing takes it away again`() {
        val list = mutableListOf(stroke("a"))
        val added = stroke("b")
        val op = Op.added(added)

        list.add(added)
        assertEquals(listOf("a", "b"), list.map { it.id })

        list.apply(op, forward = false)
        assertEquals(listOf("a"), list.map { it.id })

        list.apply(op, forward = true)
        assertEquals(listOf("a", "b"), list.map { it.id })
    }

    @Test
    fun `deleting and undoing round-trips`() {
        val a = stroke("a")
        val b = stroke("b")
        val list = mutableListOf(a, b)
        val op = Op.removed(listOf(a))

        list.apply(op, forward = true)
        assertEquals(listOf("b"), list.map { it.id })

        list.apply(op, forward = false)
        assertTrue(list.any { it.id == "a" })
        assertEquals(2, list.size)
    }

    /**
     * The shape a partial erase leaves: one stroke in, several out.
     *
     * This is the case that made the undo model take two whole lists rather than a verb, and the
     * one that has to survive being undone - the pieces are new objects with new ids, so undoing
     * has to remove all of them and put back the one that was there.
     */
    @Test
    fun `an erase that splits a stroke undoes back to the original`() {
        val original = stroke("a", n = 6)
        val list = mutableListOf(original)
        val pieces = listOf(stroke("a-1"), stroke("a-2"))
        val op = Op(listOf(original), pieces)

        list.apply(op, forward = true)
        assertEquals(listOf("a-1", "a-2"), list.map { it.id })

        list.apply(op, forward = false)
        assertEquals(listOf("a"), list.map { it.id })
    }

    // ---- erasing -------------------------------------------------------------

    @Test
    fun `erasing through the middle leaves two pieces`() {
        val s = Stroke(
            id = "a",
            kind = Stroke.Kind.FREEHAND,
            color = 0xFF000000.toInt(),
            baseWidth = 1f,
            points = (0..20).map { InkPoint(it.toFloat(), 0f, 1f) }
        )
        var n = 0
        val pieces = s.erasedAt(10f, 0f, 2f) { "new-${++n}" }

        assertEquals(2, pieces.size)
        assertTrue("the pieces get fresh ids", pieces.none { it.id == "a" })
        assertTrue("the left piece keeps its left end", pieces[0].points.first().x < 1f)
        assertTrue("the right piece keeps its right end", pieces[1].points.last().x > 19f)
    }

    @Test
    fun `erasing clear of a stroke leaves it exactly as it was`() {
        val s = stroke("a")
        val pieces = s.erasedAt(500f, 500f, 4f) { "new" }
        assertEquals(1, pieces.size)
        assertTrue("unchanged means the same object, which the caller relies on", pieces[0] === s)
    }

    @Test
    fun `erasing over the whole of a stroke removes it`() {
        val s = stroke("a", n = 3)
        assertTrue(s.erasedAt(1f, 1f, 100f) { "new" }.isEmpty())
    }

    /** A single surviving sample is a dot nobody drew; it reads as grit left behind. */
    @Test
    fun `a lone surviving point is not kept as a stroke`() {
        val s = Stroke(
            id = "a",
            kind = Stroke.Kind.FREEHAND,
            color = 0xFF000000.toInt(),
            baseWidth = 1f,
            points = listOf(
                InkPoint(0f, 0f, 1f), InkPoint(10f, 0f, 1f), InkPoint(20f, 0f, 1f)
            )
        )
        // Covers everything except the first sample.
        val pieces = s.erasedAt(15f, 0f, 12f) { "new" }
        assertTrue(pieces.isEmpty())
    }

    @Test
    fun `a partial erase leaves anything that is not freehand alone`() {
        val table = Stroke(
            id = "t",
            kind = Stroke.Kind.TABLE,
            color = 0xFF000000.toInt(),
            baseWidth = 1f,
            points = listOf(InkPoint(0f, 0f, 1f), InkPoint(100f, 100f, 1f)),
            rows = 2, cols = 2
        )
        val pieces = table.erasedAt(50f, 50f, 20f) { "new" }
        assertEquals(1, pieces.size)
        assertTrue(pieces[0] === table)
    }

    // ---- transforms ----------------------------------------------------------

    @Test
    fun `scaling about an anchor leaves the anchor where it is`() {
        val s = stroke("a", x = 10f, y = 10f)
        val scaled = s.scaledAbout(10f, 10f, 2f, 2f, 1L)
        assertEquals(10f, scaled.points[0].x, 0.001f)
        assertEquals(10f, scaled.points[0].y, 0.001f)
        // The far end moves out by the factor: it was 3 points from the anchor, now 6.
        assertEquals(16f, scaled.points[3].x, 0.001f)
        assertEquals(16f, scaled.points[3].y, 0.001f)
    }

    /**
     * Width follows the smaller factor.
     *
     * Stretching a stroke sideways should not also fatten it, and taking the larger factor would
     * make a squashed shape blow out through its own outline.
     */
    @Test
    fun `stretching in one direction does not thicken the line`() {
        val s = stroke("a")
        val stretched = s.scaledAbout(0f, 0f, 4f, 1f, 1L)
        assertEquals(2f, stretched.baseWidth, 0.001f)
    }

    @Test
    fun `bounds cover every stroke given`() {
        val box = listOf(stroke("a", 0f, 0f), stroke("b", 100f, 100f)).unionBounds()
        assertNotNull(box)
        assertTrue(box!!.left <= 0f)
        assertTrue(box.right >= 103f)
    }

    @Test
    fun `no strokes means no frame`() {
        assertNull(emptyList<Stroke>().unionBounds())
    }

    // ---- handles -------------------------------------------------------------

    @Test
    fun `each handle sits on its own corner and anchors the opposite one`() {
        val box = Box(0f, 0f, 100f, 50f)

        assertEquals(0f, Handle.TOP_LEFT.x(box), 0.001f)
        assertEquals(0f, Handle.TOP_LEFT.y(box), 0.001f)
        assertEquals(100f, Handle.TOP_LEFT.anchorX(box), 0.001f)
        assertEquals(50f, Handle.TOP_LEFT.anchorY(box), 0.001f)

        assertEquals(100f, Handle.BOTTOM_RIGHT.x(box), 0.001f)
        assertEquals(0f, Handle.BOTTOM_RIGHT.anchorX(box), 0.001f)
    }

    /** An edge handle resizes one way only, or dragging a side would distort the other axis. */
    @Test
    fun `edge handles scale a single axis`() {
        assertTrue(Handle.TOP.scalesY)
        assertTrue(!Handle.TOP.scalesX)
        assertTrue(Handle.LEFT.scalesX)
        assertTrue(!Handle.LEFT.scalesY)
        assertTrue(Handle.TOP_LEFT.scalesX && Handle.TOP_LEFT.scalesY)
    }

    @Test
    fun `a click finds the handle under it and nothing else`() {
        val box = Box(0f, 0f, 100f, 50f)
        assertEquals(Handle.TOP_LEFT, handleAt(box, 1f, 1f, 6f))
        assertEquals(Handle.BOTTOM_RIGHT, handleAt(box, 99f, 49f, 6f))
        assertNull("the middle of the box is not a handle", handleAt(box, 50f, 25f, 6f))
    }
}
