package com.inksheets.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * The microphone, shared: Listen and the metronome following the band can both hear at once. It
 * is opened for the first and closed after the last. How loud it hears, and whether it hears
 * anything at all, is shown wherever it is used: a microphone giving silence must never look the
 * same as one waiting for the music.
 */
internal object Ears {
    private val hearing = ConcurrentHashMap<String, (FloatArray) -> Unit>()
    @Volatile private var mic: Microphone? = null
    @Volatile private var lastChunkAt = 0L

    /** The rate of what is heard; 0 until open. */
    @Volatile var rate = 0
        internal set

    /** How loud, 0 (-60 dB and below) to 1 (full): a few times a second, while open. */
    var level by mutableFloatStateOf(0f)
        internal set

    /**
     * Nothing at all has come in since it opened (not quiet - silent), for a couple of seconds: the
     * input is dead, or nobody has made a sound yet. Said plainly, never shown as waiting.
     */
    var deaf by mutableStateOf(false)
        internal set

    /** The input being heard, where the device has a choice of them. */
    var device by mutableStateOf<String?>(null)
        internal set

    private var peak = 0f
    private var shownAt = 0L
    private var openedAt = 0L
    private var everHeard = false
    private var heardOn: String? = null
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** [who] hears everything from now; false when the microphone could not be opened. */
    @Synchronized
    fun listen(state: SheetsState, who: String, onChunk: (FloatArray) -> Unit): Boolean {
        hearing[who] = onChunk
        if (mic != null) return true
        val m = state.platform.microphone ?: run { hearing.remove(who); return false }
        rate = m.sampleRate
        openedAt = System.currentTimeMillis()
        everHeard = false
        heardOn = null
        peak = 0f
        failed.clear()
        lastChunkAt = openedAt
        val started = m.start { chunk ->
            measure(state, m, chunk)
            for ((name, hear) in hearing) runCatching { hear(chunk) }.onFailure {
                // Said once, not swallowed: a listener failing on every chunk looks like one hearing nothing.
                if (failed.add(name)) state.platform.log("Listening ($name) failed: $it")
            }
        }
        if (!started) { hearing.remove(who); return false }
        mic = m
        watch(state, m)
        return true
    }

    /**
     * Nothing arriving at all (the input was taken by something else, the device went away) is
     * deaf too: the chunks that would say so never come, so a timer says it instead.
     */
    private fun watch(state: SheetsState, m: Microphone) {
        Thread({
            while (mic === m) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
                if (mic === m && System.currentTimeMillis() - lastChunkAt > 2_000) {
                    state.platform.onMain { if (mic === m) { level = 0f; deaf = true } }
                }
            }
        }, "ears-watch").apply { isDaemon = true; start() }
    }

    private fun measure(state: SheetsState, m: Microphone, chunk: FloatArray) {
        for (v in chunk) peak = max(peak, abs(v))
        val now = System.currentTimeMillis()
        lastChunkAt = now
        // Another input taken (the first gave silence): it has heard nothing yet either.
        if (m.inUse != heardOn) { heardOn = m.inUse; if (!everHeard) openedAt = now }
        if (peak > 24f / 32768f) everHeard = true
        if (now - shownAt < 80) return
        shownAt = now
        val db = if (peak <= 0f) -60f else 20f * log10(peak)
        val shown = ((db + 60f) / 60f).coerceIn(0f, 1f)
        val nothing = !everHeard && now - openedAt > 2_000
        peak = 0f
        val name = m.inUse
        state.platform.onMain {
            level = shown
            deaf = nothing
            device = name
        }
    }

    /** [who] no longer hears; the microphone closes when nobody does. Off the audio thread. */
    @Synchronized
    fun stop(who: String) {
        hearing.remove(who)
        if (hearing.isEmpty()) {
            mic?.stop()
            mic = null
            level = 0f
            deaf = false
        }
    }
}
