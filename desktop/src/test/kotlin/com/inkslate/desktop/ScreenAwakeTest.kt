package com.inkslate.desktop

import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.awt.Point

/**
 * Holding the display awake.
 *
 * The property worth pinning down is the restraint, not the nudge: a machine somebody is using
 * must never be sent a synthetic event, because that is the case where it could land in the middle
 * of a stroke. A nudge is only allowed when nothing has moved since the last look.
 */
class ScreenAwakeTest {

    @Before
    fun clear() = ScreenAwake.reset()

    @Test
    fun `the first tick never nudges`() {
        assertFalse(ScreenAwake.tick(Point(100, 100)))
    }

    @Test
    fun `a pointer that has moved is left alone`() {
        ScreenAwake.tick(Point(100, 100))
        assertFalse(ScreenAwake.tick(Point(140, 120)))
    }

    @Test
    fun `a machine with no pointer at all is left alone`() {
        ScreenAwake.tick(null)
        assertFalse(ScreenAwake.tick(null))
    }
}
