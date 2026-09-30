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
        internal set

    /** What it is doing, or why it stopped - shown by the button for a moment. */
    var status by mutableStateOf<String?>(null)
        private set

    /**
     * Where it is, for showing: its place in what it follows, the page's stretch of it, when it
     * will turn (null on the last page), whether it hears music, and where the turns come from.
     */
    data class Follow(
        val atMs: Long,
        val pageFromMs: Long,
        val turnMs: Long?,
        val music: Boolean,
        val source: String,
        val page: Int,
        val pages: Int,
        /** When it last turned a page itself (System time), so the turn can be shown as its own. */
        val turnedAt: Long = 0L,
        /** The page it turns to next (0-based) when that is not simply the next - a repeat going back. */
        val nextPage: Int? = null
    )

    var follow by mutableStateOf<Follow?>(null)
        internal set

    @Volatile private var run: Any? = null

    /** The recordings' notes, worked out once each: the last few kept. */
    private val cache = object : LinkedHashMap<String, List<Chroma.Frame>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Chroma.Frame>>?) = size > 4
    }

    fun toggle(state: SheetsState) = if (active) stop(state, null) else start(state)

    /**
     * What it is doing in a few words - under its button, and on a remote's Listen button: never
     * quiet about a microphone that hears nothing, and honest when the turns are only guessed.
     */
    fun summary(state: SheetsState): String? {
        if (!state.listenTurns) return null
        if (!active) return status
        if (Ears.deaf) return "Hears nothing yet" + (Ears.device?.let { " - $it" } ?: "")
        val f = follow ?: return status
        val words = when {
            System.currentTimeMillis() - f.turnedAt < 2_000 -> "Turned to page ${f.page + 1}"
            !f.music -> "Waiting for the music"
            f.turnMs == null -> "Last page - following"
            else -> {
                val s = ((f.turnMs - f.atMs) / 1000).coerceAtLeast(0)
                if (s < 1) "Turning..." else "Turn in ${s}s"
            }
        }
        return words + if (f.source == "guessed") " (guessed)" else ""
    }

    fun start(state: SheetsState) {
        if (active) return
        val song = state.current ?: return
        // The music read off this part (experimental), when it has been: turns without teaching.
        val partFile = state.currentPath?.let { java.io.File(it) }
        val score = if (state.readMusic && partFile != null) Transcriber.cached(state, partFile) else null
        val track = song.audio.firstOrNull()
        if (track == null && score == null) { say(state, "Listen needs a recording of this song, or its music read"); return }
        val file = track?.let { state.fileOf(it.file) }
        if (track != null && file == null && score == null) { say(state, "Its recording is not on this device"); return }
        val microphone = state.platform.microphone ?: run { say(state, "No microphone here"); return }
        val me = Any()
        val rate = microphone.sampleRate
        run = me
        active = true
        status = "Getting ready..."
        val instrument = state.partShown()?.instrument?.let { com.inksheets.core.PartChoice.seat(it).first }?.let { com.inksheets.core.Instruments.byId[it] }
        val bpm = song.tempo?.toDouble()?.takeIf { it > 0 } ?: SharedMetronome.bpm
        Thread({
            val recording = file?.let { f -> synchronized(cache) { cache[f.path] } ?: run {
                val stream = ArrayList<Chroma.Frame>()
                var chroma: Chroma.Stream? = null
                val ok = state.platform.decodeAudio(f) { samples, rate ->
                    val s = chroma ?: Chroma.Stream(rate).also { chroma = it }
                    stream += s.feed(samples)
                }
                if (ok && stream.isNotEmpty()) stream.also { synchronized(cache) { cache[f.path] = it } } else null
            } }
            if (run !== me) return@Thread
            // The music as it is played: repeats twice, the right ending each time.
            val played = score?.let { com.inksheets.core.omr.PlayOrder.unrolled(it) }
            // What to follow: the recording where there is one, else the music as read.
            val reference = recording ?: played?.let { com.inksheets.core.omr.ScoreAudio.frames(it, bpm, instrument?.transpose ?: 0) }
            if (reference == null || reference.isEmpty()) { state.platform.onMain { stop(state, "Couldn't read the recording") }; return@Thread }
            val (page0, pages) = state.pageShown
            // Where the turns come from: learned by playing along; else from the music read (lined
            // up with the recording, or the music itself); else guessed.
            val learned = track?.turnsMs.orEmpty().takeIf { pages > 1 && it.size >= pages - 1 }
            // Every change of page from the music: with a repeat across a page break, some go back.
            val changes = if (learned == null && played != null) runCatching {
                if (recording != null) com.inksheets.core.omr.ScoreAudio.changesIn(played, recording, bpm, instrument?.transpose ?: 0)
                else com.inksheets.core.omr.ScoreAudio.pageChanges(played, bpm)
            }.getOrNull() else null
            val goesBack = changes != null && changes.zipWithNext().any { (a, b) -> b.second < a.second }
            val fromMusic = if (changes != null && !goesBack) changes.map { it.first }.takeIf { it.size >= pages - 1 } else null
            val path = state.currentPath
            val plan = TurnPlan(learned ?: fromMusic ?: emptyList(), pages, reference.size * Chroma.FRAME_MS)
            val source = when { learned != null -> "learned"; fromMusic != null || goesBack -> "from the music"; else -> "guessed" }
            val startMs = if (page0 <= 0) 0L else plan.turnAt(page0 - 1) ?: 0L
            val follower = ScoreFollower(reference, startMs)
            val presence = MusicPresence()
            val stream = Chroma.Stream(rate)
            // Learned turns were made when the player turned, already a little early; following
            // the music itself (no recording) runs later, so it turns earlier.
            val lead = when { learned != null -> 300L; (fromMusic != null || goesBack) && recording != null -> 500L; fromMusic != null || goesBack -> 1_500L; else -> 1_000L }
            // Following the changes themselves (repeats): the next one to make.
            var next = changes?.indexOfFirst { it.first > startMs }?.takeIf { it >= 0 } ?: 0
            var turnedFrom = -1
            var turnedAt = 0L
            var shownAt = 0L
            state.platform.log("Listen: following ${song.title} (${if (recording != null) "its recording" else "its music as read"}) from ${startMs / 1000}s, turns $source" +
                (fromMusic?.let { t -> " at " + t.joinToString { "%.1f".format(java.util.Locale.ROOT, it / 1000.0) } + "s" } ?: ""))
            state.platform.onMain { if (run === me) status = if (source == "guessed") "Listening - turns guessed" else "Listening - turns $source" }
            val opened = Ears.listen(state, WHO) { chunk ->
                if (run !== me) return@listen
                for (f in stream.feed(chunk)) {
                    presence.hear(f)
                    val at = follower.hear(f, presence.quiet(f))
                    val page = state.pageShown.first
                    if (goesBack && changes != null && path != null) {
                        // To whichever page comes next - on, or back for a repeat.
                        val c = changes.getOrNull(next)
                        if (c != null && at >= c.first - lead) {
                            next++
                            turnedAt = System.currentTimeMillis()
                            state.platform.onMain { if (run === me) Perform.jumpTo?.invoke(path, c.second) }
                        }
                    }
                    val turn = if (goesBack) changes?.getOrNull(next)?.first else plan.turnAt(page)
                    if (!goesBack && turn != null && page != turnedFrom && at >= turn - lead) {
                        turnedFrom = page
                        turnedAt = System.currentTimeMillis()
                        state.platform.onMain { if (run === me) Perform.run(PerformAction.NEXT_PAGE) }
                    }
                    val now = System.currentTimeMillis()
                    if (now - shownAt >= 200) {
                        shownAt = now
                        val from = if (goesBack) changes?.getOrNull(next - 1)?.first ?: 0L else if (page <= 0) 0L else plan.turnAt(page - 1) ?: 0L
                        val shown = Follow(at, from, turn?.let { it - lead }, !presence.quiet(f), source, page, pages, turnedAt,
                            nextPage = if (goesBack) changes?.getOrNull(next)?.second else null)
                        state.platform.onMain { if (run === me) follow = shown }
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
        follow = null
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
