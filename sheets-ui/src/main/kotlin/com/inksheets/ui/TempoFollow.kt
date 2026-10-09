package com.inksheets.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inksheets.core.TempoTracker
import kotlin.math.abs

/**
 * The metronome following the band (experimental, with Listen in Settings): the tempo heard
 * through the microphone ([TempoTracker]) becomes the metronome's, eased towards rather than
 * jumped to, and only once a few readings in a row agree - so a fill or a held note does not
 * throw it. The click goes quiet meanwhile: the microphone would hear it, and follow itself.
 */
internal object TempoFollow {
    var on by mutableStateOf(false)
        private set

    /** The tempo heard last. */
    var heard by mutableStateOf<Double?>(null)
        private set

    /** How far the band is from the song's marked tempo, as a fraction (+0.04: 4% fast); null when the song has none. */
    var drift by mutableStateOf<Double?>(null)
        private set

    var problem by mutableStateOf<String?>(null)
        private set

    private const val WHO = "tempo"
    private var wasMuted = false

    fun set(state: SheetsState, follow: Boolean) = if (follow) start(state) else stop(state)

    fun start(state: SheetsState) {
        if (on) return
        val engine = Click.engine(state) ?: run { problem = "No sound output here"; return }
        problem = null
        var tracker: TempoTracker? = null
        val recent = ArrayDeque<Double>()
        // The song's marked tempo, when it has one, is what the band is expected near - not wherever
        // the metronome happened to be - so it settles on the same beat whatever the click started at.
        val marked = state.current?.tempo?.toDouble()?.takeIf { it in 20.0..300.0 }
        on = true
        wasMuted = engine.muted
        engine.muted = true
        val opened = Ears.listen(state, WHO) { chunk ->
            val t = tracker ?: TempoTracker(Ears.rate).also { tracker = it }
            t.expected = marked ?: SharedMetronome.bpm
            if (!t.feed(chunk)) return@listen
            val bpm = t.bpm ?: return@listen
            recent.addLast(bpm)
            while (recent.size > 3) recent.removeFirst()
            // Three readings within 4% of each other: the band's tempo, not a moment of it.
            if (recent.size < 3 || recent.maxOrNull()!! / recent.minOrNull()!! > 1.04) return@listen
            val agreed = fold(recent.average(), marked)
            state.platform.onMain {
                if (!on) return@onMain
                heard = agreed
                drift = marked?.let { agreed / it - 1 }
                val now = SharedMetronome.bpm
                // Eased: half way each time, and never more than a tenth at once.
                val step = ((agreed - now) * 0.5).coerceIn(-now * 0.1, now * 0.1)
                if (abs(step) >= 0.3) Click.setBpm(state, now + step)
            }
        }
        if (!opened) { stop(state); problem = "The microphone could not be opened" }
    }

    /** [bpm] at the octave nearest the song's [marked] tempo: a half or double-time lock is the same beat. */
    internal fun fold(bpm: Double, marked: Double?): Double {
        if (marked == null) return bpm
        var b = bpm
        while (b / marked > 1.7) b /= 2
        while (b / marked < 0.6) b *= 2
        return b
    }

    fun stop(state: SheetsState) {
        if (!on) return
        on = false
        heard = null
        drift = null
        Click.engine(state)?.muted = wasMuted
        Thread({ Ears.stop(WHO) }, "tempo-stop").apply { isDaemon = true; start() }
    }
}
