package com.inksheets.ui

import java.util.concurrent.ConcurrentHashMap

/**
 * The microphone, shared: Listen and the metronome following the band can both hear at once. It
 * is opened for the first and closed after the last.
 */
internal object Ears {
    private val hearing = ConcurrentHashMap<String, (FloatArray) -> Unit>()
    private var mic: Microphone? = null

    /** The rate of what is heard; 0 until open. */
    @Volatile var rate = 0
        private set

    /** [who] hears everything from now; false when the microphone could not be opened. */
    @Synchronized
    fun listen(state: SheetsState, who: String, onChunk: (FloatArray) -> Unit): Boolean {
        hearing[who] = onChunk
        if (mic != null) return true
        val m = state.platform.microphone ?: run { hearing.remove(who); return false }
        rate = m.sampleRate
        if (!m.start { chunk -> hearing.values.forEach { runCatching { it(chunk) } } }) { hearing.remove(who); return false }
        mic = m
        return true
    }

    /** [who] no longer hears; the microphone closes when nobody does. Off the audio thread. */
    @Synchronized
    fun stop(who: String) {
        hearing.remove(who)
        if (hearing.isEmpty()) {
            mic?.stop()
            mic = null
        }
    }
}
