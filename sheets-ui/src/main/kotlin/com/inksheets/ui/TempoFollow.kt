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
        on = true
        wasMuted = engine.muted
        engine.muted = true
        val opened = Ears.listen(state, WHO) { chunk ->
            val t = tracker ?: TempoTracker(Ears.rate).also { tracker = it }
            t.expected = SharedMetronome.bpm
            if (!t.feed(chunk)) return@listen
            val bpm = t.bpm ?: return@listen
            recent.addLast(bpm)
            while (recent.size > 3) recent.removeFirst()
            // Three readings within 4% of each other: the band's tempo, not a moment of it.
            if (recent.size < 3 || recent.maxOrNull()!! / recent.minOrNull()!! > 1.04) return@listen
            val agreed = recent.average()
            state.platform.onMain {
                if (!on) return@onMain
                heard = agreed
                val now = SharedMetronome.bpm
                // Eased: half way each time, and never more than a tenth at once.
                val step = ((agreed - now) * 0.5).coerceIn(-now * 0.1, now * 0.1)
                if (abs(step) >= 0.3) Click.setBpm(state, now + step)
            }
        }
        if (!opened) { stop(state); problem = "The microphone could not be opened" }
    }

    fun stop(state: SheetsState) {
        if (!on) return
        on = false
        heard = null
        Click.engine(state)?.muted = wasMuted
        Thread({ Ears.stop(WHO) }, "tempo-stop").apply { isDaemon = true; start() }
    }
}
