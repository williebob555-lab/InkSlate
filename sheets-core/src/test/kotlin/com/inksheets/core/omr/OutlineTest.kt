package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Test

/** A shape's outline, filled again, is the shape: pixel for pixel, holes and corner-touching pixels too. */
class OutlineTest {
    private fun roundTrip(ink: Ink): Int {
        val seen = HashSet<Long>()
        val back = Ink(ink.width, ink.height)
        for (y in 0 until ink.height) for (x in 0 until ink.width) {
            if (!ink[x, y] || (x.toLong() shl 32 or (y.toLong() and 0xffffffffL)) in seen) continue
            val loops = Outline.loops(Outline.component(ink, x, y, seen))
            // A loop round pixel (x, y) runs x..x+1 (the page picture's own grid); Fill samples across at pixel middles, down at its top edge.
            Fill.polygons(back, loops.map { l -> FloatArray(l.size) { if (it % 2 == 0) l[it] - 0.5f else l[it] } })
        }
        var diff = 0
        for (y in 0 until ink.height) for (x in 0 until ink.width) if (ink[x, y] != back[x, y]) diff++
        return diff
    }

    @Test
    fun `shapes come back exactly`() {
        val w = 60; val h = 40
        // A box, a ring (a hole), two pixels touching at a corner, an L.
        val ink = Ink(w, h)
        for (y in 2..8) for (x in 2..10) ink[x, y] = true
        for (y in 12..24) for (x in 12..26) ink[x, y] = (x - 19) * (x - 19) + (y - 18) * (y - 18) in 9..40
        ink[40, 5] = true; ink[41, 6] = true; ink[42, 5] = true
        for (y in 20..30) ink[45, y] = true
        for (x in 45..55) ink[x, 30] = true
        assertEquals(0, roundTrip(ink))
    }

    @Test
    fun `random blobs come back exactly`() {
        val r = java.util.Random(5)
        repeat(20) {
            val ink = Ink(50, 50)
            for (y in 5 until 45) for (x in 5 until 45) ink[x, y] = r.nextFloat() < 0.45f
            assertEquals(0, roundTrip(ink))
        }
    }
}
