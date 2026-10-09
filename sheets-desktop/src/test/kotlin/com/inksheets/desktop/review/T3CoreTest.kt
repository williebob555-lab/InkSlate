package com.inksheets.desktop.review

import com.inksheets.core.MeshFrames
import com.inksheets.core.Metronome
import com.inksheets.core.TempoTracker
import com.inksheets.core.Tuner
import com.inksheets.core.TurnPlan
import com.inksheets.core.watch.Cue
import com.inksheets.core.watch.FlickDetector
import com.inksheets.core.watch.FlickTrainer
import com.inksheets.core.watch.Sample
import com.inksheets.core.watch.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sin
import kotlin.random.Random

/**
 * T3 core logic, no screens: the metronome's timing and tap tempo, the tuner on brass and bass
 * ranges, the tempo tracker on real recordings (the audio file standing in for a microphone), the
 * page-turn plan, Play-together frames and the watch's flick trainer on made-up arms.
 */
class T3CoreTest {
    private fun say(text: String) = T3.say("core.txt", text)

    // ---------------------------------------------------------------- page-turn plan

    @Test
    fun `turn plan - a gap left by a missed lesson turns the page the moment Listen starts`() {
        // Listener.pageChanged pads with -1 when the player turned 2->3 first (never 1->2) while the recording played.
        val plan = TurnPlan(listOf(-1L, 20_000L, 40_000L), pages = 4, lengthMs = 60_000)
        say("TurnPlan [-1,20000,40000] on 4 pages: learned=${plan.learned} turnAt(0)=${plan.turnAt(0)} turnAt(1)=${plan.turnAt(1)}")
        assertTrue("claims to be learned although turn 1 was never taught", plan.learned)
        assertEquals("the unknown turn is -1, which is 'already past'", -1L, plan.turnAt(0))
    }

    @Test
    fun `turn plan - one recording, two parts of different page counts share one list of turns`() {
        // Score: 5 pages, Trombone 1: 1 page. Both ride one AudioTrack.turnsMs.
        val fiveLearned = listOf(30_000L, 60_000L, 90_000L, 120_000L)
        val forScore = TurnPlan(fiveLearned, 5, 150_000)
        val forTwoPagePart = TurnPlan(fiveLearned, 2, 150_000)
        say("a 2-page part uses the 5-page score's turn 1 at ${forTwoPagePart.turnAt(0)} (its own would be ~75000), learned=${forTwoPagePart.learned}")
        assertTrue(forTwoPagePart.learned)
        assertEquals(30_000L, forTwoPagePart.turnAt(0))
        // The 2-page part teaches turn 0 at 70 s (writes over the score's 30 s):
        val after = fiveLearned.toMutableList().also { it[0] = 70_000L }
        val damaged = TurnPlan(after, 5, 150_000)
        say("after the 2-page part taught its turn at 70 s, the 5-page score turns 1->2 at ${damaged.turnAt(0)} instead of 30000")
        assertEquals(70_000L, damaged.turnAt(0))
        assertEquals(30_000L, forScore.turnAt(0))
        // A 1-page part never has a turn; a 6-page part shortfall:
        val sixPage = TurnPlan(fiveLearned, 6, 150_000)
        say("a 6-page part with 4 learned turns: learned=${sixPage.learned}, turnAt(4)=${sixPage.turnAt(4)} (guessed evenly: ${150_000L * 5 / 6})")
        assertTrue(!sixPage.learned)
        // the page 4 turn is guessed, the first four are the 5-page piece's: a mixture of two timelines
        assertEquals(30_000L, sixPage.turnAt(0))
    }

    // ---------------------------------------------------------------- metronome

    private fun clickTimes(samples: FloatArray, rate: Int): List<Double> {
        val out = ArrayList<Double>()
        var i = 0
        while (i < samples.size) {
            if (abs(samples[i]) > 0.1f) { out += i * 1000.0 / rate; i += rate / 20 } else i++
        }
        return out
    }

    @Test
    fun `metronome - clicks land where the tempo says, at every tempo the dialog offers, for ten minutes of audio`() {
        val rate = 48_000
        val worst = StringBuilder()
        for (bpm in listOf(20.0, 40.0, 60.0, 92.0, 127.0, 133.3, 200.0, 300.0)) {
            val m = Metronome(rate)
            m.settings = Metronome.Settings(bpm = bpm, subdivision = 1)
            val seconds = if (bpm < 40) 14.0 else 30.0
            val samples = FloatArray((seconds * rate).toInt())
            val block = FloatArray(1024)
            var at = 0
            while (at < samples.size) { m.fill(block); System.arraycopy(block, 0, samples, at, minOf(block.size, samples.size - at)); at += block.size }
            val t = clickTimes(samples, rate)
            val want = 60_000.0 / bpm
            val gaps = t.zipWithNext { a, b -> b - a }
            val drift = (t.last() - t.first()) - want * (t.size - 1)
            worst.append("  ${bpm}bpm: ${t.size} clicks, gap min/max ${"%.3f".format(gaps.min())}/${"%.3f".format(gaps.max())} ms (want ${"%.3f".format(want)}), total drift ${"%.3f".format(drift)} ms\n")
            assertTrue("$bpm bpm drifts $drift ms", abs(drift) < 2.0)
            assertTrue(gaps.all { abs(it - want) < 1.0 })
        }
        say("metronome timing:\n$worst")
    }

    @Test
    fun `metronome - a count-in cue fires on the sample, and reset un-mutes it (so following the band cannot keep the click quiet)`() {
        val rate = 48_000
        val m = Metronome(rate)
        m.settings = Metronome.Settings(bpm = 120.0, beatsPerBar = 4)
        m.muted = true            // what TempoFollow does so the microphone doesn't hear the click
        m.reset()                 // what Click.begin does on Start / Count in
        val buf = FloatArray(rate)
        m.fill(buf)
        val loud = buf.any { abs(it) > 0.1f }
        say("after TempoFollow muted the engine, Click.begin -> reset(): audible=$loud (muted flag is ${m.muted})")
        assertTrue("reset() clears muted, so Start / Count in sound again while TempoFollow listens", loud)
    }

    @Test
    fun `tap tempo - steady, bounced and slow taps`() {
        fun taps(vararg gaps: Long): List<Long> { var t = 1_000L; return listOf(t) + gaps.map { t += it; t } }
        val steady = Metronome.tapTempo(taps(500, 500, 500, 500))
        val twoTaps = Metronome.tapTempo(taps(500))
        val bounce = Metronome.tapTempo(taps(500, 80, 420, 500, 500))     // a contact bounce 80 ms after a tap
        val oneStray = Metronome.tapTempo(taps(500, 500, 900, 500))       // one late tap
        val afterPause = Metronome.tapTempo(taps(500, 500) + listOf(20_000L, 20_500L, 21_000L).map { it })
        say("tap tempo: steady=$steady twoTaps=$twoTaps bounce=$bounce (true 120) oneLate=$oneStray")
        assertEquals(120.0, steady!!, 0.5)
        assertNotNull(twoTaps)
        assertTrue("a bounce moves the tempo by ${bounce}", abs(bounce!! - 120.0) < 3.0)
        val slow = Metronome.tapTempo(taps(2900, 2900, 2900))
        say("  taps 2.9 s apart (20 bpm): $slow")
        val slower = Metronome.tapTempo(taps(3200, 3200))
        say("  taps 3.2 s apart (slower than 3 s counts as a new start): $slower")
        assertNull(slower)
        say("  taps after a long pause: $afterPause")
    }

    // ---------------------------------------------------------------- tuner

    private fun tone(hz: Double, rate: Int, n: Int, harmonics: DoubleArray, noise: Double = 0.0, seed: Int = 1): FloatArray {
        val r = java.util.Random(seed.toLong())
        return FloatArray(n) { i ->
            var v = 0.0
            for ((k, a) in harmonics.withIndex()) v += a * sin(2 * PI * hz * (k + 1) * i / rate)
            (v * 0.25 + r.nextGaussian() * noise).toFloat()
        }
    }

    @Test
    fun `tuner - the notes of trombone, euphonium and bass, in tune and off, as the tuner screen reads them`() {
        val rate = 48_000
        val window = Tuner.windowFor(rate)
        val sb = StringBuilder("tuner on a made-up brass/bass tone (${window}-sample window, clarity must be >=0.75 to show):\n")
        var misses = 0
        val cases = listOf(
            "bass E1" to 41.2, "bass A1" to 55.0, "euph Bb1 pedal" to 58.27, "bass D2" to 73.42, "trb E2" to 82.41, "trb Bb2" to 116.54,
            "trb F3" to 174.61, "trb Bb3" to 233.08, "euph F4" to 349.23, "trb Bb4" to 466.16, "trb F5" to 698.46
        )
        for ((name, hz) in cases) for (cents in listOf(0.0, -25.0, +30.0)) {
            val f = hz * Math.pow(2.0, cents / 1200.0)
            // Low notes on a laptop mic: weak fundamental, strong second harmonic.
            val weakFundamental = hz < 120
            val samples = tone(f, rate, window, if (weakFundamental) doubleArrayOf(0.35, 1.0, 0.6, 0.3) else doubleArrayOf(1.0, 0.7, 0.5, 0.3), noise = 0.01)
            val reading = Tuner.detect(samples, rate)
            val shown = reading != null && reading.clarity >= 0.75
            val err = reading?.let { 1200 * log2(it.hz / f) }
            if (!shown || abs(err!!) > 5) misses++
            sb.append("  %-15s %+4.0f cents: %s\n".format(name, cents, if (reading == null) "NOTHING" else "hz=%.2f clarity=%.2f err=%+.1f cents%s".format(reading.hz, reading.clarity, err, if (!shown) "  (not shown: clarity<.75)" else "")))
        }
        say(sb.toString())
        say("misses (nothing shown or >5 cents off): $misses of ${cases.size * 3}")
        // A chord / two instruments at once, and pure noise: the tuner should stay silent rather than invent a note.
        val chord = FloatArray(window) { i -> (0.3 * sin(2 * PI * 233.08 * i / rate) + 0.3 * sin(2 * PI * 293.66 * i / rate) + 0.3 * sin(2 * PI * 349.23 * i / rate)).toFloat() }
        val chordReading = Tuner.detect(chord, rate)
        val noise = FloatArray(window) { (java.util.Random(9).nextGaussian() * 0.05).toFloat() }
        val noiseReading = Tuner.detect(noise, rate)
        say("  Bb major chord: ${chordReading?.let { "hz=%.1f clarity=%.2f".format(it.hz, it.clarity) }}   white noise: ${noiseReading?.let { "hz=%.1f clarity=%.2f".format(it.hz, it.clarity) }}")
        val t0 = System.nanoTime()
        repeat(40) { Tuner.detect(tone(82.41, rate, window, doubleArrayOf(1.0, 0.5)), rate) }
        say("  Tuner.detect cost: ${(System.nanoTime() - t0) / 40 / 1_000_000.0} ms per call (called every quarter window = ~${window / 4 * 1000 / rate} ms)")
        // A4 shifting: the screen's A= buttons
        val n = Tuner.note(440.0, 0, 442.0)
        say("  440 Hz against A=442: ${n.name}${n.octave} ${"%.1f".format(n.cents)} cents")
    }

    // ---------------------------------------------------------------- tempo tracker (follow the band)

    private fun decodeMono(file: File, seconds: Int): Pair<FloatArray, Int> {
        var clip = FloatArray(0)
        var rate = 44_100
        val platform = com.inksheets.desktop.DesktopSheetsPlatform {}
        platform.decodeAudio(file) { s, r -> if (clip.size < r * seconds) { clip += s; rate = r } }
        return clip.copyOf(minOf(clip.size, rate * seconds)) to rate
    }

    /** What TempoFollow does with the tracker's readings, for [startBpm] on the metronome. Returns the metronome's tempo over time. */
    private fun follow(clip: FloatArray, rate: Int, startBpm: Double, band: Double): String {
        val t = TempoTracker(rate)
        var metronome = startBpm
        val recent = ArrayDeque<Double>()
        val out = StringBuilder("  start ${startBpm.toInt()} (band $band): ")
        var at = 0
        var lastShown = -100.0
        var firstClose = -1.0
        var farReadings = 0
        var readings = 0
        while (at + 480 <= clip.size) {
            val chunk = clip.copyOfRange(at, at + 480)
            at += 480
            t.expected = metronome
            if (!t.feed(chunk)) continue
            val bpm = t.bpm ?: continue
            readings++
            if (abs(log2(bpm / band)) > 0.06) farReadings++
            recent.addLast(bpm); while (recent.size > 3) recent.removeFirst()
            if (recent.size < 3 || recent.max() / recent.min() > 1.04) continue
            val agreed = recent.average()
            val step = ((agreed - metronome) * 0.5).coerceIn(-metronome * 0.1, metronome * 0.1)
            if (abs(step) >= 0.3) metronome += step
            val now = at.toDouble() / rate
            if (firstClose < 0 && abs(metronome - band) / band < 0.03) firstClose = now
            if (now - lastShown >= 10) { out.append("%.0fs->%.0f ".format(now, metronome)); lastShown = now }
        }
        out.append(" | within 3% from ${if (firstClose < 0) "never" else "%.0f s".format(firstClose)}; ${farReadings} of $readings raw readings >6% off the band (half/double/wrong)")
        return out.toString()
    }

    @Test
    fun `tempo follow - the metronome following real band recordings played into the microphone`() {
        val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/Imported/PEP BAND/Music")
        assumeTrue(music.isDirectory)
        // Known tempos (bpm): September 126, Sweet Caroline 126, Don't Stop Believin' 119, Hot Hot Hot ~ 134, Enter Sandman 123.
        val songs = listOf(
            Triple("September/September.mp3", 126.0, listOf(100.0, 60.0)),
            Triple("Sweet Caroline/Sweet Caroline 2009.mp3", 126.0, listOf(100.0, 90.0)),
            Triple("Don't Stop Believin'/Don't Stop Believin'.mp3", 119.0, listOf(100.0, 150.0)),
            Triple("Hot Hot Hot/Hot Hot Hot.mp3", 134.0, listOf(100.0)),
            Triple("Enter Sandman/Enter Sandman.mp3", 123.0, listOf(100.0, 123.0))
        )
        T3.clean("tempo.txt")
        for ((path, bpm, starts) in songs) {
            val f = File(music, path)
            if (!f.isFile) continue
            val (clip, rate) = decodeMono(f, 100)
            // Played "into a room": quieter and with room noise.
            val r = java.util.Random(4)
            val room = FloatArray(clip.size) { clip[it] * 0.4f + (r.nextGaussian() * 0.004).toFloat() }
            T3.say("tempo.txt", "$path (true tempo about $bpm):")
            for (s in starts) T3.say("tempo.txt", follow(room, rate, s, bpm))
        }
    }

    @Test
    fun `tempo follow - a drummer's click track and a half-time feel`() {
        val rate = 48_000
        val bpm = 112.0
        val beat = 60.0 / bpm
        val clip = FloatArray(rate * 40)
        val r = java.util.Random(2)
        var t = 0.0
        while (t < 38) {
            val i = (t * rate).toInt()
            for (k in 0 until 1500) if (i + k < clip.size) clip[i + k] += (sin(2 * PI * 180 * k / rate) * Math.exp(-k / 300.0) * 0.8).toFloat()
            t += beat
        }
        for (i in clip.indices) clip[i] += (r.nextGaussian() * 0.003).toFloat()
        say("tempo follow on a bare 112 bpm kick: " + follow(clip, rate, 100.0, 112.0))
        say("tempo follow on a bare 112 bpm kick, metronome wrongly at 60: " + follow(clip, rate, 60.0, 112.0))
        say("tempo follow on a bare 112 bpm kick, metronome wrongly at 200: " + follow(clip, rate, 200.0, 112.0))
    }

    // ---------------------------------------------------------------- play together frames

    @Test
    fun `mesh frames - messages sent in the same second share an id and the second never arrives`() {
        val session = MeshFrames.sessionOf("Mr Wilson")
        // Companion.meshNote uses noteId = note.at / 1000 (whole seconds).
        val at = 1_790_538_713_881L
        val a = MeshFrames.notePieces(session, MeshFrames.idOf(at), "Trumpets: take the repeat", false, 0)
        val b = MeshFrames.notePieces(session, MeshFrames.idOf(at + 80), "Trombones: mute in", false, 0)
        val asm = MeshFrames.NoteAssembler()
        val got = ArrayList<String>()
        for (p in a + b) asm.take(p)?.let { got += it }
        say("two messages 80 ms apart: assembled $got")
        assertEquals("the second message is lost or mangled", 2, got.size)
    }

    @Test
    fun `mesh frames - a long message and an emoji at the cut`() {
        val session = 7
        val text = "Please all stand for the school song now and play it with energy " + "x".repeat(60) + " 🎺🎺🎺🎺"
        val pieces = MeshFrames.notePieces(session, 99, text, true, 0)
        val asm = MeshFrames.NoteAssembler()
        var got: String? = null
        for (p in pieces) asm.take(MeshFrames.decode(MeshFrames.encode(p)) as MeshFrames.NotePiece)?.let { got = it }
        say("long message (${text.length} chars, ${text.toByteArray().size} bytes) -> ${pieces.size} pieces -> ${got?.length} chars; tail=${got?.takeLast(8)?.map { it.code.toString(16) }}")
        assertNotNull(got)
        // No half emoji (an unpaired surrogate) at the end.
        val g = got!!
        assertTrue("an emoji is cut in half at the end", g.isEmpty() || !Character.isHighSurrogate(g.last()))
        val tooLong = "a".repeat(400)
        val cut = MeshFrames.notePieces(session, 100, tooLong, false, 0)
        val asm2 = MeshFrames.NoteAssembler()
        var got2: String? = null
        for (p in cut) asm2.take(p)?.let { got2 = it }
        say("400-character message arrives as ${got2?.length} characters, with nothing to tell the leader or the readers it was cut")
    }

    @Test
    fun `mesh frames - boundaries and garbage`() {
        val big = MeshFrames.State(0xFFFF, 0xFFFF_FFFFL, 0xFFFF_FFFF_FFFFL, 65_535, 0xFFFF, 6)
        assertEquals(big, MeshFrames.decode(MeshFrames.encode(big)))
        assertTrue(MeshFrames.encode(big).size <= MeshFrames.MAX_BYTES)
        // Random bytes never throw; an unknown version is refused.
        val r = Random(3)
        var crashed = 0
        var accepted = 0
        repeat(5000) {
            val b = ByteArray(r.nextInt(0, 30)) { r.nextInt().toByte() }
            try { if (MeshFrames.decode(b) != null) accepted++ } catch (e: Throwable) { crashed++ }
        }
        say("5000 random byte strings: $crashed crashed the decoder, $accepted were taken as frames (other people's BLE service data on the same UUID would look like this)")
        assertEquals(0, crashed)
        // Two leaders whose names hash to the same 16 bits would drive each other's followers: how likely?
        val names = (1..400).map { "Galaxy Tab S9 FE ($it)" } + listOf("Pixel 8", "Pixel 7", "Galaxy S23", "Galaxy A54")
        val clashes = names.groupBy { MeshFrames.sessionOf(it) }.filter { it.value.size > 1 }
        say("session id is 16 bits of the leader's name: ${clashes.size} clashes among ${names.size} names")
        // Same title in other spellings
        say("songKey 'Sweet Caroline' == 'sweet caroline (2009)': ${MeshFrames.songKey("Sweet Caroline") == MeshFrames.songKey("sweet caroline (2009)")}")
        // Restarted leader: seq restarts at 1 after a few hundred pages
        say("leader restarted after seq 300: isNewer(1,300)=${MeshFrames.isNewer(1, 300)} (follower ignores the restarted leader until it passes 300)")
        assertTrue(!MeshFrames.isNewer(1, 300))
    }

    // ---------------------------------------------------------------- watch flicks

    private fun session(rand: Random, playing: Double, flick: Double, seconds: Int = 200, name: String = ""): Session {
        // 100 Hz sensor. Playing: a swaying arm with jerky accents (slide arm); flicks: 90 ms bursts around the flick axis.
        val axis = floatArrayOf(0.3f, 0.9f, -0.3f).let { v -> val n = Math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat(); FloatArray(3) { v[it] / n } }
        val samples = ArrayList<Sample>()
        val cues = ArrayList<Cue>()
        var t = 0L
        val cueAt = (0 until 10).map { 30_000L + it * 12_000L }
        for (c in cueAt) cues += Cue(c, c / 12_000 % 2 == 0L)
        var jerkUntil = 0L; var jerkAmp = 0f; var jerkDir = FloatArray(3)
        val total = seconds * 1000L
        while (t < total) {
            val s = t / 1000.0
            val g = FloatArray(3) { a -> (playing * 0.35 * sin((0.6 + a) * 2 * PI * s) + playing * 0.2 * sin((1.9 + a * 0.5) * 2 * PI * s) + rand.nextDouble(-0.15, 0.15)).toFloat() }
            if (t >= jerkUntil && rand.nextDouble() < 0.004) { jerkUntil = t + 80; jerkAmp = (playing * rand.nextDouble(0.5, 1.2)).toFloat(); jerkDir = floatArrayOf(rand.nextFloat() - .5f, rand.nextFloat() - .5f, rand.nextFloat() - .5f) }
            if (t < jerkUntil) for (a in 0..2) g[a] += jerkAmp * jerkDir[a]
            for (c in cues) {
                val dt = t - (c.t + 350)
                if (dt in 0..89) { val p = (flick * sin(PI * (dt + 5) / 90.0)).toFloat() * (if (c.next) 1f else -1f); for (a in 0..2) g[a] += p * axis[a] }
            }
            samples += Sample(t, g[0], g[1], g[2])
            t += 10
        }
        return Session(samples, cues)
    }

    @Test
    fun `flicks - calibration quality as the player's playing gets wilder, and what a held-out wild passage does to it`() {
        val rand = Random(11)
        val sb = StringBuilder("flick trainer on made-up arms (100 Hz, 200 s, 10 cues; flick peak 9 rad/s):\n")
        for (playing in listOf(2.0, 4.0, 6.0, 8.0)) {
            val s1 = session(rand, playing, 9.0)
            val s2 = session(rand, playing, 9.0)
            val rep = FlickTrainer.train(listOf(s1), "Trombone")
            val model = rep.model
            // A different afternoon: more energetic playing than calibrated on (a march, a last chorus).
            val wild = session(rand, playing * 1.4, 0.0, seconds = 600)
            val falses = model?.let { m ->
                val d = FlickDetector(m); var n = 0
                for (x in wild.samples) if (d.feed(x) != 0) n++
                n
            }
            val replay = model?.let { FlickTrainer.replay(listOf(s2), it) }
            sb.append("  playing x$playing: model=${model != null} threshold=${model?.next?.threshold?.let { "%.1f".format(it) }} margin=${"%.2f".format(rep.margin)} found=${rep.nextFlicks}/${rep.nextCues} " +
                "heldOut(flicks cues) right=${replay?.right} wrong=${replay?.wrong} missed=${replay?.missed} false=${replay?.falseTurns}  | 10 min of 1.4x wilder playing: $falses false turns | problem=${rep.problem}\n")
        }
        say(sb.toString())
    }
}
