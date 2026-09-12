package com.inkslate.desktop

import androidx.compose.ui.geometry.Offset

/**
 * How fast the page was moving when it was let go.
 *
 * Both places that drag the page used to take the distance between the last two samples and divide
 * by the time between them. That is the speed of the last twitch rather than of the movement, and
 * it has a worse failure: holding the page still produces no samples at all, so the figure stops
 * being updated and keeps whatever it held from the last real movement. Letting go after a pause
 * then threw the page off at the speed of a movement that finished a second ago - usually sideways,
 * because the last correction before stopping usually is.
 *
 * So two things. The speed is smoothed across recent samples, which is what makes a throw follow
 * the movement rather than its final twitch. And a throw is only handed over if the hand was still
 * moving when it let go: pausing, however briefly, means the page stays exactly where it was put.
 */
class PanThrow {

    private var lastAt = 0L
    private var movedAt = 0L
    private var vx = 0f
    private var vy = 0f

    fun begin() {
        lastAt = 0L
        movedAt = 0L
        vx = 0f
        vy = 0f
    }

    /** Another sample of the drag: how far it moved since the one before. */
    fun sample(dx: Float, dy: Float, now: Long = System.nanoTime()) {
        val previous = lastAt
        lastAt = now
        if (previous == 0L) return

        // A floor on the interval, because two samples in the same millisecond would otherwise
        // report a thousand times the speed they represent.
        val seconds = ((now - previous) / 1_000_000_000f).coerceAtLeast(MIN_INTERVAL)

        if (dx == 0f && dy == 0f) {
            // Standing still is information rather than an absence of it: the hand has stopped,
            // and a throw from here should be nothing.
            vx = 0f
            vy = 0f
            return
        }

        movedAt = now
        vx = vx * KEEP + (dx / seconds) * (1f - KEEP)
        vy = vy * KEEP + (dy / seconds) * (1f - KEEP)
    }

    /** The throw to hand to the viewport, which is nothing if the hand had already stopped. */
    fun release(now: Long = System.nanoTime()): Offset {
        if (movedAt == 0L) return Offset.Zero
        if (now - movedAt > STILL_NS) return Offset.Zero
        return Offset(vx, vy)
    }

    private companion object {
        /** How much of the speed so far survives each new sample. */
        const val KEEP = 0.55f

        const val MIN_INTERVAL = 0.004f

        /** Longer than this since the page last moved and letting go throws nothing. */
        const val STILL_NS = 80_000_000L
    }
}
