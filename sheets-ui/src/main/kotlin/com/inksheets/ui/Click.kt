package com.inksheets.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inksheets.core.Metronome
import com.inksheets.core.ClickSound
import com.inksheets.core.ClickSounds

/**
 * The metronome's one voice, and what it is doing: ticking on its own, counting in (a count-off
 * for the band, or before recording yourself or playing a recording), or clicking under a
 * recording in time with it. The count-in and the start of what follows are placed in the audio
 * itself ([Metronome.cueAt]), so the first beat lands where the count-in says it will.
 */
internal object Click {
    enum class Purpose { PLAIN, COUNT_OFF, RECORD, PLAYBACK }

    /** What the click is doing now; null when silent. */
    var purpose by mutableStateOf<Purpose?>(null)
        private set

    /** The same, for threads away from the screen. */
    @Volatile
    var live: Purpose? = null
        private set

    /** Beats of count-in still to come, for the big number over the music; 0 when not counting. */
    var counting by mutableStateOf(0)
        private set

    // ---- the options, kept on this device ---------------------------------------------------

    private var prefs: SheetsPlatform? = null
    private fun load(p: SheetsPlatform) {
        if (prefs === p) return
        prefs = p
        countInBarsState = p.pref(K_COUNT_IN)?.toIntOrNull() ?: 1
        withRecordingState = p.pref(K_WITH_RECORDING) == "true"
        withPlaybackState = p.pref(K_WITH_PLAYBACK) == "true"
        soundState=runCatching { ClickSound.valueOf(p.pref(K_SOUND).orEmpty()) }.getOrDefault(ClickSound.TONE)
    }

    private var countInBarsState by mutableStateOf(1)
    private var withRecordingState by mutableStateOf(false)
    private var withPlaybackState by mutableStateOf(false)
    private var soundState by mutableStateOf(ClickSound.TONE)
    fun sound(state: SheetsState): ClickSound { load(state.platform);return soundState }
    fun setSound(state: SheetsState,sound: ClickSound) {
        load(state.platform);soundState=sound
        state.platform.setPref(K_SOUND,sound.name)
        SharedMetronome.engine?.let { it.settings=it.settings.copy(sound=sound) }
        ScoreTools.updateClickSound(sound)
    }

    private val previews=java.util.concurrent.atomic.AtomicLong()
    /** Explicit one-shot audition. Selecting a sound or opening Timing never calls this. */
    fun preview(state: SheetsState): Boolean {
        val rate=Sound.rate(state).takeIf { it>0 } ?: return false
        val token=previews.incrementAndGet()
        val wave=ClickSounds.samples(sound(state),Metronome.Accent.STRONG,rate)
        val volume=(SharedMetronome.engine?.settings?.volume ?: .8f).coerceIn(0f,1f)*.65f
        var at=0;var done=false
        val started=Sound.play(state,PREVIEW) { out ->
            for(i in out.indices) out[i]=if(at<wave.size) wave[at++]*volume else 0f
            if(at>=wave.size && !done) { done=true;state.platform.onMain { if(previews.get()==token) Sound.stop(PREVIEW) } }
        }
        // Also release an output that stopped requesting buffers before the one-shot finished.
        if(started) java.util.Timer("click-preview",true).schedule(object: java.util.TimerTask() {
            override fun run()=state.platform.onMain { if(previews.get()==token) Sound.stop(PREVIEW) }
        },1000L)
        return started
    }

    /** Bars counted in before recording yourself and before a recording plays: 0, 1 or 2. */
    fun countInBars(state: SheetsState): Int { load(state.platform); return countInBarsState }
    fun setCountInBars(state: SheetsState, bars: Int) { load(state.platform); countInBarsState = bars.coerceIn(0, 4); state.platform.setPref(K_COUNT_IN, countInBarsState.toString()) }

    /** The click carries on while you record yourself (after the count-in). */
    fun withRecording(state: SheetsState): Boolean { load(state.platform); return withRecordingState }
    fun setWithRecording(state: SheetsState, on: Boolean) { load(state.platform); withRecordingState = on; state.platform.setPref(K_WITH_RECORDING, on.toString()) }

    /** A recording plays with the click under it, in time with it. */
    fun withPlayback(state: SheetsState): Boolean { load(state.platform); return withPlaybackState }
    fun setWithPlayback(state: SheetsState, on: Boolean) { load(state.platform); withPlaybackState = on; state.platform.setPref(K_WITH_PLAYBACK, on.toString()) }

    // ---- the voice --------------------------------------------------------------------------

    fun engine(state: SheetsState): Metronome? {
        val out = state.platform.audioOut ?: return null
        load(state.platform)
        return (SharedMetronome.engine ?: Metronome(out.sampleRate).also { SharedMetronome.engine = it }).also {
            it.settings=it.settings.copy(sound=soundState)
        }
    }

    /** Set the tempo, wherever it is used next - and at once, if it is ticking. */
    fun setBpm(state: SheetsState, bpm: Double) {
        val e = engine(state) ?: return
        val b = bpm.coerceIn(20.0, 300.0)
        e.settings = e.settings.copy(bpm = b)
        SharedMetronome.bpm = b
    }

    private fun begin(state: SheetsState, why: Purpose, countBeats: Int): Metronome? {
        state.platform.audioOut ?: return null
        val e = engine(state) ?: return null
        if (purpose != null) Sound.stop(WHO)
        e.reset()
        // Following the band, the click stays quiet: the microphone would hear it and follow itself.
        if (TempoFollow.on) e.muted = true
        counting = countBeats
        e.onBeat = { beat ->
            SharedMetronome.beat = beat
            if (counting > 0) state.platform.onMain { if (counting > 0) counting-- }
        }
        Sound.play(state, WHO) { e.fill(it) }
        purpose = why
        live = why
        SharedMetronome.running = true
        return e
    }

    fun stop(state: SheetsState) {
        if (purpose == null) return
        Sound.stop(WHO)
        purpose = null
        live = null
        counting = 0
        SharedMetronome.running = false
        SharedMetronome.beat = -1
    }

    /** The metronome button: ticking on its own, or stopped - whatever it was doing. */
    fun toggle(state: SheetsState) {
        if (purpose != null) stop(state) else begin(state, Purpose.PLAIN, 0)
    }

    /** A count-off: [bars] bars (the count-in setting when null, at least one), then quiet. */
    fun countOff(state: SheetsState, bars: Int? = null) {
        val e = engine(state) ?: return
        val n = (bars ?: countInBars(state)).coerceAtLeast(1)
        val beats = n * e.settings.beatsPerBar
        begin(state, Purpose.COUNT_OFF, beats) ?: return
        e.cueAt(e.samplesFor(n)) { state.platform.onMain { if (purpose == Purpose.COUNT_OFF) stop(state) } }
    }

    /**
     * Count in, then run [then] (on the audio thread, at the first beat after the count-in). The
     * click goes on after if [keepGoing], else falls silent and stops. With no count-in and no
     * click wanted, [then] runs straight away and nothing is heard.
     */
    fun countInThen(state: SheetsState, why: Purpose, keepGoing: Boolean, phaseMs: Double? = null, then: () -> Unit): Boolean {
        val e = engine(state)
        val bars = countInBars(state)
        if (e == null || (bars == 0 && !keepGoing)) { then(); return false }
        val beats = bars * e.settings.beatsPerBar
        begin(state, why, beats) ?: run { then(); return false }
        val countSamples = e.samplesFor(bars)
        // The grid lined up with the music: at the end of the count-in it is where the music starts.
        phaseMs?.let { e.phaseTo(it - e.msFor(bars)) }
        if (countSamples == 0L) {
            then()
        } else e.cueAt(countSamples) {
            then()
            if (!keepGoing) {
                e.muted = true
                state.platform.onMain { if (purpose == why) stop(state) }
            }
        }
        return true
    }

    private const val WHO = "click"
    private const val PREVIEW = "click-preview"
    private const val K_SOUND = "sheets_click_sound"
    private const val K_COUNT_IN = "sheets_count_in_bars"
    private const val K_WITH_RECORDING = "sheets_click_recording"
    private const val K_WITH_PLAYBACK = "sheets_click_playback"
}
