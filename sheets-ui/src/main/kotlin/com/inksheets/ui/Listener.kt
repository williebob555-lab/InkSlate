package com.inksheets.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inksheets.core.AudioTrack
import com.inksheets.core.Chroma
import com.inksheets.core.MusicPresence
import com.inksheets.core.ScoreFollower
import com.inksheets.core.TurnPlan

/**
 * Turning pages by ear (experimental, in Settings): for one run of a song, the microphone is
 * followed along the song's recording ([ScoreFollower]) and the page turned as each turn in the
 * recording comes - a moment early, so the next page is up in time. It stops by itself when the
 * music does, when the song changes, or at the end of the recording.
 *
 * Where the turns fall is learned: playing the recording and turning the pages by hand writes
 * each turn's place into the recording ([AudioTrack.turnsMs]). Until then they are guessed, the
 * pages spread evenly over it.
 */
internal object Listener {
    /** Listening now: the toolbar button is lit. */
    var active by mutableStateOf(false)
        private set

    /** What it is doing, or why it stopped - shown by the button for a moment. */
    var status by mutableStateOf<String?>(null)
        private set

    @Volatile private var run: Any? = null

    /** The recordings' notes, worked out once each: the last few kept. */
    private val cache = object : LinkedHashMap<String, List<Chroma.Frame>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Chroma.Frame>>?) = size > 4
    }

    fun toggle(state: SheetsState) = if (active) stop(state, null) else start(state)

    fun start(state: SheetsState) {
        if (active) return
        val song = state.current ?: return
        val track = song.audio.firstOrNull() ?: run { say(state, "Listen needs a recording of this song"); return }
        val file = state.fileOf(track.file) ?: run { say(state, "Its recording is not on this device"); return }
        val microphone = state.platform.microphone ?: run { say(state, "No microphone here"); return }
        val me = Any()
        val rate = microphone.sampleRate
        run = me
        active = true
        status = "Getting ready..."
        Thread({
            val reference = synchronized(cache) { cache[file.path] } ?: run {
                val stream = ArrayList<Chroma.Frame>()
                var chroma: Chroma.Stream? = null
                val ok = state.platform.decodeAudio(file) { samples, rate ->
                    val s = chroma ?: Chroma.Stream(rate).also { chroma = it }
                    stream += s.feed(samples)
                }
                if (ok && stream.isNotEmpty()) stream.also { synchronized(cache) { cache[file.path] = it } } else null
            }
            if (run !== me) return@Thread
            if (reference == null) { state.platform.onMain { stop(state, "Couldn't read the recording") }; return@Thread }
            val (page0, pages) = state.pageShown
            val plan = TurnPlan(track.turnsMs, pages, reference.size * Chroma.FRAME_MS)
            val startMs = if (page0 <= 0) 0L else plan.turnAt(page0 - 1) ?: 0L
            val follower = ScoreFollower(reference, startMs)
            val presence = MusicPresence()
            val stream = Chroma.Stream(rate)
            // Learned turns were made when the player turned, already a little early.
            val lead = if (plan.learned) 300L else 1_000L
            var turnedFrom = -1
            state.platform.log("Listen: following ${song.title} from ${startMs / 1000}s, turns " + if (plan.learned) "learned" else "guessed")
            state.platform.onMain { if (run === me) status = if (plan.learned) "Listening" else "Listening - turns guessed" }
            val opened = Ears.listen(state, WHO) { chunk ->
                if (run !== me) return@listen
                for (f in stream.feed(chunk)) {
                    presence.hear(f)
                    val at = follower.hear(f, presence.quiet(f))
                    val page = state.pageShown.first
                    val turn = plan.turnAt(page)
                    if (turn != null && page != turnedFrom && at >= turn - lead) {
                        turnedFrom = page
                        state.platform.onMain { if (run === me) Perform.run(PerformAction.NEXT_PAGE) }
                    }
                    val why = when {
                        state.current?.id != song.id -> "Stopped: another song"
                        presence.stopped -> "The music stopped"
                        at >= follower.lengthMs - 500 && presence.quiet(f) -> "The end of the recording"
                        else -> null
                    }
                    if (why != null) {
                        run = null
                        state.platform.onMain { stop(state, why) }
                        return@listen
                    }
                }
            }
            if (!opened) state.platform.onMain { stop(state, "The microphone could not be opened") }
            else if (run !== me) Ears.stop(WHO)
        }, "listen").apply { isDaemon = true; start() }
    }

    private const val WHO = "listen"

    /** Stop listening; [why] shows by the button for a moment. On the UI thread. */
    fun stop(state: SheetsState, why: String? = null) {
        run = null
        Thread({ Ears.stop(WHO) }, "listen-stop").apply { isDaemon = true; start() }
        if (active) why?.let { state.platform.log("Listen: $it") }
        active = false
        say(state, why)
    }

    private fun say(state: SheetsState, text: String?) {
        status = text
        if (text != null) java.util.Timer("listen-status", true).schedule(object : java.util.TimerTask() {
            override fun run() = state.platform.onMain { if (!active && status == text) status = null }
        }, 5_000L)
    }

    // ---- learning where the turns fall -----------------------------------------------------

    private var lastPage = -1
    private var lastPath: String? = null

    /**
     * The page in front changed. While a song's recording plays (and Listen is on in Settings),
     * a turn on to the next page by hand is where that turn falls in the recording: kept with it.
     */
    fun pageChanged(state: SheetsState, path: String, page: Int) {
        val was = if (lastPath == path) lastPage else -1
        lastPath = path; lastPage = page
        if (!state.listenTurns || active || !Recording.playing || page != was + 1 || was < 0) return
        val song = state.songAt(path) ?: return
        if (song.id != Recording.songId) return
        val track = Recording.track ?: return
        val at = Recording.player?.positionMs ?: return
        state.change {
            editSong(song.id) {
                audio = audio.orEmpty().map { t ->
                    if (t.file != track.file) t else {
                        val turns = t.turnsMs.toMutableList()
                        while (turns.size < was) turns += -1L
                        if (turns.size == was) turns += at else turns[was] = at
                        t.copy(turnsMs = turns)
                    }
                }
            }
        }
        state.platform.log("Listen: learned the turn from page ${was + 1} at ${at / 1000.0}s in ${track.file.substringAfterLast('/')}")
    }
}
