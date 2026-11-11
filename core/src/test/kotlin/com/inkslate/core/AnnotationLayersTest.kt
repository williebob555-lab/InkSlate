package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationLayersTest {
    @Test fun `old strokes decode into layer one and new combinations survive`() {
        val old = """{"format":"inkdoc","docId":"d","source":{"name":"x","kind":"blank"},"pages":{"0":[{"id":"s","kind":"FREEHAND","color":-16777216,"baseWidth":2.0,"points":[{"x":1.0,"y":2.0,"width":2.0}]}]}}"""
        assertEquals(AnnotationLayers.ONE, InkDocument.parse(old)!!.strokesOn(0).single().layerMask)

        val combined = InkDocument.parse(old)!!.let { doc ->
            doc.copy(pages = mapOf("0" to listOf(doc.strokesOn(0).single().copy(layerMask = 5))))
        }
        assertEquals(5, InkDocument.parse(combined.serialize())!!.strokesOn(0).single().layerMask)
    }

    @Test fun `filters replace viewport target and overlaps combine`() {
        val two = LayerFilter(0, AnnotationLayers.TWO, 10f, 10f, 80f, 80f)
        val three = LayerFilter(0, AnnotationLayers.THREE, 40f, 40f, 100f, 100f)
        assertEquals(AnnotationLayers.ONE, AnnotationLayers.writeMask(1, listOf(two, three), 0, 5f, 5f))
        assertEquals(AnnotationLayers.TWO, AnnotationLayers.writeMask(1, listOf(two, three), 0, 20f, 20f))
        assertEquals(AnnotationLayers.TWO or AnnotationLayers.THREE,
            AnnotationLayers.writeMask(1, listOf(two, three), 0, 50f, 50f))
    }

    @Test fun `mixer may mute everything while writing still falls back to layer one`() {
        assertEquals(0, AnnotationLayers.mixerMask(0))
        assertEquals(AnnotationLayers.ONE, AnnotationLayers.writeMask(0, emptyList(), 0, 5f, 5f))
    }

    @Test fun `export mix mutes channels and uses strongest shared membership fader`() {
        assertEquals(null, AnnotationLayers.mixedOpacity(AnnotationLayers.ONE, 0, listOf(1f, 1f, 1f)))
        assertEquals(
            .8f,
            AnnotationLayers.mixedOpacity(
                AnnotationLayers.ONE or AnnotationLayers.TWO,
                AnnotationLayers.ALL,
                listOf(.25f, .8f, .5f)
            )!!,
            .0001f
        )
    }

    @Test fun `filter geometry moves and remains usable when resized`() {
        val filter = LayerFilter(2, 1, 10f, 20f, 110f, 80f).moved(5f, -5f)
        assertTrue(filter.contains(20f, 20f))
        assertFalse(filter.contains(10f, 20f))
        assertTrue(filter.withBounds(15f, 15f, 20f, 20f).width >= 24f)
    }

    @Test fun `every nonempty layer combination has a distinct colour`() {
        assertEquals(7, (1..7).map(AnnotationLayers::colour).toSet().size)
    }
}
