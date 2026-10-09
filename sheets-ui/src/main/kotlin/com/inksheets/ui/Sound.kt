package com.inksheets.ui

import java.util.concurrent.ConcurrentHashMap

/**
 * The one sound output, shared: the metronome's click and the instrument playing a passage are
 * heard together, each adding its own into what goes out. Opened for the first, closed after the
 * last.
 */
internal object Sound {
    private val sources = ConcurrentHashMap<String, (FloatArray) -> Unit>()
    /** Sources made ahead of the speaker (see RenderAhead): the instrument, not the metronome, whose clicks must be on time. */
    private val ahead = ConcurrentHashMap<String, com.inksheets.core.RenderAhead>()
    private var out: AudioOut? = null
    @Volatile private var scratch = FloatArray(0)

    /** The rate of the output here; 0 where there is none. */
    fun rate(state: SheetsState): Int = state.platform.audioOut?.sampleRate ?: 0

    /** [who] is heard from now: [fill] writes its next samples into the buffer it is given. False with no output here. */
    @Synchronized
    fun play(state: SheetsState, who: String, ahead: Boolean = false, fill: (FloatArray) -> Unit): Boolean {
        val o = state.platform.audioOut ?: return false
        this.ahead.remove(who)?.stop()
        if (ahead) {
            // Rendered on a thread of its own, 200 ms in front: a busy device cannot starve the speaker.
            val made = com.inksheets.core.RenderAhead(o.sampleRate, 200, 480, fill)
            made.start(prefillMs = 0)
            this.ahead[who] = made
            sources[who] = { buf -> made.read(buf) }
        } else sources[who] = fill
        if (out == null) {
            out = o
            o.start { buf -> mix(buf) }
        }
        return true
    }

    fun playing(who: String) = sources.containsKey(who)

    private fun mix(buf: FloatArray) {
        java.util.Arrays.fill(buf, 0f)
        var part = scratch
        if (part.size != buf.size) { part = FloatArray(buf.size); scratch = part }
        for (fill in sources.values) {
            java.util.Arrays.fill(part, 0f)
            runCatching { fill(part) }
            for (i in buf.indices) buf[i] += part[i]
        }
        // Two things loud at once never clip harshly.
        for (i in buf.indices) { val v = buf[i]; if (v > 1f || v < -1f) buf[i] = kotlin.math.tanh(v) }
    }

    /** [who] is no longer heard; the output closes when nothing is. */
    @Synchronized
    fun stop(who: String) {
        sources.remove(who)
        ahead.remove(who)?.stop()
        if (sources.isEmpty()) {
            out?.stop()
            out = null
        }
    }
}
