package com.inksheets.desktop.review

import androidx.compose.ui.test.ExperimentalTestApi
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inksheets.core.AudioTrack
import com.inksheets.ui.PlayAlong
import com.inksheets.ui.Recording
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * T6: play-along in the real app. A fake player (T3Player) stands in for the sound card; the
 * song's pages are turned by hand while "its recording" plays (the app learns each turn),
 * then it plays again and the app has to turn the pages itself, a moment early.
 */
@OptIn(ExperimentalTestApi::class)
class T6PlayAlongTest {
    private fun say(t: String) = T3.say("t6-playalong.txt", t)
    private fun ok(name: String, cond: Boolean, detail: String = "") = say((if (cond) "PASS " else "FAIL ") + name + (if (detail.isNotEmpty()) " - $detail" else ""))

    @After
    fun reset() { Recording.player = null; Recording.loadedFile = null; resetFlavor() }

    @Test
    fun `learn the turns by hand, then the pages turn by themselves`() {
        assumeTrue(T2Lib.available())
        val mp3 = File(T3.lib, "Sep/September.mp3")
        assumeTrue(mp3.isFile)
        Recording.player = null; Recording.loadedFile = null
        t6App("t6pa") {
            val st = sheets()
            mp3.copyTo(File(T2Lib.dir, "Party Medley/Party Medley.mp3"), overwrite = true)
            st.scanFolder(); waitFor(10_000) { st.library!!.songs.first { it.title == "Party Medley" }.audio.isNotEmpty() }
            var song = st.library!!.songs.first { it.title == "Party Medley" }
            say("INFO audio after scan: ${song.audio.map { it.file }}")
            if (song.audio.isEmpty()) {
                st.change { editSong(song.id) { audio = listOf(AudioTrack(file = "Party Medley/Party Medley.mp3")) } }
                song = st.library!!.song(song.id)!!
            }
            ok("the song has a recording", song.audio.isNotEmpty())
            val player = T3Player { 190_000L }
            Recording.player = player
            open("Party Medley"); waitPages()
            val pages = count()
            ok("the part has several pages", pages >= 2, "pages $pages")
            st.stripCollapsed = false; settle(30)
            Perform.run(PerformAction.FIRST_PAGE); settle(40)
            val track = song.audio.first()
            ok("recording loads", Recording.load(st, track))
            Recording.play(st, track, st.current)
            settle(20)
            // Teach: turn by hand at about 1.2 s, 2.4 s, ... of the recording.
            val gap = 1200L
            for (k in 1 until pages) {
                waitFor(10_000) { player.positionMs >= k * gap }
                Perform.run(PerformAction.NEXT_PAGE); settle(40)
            }
            settle(40)
            val partId = st.partShown()?.id
            val learned = PlayAlong.currentTrack(st)?.turnsFor(partId, pages)
            ok("every turn was learned for this part", learned != null, "turns $learned part $partId pages $pages")
            say("INFO learned turns: $learned")
            // Play it again from the start: the app turns by itself.
            Recording.pause(st); settle(10)
            player.seek(0); Perform.run(PerformAction.FIRST_PAGE); settle(40)
            Recording.play(st, track, st.current); settle(10)
            val seenAt = HashMap<Int, Long>()
            val hints = LinkedHashSet<String>()
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < (pages * gap + 1500)) {
                settle(3)
                PlayAlong.hint?.let { hints += it }
                val p = page()
                if (p > 0 && p !in seenAt) seenAt[p] = player.positionMs
            }
            say("INFO pages seen turning at (page -> recording ms): $seenAt ; hints $hints")
            for (k in 1 until pages) {
                val at = seenAt[k]
                val want = learned?.getOrNull(k - 1) ?: (k * gap)
                ok("page ${k + 1} turned by itself near the learned moment", at != null && at in (want - 1200)..(want + 400), "turned at $at, learned $want")
            }
            ok("a hint was on show while it played", hints.isNotEmpty(), hints.toString())
            // Skipping back in the recording puts the right page up.
            player.seek(300); settle(10)
            ok("skipping back to 0.3 s puts page 1 up at once", page() == 0, "page ${page() + 1} pos ${player.positionMs}")
            player.seek(learned?.getOrNull(1)?.plus(300) ?: 3000); settle(10)
            ok("skipping on past the second turn puts page 3 up at once", page() == 2, "page ${page() + 1} pos ${player.positionMs}")
            // Turned off: nothing turns.
            Recording.pause(st); settle(10)
            PlayAlong.toggle(st); settle(10)
            player.seek(0); Perform.run(PerformAction.FIRST_PAGE); settle(40)
            Recording.play(st, track, st.current); settle(10)
            val t1 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t1 < 3000) settle(3)
            ok("with play-along off, no page turns by itself", page() == 0, "page ${page() + 1}, hint ${PlayAlong.hint}")
            PlayAlong.toggle(st)
            Recording.pause(st)
            // Leaving the song: the recording keeps playing, nothing crashes.
            Recording.end(st)
        }
    }
}
