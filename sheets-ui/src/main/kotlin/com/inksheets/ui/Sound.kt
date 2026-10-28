package com.inksheets.ui

import java.util.concurrent.ConcurrentHashMap

/**
 * The one sound output, shared: the metronome's click and the instrument playing a passage are
 * heard together, each adding its own into what goes out. Opened for the first, closed after the
 * last.
 */
internal object Sound {
    private val sources = ConcurrentHashMap<String, (FloatArray) -> Unit>()
    private val paused = ConcurrentHashMap<String, (FloatArray) -> Unit>()
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
        paused.remove(who)
        this.ahead.remove(who)?.stop()
        if (ahead) {
            // Rendered on a thread of its own, 200 ms in front: a busy device cannot starve the speaker.
            val made = com.inksheets.core.RenderAhead(o.sampleRate, 200, 480, fill)
            made.start(prefillMs = 0)
            this.ahead[who] = made
            sources[who] = { buf -> if(!paused.containsKey(who)) made.read(buf) }
        } else sources[who] = fill
        if (out == null) {
            out = o
            o.start { buf -> mix(buf) }
        }
        return true
    }

    fun playing(who: String) = sources.containsKey(who)

    /** Silence this source without losing its queued audio, note tails or repeat position. */
    fun pause(who: String): Boolean {
        val renderer=synchronized(this) {
            val source=sources.remove(who) ?: return false
            paused[who]=source
            // Keep the device open and mix silence until Resume/Stop. Stopping a device can join
            // its callback thread; pause must never wait for that callback under Sound's monitor.
            ahead[who]
        }
        // A renderer's completion may call Sound: never hold Sound's monitor while waiting for it.
        renderer?.pause()
        return true
    }

    @Synchronized
    fun resume(state: SheetsState,who: String): Boolean {
        val source=paused[who] ?: return false
        val output=state.platform.audioOut ?: return false
        if(ahead[who]?.sampleRate?.let { it!=output.sampleRate }==true) return false
        if(ahead[who]?.resume()==false) return false
        paused.remove(who)
        sources[who]=source
        if(out==null) { out=output;output.start { buf -> mix(buf) } }
        return true
    }

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
        for(i in buf.indices) buf[i]=smoothSoundMix(buf[i])
    }

    /** [who] is no longer heard; the output closes when nothing is. */
    @Synchronized
    fun stop(who: String) {
        sources.remove(who)
        paused.remove(who)
        ahead.remove(who)?.stop()
        if (sources.isEmpty()) {
            out?.stop()
            out = null
        }
    }
}
