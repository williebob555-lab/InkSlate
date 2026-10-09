package com.inksheets.core.sampler

import com.inksheets.core.omr.Box
import com.inksheets.core.omr.Clef
import com.inksheets.core.omr.Direction
import com.inksheets.core.omr.Duration
import com.inksheets.core.omr.EnsemblePlayer
import com.inksheets.core.omr.Key
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Pitch
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.ScorePlayer
import com.inksheets.core.omr.Synth
import com.inksheets.core.omr.TimeSig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class SamplerTest {
    private val rate = 44100

    // ---- fixtures -------------------------------------------------------------------------------

    private fun le(o: ByteArrayOutputStream, v: Int, n: Int) { for (i in 0 until n) o.write((v shr (8 * i)) and 0xFF) }

    /** A WAV file: [data] (frames x channels interleaved) as PCM [bits] (16, 24) or float32 (bits = 32). */
    private fun wav(data: FloatArray, sr: Int = rate, bits: Int = 16, channels: Int = 1): ByteArray {
        val bytes = bits / 8
        val o = ByteArrayOutputStream()
        o.write("RIFF".toByteArray()); le(o, 36 + data.size * bytes, 4); o.write("WAVE".toByteArray())
        o.write("fmt ".toByteArray()); le(o, 16, 4); le(o, if (bits == 32) 3 else 1, 2); le(o, channels, 2)
        le(o, sr, 4); le(o, sr * channels * bytes, 4); le(o, channels * bytes, 2); le(o, bits, 2)
        o.write("data".toByteArray()); le(o, data.size * bytes, 4)
        for (x in data) when (bits) {
            16 -> le(o, (x * 32767).toInt(), 2)
            24 -> le(o, (x * 8388607).toInt(), 3)
            else -> le(o, java.lang.Float.floatToIntBits(x), 4)
        }
        return o.toByteArray()
    }

    private fun sine(hz: Double, seconds: Double, amp: Double = 0.5, sr: Int = rate, decay: Double = 0.0) =
        FloatArray((seconds * sr).toInt()) { (amp * sin(2 * PI * hz * it / sr) * kotlin.math.exp(-decay * it / sr)).toFloat() }

    private fun dir(): File = Files.createTempDirectory("sampler-test").toFile().also { it.deleteOnExit(); File(it, "samples").mkdirs() }

    private fun put(d: File, name: String, data: FloatArray) = File(d, "samples/$name").writeBytes(wav(data))

    /** A preset folder as the sampler app writes one. [loopXf]: the loop crossfade in frames. */
    private fun fixture(loopXf: Int = 2000, release: Boolean = true): File {
        val d = dir()
        // 441 Hz: exactly 100 frames a cycle. The loop points do not fall on a cycle, so only the crossfade makes it seamless.
        put(d, "sus.wav", sine(441.0, 1.5))
        put(d, "stac.wav", sine(1500.0, 0.3, decay = 8.0))
        put(d, "rel.wav", sine(3000.0, 0.3, 0.8, decay = 12.0))
        put(d, "rr1.wav", sine(600.0, 0.5)); put(d, "rr2.wav", sine(900.0, 0.5))
        put(d, "soft.wav", sine(300.0, 0.5, 0.1)); put(d, "loud.wav", sine(300.0, 0.5, 0.8))
        val rel = if (release) """
    <group name="Release" attack="0" decay="0" sustain="1" release="0.05" trigger="release">
      <sample path="samples/rel.wav" rootNote="69" loNote="0" hiNote="127" loVel="0" hiVel="127" />
    </group>""" else ""
        File(d, "Test.dspreset").writeText("""<?xml version="1.0" encoding="UTF-8"?>
<DecentSampler minVersion="1.11.0">
  <!-- Test - someone -->
  <groups volume="0dB" seqMode="round_robin">
    <group name="Sustain" attack="0.01" decay="0" sustain="1" release="0.05" tags="art_1">
      <sample path="samples/sus.wav" rootNote="69" loNote="40" hiNote="79" loVel="0" hiVel="127" loopEnabled="true" loopStart="4410" loopEnd="30000" loopCrossfade="$loopXf" tuning="0" extra="ignored" />
    </group>
    <group name="Staccato" attack="0.001" decay="0" sustain="1" release="0.03" tags="art_2">
      <sample path="samples/stac.wav" rootNote="69" loNote="40" hiNote="79" loVel="0" hiVel="127" />
    </group>
    <group name="RR 1" attack="0.005" decay="0" sustain="1" release="0.05" seqPosition="1">
      <sample path="samples/rr1.wav" rootNote="85" loNote="80" hiNote="90" loVel="0" hiVel="127" />
    </group>
    <group name="RR 2" attack="0.005" decay="0" sustain="1" release="0.05" seqPosition="2">
      <sample path="samples/rr2.wav" rootNote="85" loNote="80" hiNote="90" loVel="0" hiVel="127" />
    </group>
    <group name="Layers" attack="0.005" decay="0" sustain="1" release="0.05" volume="-6dB">
      <sample path="samples/soft.wav" rootNote="95" loNote="91" hiNote="100" loVel="0" hiVel="63" volume="-3dB" pan="-50" tuning="0.5" />
      <sample path="samples/loud.wav" rootNote="95" loNote="91" hiNote="100" loVel="64" hiVel="127" />
    </group>$rel
  </groups>
  <ui width="812" height="200" bgColor="FF1A1A1A"><tab name="main"/></ui>
</DecentSampler>
""")
        return d
    }

    private fun tone(midi: Int, start: Double, len: Double, patch: Synth.Patch, legato: Boolean = false, from: Int? = null, art: Int = 0, vel: Float = 0.7f) =
        Synth.Tone(midi, (start * rate).toLong(), (len * rate).toLong(), vel, patch, legato = legato, from = from, art = art)

    /** [tones] through a [SampledVoice] (several joined if legato) straight to samples, no room or limiter. */
    private fun render(inst: SampledInstrument, seconds: Double, tones: List<Synth.Tone>): FloatArray {
        val total = (seconds * rate).toInt()
        val out = FloatArray(total)
        val voices = ArrayList<SampledVoice>()
        val pending = tones.sortedBy { it.start }.toMutableList()
        val block = FloatArray(480)
        var pos = 0L
        while (pos < total) {
            while (pending.isNotEmpty() && pending[0].start < pos + block.size) {
                val t = pending.removeAt(0)
                val carrier = if (t.legato) voices.firstOrNull { abs(it.last().start + it.last().length - t.start) <= rate * 0.06 } else null
                if (carrier != null) carrier.queue += t else voices += SampledVoice(inst, rate, t, 1f)
            }
            java.util.Arrays.fill(block, 0f)
            val it = voices.iterator()
            while (it.hasNext()) if (it.next().render(block, pos)) it.remove()
            System.arraycopy(block, 0, out, pos.toInt(), minOf(block.size, total - pos.toInt()))
            pos += block.size
        }
        return out
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from))
    }

    // ---- tests ----------------------------------------------------------------------------------

    @Test
    fun `reads PCM 16 and 24 bit and float, mono and stereo, and resamples`() {
        val ramp = FloatArray(1000) { sin(it * 0.05).toFloat() * 0.5f }
        for (bits in listOf(16, 24, 32)) {
            val p = Wav.decode(wav(ramp, 22050, bits))
            assertEquals(22050, p.rate); assertEquals(1000, p.frames)
            for (i in ramp.indices) assertEquals(ramp[i].toDouble(), p.data[i].toDouble(), 1e-3)
        }
        val stereo = FloatArray(2000) { if (it % 2 == 0) 0.5f else -0.1f }
        val p = Wav.decode(wav(stereo, 48000, 24, 2))
        assertEquals(1000, p.frames); assertEquals(0.2, p.data[10].toDouble(), 1e-3)
        val up = Wav.resample(Wav.decode(wav(sine(440.0, 0.2, sr = 22050), 22050)), 44100)
        assertTrue(abs(up.frames - 8820) <= 2)
        val ref = sine(440.0, 0.2)
        var worst = 0.0
        for (i in 10 until up.frames - 10) worst = maxOf(worst, abs(up.data[i] - ref[i]).toDouble())
        assertTrue("resample error $worst", worst < 0.01)
    }

    @Test
    fun `parses a dspreset as the sampler app writes it`() {
        val inst = SampledInstrument.load(fixture())
        assertEquals(6, inst.groups.size)
        val sus = inst.groups[0]
        assertEquals(Art.DEFAULT, sus.art); assertEquals(0.05, sus.release, 1e-9); assertEquals(0.01, sus.attack, 1e-9)
        val z = sus.zones[0]
        assertEquals(69, z.root); assertEquals(40, z.keyLow); assertEquals(79, z.keyHigh)
        assertTrue(z.loop); assertEquals(4410, z.loopStart); assertEquals(30000, z.loopEnd); assertEquals(2000, z.crossfade)
        assertEquals(Art.STACCATO, inst.groups[1].art)
        assertEquals(2, inst.groups[3].seqPos)
        val layers = inst.groups[4]
        assertEquals(-6.0, layers.gainDb, 1e-9); assertEquals(-3.0, layers.zones[0].gainDb, 1e-9); assertEquals(0.5, layers.zones[0].tune, 1e-9)
        assertEquals(Trigger.RELEASE, inst.groups[5].trigger)
        assertTrue(inst.warnings.isEmpty())
    }

    @Test
    fun `parses an sfz as the sampler app writes it`() {
        val d = dir()
        put(d, "a.wav", sine(440.0, 0.5)); put(d, "b.wav", sine(660.0, 0.5)); put(d, "r.wav", sine(2000.0, 0.2))
        File(d, "Horn.sfz").writeText("""// Horn
// Generated 2026-10-09

<control>
default_path=samples\

<global>
bend_up=200
bend_down=-200

// ---- Sustain ----
<group>
ampeg_attack=0.02
ampeg_decay=0
ampeg_sustain=80
ampeg_release=0.2
volume=-2

<region>
sample=a.wav
lokey=48 hikey=71 pitch_keycenter=60
lovel=1 hivel=127
seq_length=2 seq_position=1
transpose=1 tune=-20 volume=-1
offset=100 end=20000
loop_mode=loop_continuous loop_start=5000 loop_end=15000

<region>
sample=b.wav
lokey=48 hikey=71 pitch_keycenter=60 lovel=1 hivel=127
seq_length=2 seq_position=2
loop_mode=no_loop

// ---- Release (Release) ----
<group>
trigger=release
ampeg_attack=0
ampeg_release=0.05

<region>
sample=r.wav
lokey=0 hikey=127 pitch_keycenter=60
""")
        val inst = SampledInstrument.load(d)
        assertEquals(2, inst.groups.size)
        val g = inst.groups[0]
        assertEquals("Sustain", g.name); assertEquals(0.8, g.sustain, 1e-9); assertEquals(0.2, g.release, 1e-9)
        assertEquals(2, g.zones.size)
        val a = g.zones[0]
        assertEquals(48, a.keyLow); assertEquals(71, a.keyHigh); assertEquals(60, a.root)
        assertEquals(-3.0, a.gainDb, 1e-9)   // group -2 and region -1 add
        assertEquals(1.0 - 0.2, a.tune, 1e-9)
        assertEquals(100, a.start); assertEquals(20001, a.end)
        assertTrue(a.loop); assertEquals(5000, a.loopStart)
        assertEquals(1, a.seqPos); assertEquals(2, g.zones[1].seqPos)
        assertTrue(!g.zones[1].loop)
        assertEquals(Trigger.RELEASE, inst.groups[1].trigger)
    }

    @Test
    fun `zones are picked by key and velocity, round robins turn, articulations choose their group`() {
        val inst = SampledInstrument.load(fixture())
        fun name(midi: Int, vel: Int, prefer: List<Art> = emptyList()) = inst.pick(midi, vel, prefer, Trigger.ATTACK, 1)!!
        assertEquals("Sustain", name(60, 80).group.name)
        assertEquals("Staccato", name(60, 80, listOf(Art.STACCATO)).group.name)
        assertEquals("Sustain", name(60, 80, listOf(Art.LEGATO)).group.name)   // no legato group: the default
        assertEquals("soft.wav", inst.pick(95, 30, emptyList(), Trigger.ATTACK, 1)!!.zone.let { z -> if (z.pcm.data.maxOrNull()!! < 0.2f) "soft.wav" else "loud.wav" })
        assertEquals("loud.wav", inst.pick(95, 100, emptyList(), Trigger.ATTACK, 1)!!.zone.let { z -> if (z.pcm.data.maxOrNull()!! < 0.2f) "soft.wav" else "loud.wav" })
        // A key with no sample takes the nearest zone.
        assertEquals(Art.DEFAULT, name(30, 80).group.art)
        assertEquals(40, name(30, 80).zone.keyLow)
        val seq = (0 until 6).map { name(85, 90).group.name }
        assertEquals(listOf("RR 1", "RR 2", "RR 1", "RR 2", "RR 1", "RR 2"), seq)
        assertNull(inst.pick(60, 80, emptyList(), Trigger.LEGATO, 1))
        assertNotNull(inst.pick(60, 80, emptyList(), Trigger.RELEASE, 1))
    }

    @Test
    fun `a loop is seamless - no step bigger than the waveform's own steepest`() {
        for ((xf, seamless) in listOf(2000 to true, 0 to false)) {
            val inst = SampledInstrument.load(fixture(loopXf = xf))
            val patchTone = tone(69, 0.0, 3.0, Synth.sampledPatch(inst))
            val out = render(inst, 3.2, listOf(patchTone))
            // The first 0.2 s are the attack and the unlooped start; after that the loop (25590 frames) wraps every 0.58 s.
            val from = (0.2 * rate).toInt(); val to = (2.9 * rate).toInt()
            var peak = 0f; var step = 0f
            for (i in from until to) { peak = maxOf(peak, abs(out[i])); step = maxOf(step, abs(out[i + 1] - out[i])) }
            val scale = peak / 0.5f
            val own = scale * 0.5f * (2 * PI * 441.0 / rate).toFloat()
            println("PS loop xf=$xf: biggest step $step vs the waveform's own $own (x${"%.2f".format(step / own)})")
            if (seamless) assertTrue("step $step vs $own", step <= own * 1.35f)
            else assertTrue("without a crossfade this loop does jump ($step vs $own)", step > own * 1.35f)
        }
    }

    @Test
    fun `under a slur the sound crossfades into the next note with no dip and no new attack`() {
        val d = dir()
        put(d, "s.wav", sine(441.0, 1.0))
        File(d, "L.dspreset").writeText("""<DecentSampler><groups volume="0dB">
<group name="Sustain" attack="0.02" decay="0" sustain="1" release="0.05">
<sample path="samples/s.wav" rootNote="69" loNote="0" hiNote="127" loopEnabled="true" loopStart="2000" loopEnd="40000" loopCrossfade="400" />
</group></groups></DecentSampler>""")
        val inst = SampledInstrument.load(d)
        val patch = Synth.sampledPatch(inst)
        val out = render(inst, 2.2, listOf(tone(69, 0.0, 1.0, patch), tone(76, 1.0, 1.0, patch, legato = true, from = 69)))
        val win = (0.02 * rate).toInt()
        fun level(t0: Double) = rms(out, (t0 * rate).toInt(), (t0 * rate).toInt() + win)
        val steady = listOf(0.5, 0.6, 0.7, 0.8, 1.2, 1.3, 1.4, 1.5).map { level(it) }.average()
        var worst = 1e9; var best = 0.0
        var t = 0.93
        while (t < 1.07) { val l = level(t); worst = minOf(worst, l); best = maxOf(best, l); t += 0.005 }
        val dipDb = 20 * log10(worst / steady)
        println("PS legato: steady rms $steady, lowest across the move ${"%.2f".format(dipDb)} dB, highest ${"%.2f".format(20 * log10(best / steady))} dB")
        assertTrue("dip $dipDb dB", dipDb >= -1.5)
        assertTrue("no fresh attack: peak ${20 * log10(best / steady)} dB", 20 * log10(best / steady) <= 1.5)
        // And the move did happen: the pitch is a fifth higher after it.
        fun crossings(a: Double, b: Double): Int { var c = 0; for (i in (a * rate).toInt() until (b * rate).toInt()) if (out[i] <= 0f && out[i + 1] > 0f) c++; return c }
        assertEquals(441.0, crossings(0.5, 0.9) / 0.4, 12.0)
        assertEquals(441.0 * Math.pow(2.0, 7 / 12.0), crossings(1.2, 1.6) / 0.4, 14.0)
    }

    @Test
    fun `release samples play at note off, and a staccato note uses the staccato group`() {
        val with = SampledInstrument.load(fixture(release = true))
        val without = SampledInstrument.load(fixture(release = false))
        fun after(inst: SampledInstrument): Double {
            val out = render(inst, 1.2, listOf(tone(69, 0.0, 0.5, Synth.sampledPatch(inst))))
            return rms(out, (0.56 * rate).toInt(), (0.66 * rate).toInt())   // after the note and its 50 ms release
        }
        val a = after(with); val b = after(without)
        println("PS release: ${"%.5f".format(a)} with release samples, ${"%.5f".format(b)} without")
        assertTrue(a > 0.05); assertTrue(b < 0.001)
        // Staccato: the staccato group's 1500 Hz decaying sample, not the 441 Hz sustain.
        val out = render(with, 0.4, listOf(tone(69, 0.0, 0.2, Synth.sampledPatch(with), art = Synth.ART_STACCATO)))
        var c = 0; for (i in (0.01 * rate).toInt() until (0.1 * rate).toInt()) if (out[i] <= 0f && out[i + 1] > 0f) c++
        assertEquals(1500.0, c / 0.09, 120.0)
    }

    @Test
    fun `assigned instruments replace the synth voice and the assignment is remembered`() {
        val store = Files.createTempDirectory("sampler-store").toFile().also { it.deleteOnExit() }
        val lib = SamplerLibrary(store)
        val inst = lib.load(fixture(), "Warm Euph")
        assertEquals(listOf("Warm Euph"), lib.list().map { it.name })
        assertNull(lib.patchFor("euphonium"))
        lib.assign("euphonium", "Warm Euph")
        assertTrue(lib.patchFor("euphonium|1")!!.sampled === inst)
        assertTrue(Synth.patchFor("euphonium", lib).sampled === inst)
        assertNull(Synth.patchFor("trumpet", lib).sampled)
        assertTrue(Synth.patchFor("trumpet", lib) === Synth.BRASS)
        val again = SamplerLibrary(store)
        assertEquals("Warm Euph", again.assignedTo("Euphonium"))
        assertNull(again.patchFor("euphonium"))        // not loaded yet
        again.load(fixture(), "Warm Euph")
        assertNotNull(again.patchFor("euphonium"))
        again.assign("euphonium", null)
        assertNull(again.patchFor("euphonium"))
        assertNull(SamplerLibrary(store).assignedTo("euphonium"))
    }

    // ---- speed ----------------------------------------------------------------------------------

    private fun pitchOf(midi: Int): Pitch {
        val names = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
        val alters = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
        return Pitch(names[midi % 12], midi / 12 - 1, alters[midi % 12])
    }

    private fun part(bars: Int, base: Int): Score {
        val ms = (1..bars).map { b ->
            val events = (0 until 8).map { i ->
                val m = base + intArrayOf(0, 2, 4, 5, 7, 5, 4, 2)[(i + b) % 8]
                val chord = if (i == 0 && b % 4 == 0) listOf(pitchOf(m), pitchOf(m + 4)) else listOf(pitchOf(m))
                Note(listOf(0), chord, Duration(8), 20f + i * 40f, articulations = if (i % 4 == 3) listOf("staccato") else emptyList())
            }
            val slurs = (0 until 4).map { k -> Direction("slur", 20f + k * 80f, 20f + k * 80f + 45f) }
            Measure(b, 0, 0, Box(0, 0, 400, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4), events, directions = slurs)
        }
        return Score(ms, 1, ms.map { 1000 })
    }

    @Test
    fun `an eight part band of sampled instruments renders at least twenty times real time`() {
        val inst = SampledInstrument.load(fixture())
        val patch = Synth.sampledPatch(inst)
        val bars = 32
        val seconds = bars * 2.0
        val band = (0 until 8).map { EnsemblePlayer.Voice(part(bars, 48 + it * 3), 0, patch) }
        fun ensemble() = EnsemblePlayer(Synth(rate), part(bars, 60), band, 1, bars, 120.0)
        fun run(p: EnsemblePlayer, secs: Double): Double {
            val buf = FloatArray(480); val n = (secs * rate / buf.size).toInt()
            val t0 = System.nanoTime()
            repeat(n) { java.util.Arrays.fill(buf, 0f); p.fill(buf) }
            return secs / ((System.nanoTime() - t0) / 1e9)
        }
        run(ensemble(), 8.0)
        val x = (1..3).maxOf { run(ensemble(), seconds) }
        val solo = (1..3).maxOf { val p = ScorePlayer(Synth(rate), part(bars, 55), 1, bars, 120.0, 0, patch); val buf = FloatArray(480); val n = (seconds * rate / 480).toInt(); val t0 = System.nanoTime(); repeat(n) { java.util.Arrays.fill(buf, 0f); p.fill(buf) }; seconds / ((System.nanoTime() - t0) / 1e9) }
        println("PS sampled speed: 8-part band ${"%.0f".format(x)}x real time, solo ${"%.0f".format(solo)}x")
        assertTrue("band $x x", x >= 20)
        // And it made sound.
        val p = ensemble(); val buf = FloatArray(44100); p.fill(buf)
        assertTrue(buf.any { abs(it) > 0.01f })
    }
}
