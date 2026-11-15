package com.inkslate.core

import org.junit.Assert.*
import org.junit.Test

class SelectionPropertiesTest {
    private var serial = 0
    private fun next() = "edit-${serial++}"
    private fun ink(id: String = "ink", layer: Int = 1) = Stroke(id, Stroke.Kind.FREEHAND,
        0xFF123456.toInt(), 2f, listOf(InkPoint(10f, 20f, 1f), InkPoint(40f, 50f, 3f)), layerMask = layer)

    @Test fun `a graph is one object and reports its main weight rather than all detail weights`() {
        val graph = Stamps.build(Stamps.Kind.COORD_GRID, Box(0f, 0f, 200f, 200f), 0,
            Stamps.Kind.COORD_GRID.defaults, "graph", ::next)
        val p = SelectionProperties.of(graph)
        assertEquals(1, p.objectCount)
        assertEquals(Stamps.Kind.COORD_GRID.label, p.title)
        assertEquals(Stamps.Kind.COORD_GRID.defaults.weight, p.width)
    }

    @Test fun `appearance keeps pressure variation and leaves pictures and text widths alone`() {
        val picture = ink("picture").copy(kind = Stroke.Kind.IMAGE)
        val text = ink("text").copy(kind = Stroke.Kind.TEXT)
        val after = SelectionAppearance.restyle(listOf(ink(), picture, text), 0xFFABCDEF.toInt(), 4f, 100, ::next)
        assertEquals(listOf(2f, 6f), after[0].points.map { it.width })
        assertEquals(picture, after[1])
        assertEquals(text.baseWidth, after[2].baseWidth)
        assertEquals(text.points, after[2].points)
        assertEquals(0xFFABCDEF.toInt(), after[2].color)
    }

    @Test fun `stamp appearance survives opening its settings and preserves layer`() {
        val original = Stamps.build(Stamps.Kind.COORD_GRID, Box(0f, 0f, 200f, 200f), 0,
            Stamps.Kind.COORD_GRID.defaults, "graph", ::next).map { it.copy(layerMask = 4) }
        val after = SelectionAppearance.restyle(original, 0xFFCC2233.toInt(), 3f, 100, ::next)
        val tag = Stamps.stampOf(after)!!
        assertEquals(3f, tag.options.weight)
        assertEquals(0xFFCC2233.toInt(), tag.options.color)
        assertTrue(after.all { it.layerMask == 4 })
        assertTrue(after.any { it.baseWidth < 3f })
        assertEquals(tag.options.color, SelectionProperties.of(after).color)
        val editedAgain = Stamps.rebuild(after, tag.options.copy(rangeTo = 8f), ::next)
        assertTrue(editedAgain.all { it.layerMask == 4 })
    }

    @Test fun `shared legacy move preserves other channels and copy has independent stamp groups`() {
        val shared = ink(layer = 3)
        val move = SelectionLayers.transfer(listOf(shared), 1, 4, false, 100, ::next)
        assertEquals(listOf(shared), move.removed)
        assertEquals(2, move.added.first().layerMask)
        assertEquals(shared.id, move.added.first().id)
        assertEquals(4, move.selected.single().layerMask)
        assertNotEquals(shared.id, move.selected.single().id)
        val copy = SelectionLayers.transfer(listOf(shared), 1, 2, true, 100, ::next)
        assertTrue(copy.removed.isEmpty())
        assertEquals(2, copy.selected.single().layerMask)
        assertNotEquals(shared.id, copy.selected.single().id)
    }

    @Test fun `mixed properties are explicit and a picture alone has no appearance controls`() {
        val p = SelectionProperties.of(listOf(ink(), ink("second", 2).copy(color = 0xFF000000.toInt(), baseWidth = 3f)))
        assertNull(p.color); assertNull(p.width); assertNull(p.layerMask)
        assertEquals(2, p.objectCount)
        val image = SelectionProperties.of(listOf(ink().copy(kind = Stroke.Kind.IMAGE)))
        assertFalse(image.canColor); assertFalse(image.canWidth)
    }

    @Test fun `moving shared ink onto an existing membership never stacks a duplicate`() {
        val shared = ink(layer = 7)
        val move = SelectionLayers.transfer(listOf(shared), 1, 2, false, 100, ::next)
        assertEquals(setOf(2, 4), move.added.map { it.layerMask }.toSet())
        assertEquals(1, move.added.count { it.layerMask and 2 != 0 })
        assertEquals(2, move.selected.single().layerMask)
        val twoLayers = SelectionLayers.transfer(listOf(shared.copy(layerMask = 3)), 1, 2, false, 100, ::next)
        assertEquals(1, twoLayers.added.size)
        assertEquals(shared.id, twoLayers.selected.single().id)
    }
}
