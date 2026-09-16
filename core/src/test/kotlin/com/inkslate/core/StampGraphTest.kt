package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Graph stamps, and stamps as things that can be changed after they are placed.
 *
 * The graph used to be four quadrants or one, with a tick count and a single switch for every
 * letter and number on it - no way to have no ticks, to call the axes t and d, or to count in
 * twos. These pin the settings that replaced that to what actually gets drawn.
 */
class StampGraphTest {

    private val box = Box(100f, 100f, 400f, 400f)

    private fun build(kind: Stamps.Kind, o: Stamps.StampOptions, group: String? = null): List<Stroke> {
        var n = 0
        return Stamps.build(kind, box, page = 0, options = o, group = group) { "s${n++}" }
    }

    private fun texts(strokes: List<Stroke>) =
        strokes.filter { it.kind == Stroke.Kind.TEXT }.mapNotNull { it.text }

    @Test
    fun `ticks fall on multiples of the step, including zero`() {
        assertEquals(listOf(-4f, -2f, 0f, 2f, 4f), Stamps.tickValues(-5f, 5f, 2f))
        assertEquals(listOf(0f, 0.5f, 1f), Stamps.tickValues(0f, 1f, 0.5f))
    }

    @Test
    fun `a step too fine to draw is widened rather than drawing thousands of ticks`() {
        val o = Stamps.sanitise(
            Stamps.Kind.NUMBER_LINE,
            Stamps.StampOptions(rangeFrom = 0f, rangeTo = 1000f, step = 0.01f)
        )
        assertTrue(Stamps.tickValues(o.rangeFrom, o.rangeTo, o.step).size <= Stamps.MAX_TICKS + 1)
    }

    @Test
    fun `axes can have no ticks at all`() {
        val base = Stamps.StampOptions(tickValues = false, xName = "", yName = "")
        val with = build(Stamps.Kind.AXES, base.copy(ticks = true))
        val without = build(Stamps.Kind.AXES, base.copy(ticks = false))
        // two arrows and nothing else
        assertEquals(2, without.size)
        assertTrue(with.size > without.size)
    }

    @Test
    fun `tick values follow the step and the axis names are what was typed`() {
        val o = Stamps.StampOptions(
            rangeFrom = 0f, rangeTo = 10f, step = 2f,
            yFrom = 0f, yTo = 30f, yStep = 10f,
            xName = "t (s)", yName = "d (m)"
        )
        val words = texts(build(Stamps.Kind.AXES, o))
        assertTrue(words.containsAll(listOf("2", "4", "6", "8", "10", "10", "20", "30")))
        assertTrue("t (s)" in words)
        assertTrue("d (m)" in words)
        assertTrue("no odd x values", "3" !in words)
    }

    @Test
    fun `turning tick values and names off leaves no text`() {
        val o = Stamps.StampOptions(tickValues = false, xName = "", yName = "")
        assertTrue(texts(build(Stamps.Kind.COORD_GRID, o)).isEmpty())
        assertTrue(texts(build(Stamps.Kind.NUMBER_LINE, o)).isEmpty())
    }

    @Test
    fun `a stamp is drawn in its own colour and weight`() {
        val red = 0xFFD32F2F.toInt()
        val strokes = build(Stamps.Kind.NUMBER_LINE, Stamps.StampOptions(color = red, weight = 3f))
        assertTrue(strokes.isNotEmpty())
        assertTrue(strokes.all { it.color == red })
        assertTrue(strokes.filter { it.kind == Stroke.Kind.ARROW }.all { it.baseWidth == 3f })
    }

    @Test
    fun `a placed stamp is recognised as one stamp and a mixture is not`() {
        val one = build(Stamps.Kind.AXES, Stamps.StampOptions(), group = "g1")
        val other = build(Stamps.Kind.GRID, Stamps.Kind.GRID.defaults, group = "g2")
        assertNotNull(Stamps.stampOf(one))
        assertNull(Stamps.stampOf(one + other))
        assertNull(Stamps.stampOf(build(Stamps.Kind.AXES, Stamps.StampOptions())))
    }

    @Test
    fun `rebuilding a moved stamp builds it where it now is`() {
        val placed = build(Stamps.Kind.NUMBER_LINE, Stamps.StampOptions(), group = "g")
        val moved = placed.map { s ->
            s.copy(points = s.points.map { it.copy(x = it.x + 50f, y = it.y + 20f) })
        }
        var n = 0
        val rebuilt = Stamps.rebuild(moved, Stamps.StampOptions(rangeFrom = 0f, rangeTo = 20f, step = 5f)) { "r${n++}" }
        val tag = Stamps.stampOf(rebuilt)!!
        assertEquals(150f, tag.box[0], 0.5f)
        assertEquals(120f, tag.box[1], 0.5f)
        assertEquals("g", tag.group)
        assertTrue("20" in texts(rebuilt))
    }

    @Test
    fun `a resized stamp is rebuilt at its new size`() {
        val placed = build(Stamps.Kind.GRID, Stamps.Kind.GRID.defaults, group = "g")
        val tag = Stamps.stampOf(placed)!!
        // Double it about its own top-left reach.
        val ex = tag.extent
        val doubled = placed.map { s ->
            s.copy(points = s.points.map { it.copy(x = ex[0] + (it.x - ex[0]) * 2f, y = ex[1] + (it.y - ex[1]) * 2f) })
        }
        val grown = Stamps.currentBox(tag, doubled)!!
        assertEquals(box.width * 2f, grown.width, 4f)
    }

    @Test
    fun `a line keeps its direction when restyled`() {
        val placed = build(Stamps.Kind.ARROW, Stamps.Kind.ARROW.defaults, group = "g")
        val turned = placed.map { s ->
            s.copy(points = listOf(InkPoint(0f, 0f, 1.5f), InkPoint(80f, 90f, 1.5f)))
        }
        var n = 0
        val blue = 0xFF1565C0.toInt()
        val restyled = Stamps.rebuild(turned, Stamps.StampOptions(color = blue, dash = DashStyle.DASHED)) { "r${n++}" }
        assertEquals(90f, restyled.single().points.last().y, 0.01f)
        assertEquals(blue, restyled.single().color)
        assertEquals(DashStyle.DASHED, restyled.single().dash)
    }

    @Test
    fun `pasted copies of a stamp get groups of their own`() {
        val placed = build(Stamps.Kind.AXES, Stamps.StampOptions(), group = "g")
        var n = 0
        val copy = Stamps.regroup(placed) { "new${n++}" }
        assertEquals(setOf("new0"), copy.mapNotNull { it.stamp?.group }.toSet())
    }

    @Test
    fun `a stamped stroke survives the document round trip`() {
        val placed = build(Stamps.Kind.AXES, Stamps.StampOptions(xName = "t", color = 0xFF00897B.toInt()), group = "g")
        val doc = InkDocument.create("a.pdf", "pdf", 1, 0L, "").withPage(0, placed, "test")
        val back = InkDocument.parse(doc.serialize())!!
        assertEquals(placed, back.strokesOn(0))
        assertEquals("t", Stamps.stampOf(back.strokesOn(0))!!.options.xName)
    }

    @Test
    fun `every stamp still builds with its defaults`() {
        for (kind in Stamps.Kind.entries) {
            val strokes = build(kind, kind.defaults, group = "g")
            assertTrue("${kind.name} drew nothing", strokes.isNotEmpty())
            assertNotNull("${kind.name} is not one stamp", Stamps.stampOf(strokes))
        }
    }
}
