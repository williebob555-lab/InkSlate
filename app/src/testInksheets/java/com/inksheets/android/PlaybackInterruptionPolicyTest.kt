package com.inksheets.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackInterruptionPolicyTest {
    @Test fun `a brief interruption resumes previously playing music once`() {
        val p = PlaybackInterruptionPolicy()
        p.userPlay(); p.focusLost(true, true)
        assertTrue(p.waitingForFocus)
        assertTrue(p.focusGained())
        assertFalse(p.focusGained())
    }

    @Test fun `manual pause during a call cancels subsequent automatic resume`() {
        val p = PlaybackInterruptionPolicy()
        p.userPlay(); p.focusLost(true, true); p.userPause()
        assertFalse(p.focusGained())
        assertFalse(p.playRequested)
    }

    @Test fun `permanent loss needs a new explicit play`() {
        val p = PlaybackInterruptionPolicy()
        p.userPlay(); p.focusLost(false, true)
        assertFalse(p.focusGained())
        assertFalse(p.playRequested)
        p.userPlay()
        assertTrue(p.playRequested)
    }

    @Test fun `unplug while temporarily paused never resumes through the loudspeaker`() {
        val p = PlaybackInterruptionPolicy()
        p.userPlay(); p.focusLost(true, true); p.headphonesDisconnected()
        assertFalse(p.focusGained())
        assertFalse(p.playRequested)
    }

    @Test fun `repeated temporary losses preserve the original suspended intent`() {
        val p = PlaybackInterruptionPolicy()
        p.userPlay(); p.focusLost(true, true); p.focusLost(true, false)
        assertTrue(p.focusGained())
    }

    @Test fun `gain cannot start music that was only loaded or never started`() {
        val p = PlaybackInterruptionPolicy()
        assertFalse(p.focusGained())
        p.userPlay(); p.focusLost(true, false)
        assertFalse(p.focusGained())
    }

    @Test fun `completion and failure revoke pending automatic resume`() {
        val p = PlaybackInterruptionPolicy()
        p.userPlay(); p.focusLost(true, true); p.completed()
        assertFalse(p.focusGained())
        p.userPlay(); p.focusLost(true, true); p.failed()
        assertFalse(p.focusGained())
    }
}
