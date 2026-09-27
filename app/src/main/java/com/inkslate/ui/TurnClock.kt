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
        to = title
    }

    /** Its page is on screen: [how] it got there - drawn ahead, or drawn on arrival. */
    fun shown(how: String) {
        val at = startedAt
        if (at == 0L) return
        startedAt = 0L
        EventLog.info("turn", "${to ?: "Song"} shown ${android.os.SystemClock.uptimeMillis() - at}ms after the turn ($how)")
    }
}
