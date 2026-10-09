package com.inksheets.ui

/**
 * Practice time, kept without being asked: the time a song is in front while pages are turning,
 * a recording is playing or the metronome is going. Added to the library a minute at a time
 * (each device its own total per song and day, so it syncs without clashing) and shown on the
 * song's details.
 */
internal object PracticeLog {
    /** When a page last turned, a note was written, or anything else that says someone is playing. */
    @Volatile private var activeAt = 0L
    private const val IDLE_MS = 120_000L
    private const val TICK_MS = 5_000L
    private const val FLUSH_SECONDS = 60L

    private var started = false
    private var songId: String? = null
    private var pending = 0L

    fun touch() { activeAt = System.currentTimeMillis() }

    @Synchronized
    fun start(state: SheetsState) {
        if (started) return
        started = true
        Thread({
            while (true) {
                try { Thread.sleep(TICK_MS) } catch (_: InterruptedException) { return@Thread }
                runCatching { tick(state) }
            }
        }, "practice-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private fun tick(state: SheetsState) {
        val id = if (state.homeInFront) null else state.current?.id
        val playing = Recording.playing || SharedMetronome.running
        val active = id != null && (playing || System.currentTimeMillis() - activeAt < IDLE_MS)
        if (songId != id) { flush(state); songId = id }
        if (active) pending += TICK_MS / 1000
        if (pending >= FLUSH_SECONDS) flush(state)
    }

    private fun flush(state: SheetsState) {
        val id = songId
        val seconds = pending
        pending = 0
        if (id == null || seconds <= 0) return
        state.library?.addPractice(id, java.time.LocalDate.now().toString(), seconds)
    }

    /** "3 h 20 min in all, last 8 Oct" - or null when there is none. */
    fun summary(state: SheetsState, songId: String): String? {
        val p = state.library?.practiceOf(songId) ?: return null
        if (p.totalSeconds < 60) return null
        val minutes = p.totalSeconds / 60
        val total = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
        val last = p.lastDay?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        val lastText = last?.let { ", last " + it.dayOfMonth + " " + it.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) }
        return "Practised $total in all" + (lastText ?: "")
    }
}
