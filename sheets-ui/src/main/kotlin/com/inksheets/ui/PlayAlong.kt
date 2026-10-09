package com.inksheets.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction

/**
 * Playing along with a song's recording: the pages turn by themselves.
 *
 * The app knows exactly where its own recording is, so this needs no microphone and cannot
 * mishear. The first time through, the player turns the pages as the recording plays and each
 * turn is kept - for this part (the bass part's three pages turn at other moments than the
 * trombone part's two). From then on the turns come by themselves a moment early; a turn made by
 * hand before one comes moves it. Skipping back or on in the recording puts the right page up.
 *
 * What it will do is always on show (see [hint]): the next turn counting down, or that it is
 * learning, or that it is off - never a page that turns from nowhere.
 */
internal object PlayAlong {
    /** On unless the player turned it off (per device: one player may like it, another not). */
    var on by mutableStateOf(true)
        private set

    fun load(state: SheetsState) { on = state.platform.pref(K_ON) != "false" }

    fun toggle(state: SheetsState) {
        on = !on
        state.platform.setPref(K_ON, on.toString())
    }

    /** What it will do next, in a few words, for beside the recording's controls; null: nothing to say. */
    var hint by mutableStateOf<String?>(null)
        private set

    /** The turns it is following now are known for every page of the part in front. */
    var learned by mutableStateOf(false)
        private set

    private var autoTurnedAt = 0L
    private var lastPos = -1L
    private var lastAt = 0L
    private var lastPage = -1
    private var lastPath: String? = null

    /** Once every tenth of a second or so while a recording is loaded. */
    fun tick(state: SheetsState) {
        val player = Recording.player
        val song = state.current
        val path = state.currentPath
        if (player == null || song == null || path == null || song.id != Recording.songId) { hint = null; learned = false; return }
        val part = state.partShown()
        val (page, pages) = state.pageShown
        val track = currentTrack(state) ?: run { hint = null; return }
        val turns = track.turnsFor(part?.id, pages)
        learned = turns != null
        val pos = player.positionMs
        val now = System.currentTimeMillis()
        val jumped = lastPos >= 0 && kotlin.math.abs(pos - (lastPos + (now - lastAt) * track.speed)) > 2_000
        lastPos = pos; lastAt = now
        if (pages < 2) { hint = null; return }
        if (!on) { hint = "Turns: off"; return }
        if (Listener.active) { hint = null; return }
        if (turns == null) {
            hint = if (Recording.playing) "Turn as it plays: learning" else "Turns not learned"
            return
        }
        // Skipped back or on: the page that goes with that moment.
        if (jumped) {
            val want = turns.count { it - LEAD_MS <= pos }.coerceIn(0, pages - 1)
            if (want != page) {
                autoTurnedAt = now
                repeat(kotlin.math.abs(want - page)) { Perform.run(if (want > page) PerformAction.NEXT_PAGE else PerformAction.PREVIOUS_PAGE) }
            }
            return
        }
        val next = turns.getOrNull(page)
        if (next == null) { hint = "Last page"; return }
        val inMs = next - LEAD_MS - pos
        if (Recording.playing && inMs <= 0 && now - autoTurnedAt > 800) {
            autoTurnedAt = now
            Perform.run(PerformAction.NEXT_PAGE)
            hint = "Turned"
            return
        }
        hint = if (!Recording.playing) "Turns by itself" else if (inMs < 10_000) "Turn in ${(inMs / 1000) + 1}s" else "Turns by itself"
    }

    /**
     * The page in front changed. While the song's recording plays, a turn on to the next page
     * made by hand (not by [tick]) is where that turn falls for this part: kept with the recording.
     */
    fun pageChanged(state: SheetsState, path: String, page: Int) {
        val was = if (lastPath == path) lastPage else -1
        lastPath = path; lastPage = page
        if (!Recording.playing || Listener.active || page != was + 1 || was < 0) return
        if (System.currentTimeMillis() - autoTurnedAt < 1_500) return
        val song = state.songAt(path) ?: return
        if (song.id != Recording.songId) return
        val part = state.partShown() ?: return
        val track = currentTrack(state) ?: return
        val at = Recording.player?.positionMs ?: return
        state.change {
            editSong(song.id) {
                audio = song(song.id)?.audio.orEmpty().map { t -> if (t.file != track.file) t else t.withTurn(part.id, was, at) }
            }
        }
        state.platform.log("Play along: learned the turn from page ${was + 1} at ${at / 1000.0}s in ${track.file.substringAfterLast('/')}")
    }

    /** The recording playing, as the library has it now (turns learned since it was loaded included). */
    fun currentTrack(state: SheetsState): com.inksheets.core.AudioTrack? {
        val file = Recording.track?.file ?: return null
        return state.library?.song(Recording.songId ?: return null)?.audio?.firstOrNull { it.file == file } ?: Recording.track
    }

    /** Turns come this much early: the next page is up before its first note. */
    private const val LEAD_MS = 300L
    private const val K_ON = "sheets_play_along"
}
