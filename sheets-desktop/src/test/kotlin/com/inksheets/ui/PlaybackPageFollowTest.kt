package com.inksheets.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPageFollowTest {
    @Test fun `manual page turn suspends automatic following`() {
        val follow = PlaybackPageFollow(4)
        follow.observe(5, 100)
        assertTrue(follow.suspended)
        assertFalse(follow.turnTo(4, 5, 200))
    }

    @Test fun `an automatic page turn gets time to settle`() {
        val follow = PlaybackPageFollow(4)
        assertTrue(follow.turnTo(5, 4, 100))
        follow.observe(4, 500)
        assertFalse(follow.suspended)
        follow.observe(5, 600)
        assertFalse(follow.suspended)
    }
}
