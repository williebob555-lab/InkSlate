package com.inksheets.desktop.review

import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inksheets.core.AudioTrack
import com.inksheets.core.Song
import com.inksheets.ui.Ears
import com.inksheets.ui.Listener
import com.inksheets.ui.Recording
import com.inksheets.ui.SheetsState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Listen and the learned page turns, against real recordings from the library fed to a made-up
 * microphone (nothing is heard by a real one): what the turns record, what Listen does with them,
 * what stops it, and what else shares the microphone.
 */
class T3ListenTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/Imported/PEP BAND/Music")

    private fun say(text: String) = T3.say("listen.txt", text)

    @After
    fun reset() {
        Perform.document = null
        runCatching { javax.swing.SwingUtilities.invokeAndWait { Listener.stop(state ?: return@invokeAndWait) } }
        Ears.deaf = false; Ears.level = 0f
    }

    private var state: SheetsState? = null
    private var platform: T3Platform? = null

    private class Rig(val state: SheetsState, val platform: T3Platform, val song: Song, val scorePath: String, val bassPath: String, val clip: FloatArray, val rate: Int)

    private fun decode(file: File, seconds: Int): Pair<FloatArray, Int> {
        var clip = FloatArray(0); var rate = 44_100
        val p = com.inksheets.desktop.DesktopSheetsPlatform {}
        p.decodeAudio(file) { s, r -> if (clip.size < r * seconds) { clip += s; rate = r } }
        return clip.copyOf(minOf(clip.size, rate * seconds)) to rate
    }

    /** September: a 5-page Score and 1-page parts, one recording. [mic] is made from the recording's decoded sound. */
    private fun rig(micFor: (FloatArray, Int) -> T3Mic, seconds: Int = 60, player: T3Player? = T3Player { 100_000L }): Rig {
        val src = File(music, "September/September.mp3")
        assumeTrue("no recording here", src.isFile)
        val root = T3.library(tmp.newFolder("Music" + System.nanoTime()), "Sep")
        val (clip, rate) = decode(src, seconds)
        // A 60 s version of the recording so the reference is short and the test quick.
        val wav = File(root, "Sep/September.wav")
        File(root, "Sep/September.mp3").delete()
        writeWav(wav, clip, rate)
        val mic = micFor(clip, rate)
        val platform = T3Platform(root, mic = mic, player = player)
        val state = SheetsState(platform)
        state.scanFolder()
        val song = state.library!!.songs.first { it.title.contains("September", true) }
        say("library: ${state.library!!.songs.map { it.title + " (" + it.parts.size + " parts, " + it.audio.size + " rec)" }}")
        val score = song.parts.first { it.file.contains("Score", true) }
        val bass = song.parts.first { it.file.contains("Electric Bass", true) }
        this.state = state; this.platform = platform
        state.listenTurns = true
        return Rig(state, platform, state.library!!.song(song.id)!!, state.fileOf(score.file)!!.absolutePath, state.fileOf(bass.file)!!.absolutePath, clip, rate)
    }

    private fun writeWav(file: File, samples: FloatArray, rate: Int) {
        val data = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        val format = javax.sound.sampled.AudioFormat(rate.toFloat(), 16, 1, true, false)
        javax.sound.sampled.AudioSystem.write(
            javax.sound.sampled.AudioInputStream(java.io.ByteArrayInputStream(data.array()), format, samples.size.toLong()),
            javax.sound.sampled.AudioFileFormat.Type.WAVE, file
        )
    }

    private fun turnsOf(r: Rig) = r.state.library!!.song(r.song.id)!!.audio.firstOrNull()?.turnsMs

    // ------------------------------------------------------------------ learning

    /**
     * What Listener.pageChanged is meant to do, written the way it has to be to keep the song's recordings:
     * the same arithmetic on the track the recording is playing, but reading the song's tracks from the library
     * (inside editSong, `audio` is the edit's own empty field - see the next test).
     */
    private fun learnIntended(s: SheetsState, songId: String, trackFile: String, was: Int, at: Long) {
        s.change {
            val tracks = song(songId)!!.audio
            editSong(songId) {
                audio = tracks.map { t ->
                    if (t.file != trackFile) t else {
                        val turns = t.turnsMs.toMutableList()
                        while (turns.size < was) turns += -1L
                        if (turns.size == was) turns += at else turns[was] = at
                        t.copy(turnsMs = turns)
                    }
                }
            }
        }
    }

    @Test
    fun `teaching a page turn erases every recording of the song`() {
        val r = rig({ c, rate -> T3Mic(rate, c) })
        val s = r.state
        val player = r.platform.player as T3Player
        val track = r.song.audio.first().copy(label = "Reference", speed = 0.85, loopStartMs = 10_000, loopEndMs = 20_000)
        s.change { editSong(r.song.id) { audio = listOf(track) } }
        val song = s.library!!.song(r.song.id)!!
        s.current = song
        javax.swing.SwingUtilities.invokeAndWait { Recording.load(s, song.audio[0]); Recording.play(s, song.audio[0], song) }
        assertTrue(Recording.playing)
        say("before: ${s.library!!.song(song.id)!!.audio.map { it.label to it.speed }}")
        player.seek(0)
        javax.swing.SwingUtilities.invokeAndWait { Listener.pageChanged(s, r.scorePath, 0) }
        player.seek(30_000)
        javax.swing.SwingUtilities.invokeAndWait { Listener.pageChanged(s, r.scorePath, 1) }     // the player turns page 1 -> 2 by hand
        val after = s.library!!.song(song.id)!!.audio
        say("after turning page 1 -> 2 once while the recording played (Settings > Listen on): recordings = ${after.map { it.label to it.turnsMs }}  log: ${r.platform.events.filter { it.contains("learned") }}")
        assertTrue("the song still has its recording", after.isNotEmpty())
    }

    @Test
    fun `two parts with different page counts teach one recording, and the second spoils the first`() {
        val r = rig({ c, rate -> T3Mic(rate, c) })
        val s = r.state
        val file = r.song.audio.first().file
        // (learnIntended: what the turns would look like if learning worked as written.)
        for ((i, at) in listOf(30_000L, 60_000L, 90_000L, 120_000L).withIndex()) learnIntended(s, r.song.id, file, i, at)
        say("after teaching on the 5-page Score (as intended): turnsMs=${turnsOf(r)}")
        // The next day the 2-page part is turned once, at 70 s of the same recording.
        learnIntended(s, r.song.id, file, 0, 70_000)
        say("after the 2-page part's one turn at 70 s:      turnsMs=${turnsOf(r)}")
        val turns = turnsOf(r)!!
        assertFalse("the list is no longer in order: ${turns}", turns.zipWithNext().all { (a, b) -> a < b })
        learnIntended(s, r.song.id, file, 0, 6_000)
        say("a glance at page 2 six seconds in (recording happens to be playing) rewrote turn 1 to ${turnsOf(r)!![0]}")
    }

    @Test
    fun `a missed first lesson leaves a minus one`() {
        val r = rig({ c, rate -> T3Mic(rate, c) })
        val file = r.song.audio.first().file
        // The player lands on page 2 by hand before pressing play, then turns 2 -> 3 while it plays.
        learnIntended(r.state, r.song.id, file, 1, 40_000)
        say("started playing from page 2, turned 2->3 at 40 s (as intended): turnsMs=${turnsOf(r)}")
        assertEquals(listOf(-1L, 40_000L), turnsOf(r))
    }

    @Test
    fun `things edited after a song came to the front are not seen by Listen or the play button`() {
        val r = rig({ c, rate -> T3Mic(rate, c) })
        val s = r.state
        val song = s.library!!.song(r.song.id)!!
        s.current = song                                                // the song is in front: a snapshot
        val file = song.audio.first().file
        learnIntended(s, song.id, file, 0, 10_000)
        learnIntended(s, song.id, file, 1, 20_000)
        learnIntended(s, song.id, file, 2, 30_000)
        val inLibrary = s.library!!.song(song.id)!!.audio.first().turnsMs
        val seenByListen = s.current!!.audio.first().turnsMs
        say("turns taught while the song is in front: library=$inLibrary, state.current (what Listen.start and the play button read)=$seenByListen")
        assertEquals(inLibrary, seenByListen)
    }

    @Test
    fun `playing a recording never turns a page by itself, microphone or no microphone`() {
        val r = rig({ c, rate -> T3Mic(rate, c) })
        val s = r.state
        val player = r.platform.player as T3Player
        val track0 = r.song.audio.first()
        // Learned turns are known for all four turns of the 5-page score.
        s.change { editSong(r.song.id) { audio = listOf(track0.copy(turnsMs = listOf(10_000L, 20_000L, 30_000L, 40_000L))) } }
        val track = s.library!!.song(r.song.id)!!.audio.first()
        s.pageShown = 0 to 5
        s.currentPath = r.scorePath
        val turned = java.util.concurrent.CopyOnWriteArrayList<PerformAction>()
        Perform.document = { a -> turned += a; true }
        javax.swing.SwingUtilities.invokeAndWait { Recording.load(s, track); Recording.play(s, track, r.song) }
        player.seek(44_000)         // well past every learned turn
        Thread.sleep(1500)
        javax.swing.SwingUtilities.invokeAndWait { Recording.pause(s) }
        say("recording played to 44 s over a score with 4 learned turns, Listen off: page actions fired = $turned (the learned turns are only ever used by the microphone follower, grep turnsMs: Listener.kt only)")
        assertTrue(turned.isEmpty())
    }

    // ------------------------------------------------------------------ Listen acting on the turns

    private class Turns(val at: Double, val toPage: Int)

    /** Listen on [r] with the made-up pages; returns the turns it made with when (seconds of sound heard) and a log of what the button said. */
    private fun listenThrough(r: Rig, mic: T3Mic, pagesOnScore: Int, firstPage: Int = 0, maxSeconds: Long = 40): Pair<List<Turns>, List<Pair<Double, String>>> {
        val s = r.state
        s.current = s.library!!.song(r.song.id)
        s.pageShown = firstPage to pagesOnScore
        s.currentPath = r.scorePath
        val turns = java.util.Collections.synchronizedList(ArrayList<Turns>())
        Perform.document = { a ->
            if (a == PerformAction.NEXT_PAGE) {
                s.pageShown = (s.pageShown.first + 1) to s.pageShown.second
                turns += Turns(mic.heardSeconds, s.pageShown.first)
            }
            true
        }
        javax.swing.SwingUtilities.invokeAndWait { Listener.start(s) }
        val said = ArrayList<Pair<Double, String>>()
        val until = System.currentTimeMillis() + maxSeconds * 1000
        while (System.currentTimeMillis() < until && (Listener.active || said.isEmpty())) {
            Thread.sleep(25)
            var w: String? = null
            javax.swing.SwingUtilities.invokeAndWait { w = Listener.summary(s) }
            val words = w ?: continue
            if (said.lastOrNull()?.second != words) said += mic.heardSeconds to words
        }
        return turns.toList() to said
    }

    @Test
    fun `learned turns with a minus one turn the page the moment Listen hears anything`() {
        lateinit var mic: T3Mic
        val r = rig({ c, rate -> T3Mic(rate, c, speedUp = 8).also { mic = it } })
        val s = r.state
        val track0 = r.song.audio.first()
        s.change { editSong(r.song.id) { audio = listOf(track0.copy(turnsMs = listOf(-1L, 20_000L, 40_000L))) } }
        val (turns, said) = listenThrough(r, mic, pagesOnScore = 4, maxSeconds = 25)
        say("turnsMs=[-1,20000,40000] on a 4-page part, Listen pressed on page 1:\n  turns made (heard seconds -> page): ${turns.map { "%.1f->%d".format(it.at, it.toPage + 1) }}\n  button said: ${said.take(8)}")
        assertTrue("it turned within 2 s of starting, on a turn nobody taught", turns.firstOrNull()?.at?.let { it < 2.5 } == true)
    }

    @Test
    fun `learned turns out of order turn twice in a row`() {
        lateinit var mic: T3Mic
        val r = rig({ c, rate -> T3Mic(rate, c, speedUp = 8).also { mic = it } })
        val s = r.state
        val track0 = r.song.audio.first()
        s.change { editSong(r.song.id) { audio = listOf(track0.copy(turnsMs = listOf(30_000L, 20_000L, 45_000L))) } }
        val (turns, _) = listenThrough(r, mic, pagesOnScore = 4, maxSeconds = 25)
        say("turnsMs=[30000,20000,45000] (as left by two parts teaching one recording): turns made ${turns.map { "%.1f->%d".format(it.at, it.toPage + 1) }}")
        val gap = turns.zipWithNext { a, b -> b.at - a.at }.minOrNull()
        assertTrue("two turns ${gap} s apart", gap != null && gap < 1.0)
    }

    @Test
    fun `Listen follows the first recording, not the one that was playing, and teaches the other`() {
        lateinit var mic: T3Mic
        val r = rig({ c, rate -> T3Mic(rate, c, speedUp = 8).also { mic = it } })
        val s = r.state
        val first = r.song.audio.first()
        val second = AudioTrack(file = first.file, label = "Practice take", turnsMs = listOf(8_000L, 16_000L, 24_000L))
        s.change { editSong(r.song.id) { audio = listOf(first, second) } }
        val song = s.library!!.song(r.song.id)!!
        val player = r.platform.player as T3Player
        javax.swing.SwingUtilities.invokeAndWait { Recording.load(s, song.audio[1]); Recording.play(s, song.audio[1], song) }
        s.current = song
        val (turns, _) = listenThrough(r, mic, pagesOnScore = 4, maxSeconds = 25)
        val source = r.platform.events.filter { it.startsWith("Listen: following") }
        say("Recording playing = 'Practice take' (turns taught 8/16/24 s); Listen says: $source\n  turns made: ${turns.map { "%.1f".format(it.at) }}")
        assertTrue(source.any { it.contains("guessed") })
        // Teaching now goes to the playing one:
        javax.swing.SwingUtilities.invokeAndWait { Listener.stop(s) }
        learnIntended(s, song.id, Recording.track!!.file, 0, 5_000)
        say("a turn taught while 'Practice take' plays goes to the playing track (Recording.track), Listen reads audio.first(): " + s.library!!.song(song.id)!!.audio.map { it.label to it.turnsMs })
    }

    @Test
    fun `a band that starts again from the top finds its place in the music but the page stays where it was`() {
        lateinit var mic: T3Mic
        // Heard: 0..28 s of the recording, then the band stops and takes it from the top for 40 s (a rehearsal restart, no gap).
        val r = rig({ c, rate ->
            val cut = rate * 28
            val whole = c.copyOf(cut) + c.copyOf(minOf(c.size, rate * 40))
            T3Mic(rate, whole, speedUp = 8).also { mic = it }
        })
        val s = r.state
        val track0 = r.song.audio.first()
        s.change { editSong(r.song.id) { audio = listOf(track0.copy(turnsMs = listOf(10_000L, 20_000L, 30_000L))) } }
        val (turns, said) = listenThrough(r, mic, pagesOnScore = 4, maxSeconds = 40)
        var shownPageAt = ArrayList<String>()
        say("restart from the top at heard 28 s, turns taught 10/20/30 s on a 4-page part:\n  turns: ${turns.map { "%.1f s -> page %d".format(it.at, it.toPage + 1) }}\n  button: ${said.take(14)}")
        // Pages never go back: after the restart at 28 s the music is on page 1 for 10 s, and the page is page 3.
        assertTrue(turns.none { it.at > 28 && it.toPage < 2 })
    }

    @Test
    fun `a four second silence in the middle of the piece ends Listen for good`() {
        lateinit var mic: T3Mic
        val r = rig({ c, rate ->
            val before = c.copyOf(rate * 20)
            val gap = FloatArray(rate * 5)         // a dramatic pause, a fermata, a tacet section, the director speaks
            val after = c.copyOfRange(rate * 20, minOf(c.size, rate * 50))
            T3Mic(rate, before + gap + after, speedUp = 8).also { mic = it }
        })
        val s = r.state
        val track0 = r.song.audio.first()
        s.change { editSong(r.song.id) { audio = listOf(track0.copy(turnsMs = listOf(10_000L, 25_000L, 40_000L))) } }
        val (turns, said) = listenThrough(r, mic, pagesOnScore = 4, maxSeconds = 40)
        say("5 s of silence at 20 s of a 50 s piece (turns taught 10/25/40):\n  turns made: ${turns.map { "%.1f->%d".format(it.at, it.toPage + 1) }}\n  button: ${said}\n  status after: ${Listener.status}")
        assertFalse(Listener.active)
        assertTrue("it stopped before the 40 s turn", turns.none { it.at > 40 })
    }

    // ------------------------------------------------------------------ the microphone is shared by only some

    @Test
    fun `the tuner or a self recording takes the microphone from Listen and Listen goes on saying it is waiting`() {
        lateinit var mic: T3Mic
        val r = rig({ c, rate -> T3Mic(rate, c, speedUp = 4).also { mic = it } })
        val s = r.state
        val track0 = r.song.audio.first()
        s.change { editSong(r.song.id) { audio = listOf(track0.copy(turnsMs = listOf(30_000L, 45_000L, 55_000L))) } }
        s.current = s.library!!.song(r.song.id)
        s.pageShown = 0 to 4
        s.currentPath = r.scorePath
        javax.swing.SwingUtilities.invokeAndWait { Listener.start(s) }
        Thread.sleep(3000)
        val before = mic.chunksDelivered
        var level = 0f
        javax.swing.SwingUtilities.invokeAndWait { level = Ears.level }
        say("Listen running: chunks=$before level=$level words='${Listener.summary(s)}'")
        // The tuner's TunerBody does exactly this on open ...
        val tunerChunks = java.util.concurrent.atomic.AtomicInteger()
        Ears.listen(s, "tuner") { tunerChunks.incrementAndGet() }
        Thread.sleep(2500)
        // ... and exactly this on close.
        Ears.stop("tuner")
        Thread.sleep(1500)
        assertTrue("the tuner shares the microphone: Listen keeps hearing", mic.running)
        assertTrue("and the tuner heard it too", tunerChunks.get() > 0)
        // A microphone taken by something else entirely stops delivering: that is said too.
        mic.stop()
        Thread.sleep(3500)
        javax.swing.SwingUtilities.invokeAndWait { }
        var words: String? = null
        var deaf = false
        javax.swing.SwingUtilities.invokeAndWait { words = Listener.summary(s); deaf = Ears.deaf }
        say("tuner opened and closed 2.5 s later. Microphone log: ${mic.log}. Listen still active=${Listener.active}, Ears.deaf=$deaf, button says '$words' ${if (!mic.running) "- but nothing is listening any more" else ""}")
        assertTrue("Listen thinks it is listening", Listener.active)
        assertFalse("the microphone stopped delivering", mic.running)
        assertTrue("and it says so", deaf)
    }

    @Test
    fun `Listen with a microphone that cannot be opened says so and does not stay lit`() {
        val r = rig({ _, rate -> object : T3Mic(rate, null) { override fun start(onChunk: (FloatArray) -> Unit) = false } })
        val s = r.state
        s.current = s.library!!.song(r.song.id)
        s.pageShown = 0 to 4
        s.currentPath = r.scorePath
        javax.swing.SwingUtilities.invokeAndWait { Listener.start(s) }
        Thread.sleep(2500)
        javax.swing.SwingUtilities.invokeAndWait { }
        var words: String? = null
        javax.swing.SwingUtilities.invokeAndWait { words = Listener.summary(s) }
        say("microphone refuses to open (Android: permission not given yet): active=${Listener.active} words='$words' status='${Listener.status}'")
        assertFalse(Listener.active)
    }

    @Test
    fun `Listen pressed while the band is already part way through, and from a page that is not the first`() {
        for ((label, offsetS, startPage) in listOf(Triple("band at 32 s, Listen pressed on page 1", 32, 0), Triple("band at 5 s, Listen pressed on page 3", 5, 2), Triple("band at 20 s, Listen pressed on page 2", 20, 1))) {
            lateinit var mic: T3Mic
            val r = rig({ c, rate -> T3Mic(rate, c.copyOfRange(rate * offsetS, minOf(c.size, rate * (offsetS + 28))), speedUp = 8).also { mic = it } })
            val s = r.state
            val track0 = r.song.audio.first()
            s.change { editSong(r.song.id) { audio = listOf(track0.copy(turnsMs = listOf(15_000L, 30_000L, 45_000L))) } }
            val (turns, said) = listenThrough(r, mic, pagesOnScore = 4, firstPage = startPage, maxSeconds = 30)
            say("$label (turns taught 15/30/45 s of 60; page should be ${offsetS / 15 + 1} at the start): turns: ${turns.map { "%.1fs->p%d".format(it.at, it.toPage + 1) }} || button: ${said.take(10).map { "%.1f %s".format(it.first, it.second) }}")
            javax.swing.SwingUtilities.invokeAndWait { Listener.stop(s) }
            Thread.sleep(300)
        }
    }
}
