package com.inksheets.desktop.review

import com.inksheets.core.AudioTrack
import com.inksheets.core.WavWriter
import com.inksheets.desktop.JavaSoundPlayer
import com.inksheets.ui.Click
import com.inksheets.ui.Listener
import com.inksheets.ui.Recording
import com.inksheets.ui.SelfRecorder
import com.inksheets.ui.SharedMetronome
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Recordings: the one shared player, count-in and click under a recording, recording yourself,
 * the files that come out, and what stops what. Made-up player/mic/output except where the real
 * desktop player is named (played at volume 0).
 */
class T3RecordingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun say(text: String) = T3.say("recording.txt", text)

    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    @After
    fun reset() {
        runCatching { javax.swing.SwingUtilities.invokeAndWait { state?.let { Recording.end(it); Click.stop(it) } } }
        runCatching { SelfRecorder.recording = false }
        Click.setCountInBars(state ?: return, 1)
    }

    private var state: SheetsState? = null

    private fun wav(file: File, seconds: Double, hz: Double = 220.0, rate: Int = 44_100) {
        file.parentFile.mkdirs()
        WavWriter(file, rate).use { w -> w.write(FloatArray((seconds * rate).toInt()) { (0.3 * sin(2 * PI * hz * it / rate)).toFloat() }) }
    }

    private fun rig(out: T3Out? = null, mic: T3Mic? = T3Mic(), player: T3Player? = T3Player()): Triple<SheetsState, T3Platform, File> {
        // Process-wide statics from an earlier test: the one shared player and the one metronome.
        Recording.player = null; Recording.loadedFile = null
        SharedMetronome.engine = null
        val root = tmp.newFolder("Music")
        val a = File(root, "Band/Song A - take one.wav").also { wav(it, 6.0, 220.0) }
        val b = File(root, "Band/Song A - take two.wav").also { wav(it, 6.0, 330.0) }
        File(root, "Band/Song A - Trombone 1.pdf").writeText("x")
        val platform = T3Platform(root, mic = mic, out = out, player = player)
        val s = SheetsState(platform)
        state = s
        s.change {
            editSong(ensureSong("Song A").id) {
                audio = listOf(AudioTrack(s.relative(a)!!, label = "One", speed = 1.0), AudioTrack(s.relative(b)!!, label = "Two", speed = 0.75, volume = 0.5))
            }
        }
        Click.setCountInBars(s, 0)
        return Triple(s, platform, root)
    }

    @Test
    fun `choosing another recording in the list while one plays swaps the sound under the playback column`() {
        val (s, p, _) = rig()
        val player = p.player as T3Player
        val song = s.library!!.songs.first()
        val one = song.audio[0]; val two = song.audio[1]
        javax.swing.SwingUtilities.invokeAndWait { Recording.load(s, one); Recording.play(s, one, song) }
        Thread.sleep(300)
        say("playing 'One': playing=${Recording.playing} loaded=${player.loaded?.name} track=${Recording.track?.label}")
        // The list: tapping 'Two' makes TrackControls' LaunchedEffect call Recording.load(state, track).
        Recording.peek(s, two)       // the list now only loads a row when nothing plays
        say("...tapped 'Two' in the list (only to look at its speed): player log=${player.log.takeLast(4)}; Recording.playing=${Recording.playing}, player.playing=${player.playing}, loaded=${player.loaded?.name}, playback column still says track=${Recording.track?.label}")
        assertTrue("the playing recording must not be replaced by looking at another", player.playing)
        assertEquals("and One is still what is loaded", s.fileOf(one.file)?.name, player.loaded?.name)
        javax.swing.SwingUtilities.invokeAndWait { Recording.playPause(s) }     // the column: shows 'Pause' (Recording.playing is still true) -> pauses
        javax.swing.SwingUtilities.invokeAndWait { Recording.playPause(s) }     // -> plays: but the player holds 'Two'
        say("after Pause then Play on the column: player.loaded=${player.loaded?.name} (the column's track is ${Recording.track?.label}), speed now ${player.speed} (One's is 1.0, Two's 0.75)")
    }

    @Test
    fun `changing speed or the loop of a recording in the list changes the one that is playing`() {
        val (s, p, _) = rig()
        val player = p.player as T3Player
        val song = s.library!!.songs.first()
        javax.swing.SwingUtilities.invokeAndWait { Recording.load(s, song.audio[0]); Recording.play(s, song.audio[0], song) }
        // AudioDialog's 'update' for track Two (selected in the list, not the one loaded) does: Recording.player?.let { p.speed = t.speed; p.pitch...; p.setLoop(...) }
        val two = song.audio[1].copy(speed = 0.6, loopStartMs = 1000, loopEndMs = 2000)
        Recording.apply(two)
        say("dragging Two's speed slider to 60% while One is the loaded/playing recording: player speed=${player.speed}, loop=${player.loop}, volume=${player.volume}; One's settings on file: speed ${song.audio[0].speed}")
        assertEquals("One keeps its own speed", 1.0, player.speed, 0.001)
    }

    @Test
    fun `count in then play, press pause during the count, and tap play twice`() {
        val out = T3Out(pump = true)
        val (s, p, _) = rig(out = out)
        val player = p.player as T3Player
        val song = s.library!!.songs.first()
        val one = song.audio[0]
        Click.setCountInBars(s, 1)
        Click.setBpm(s, 120.0)
        javax.swing.SwingUtilities.invokeAndWait { Recording.load(s, one) }
        val t0 = System.currentTimeMillis()
        javax.swing.SwingUtilities.invokeAndWait { Recording.play(s, one, song) }
        while (!player.log.any { it.startsWith("play ") } && System.currentTimeMillis() - t0 < 6000) Thread.sleep(5)
        val dt = System.currentTimeMillis() - t0
        say("1-bar count-in at 120 bpm: the recording started after $dt ms (2000 expected); metronome UI bpm=${SharedMetronome.bpm}")
        assertTrue(abs(dt - 2000) < 250)
        javax.swing.SwingUtilities.invokeAndWait { Recording.pause(s) }
        // Pause during a count-in: the recording must not start afterwards.
        player.log.clear()
        javax.swing.SwingUtilities.invokeAndWait { Recording.play(s, one, song) }
        Thread.sleep(900)
        javax.swing.SwingUtilities.invokeAndWait { Recording.pause(s) }
        Thread.sleep(2500)
        say("paused in the middle of a count-in: the player log afterwards = ${player.log}, playing=${player.playing}, Recording.playing=${Recording.playing}")
        assertFalse(player.log.any { it.startsWith("play ") })
        // Tap Play twice during one count-in
        player.log.clear()
        javax.swing.SwingUtilities.invokeAndWait { Recording.play(s, one, song) }
        Thread.sleep(500)
        javax.swing.SwingUtilities.invokeAndWait { Recording.play(s, one, song) }
        Thread.sleep(3500)
        say("two taps on Play 0.5 s apart during a count-in: player log = ${player.log}")
    }

    @Test
    fun `the click tempo and the metronome after a recording with the click under it`() {
        val out = T3Out(pump = true)
        val (s, p, _) = rig(out = out)
        val song = s.library!!.songs.first()
        val track = song.audio[1].copy(clickBpm = 140.0)       // speed 0.75
        Click.setWithPlayback(s, true)
        Click.setCountInBars(s, 0)
        Click.setBpm(s, 91.0)
        javax.swing.SwingUtilities.invokeAndWait { Recording.load(s, track); Recording.play(s, track, song) }
        Thread.sleep(600)
        val during = SharedMetronome.bpm
        javax.swing.SwingUtilities.invokeAndWait { Recording.pause(s) }
        Thread.sleep(200)
        say("metronome set to 91, recording with click 140 at speed 75%: while playing the metronome shows $during (105 expected); after pause: ${SharedMetronome.bpm}")
        Click.setWithPlayback(s, false)
        assertEquals(91.0, SharedMetronome.bpm, 0.5)
    }

    // ---------------------------------------------------------------- recording yourself

    @Test
    fun `recording yourself with a one bar count-in keeps the count-in out of the take and pairs it with the song`() {
        val rate = 48_000
        val clip = FloatArray(rate * 12) { (0.3 * sin(2 * PI * 196.0 * it / rate)).toFloat() }
        val out = T3Out(pump = true)
        val mic = T3Mic(rate, clip, gain = 1f)
        val (s, p, root) = rig(out = out, mic = mic)
        val song = s.library!!.songs.first()
        Click.setBpm(s, 120.0)
        Click.setCountInBars(s, 1)
        Click.setWithRecording(s, false)
        val t0 = System.currentTimeMillis()
        assertTrue(SelfRecorder.start(s, song))
        Thread.sleep(6000)
        javax.swing.SwingUtilities.invokeAndWait { SelfRecorder.stop(s) }
        val tracks = s.library!!.song(song.id)!!.audio
        val take = tracks.last()
        val file = s.fileOf(take.file)!!
        val seconds = (file.length() - 44) / 2.0 / rate
        say("recorded 6.0 s in all with a 2.0 s count-in: take '${take.label}' ${file.relativeTo(root)} is ${"%.2f".format(seconds)} s, ${file.length() / 1024} KB (${"%.1f".format(file.length() / seconds * 60 / 1_048_576)} MB per minute), clickBpm=${take.clickBpm}, tracks now ${tracks.size}")
        assertTrue(abs(seconds - 4.0) < 0.6)
        // Scan again: the take must not turn into a song or a part.
        val before = s.library!!.songs.map { it.title to it.parts.size }
        s.scanFolder()
        val after = s.library!!.songs.map { it.title to it.parts.size to it.audio.size }
        say("after a rescan: songs $before -> $after")
    }

    @Test
    fun `recording yourself takes the microphone from Listen without telling it`() {
        val rate = 48_000
        val clip = FloatArray(rate * 30) { (0.3 * sin(2 * PI * 196.0 * it / rate)).toFloat() }
        val mic = T3Mic(rate, clip, gain = 1f)
        val (s, _, _) = rig(mic = mic)
        val song = s.library!!.songs.first()
        s.current = song
        s.listenTurns = true
        s.pageShown = 0 to 3
        javax.swing.SwingUtilities.invokeAndWait { Listener.start(s) }
        Thread.sleep(1500)
        val chunks = mic.chunksDelivered
        SelfRecorder.start(s, song)
        Thread.sleep(1500)
        say("Listen on, then 'Record yourself': mic log ${mic.log}; Listen active=${Listener.active}; recorder ${if (SelfRecorder.recording) "recording" else "not recording"}; chunks before=$chunks")
        javax.swing.SwingUtilities.invokeAndWait { SelfRecorder.stop(s) }
        Thread.sleep(500)
        say("...after stopping the take: mic running=${mic.running}, Listen active=${Listener.active}, button says '${Listener.summary(s)}'")
        javax.swing.SwingUtilities.invokeAndWait { Listener.stop(s) }
    }

    @Test
    fun `a take killed mid-write cannot be played back`() {
        val root = tmp.newFolder("kill")
        val live = File(root, "live.wav")
        val w = WavWriter(live, 48_000)
        w.write(FloatArray(48_000 * 4) { (0.3 * sin(2 * PI * 220.0 * it / 48_000)).toFloat() })
        // The app is killed here (battery, crash, task switch): the file as it is on disk right now.
        val killed = File(root, "killed.wav").also { live.copyTo(it) }
        w.close()
        val player = JavaSoundPlayer()
        val ok = player.load(killed)
        assertTrue("a take cut short still plays", ok)
        val ok2 = player.load(live)
        say("a 4 s take copied before close(): load=${ok}, duration=${player.durationMs.takeIf { ok } } ms; the same take closed properly: load=$ok2, duration ${player.durationMs} ms; killed file is ${killed.length()} bytes, header says data length ${java.nio.ByteBuffer.wrap(killed.readBytes(), 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int}")
        player.release()
    }

    @Test
    fun `the real recording of yourself in the library plays`() {
        val take = File(music, "Recordings/Maryland School Songs/2026-09-30T12-06-06.wav")
        assumeTrue(take.isFile)
        val player = JavaSoundPlayer()
        say("the take made 30 Sep in the real library: ${take.length() / 1024} KB, loads=${player.load(take)}, ${player.durationMs} ms")
    }

    // ---------------------------------------------------------------- the real desktop player on big files

    @Test
    fun `the desktop player on the biggest recordings in the library, silently`() {
        val names = listOf("MobileSheets/Barbeque Sauce.mp3", "MobileSheets/Gustav Holst - First Suite in E-flat.mp3", "MobileSheets/Handel_ Let Thy Hand Be Strengthened.mp3",
            "Imported/PEP BAND/Music/Shorts/MSOM SHORTS 2025.mp3", "Imported/PEP BAND/Music/Sweet Caroline/Sweet Caroline 2009.mp3")
        val rt = Runtime.getRuntime()
        val sb = StringBuilder("desktop JavaSoundPlayer, volume 0:\n")
        for (n in names) {
            val f = File(music, n)
            if (!f.isFile) continue
            System.gc()
            val before = rt.totalMemory() - rt.freeMemory()
            val player = JavaSoundPlayer()
            player.volume = 0.0
            val t0 = System.nanoTime()
            val ok = player.load(f)
            val loadMs = (System.nanoTime() - t0) / 1_000_000
            val after = rt.totalMemory() - rt.freeMemory()
            val t1 = System.nanoTime()
            player.play()
            var waited = 0
            while (!player.playing && waited < 2000) { Thread.sleep(5); waited += 5 }
            Thread.sleep(400)
            val t2 = System.nanoTime()
            player.pause()
            val pauseMs = (System.nanoTime() - t2) / 1_000_000
            val t3 = System.nanoTime()
            player.seek(player.durationMs / 2)
            val seekMs = (System.nanoTime() - t3) / 1_000_000
            player.speed = 0.75
            val t4 = System.nanoTime()
            player.play(); Thread.sleep(300)
            val pos1 = player.positionMs
            Thread.sleep(1000)
            val pos2 = player.positionMs
            player.pause()
            sb.append("  %-62s %5d KB  load=%d ms ok=%s  duration=%d s  heap +%d MB  pause()=%d ms  seek=%d ms  at 75%% speed 1 s of wall = %d ms of recording\n".format(
                n.takeLast(62), f.length() / 1024, loadMs, ok, player.durationMs / 1000, (after - before) / 1_048_576, pauseMs, seekMs, pos2 - pos1))
            player.release()
        }
        say(sb.toString())
    }

    // ---------------------------------------------------------------- what stops a recording

    @Test
    fun `only closing the song's tab stops its recording, and a recording started from Home is never stopped by that`() {
        val src = File(T3.lib, "Sep")
        assumeTrue(src.isDirectory)
        val root = T3.library(tmp.newFolder("Music"), "Sep")
        Recording.player = null; Recording.loadedFile = null
        val platform = T3Platform(root, player = T3Player())
        val s = SheetsState(platform)
        state = s
        s.scanFolder()
        val song = s.library!!.songs.first { it.title.contains("September", true) }
        val part = song.parts.first { it.file.contains("Trombone 1") }
        val file = s.fileOf(part.file)!!
        val track = song.audio.first()
        // Started from Home, no tab open:
        javax.swing.SwingUtilities.invokeAndWait { Recording.load(s, track); Recording.play(s, track, song) }
        s.openTabs(listOf(file))
        s.openTabs(emptyList())
        say("recording started from Home (song had no tab), then the song's tab opened and closed: session=${Recording.session}, playing=${Recording.playing}")
        javax.swing.SwingUtilities.invokeAndWait { Recording.end(s) }
        // Started with a tab:
        s.openTabs(listOf(file))
        javax.swing.SwingUtilities.invokeAndWait { Recording.load(s, track); Recording.play(s, track, song) }
        s.openTabs(listOf(file, File(root, "other.pdf")))
        s.openTabs(emptyList())
        say("recording started with the tab open, tab closed: session=${Recording.session}, playing=${Recording.playing}")
        assertFalse(Recording.session)
    }
}
