package com.inkslate.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Letting go of a page that is not moving must not throw it.
 *
 * Reported as "I release it when it is perfectly still and it gives it a random horizontal
 * velocity", which is exactly what taking the last two samples and dividing does: holding still
 * produces no samples, so the last figure stands, and it is usually sideways because the last
 * correction before stopping usually is.
 */
class PanThrowTest {

    private val ms = 1_000_000L

    @Test
    fun `a page held still is not thrown`() {
        val throwing = PanThrow()
        throwing.begin()
        // Moved briskly sideways...
        throwing.sample(40f, 0f, now = 10 * ms)
        throwing.sample(40f, 0f, now = 20 * ms)
        // ...then held, which produces no samples at all.
        val released = throwing.release(now = 400 * ms)

        assertEquals("nothing sideways", 0f, released.x, 0.001f)
        assertEquals("nothing downwards", 0f, released.y, 0.001f)
    }

    /** The same thing when the hand reports standing still rather than reporting nothing. */
    @Test
    fun `a page reported as still is not thrown`() {
        val throwing = PanThrow()
        throwing.begin()
        throwing.sample(40f, 0f, now = 10 * ms)
        throwing.sample(0f, 0f, now = 25 * ms)

        assertEquals(0f, throwing.release(now = 30 * ms).x, 0.001f)
    }

    @Test
    fun `a page let go while moving is thrown`() {
        val throwing = PanThrow()
        throwing.begin()
        repeat(6) { throwing.sample(0f, 12f, now = (10 + it * 10) * ms) }

        val released = throwing.release(now = 65 * ms)

        assertTrue("should carry on downwards, got ${released.y}", released.y > 300f)
        assertEquals("and not sideways", 0f, released.x, 0.001f)
    }

    /**
     * A single twitch at the end must not decide the throw.
     *
     * The old arithmetic used the last pair alone, so one stray sample a millisecond apart threw
     * the page at a thousand times the speed of the movement it belonged to.
     */
    @Test
    fun `one stray sample does not decide the throw`() {
        val steady = PanThrow()
        steady.begin()
        repeat(6) { steady.sample(0f, 10f, now = (10 + it * 10) * ms) }
        val expected = steady.release(now = 65 * ms).y

        val twitched = PanThrow()
        twitched.begin()
        repeat(6) { twitched.sample(0f, 10f, now = (10 + it * 10) * ms) }
        twitched.sample(0f, 3f, now = 70 * ms)

        val actual = twitched.release(now = 72 * ms).y
        assertTrue(
            "a last twitch should nudge the throw, not replace it: $expected then $actual",
            actual > expected * 0.5f && actual < expected * 2f
        )
    }

    @Test
    fun `a drag that never moved throws nothing`() {
        val throwing = PanThrow()
        throwing.begin()

        assertEquals(0f, throwing.release().x, 0.001f)
        assertEquals(0f, throwing.release().y, 0.001f)
    }
}
