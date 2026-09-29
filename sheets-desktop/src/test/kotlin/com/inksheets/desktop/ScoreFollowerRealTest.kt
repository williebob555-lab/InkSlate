package com.inksheets.desktop

import com.inksheets.core.Chroma
import com.inksheets.core.MusicPresence
import com.inksheets.core.ScoreFollower
import com.inksheets.core.TimeStretch
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.abs

/**
 * Following a real recording from the music folder: the "band in the room" is the same recording
 * played slower, quieter and with noise, fed in blocks as a microphone would. Skipped where the
 * music folder is not on this machine.
 */
class ScoreFollowerRealTest {

    private val library = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/Imported/PEP BAND/Music")

    private fun decode(file: File): Pair<FloatArray, Int> {
        AudioSystem.getAudioInputStream(file).use { raw ->
            val src = raw.format
            val pcm = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.sampleRate, 16, src.channels, src.channels * 2, src.sampleRate, false)
            AudioSystem.getAudioInputStream(pcm, raw).use { d ->
                val bytes = d.readAllBytes()
                val ch = pcm.channels
                val frames = bytes.size / (2 * ch)
                val mono = FloatArray(frames) { f ->
                    var s = 0f
                    for (c in 0 until ch) {
                        val at = (f * ch + c) * 2
                        s += ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort() / 32768f
                    }
                    s / ch
                }
                return mono to pcm.sampleRate.toInt()
            }
        }
    }

    private fun follow(name: String, speed: Double, seconds: Int) {
        val file = library.walkTopDown().firstOrNull { it.name == name }
        assumeTrue("no $name here", file != null)
        val (all, rate) = decode(file!!)
        val clip = all.copyOf(minOf(all.size, rate * seconds))
        val t0 = System.nanoTime()
        val reference = Chroma.frames(Chroma.resample(clip, rate))
        println("$name: ${reference.size} frames in ${(System.nanoTime() - t0) / 1_000_000} ms")

        val follower = ScoreFollower(reference)
        val presence = MusicPresence()
        val stream = Chroma.Stream(rate)
        val live = TimeStretch(clip, rate).apply { this.speed = speed }
        val random = java.util.Random(7)
        val block = FloatArray(2048)
        var heardSamples = 0L
        val errors = ArrayList<Double>()
        var work = 0L
        while (!live.atEnd) {
            live.read(block)
            for (i in block.indices) block[i] = block[i] * 0.4f + (random.nextGaussian() * 0.01).toFloat()
            heardSamples += block.size
            val s = System.nanoTime()
            for (f in stream.feed(block)) {
                presence.hear(f)
                follower.hear(f, presence.quiet(f))
            }
            work += System.nanoTime() - s
            val liveS = heardSamples.toDouble() / rate
            if (liveS > 10 && (heardSamples / block.size) % 50 == 0L) {
                val truth = liveS * speed
                errors += abs(follower.positionMs / 1000.0 - truth)
            }
        }
        // Then the band stops: quiet, with the room's noise.
        repeat((rate * 6) / block.size) {
            for (i in block.indices) block[i] = (random.nextGaussian() * 0.002).toFloat()
            for (f in stream.feed(block)) { presence.hear(f); follower.hear(f, presence.quiet(f)) }
        }
        errors.sort()
        val median = errors[errors.size / 2]
        val worst = errors.last()
        val liveSeconds = heardSamples.toDouble() / rate
        println("$name at ${(speed * 100).toInt()}%: median error ${"%.2f".format(median)} s, worst ${"%.2f".format(worst)} s over ${errors.size} checks; " +
            "${work / 1_000_000} ms of work for ${"%.0f".format(liveSeconds)} s heard; stopped=${presence.stopped}")
        assertTrue("median $median", median < 1.5)
        assertTrue("worst $worst", worst < 5.0)
        assertTrue("noticed the music stop", presence.stopped)
    }

    /** The tempo the tracker hears in [seconds] of [clip] played at [speed] in a noisy room. */
    private fun tempoOf(clip: FloatArray, rate: Int, speed: Double, expected: Double): Pair<Double, Double> {
        val live = TimeStretch(clip, rate).apply { this.speed = speed }
        val tracker = com.inksheets.core.TempoTracker(rate).apply { this.expected = expected }
        val random = java.util.Random(5)
        val block = FloatArray(2048)
        val seen = ArrayList<Double>()
        var heard = 0
        while (!live.atEnd && heard < rate * 40) {
            live.read(block)
            for (i in block.indices) block[i] = block[i] * 0.4f + (random.nextGaussian() * 0.01).toFloat()
            heard += block.size
            if (tracker.feed(block) && heard > rate * 10) tracker.bpm?.let { seen += it }
        }
        seen.sort()
        return seen[seen.size / 2] to (seen.last() - seen.first())
    }

    @Test
    fun `the tempo heard follows the band speeding up and slowing down`() {
        for (name in listOf("Sweet Caroline 2009.mp3", "September.mp3", "Enter Sandman.mp3")) {
            val file = library.walkTopDown().firstOrNull { it.name == name } ?: continue
            val (all, rate) = decode(file)
            val clip = all.copyOfRange(rate * 20, minOf(all.size, rate * 80))
            val (base, spread) = tempoOf(clip, rate, 1.0, 110.0)
            val (slow, _) = tempoOf(clip, rate, 0.85, base)
            val (fast, _) = tempoOf(clip, rate, 1.15, base)
            println("$name: ${"%.1f".format(base)} bpm (spread ${"%.1f".format(spread)}), at 85% ${"%.1f".format(slow)} (x${"%.3f".format(slow / base)}), at 115% ${"%.1f".format(fast)} (x${"%.3f".format(fast / base)})")
            assertTrue("$name slowed: ${slow / base}", kotlin.math.abs(slow / base - 0.85) < 0.03)
            assertTrue("$name sped up: ${fast / base}", kotlin.math.abs(fast / base - 1.15) < 0.03)
        }
    }

    @Test
    fun `follows Sweet Caroline played slower in a noisy room, and hears it stop`() = follow("Sweet Caroline 2009.mp3", 0.85, 120)

    @Test
    fun `follows September played faster`() = follow("September.mp3", 1.15, 120)
}
