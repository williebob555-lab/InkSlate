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

    @Test fun `filters replace viewport target and overlaps choose one deterministic channel`() {
        val two = LayerFilter(0, AnnotationLayers.TWO, 10f, 10f, 80f, 80f)
        val three = LayerFilter(0, AnnotationLayers.THREE, 40f, 40f, 100f, 100f)
        assertEquals(AnnotationLayers.ONE, AnnotationLayers.writeMask(1, listOf(two, three), 0, 5f, 5f))
        assertEquals(AnnotationLayers.TWO, AnnotationLayers.writeMask(1, listOf(two, three), 0, 20f, 20f))
        assertEquals(AnnotationLayers.TWO,
            AnnotationLayers.writeMask(1, listOf(two, three), 0, 50f, 50f))
        assertEquals(AnnotationLayers.TWO,
            AnnotationLayers.writeMask(1, listOf(three, two), 0, 50f, 50f))
    }

    @Test fun `overlapping filters prefer the active recording channel when present`() {
        val two = LayerFilter(0, AnnotationLayers.TWO, 0f, 0f, 100f, 100f)
        val three = two.copy(layerMask = AnnotationLayers.THREE)
        for (filters in listOf(listOf(two, three), listOf(three, two))) {
            assertEquals(AnnotationLayers.THREE,
                AnnotationLayers.writeMask(AnnotationLayers.THREE, filters, 0, 50f, 50f))
            assertEquals(AnnotationLayers.TWO,
                AnnotationLayers.writeMask(AnnotationLayers.TWO, filters, 0, 50f, 50f))
        }
    }

    @Test fun `legacy recording combinations select their lowest valid channel`() {
        for (mask in listOf(0, 8, Int.MIN_VALUE)) {
            assertEquals(AnnotationLayers.ONE, AnnotationLayers.recordingLayer(mask))
        }
        assertEquals(AnnotationLayers.ONE, AnnotationLayers.recordingLayer(AnnotationLayers.ALL))
        assertEquals(AnnotationLayers.TWO, AnnotationLayers.recordingLayer(AnnotationLayers.TWO or AnnotationLayers.THREE))
        assertEquals(AnnotationLayers.THREE, AnnotationLayers.recordingLayer(8 or AnnotationLayers.THREE))
        for (mask in 0..31) {
            val layer = AnnotationLayers.writeMask(mask, emptyList(), 0, 5f, 5f)
            assertTrue(layer == AnnotationLayers.ONE || layer == AnnotationLayers.TWO || layer == AnnotationLayers.THREE)
        }
    }

    @Test fun `invalid and other page filters do not override recording target`() {
        val invalid = LayerFilter(0, 8, 0f, 0f, 100f, 100f)
        val otherPage = invalid.copy(pageIndex = 1, layerMask = AnnotationLayers.ONE)
        assertEquals(AnnotationLayers.THREE,
            AnnotationLayers.writeMask(AnnotationLayers.THREE, listOf(invalid, otherPage), 0, 50f, 50f))
        val shared = invalid.copy(layerMask = AnnotationLayers.ONE or AnnotationLayers.THREE)
        assertEquals(AnnotationLayers.THREE,
            AnnotationLayers.writeMask(AnnotationLayers.THREE, listOf(shared), 0, 50f, 50f))
    }

    @Test fun `removing one channel preserves remaining legacy shared memberships`() {
        assertEquals(AnnotationLayers.ONE or AnnotationLayers.THREE,
            AnnotationLayers.withoutLayer(AnnotationLayers.ALL, AnnotationLayers.TWO))
        assertEquals(0, AnnotationLayers.withoutLayer(AnnotationLayers.TWO, AnnotationLayers.TWO))
        assertEquals(AnnotationLayers.THREE,
            AnnotationLayers.withoutLayer(AnnotationLayers.THREE, AnnotationLayers.TWO))
        assertEquals(AnnotationLayers.ALL, AnnotationLayers.normalise(AnnotationLayers.ALL))
        assertEquals(AnnotationLayers.ALL, AnnotationLayers.mixerMask(AnnotationLayers.ALL))
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

    @Test fun `two finger filter transform follows midpoint scales and remains on page`() {
        val original = LayerFilter(0, 1, 40f, 50f, 140f, 110f)
        val changed = original.pinched(30f, -20f, 1.5f, 240f, 180f)
        assertEquals(150f, changed.width, .001f)
        assertEquals(90f, changed.height, .001f)
        assertEquals(120f, (changed.left + changed.right) / 2f, .001f)
        assertEquals(60f, (changed.top + changed.bottom) / 2f, .001f)

        val clamped = original.pinched(-500f, 500f, 8f, 200f, 160f)
        assertEquals(0f, clamped.left, .001f)
        assertEquals(0f, clamped.top, .001f)
        assertEquals(200f, clamped.right, .001f)
        assertEquals(160f, clamped.bottom, .001f)
    }

    @Test fun `every nonempty layer combination has a distinct colour`() {
        assertEquals(7, (1..7).map(AnnotationLayers::colour).toSet().size)
    }

    @Test fun `infinite filter pinch ignores seed page bounds in every direction`() {
        val original = LayerFilter(0, 2, 40f, 50f, 140f, 110f)
        val negative = original.pinched(-1500f, -2500f, 3f, 200f, 160f, bounded = false)
        assertEquals(300f, negative.width, .001f)
        assertEquals(180f, negative.height, .001f)
        assertEquals(-1410f, (negative.left + negative.right) / 2f, .001f)
        assertEquals(-2420f, (negative.top + negative.bottom) / 2f, .001f)
        val positive = original.pinched(5000f, 7000f, 10f, 200f, 160f, bounded = false)
        assertEquals(1000f, positive.width, .001f)
        assertTrue(positive.left > 200f)
        assertTrue(positive.top > 160f)
        val small = original.pinched(-1000f, 1000f, .001f, 200f, 160f, bounded = false)
        assertEquals(24f, small.width, .001f)
        assertEquals(24f, small.height, .001f)
    }
}
