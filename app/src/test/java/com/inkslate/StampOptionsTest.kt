package com.inkslate

import com.inkslate.ink.Stamps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stamps share one options object, so every builder is entitled to assume it has already been
 * made sensible for the stamp in hand. These check that assumption is actually earned: a value
 * carried over from the last stamp - eight slices arriving at a triangle, five shaded parts in a
 * three-part bar - is the ordinary case here, not a corner one.
 */
class StampOptionsTest {

    @Test
    fun `divisions are clamped into each stamp's own range`() {
        for (kind in Stamps.Kind.entries) {
            val tooMany = Stamps.sanitise(kind, Stamps.StampOptions(divisions = 9999))
            val tooFew = Stamps.sanitise(kind, Stamps.StampOptions(divisions = -50))
            assertTrue(
                "${kind.name} allowed ${tooMany.divisions}",
                tooMany.divisions in kind.divisionsRange
            )
            assertTrue(
                "${kind.name} allowed ${tooFew.divisions}",
                tooFew.divisions in kind.divisionsRange
            )
        }
    }

    @Test
    fun `a variant left over from another stamp lands on one this stamp has`() {
        for (kind in Stamps.Kind.entries) {
            val o = Stamps.sanitise(kind, Stamps.StampOptions(variant = 7))
            if (kind.variants.isEmpty()) {
                assertEquals("${kind.name} has no variants", 0, o.variant)
            } else {
                assertTrue("${kind.name} allowed ${o.variant}", o.variant in kind.variants.indices)
            }
        }
    }

    @Test
    fun `you cannot shade more parts than there are`() {
        for (kind in Stamps.Kind.entries) {
            val o = Stamps.sanitise(kind, Stamps.StampOptions(divisions = 4, filled = 40))
            assertTrue("${kind.name} shaded ${o.filled} of ${o.divisions}", o.filled <= o.divisions)
            assertTrue(o.filled >= 0)
        }
    }

    @Test
    fun `a backwards or unusable range falls back to the stamp's own`() {
        val kind = Stamps.Kind.NUMBER_LINE
        val backwards = Stamps.sanitise(kind, Stamps.StampOptions(rangeFrom = 10f, rangeTo = -10f))
        assertEquals(kind.defaults.rangeFrom, backwards.rangeFrom, 1e-4f)
        assertEquals(kind.defaults.rangeTo, backwards.rangeTo, 1e-4f)

        val empty = Stamps.sanitise(kind, Stamps.StampOptions(rangeFrom = 3f, rangeTo = 3f))
        assertTrue(empty.rangeTo > empty.rangeFrom)

        val broken = Stamps.sanitise(kind, Stamps.StampOptions(rangeFrom = Float.NaN, rangeTo = 5f))
        assertTrue(broken.rangeFrom.isFinite())
    }

    @Test
    fun `every stamp's own defaults survive a round trip unchanged`() {
        for (kind in Stamps.Kind.entries) {
            assertEquals(
                "${kind.name} defaults were not already valid",
                Stamps.sanitise(kind, kind.defaults),
                Stamps.sanitise(kind, Stamps.sanitise(kind, kind.defaults))
            )
        }
    }

    @Test
    fun `every stamp has a usable shape at any setting`() {
        for (kind in Stamps.Kind.entries) {
            for (divisions in listOf(kind.divisionsRange.first, kind.divisionsRange.last)) {
                val o = Stamps.sanitise(kind, kind.defaults.copy(divisions = divisions))
                val aspect = Stamps.aspectFor(kind, o)
                assertTrue("${kind.name} aspect $aspect", aspect.isFinite() && aspect > 0f)
            }
        }
    }

    @Test
    fun `a stamp declaring a knob is one that has something to set`() {
        for (kind in Stamps.Kind.entries) {
            if (Stamps.Knob.VARIANT in kind.knobs) {
                assertTrue(
                    "${kind.name} offers styles but lists none",
                    kind.variants.size >= 2
                )
            }
            if (Stamps.Knob.DIVISIONS in kind.knobs) {
                assertTrue(
                    "${kind.name} offers a count with nothing to count",
                    kind.divisionsRange.last > kind.divisionsRange.first
                )
            }
        }
    }
}
