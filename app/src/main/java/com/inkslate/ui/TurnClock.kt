package com.inkslate.ui

import com.inkslate.data.EventLog

/**
 * How long a turn to another song takes to show its page, said once per turn in the log - the
 * number to look at when a turn feels slow, rather than a guess at which part of it is.
 */
object TurnClock {
    @Volatile private var startedAt = 0L
    @Volatile private var to: String? = null

    /** A turn to [title] has begun. */
    fun start(title: String?) {
        startedAt = android.os.SystemClock.uptimeMillis()
        watchUntil = startedAt + WATCH_MS
        to = title
        stalls = 0
        watchFrames()
    }

    @Volatile private var watchUntil = 0L
    @Volatile private var stalls = 0
    private var lastFrameNs = 0L
    private var watching = false

    /**
     * For a few seconds after a turn, every frame that took far longer than it should is said in
     * the log with how long it took - a freeze named by number, when it happens, not guessed at.
     */
    private fun watchFrames() {
        if (watching) return
        watching = true
        lastFrameNs = 0L
        val choreographer = android.view.Choreographer.getInstance()
        choreographer.postFrameCallback(object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameNs: Long) {
                if (lastFrameNs != 0L) {
                    val gap = (frameNs - lastFrameNs) / 1_000_000
                    if (gap >= STALL_MS && stalls < 5) {
                        stalls++
                        val since = android.os.SystemClock.uptimeMillis() - (watchUntil - WATCH_MS)
                        EventLog.warn("turn", "Screen froze ${gap}ms, ${since}ms after turning to ${to ?: "a song"}")
                    }
                }
                lastFrameNs = frameNs
                if (android.os.SystemClock.uptimeMillis() < watchUntil) choreographer.postFrameCallback(this)
                else watching = false
            }
        })
    }

    private const val WATCH_MS = 3000L
    private const val STALL_MS = 100L

    /** Its page is on screen: [how] it got there - drawn ahead, or drawn on arrival. */
    fun shown(how: String) {
        val at = startedAt
        if (at == 0L) return
        startedAt = 0L
        EventLog.info("turn", "${to ?: "Song"} shown ${android.os.SystemClock.uptimeMillis() - at}ms after the turn ($how)")
    }
}
