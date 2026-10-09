package com.inksheets.desktop.review

import com.inksheets.core.AudioTrack
import com.inksheets.core.Library
import com.inksheets.core.LibraryLog
import com.inksheets.core.WavWriter
import com.inksheets.desktop.JavaSoundPlayer
import com.inksheets.ui.Recording
import com.inksheets.ui.SelfRecorder
import com.inksheets.ui.SharedMetronome
import com.inksheets.ui.SheetsState
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Rounds 3 and 4 for recordings: two devices editing, files missing, renamed, corrupt or odd-named,
 * nonsense numbers in a synced track, titles Windows cannot make a folder of.
 */
class T3EdgeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun say(t: String) = T3.say("edge.txt", t)
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    @After
    fun reset() { SelfRecorder.recording = false; Recording.player = null; Recording.loadedFile = null; SharedMetronome.engine = null }

    private fun wav(file: File, seconds: Double, rate: Int = 44_100) {
        file.parentFile.mkdirs()
        WavWriter(file, rate).use { w -> w.write(FloatArray((seconds * rate).toInt()) { (0.3 * sin(2 * PI * 220.0 * it / rate)).toFloat() }) }
    }

    // ------------------------------------------------------------ two devices

    @Test
    fun `two devices change different things on the recordings of one song at the same time`() {
        var time = 100L
        val tabletRoot = tmp.newFolder("tablet"); val laptopRoot = tmp.newFolder("laptop")
        val tablet = Library(LibraryLog(tabletRoot, "tablet")) { time++ }
        val laptop = Library(LibraryLog(laptopRoot, "laptop")) { time++ }
        val song = tablet.addSong("September")
        tablet.editSong(song.id) { audio = listOf(AudioTrack("a.mp3", label = "Band"), AudioTrack("b.mp3", label = "Me")) }
        fun sync(a: File, b: File) = File(a, ".inksheets/log").listFiles()!!.forEach { it.copyTo(File(b, ".inksheets/log/${it.name}"), overwrite = true) }
        File(laptopRoot, ".inksheets/log").mkdirs()
        sync(tabletRoot, laptopRoot); laptop.refresh()
        // Offline from each other: the tablet teaches the page turns of 'Band', the laptop slows 'Me' to 70% and renames it.
        tablet.editSong(song.id) { audio = tablet.song(song.id)!!.audio.mapIndexed { i, t -> if (i == 0) t.copy(turnsMs = listOf(10_000L, 20_000L)) else t } }
        laptop.editSong(song.id) { audio = laptop.song(song.id)!!.audio.mapIndexed { i, t -> if (i == 1) t.copy(speed = 0.7, label = "Me, slowed") else t } }
        sync(tabletRoot, laptopRoot); sync(laptopRoot, tabletRoot); tablet.refresh(); laptop.refresh()
        val t = tablet.song(song.id)!!.audio; val l = laptop.song(song.id)!!.audio
        say("tablet taught turns on 'Band'; laptop slowed 'Me' (at the same time) -> after sync tablet sees ${t.map { it.label to it.turnsMs.size to it.speed }}; laptop sees ${l.map { it.label to it.turnsMs.size to it.speed }}")
        say("(the audio list is one field: the later whole list wins and the other device's change is gone)")
        // Adding a recording on each device:
        tablet.editSong(song.id) { audio = tablet.song(song.id)!!.audio + AudioTrack("tablet-take.wav", label = "Tablet take") }
        laptop.editSong(song.id) { audio = laptop.song(song.id)!!.audio + AudioTrack("laptop-take.wav", label = "Laptop take") }
        sync(tabletRoot, laptopRoot); sync(laptopRoot, tabletRoot); tablet.refresh(); laptop.refresh()
        say("each device recorded a take: both end up with ${tablet.song(song.id)!!.audio.map { it.label }} / ${laptop.song(song.id)!!.audio.map { it.label }}")
    }

    // ------------------------------------------------------------ files underneath

    @Test
    fun `the recording file is missing, renamed, or not audio at all`() {
        val root = tmp.newFolder("Music")
        wav(File(root, "Band/Song - one.wav"), 3.0)
        File(root, "Band/Song - Trombone 1.pdf").writeText("x")
        File(root, "Band/Song - broken.mp3").writeText("this is not audio")
        File(root, "Band/Song - empty.wav").writeBytes(ByteArray(0))
        File(root, "Band/Song - cut.wav").also { wav(it, 2.0) }.let { f -> val b = f.readBytes(); f.writeBytes(b.copyOf(b.size / 3)) }
        val platform = T3Platform(root, player = T3Player())
        val s = SheetsState(platform)
        s.scanFolder()
        val song = s.library!!.songs.first()
        say("songs: ${s.library!!.songs.map { it.title to it.audio.map { a -> a.file.substringAfterLast('/') } }}")
        val player = JavaSoundPlayer()
        player.volume = 0.0
        for (n in listOf("Song - one.wav", "Song - broken.mp3", "Song - empty.wav", "Song - cut.wav", "Song - missing.wav")) {
            val f = File(root, "Band/$n")
            val t0 = System.nanoTime()
            val ok = try { player.load(f) } catch (e: Throwable) { say("  $n THREW $e"); false }
            say("  $n -> load=$ok in ${(System.nanoTime() - t0) / 1_000_000} ms, duration=${player.durationMs} ms (a failed load leaves the previous file's sound loaded)")
        }
        player.release()
        // Strip Play with a missing file: what the player is told.
        Recording.player = null; Recording.loadedFile = null
        val missing = AudioTrack("Band/Song - gone.wav")
        s.change { editSong(song.id) { audio = song.audio + missing } }
        s.current = s.library!!.song(song.id)
        val first = s.current!!.audio
        say("Play button on a song whose first recording file is gone: it just plays nothing, no message (Recording.toggle runs load on a thread and ignores false). first recording = ${first.first().file}")
    }

    @Test
    fun `a synced track with nonsense numbers`() {
        val root = tmp.newFolder("Music")
        val f = File(root, "x.wav").also { wav(it, 3.0) }
        val player = JavaSoundPlayer()
        player.volume = 0.0
        player.load(f)
        for ((label, speed) in listOf("speed 0" to 0.0, "speed -1" to -1.0, "speed NaN" to Double.NaN, "speed 50" to 50.0, "speed 0.01" to 0.01)) {
            player.speed = speed
            player.pitch = 0
            player.seek(0)
            player.play()
            Thread.sleep(700)
            val moved = player.positionMs
            val alive = player.playing
            player.pause()
            say("  $label: after 0.7 s position=$moved ms, still playing=$alive")
        }
        player.speed = 1.0
        player.pitch = 400
        player.seek(0)
        player.play(); Thread.sleep(500)
        say("  pitch +400 semitones: position ${player.positionMs} ms, playing ${player.playing}")
        player.pause()
        player.pitch = -100
        player.play(); Thread.sleep(500)
        say("  pitch -100 semitones: position ${player.positionMs} ms, playing ${player.playing}")
        player.pause()
        player.release()
    }

    @Test
    fun `a song title that Windows cannot make a folder from, recording yourself`() {
        val titles = listOf("AUX", "CON", "Mr. Brightside.", "AC/DC - Thunderstruck", "Für Elise ♪ 🎺", "A".repeat(120), "B".repeat(250), "  spaced  ", "What?")
        for (title in titles) {
            val root = tmp.newFolder("Music-" + titles.indexOf(title))
            val mic = T3Mic(48_000, FloatArray(48_000 * 2) { 0.1f })
            val platform = T3Platform(root, mic = mic, player = T3Player())
            val s = SheetsState(platform)
            val song = s.library!!.addSong(title).let { s.library!!.song(it.id)!! }
            s.version
            val result = try {
                val ok = SelfRecorder.start(s, song)
                Thread.sleep(300)
                javax.swing.SwingUtilities.invokeAndWait { SelfRecorder.stop(s) }
                val tracks = s.library!!.song(song.id)!!.audio
                "started=$ok, paired=${tracks.size}, file=${tracks.firstOrNull()?.file?.let { s.fileOf(it)?.exists() }}, path length ${tracks.firstOrNull()?.file?.length}"
            } catch (e: Throwable) {
                "THREW ${e::class.simpleName}: ${e.message?.take(120)}"
            }
            SelfRecorder.recording = false
            say("  title '${title.take(40)}${if (title.length > 40) "..." else ""}' (${title.length} chars): $result")
        }
    }

    @Test
    fun `the real m4a and the big mp3 in the library load on this player`() {
        val m4a = File(music, "Imported/PEP BAND/Music/Hey Baby/Hey Baby - Audio.m4a")
        assumeTrue(m4a.isFile)
        val p = JavaSoundPlayer()
        val t0 = System.nanoTime()
        say("Hey Baby - Audio.m4a (${m4a.length() / 1024} KB): load=${p.load(m4a)} in ${(System.nanoTime() - t0) / 1_000_000} ms, ${p.durationMs / 1000} s")
        p.release()
    }
}
