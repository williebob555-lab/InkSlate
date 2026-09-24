package com.inkslate.desktop

import java.awt.MouseInfo
import java.awt.Robot

/**
 * Keeps the display from blanking while a document is open.
 *
 * On Linux the desktop is simply asked, through [ScreenInhibit]; this is the Windows half.
 *
 * The tablet asks the window for this and gets it. Windows offers nothing a plain desktop program
 * can call without dropping into native code, so this does the only thing the JVM can: it nudges
 * the pointer to exactly where it already is, which resets the idle timer without moving anything.
 *
 * The nudge only happens when the pointer has not moved since the last check - that is, when the
 * machine is idle, which is both the case where the display would otherwise blank and the case
 * where a synthetic event can disturb nothing. Someone drawing is already generating input.
 */
object ScreenAwake {

    /** Comfortably inside the shortest blank timeout anyone sets, and rare enough to be free. */
    const val INTERVAL_MS = 50_000L

    private var lastSeen: java.awt.Point? = null

    private val robot: Robot? by lazy { runCatching { Robot() }.getOrNull() }

    /**
     * One tick. Returns true when a nudge was actually sent, which is what the tests check - the
     * useful property is not that the pointer moved but that a busy machine is left alone.
     */
    fun tick(pointerNow: java.awt.Point? = pointer()): Boolean {
        val here = pointerNow
        val before = lastSeen
        lastSeen = here
        if (here == null || before == null || here != before) return false
        // Linux is asked properly instead - see ScreenInhibit - and a synthetic pointer event
        // under Wayland brings up a remote-control permission prompt rather than doing anything.
        if (!AppDirs.isWindows) return false
        robot?.mouseMove(here.x, here.y) ?: return false
        return true
    }

    /** Forget the last position, so reopening a document does not nudge on its first tick. */
    fun reset() {
        lastSeen = null
    }

    private fun pointer(): java.awt.Point? =
        runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
}
