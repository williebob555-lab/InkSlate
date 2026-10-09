package com.inksheets.core.sampler

import java.io.File
import kotlin.math.abs
import kotlin.math.floor

/** How a note is played, as far as a sampled instrument's articulation groups care. */
enum class Art { DEFAULT, LEGATO, STACCATO, ACCENT, TENUTO }

/** One sample placed on the keyboard, its audio loaded. Frames are the recording's own. */
class Zone(
    val pcm: Pcm,
    val root: Int, val keyLow: Int, val keyHigh: Int, val velLow: Int, val velHigh: Int,
    val start: Int, val end: Int,
    val loop: Boolean, val loopStart: Int, val loopEnd: Int, val crossfade: Int,
    val gainDb: Double, val tune: Double,
    val seqPos: Int, val randLo: Double, val randHi: Double
) {
    lateinit var group: Group
}

class Group(
    val name: String, val tags: List<String>, val art: Art,
    val attack: Double, val decay: Double, val sustain: Double, val release: Double,
    val gainDb: Double, val trigger: Trigger, val seqPos: Int
) {
    val zones = ArrayList<Zone>()
}

/**
 * An instrument made of recordings, from a sampler preset: zones by key and velocity, round
 * robins, loops, envelopes, release samples and articulation groups. Played by [SampledVoice].
 */
class SampledInstrument(
    val name: String, val folder: File?, val masterDb: Double, val random: Boolean,
    val groups: List<Group>, val warnings: List<String> = emptyList()
) {
    /** Where each key's round robin has got to (sequential mode). */
    private val turn = IntArray(128)

    val zoneCount: Int get() = groups.sumOf { it.zones.size }

    /** The articulations this instrument has samples for, beyond the default. */
    val articulations: Set<Art> get() = groups.map { it.art }.filter { it != Art.DEFAULT }.toSet()

    fun resetRoundRobin() = turn.fill(0)

    /** What was picked to play. */
    class Pick(val zone: Zone, val group: Group)

    /**
     * The zone to play for [midi] at MIDI velocity [vel]: from the first of [prefer] (in order) that
     * has samples there, else the default group, else any. Round robin as the preset says; [seed]
     * makes a random draw repeatable. [trigger]: note-on, note-off (release samples) or a legato move.
     */
    fun pick(midi: Int, vel: Int, prefer: List<Art>, trigger: Trigger, seed: Long): Pick? {
        val order = ArrayList<Art?>(prefer.filter { it != Art.DEFAULT }); order += Art.DEFAULT; order += null
        val r = hash01(seed)
        for (kind in order) {
            val pool = groups.filter { it.trigger == trigger && (kind == null || it.art == kind) }
            if (pool.isEmpty()) continue
            val exact = ArrayList<Zone>()
            for (g in pool) for (z in g.zones)
                if (midi in z.keyLow..z.keyHigh && vel in z.velLow..z.velHigh && (r >= z.randLo && (r < z.randHi || z.randHi >= 1.0))) exact += z
            if (exact.isNotEmpty()) return choose(exact, midi, r)
            // Release samples are an extra: none for this key means none, not the nearest.
            if (trigger == Trigger.RELEASE) continue
            // Nothing exactly there: the nearest zone in key, then velocity.
            val all = pool.flatMap { it.zones }
            if (all.isEmpty()) continue
            val best = all.minByOrNull { z -> gap(midi, z.keyLow, z.keyHigh) * 4.0 + gap(vel, z.velLow, z.velHigh) * 0.05 } ?: continue
            val near = all.filter { it.keyLow == best.keyLow && it.keyHigh == best.keyHigh && vel in it.velLow..it.velHigh }.ifEmpty { listOf(best) }
            return choose(near, midi, r)
        }
        return null
    }

    private fun gap(x: Int, lo: Int, hi: Int) = if (x < lo) (lo - x).toDouble() else if (x > hi) (x - hi).toDouble() else 0.0

    private fun choose(c: List<Zone>, midi: Int, r: Double): Pick {
        fun pos(z: Zone) = if (z.seqPos > 0) z.seqPos else z.group.seqPos
        val n = c.maxOf { pos(it) }
        var pool = c
        if (n > 1) {
            val want = if (random) floor(r * n).toInt() + 1 else (turn[midi.coerceIn(0, 127)]++ % n) + 1
            pool = c.filter { pos(it) == want }.ifEmpty { c.filter { pos(it) == 0 } }.ifEmpty { c }
        }
        // Of several that still cover the note, the one whose velocity window starts highest (the closest layer).
        val z = pool.maxByOrNull { it.velLow } ?: pool[0]
        return Pick(z, z.group)
    }

    private fun hash01(seed: Long): Double {
        var z = seed * -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return (z ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    companion object {
        /** The articulation a group's name or tags say it is. */
        fun artOf(name: String, tags: List<String>): Art {
            val s = (name + " " + tags.joinToString(" ")).lowercase()
            return when {
                "legato" in s -> Art.LEGATO
                "staccat" in s || "spiccato" in s -> Art.STACCATO
                "marcato" in s || "accent" in s || "sfz" in s -> Art.ACCENT
                "tenuto" in s -> Art.TENUTO
                else -> Art.DEFAULT
            }
        }

        /** Builds from a parsed [preset]; [audio] gives the recording for a path in it (null: missing). */
        fun build(preset: Preset, folder: File?, audio: (String) -> Pcm?): SampledInstrument {
            val warnings = ArrayList<String>()
            val groups = ArrayList<Group>()
            for (gs in preset.groups) {
                val g = Group(gs.name, gs.tags, artOf(gs.name, gs.tags), gs.attack, gs.decay, gs.sustain, gs.release, gs.gainDb, gs.trigger, gs.seqPos)
                for (zs in gs.zones) {
                    val pcm = audio(zs.path)
                    if (pcm == null || pcm.frames == 0) { warnings += "missing sample ${zs.path}"; continue }
                    val frames = pcm.frames
                    val start = zs.start.coerceIn(0, frames - 1)
                    val end = (if (zs.end in 1..frames) zs.end else frames).coerceAtLeast(start + 1)
                    var loop = zs.loop
                    var ls = if (zs.loopStart >= 0) zs.loopStart else start
                    var le = if (zs.loopEnd > 0) zs.loopEnd else end
                    ls = ls.coerceIn(start, end - 1); le = le.coerceIn(ls + 1, end)
                    if (le - ls < 16) loop = false
                    // A loop with no crossfade of its own gets a short one, so it never clicks.
                    var xf = if (zs.xfade >= 0) zs.xfade else minOf(256, (le - ls) / 4)
                    xf = xf.coerceIn(0, minOf(ls, le - ls))
                    g.zones += Zone(pcm, zs.root, zs.lo, zs.hi, zs.velLo, zs.velHi, start, end, loop, ls, le, xf, zs.gainDb, zs.tune, zs.seqPos, zs.randLo, zs.randHi)
                        .also { it.group = g }
                }
                if (g.zones.isNotEmpty()) groups += g
            }
            return SampledInstrument(preset.name, folder, preset.masterDb, preset.random, groups, warnings)
        }

        /** The preset file in [folder] (a .dspreset by preference, else a .sfz), looking one folder down too. */
        fun presetIn(folder: File): File? {
            val found = ArrayList<File>()
            fun scan(d: File, depth: Int) {
                val list = d.listFiles() ?: return
                for (f in list.sortedBy { it.name.lowercase() }) {
                    if (f.isFile && (f.extension.equals("dspreset", true) || f.extension.equals("sfz", true))) found += f
                    else if (f.isDirectory && depth < 1 && !f.name.equals("samples", true)) scan(f, depth + 1)
                }
            }
            scan(folder, 0)
            return found.firstOrNull { it.extension.equals("dspreset", true) } ?: found.firstOrNull()
        }

        /** Loads the instrument in [folder] (or a preset file itself): preset parsed, WAVs decoded once each. */
        fun load(location: File): SampledInstrument {
            val file = if (location.isDirectory) presetIn(location) ?: throw IllegalArgumentException("no .dspreset or .sfz in ${location.path}") else location
            val dir = file.parentFile ?: File(".")
            val preset = Presets.parse(file)
            if (location.isDirectory && location.name.isNotBlank()) preset.name = location.name
            val cache = HashMap<String, Pcm?>()
            return build(preset, dir) { path ->
                cache.getOrPut(path) {
                    resolve(dir, path)?.let { runCatching { Wav.read(it) }.getOrNull() }
                }
            }
        }

        /** [path] under [dir], forgiving about separators and case (presets travel between systems). */
        private fun resolve(dir: File, path: String): File? {
            val direct = File(dir, path.replace('\\', '/'))
            if (direct.isFile) return direct
            var cur = dir
            for (part in path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }) {
                cur = if (part == "..") cur.parentFile ?: return null else (cur.listFiles()?.firstOrNull { it.name.equals(part, true) } ?: return null)
            }
            return cur.takeIf { it.isFile }
        }
    }
}
