package com.inksheets.core.sampler

import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import kotlin.math.pow

/** What a preset file says about one sample, before its audio is loaded. Frames are the file's own. */
class ZoneSpec(
    var path: String = "",
    var root: Int = 60, var lo: Int = 0, var hi: Int = 127,
    var velLo: Int = 0, var velHi: Int = 127,
    var start: Int = 0, var end: Int = -1,
    var loop: Boolean = false, var loopStart: Int = -1, var loopEnd: Int = -1, var xfade: Int = -1,
    var gainDb: Double = 0.0,
    /** Semitones. */
    var tune: Double = 0.0,
    var seqPos: Int = 0,
    var randLo: Double = 0.0, var randHi: Double = 1.0
)

enum class Trigger { ATTACK, RELEASE, LEGATO }

class GroupSpec(
    var name: String = "",
    var tags: List<String> = emptyList(),
    var attack: Double = 0.0, var decay: Double = 0.0, var sustain: Double = 1.0, var release: Double = 0.1,
    var gainDb: Double = 0.0,
    var trigger: Trigger = Trigger.ATTACK,
    var seqPos: Int = 0,
    val zones: MutableList<ZoneSpec> = ArrayList()
)

/** A parsed .dspreset or .sfz. [random]: round robins are drawn at random rather than taken in turn. */
class Preset(var name: String = "", var masterDb: Double = 0.0, var random: Boolean = false, val groups: MutableList<GroupSpec> = ArrayList())

/** Reads the presets the sampler app writes (and tolerates other attributes and opcodes). */
object Presets {
    fun parse(file: File): Preset {
        val text = file.readText(Charsets.UTF_8)
        val p = if (file.extension.equals("sfz", true)) parseSfz(text) else parseDecentSampler(text)
        if (p.name.isEmpty()) p.name = file.nameWithoutExtension
        return p
    }

    // ---- shared ---------------------------------------------------------------------------------

    private val NOTES = mapOf('c' to 0, 'd' to 2, 'e' to 4, 'f' to 5, 'g' to 7, 'a' to 9, 'b' to 11)

    /** A key as a number, or as a name ("c#4", "Bb3", middle C being c4 = 60). */
    fun note(s: String?, default: Int): Int {
        val t = s?.trim()?.lowercase() ?: return default
        t.toDoubleOrNull()?.let { return it.toInt() }
        val base = NOTES[t.firstOrNull() ?: return default] ?: return default
        var i = 1; var alt = 0
        while (i < t.length && (t[i] == '#' || t[i] == 'b')) { alt += if (t[i] == '#') 1 else -1; i++ }
        val oct = t.substring(i).toIntOrNull() ?: return default
        return (oct + 1) * 12 + base + alt
    }

    /** "-3dB", "-3 dB", or a plain linear factor ("0.5"), as decibels. */
    fun db(s: String?, default: Double = 0.0): Double {
        val t = s?.trim() ?: return default
        if (t.endsWith("db", true)) return t.dropLast(2).trim().toDoubleOrNull() ?: default
        val lin = t.toDoubleOrNull() ?: return default
        return if (lin <= 0.0) -120.0 else 20 * kotlin.math.log10(lin)
    }

    private fun num(s: String?, d: Double) = s?.trim()?.toDoubleOrNull() ?: d
    private fun int(s: String?, d: Int) = s?.trim()?.toDoubleOrNull()?.toInt() ?: d

    // ---- DecentSampler --------------------------------------------------------------------------

    fun parseDecentSampler(xml: String): Preset {
        val f = DocumentBuilderFactory.newInstance()
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.isExpandEntityReferences = false }
        val doc = f.newDocumentBuilder().parse(InputSource(StringReader(xml.removePrefix("﻿"))))
        val root = doc.documentElement
        val preset = Preset()
        fun kids(e: Element, tag: String): List<Element> {
            val out = ArrayList<Element>()
            val n = e.childNodes
            for (i in 0 until n.length) (n.item(i) as? Element)?.takeIf { it.tagName.equals(tag, true) }?.let { out += it }
            return out
        }
        for (groups in kids(root, "groups")) {
            val gDb = db(groups.getAttribute("volume").ifEmpty { null })
            preset.masterDb += gDb
            if (groups.getAttribute("seqMode").equals("random", true)) preset.random = true
            fun has(e: Element, a: String) = e.hasAttribute(a)
            for (g in kids(groups, "group")) {
                val spec = GroupSpec(
                    name = g.getAttribute("name"),
                    tags = g.getAttribute("tags").split(',', ' ').filter { it.isNotBlank() },
                    attack = num(if (has(g, "attack")) g.getAttribute("attack") else groups.getAttribute("attack"), 0.0),
                    decay = num(if (has(g, "decay")) g.getAttribute("decay") else groups.getAttribute("decay"), 0.0),
                    sustain = num(if (has(g, "sustain")) g.getAttribute("sustain") else groups.getAttribute("sustain"), 1.0).coerceIn(0.0, 1.0),
                    release = num(if (has(g, "release")) g.getAttribute("release") else groups.getAttribute("release"), 0.1),
                    gainDb = db(g.getAttribute("volume").ifEmpty { null }),
                    trigger = when (g.getAttribute("trigger").lowercase()) { "release" -> Trigger.RELEASE; "legato" -> Trigger.LEGATO; else -> Trigger.ATTACK },
                    seqPos = int(g.getAttribute("seqPosition"), 0)
                )
                for (s in kids(g, "sample")) {
                    fun a(n: String) = s.getAttribute(n).takeIf { s.hasAttribute(n) }
                    val z = ZoneSpec(
                        path = (a("path") ?: continue),
                        root = note(a("rootNote"), 60), lo = note(a("loNote"), 0), hi = note(a("hiNote"), 127),
                        velLo = int(a("loVel"), 0), velHi = int(a("hiVel"), 127),
                        start = int(a("start"), 0), end = int(a("end"), -1),
                        loop = a("loopEnabled")?.equals("true", true) == true,
                        loopStart = int(a("loopStart"), -1), loopEnd = int(a("loopEnd"), -1), xfade = int(a("loopCrossfade"), -1),
                        gainDb = db(a("volume")), tune = num(a("tuning"), 0.0),
                        seqPos = int(a("seqPosition"), 0)
                    )
                    spec.zones += z
                }
                preset.groups += spec
            }
        }
        return preset
    }

    // ---- SFZ ------------------------------------------------------------------------------------

    private val TOKEN = Regex("<(\\w+)>|([A-Za-z_][A-Za-z0-9_$]*)=")
    private val LABEL = Regex("^\\s*//\\s*-{2,}\\s*(.+?)\\s*-{2,}\\s*$", RegexOption.MULTILINE)

    fun parseSfz(text0: String): Preset {
        val text = text0.removePrefix("﻿")
        // The exporter names each group in a "// ---- name ----" comment ahead of it.
        val labels = ArrayList<Pair<Int, String>>()
        for (m in LABEL.findAll(text)) labels += m.range.first to m.groupValues[1]
        val clean = text.lines().joinToString("\n") { l -> val i = l.indexOf("//"); if (i >= 0) l.substring(0, i) else l }
        // Offsets differ after stripping comments, so count headers instead: labels are in group order.
        val preset = Preset()
        val control = HashMap<String, String>()
        val global = HashMap<String, String>(); val master = HashMap<String, String>(); val group = HashMap<String, String>()
        var header = ""
        var region: HashMap<String, String>? = null
        var spec: GroupSpec? = null
        var groupIndex = -1
        var defaultPath = ""
        val matches = TOKEN.findAll(clean).toList()
        fun put(ops: HashMap<String, String>, k: String, v: String) {
            ops[k.lowercase()] = if (k.equals("volume", true)) ((ops[k.lowercase()]?.toDoubleOrNull() ?: 0.0) + (v.toDoubleOrNull() ?: 0.0)).toString() else v
        }
        fun finishRegion() {
            val r = region ?: return
            val s = spec ?: return
            val m = HashMap<String, String>()
            for (level in listOf(global, master, group)) for ((k, v) in level) if (k == "volume") m[k] = ((m[k]?.toDoubleOrNull() ?: 0.0) + (v.toDoubleOrNull() ?: 0.0)).toString() else m[k] = v
            for ((k, v) in r) if (k == "volume") m[k] = ((m[k]?.toDoubleOrNull() ?: 0.0) + (v.toDoubleOrNull() ?: 0.0)).toString() else m[k] = v
            sfzZone(m, defaultPath)?.let { s.zones += it }
            region = null
        }
        fun startGroup() {
            val m = HashMap<String, String>()
            for (level in listOf(global, master, group)) m.putAll(level)
            val label = labels.getOrNull(groupIndex)?.second
            val g = GroupSpec(
                name = label ?: m["sw_label"] ?: "group ${groupIndex + 1}",
                tags = listOfNotNull(m["sw_label"]),
                attack = num(m["ampeg_attack"], 0.0), decay = num(m["ampeg_decay"], 0.0),
                sustain = (num(m["ampeg_sustain"], 100.0) / 100.0).coerceIn(0.0, 1.0), release = num(m["ampeg_release"], 0.1),
                gainDb = 0.0,
                trigger = when (m["trigger"]?.lowercase()) { "release", "release_key" -> Trigger.RELEASE; "legato" -> Trigger.LEGATO; else -> Trigger.ATTACK }
            )
            spec = g; preset.groups += g
        }
        for ((idx, m) in matches.withIndex()) {
            val valueEnd = if (idx + 1 < matches.size) matches[idx + 1].range.first else clean.length
            if (m.groupValues[1].isNotEmpty()) {
                finishRegion()
                header = m.groupValues[1].lowercase()
                when (header) {
                    "global" -> { global.clear(); master.clear(); group.clear() }
                    "master" -> { master.clear(); group.clear() }
                    "group" -> { group.clear(); groupIndex++; spec = null }
                    "region" -> { region = HashMap(); if (spec == null) startGroup() }
                }
                continue
            }
            val key = m.groupValues[2].lowercase()
            val value = clean.substring(m.range.last + 1, valueEnd).trim()
            when (header) {
                "control" -> { control[key] = value; if (key == "default_path") defaultPath = value }
                "global" -> put(global, key, value)
                "master" -> put(master, key, value)
                "group" -> put(group, key, value)
                "region" -> region?.let { put(it, key, value) }
            }
        }
        finishRegion()
        // A group's own volume is carried by its zones (SFZ adds the levels); the first <global> volume is the master.
        preset.masterDb = 0.0
        // Empty groups made by a <group> with no region of its own are dropped.
        preset.groups.removeAll { it.zones.isEmpty() }
        return preset
    }

    private fun sfzZone(m: Map<String, String>, defaultPath: String): ZoneSpec? {
        val sample = m["sample"] ?: return null
        val key = m["key"]
        val z = ZoneSpec(
            path = (defaultPath + sample).replace('\\', '/'),
            lo = note(key ?: m["lokey"], 0), hi = note(key ?: m["hikey"], 127),
            root = note(m["pitch_keycenter"] ?: key, 60),
            velLo = int(m["lovel"], 0), velHi = int(m["hivel"], 127),
            start = int(m["offset"], 0), end = int(m["end"], -1).let { if (it >= 0) it + 1 else it },
            gainDb = num(m["volume"], 0.0),
            tune = num(m["transpose"], 0.0) + num(m["tune"], 0.0) / 100.0 + num(m["pitch"], 0.0) / 100.0,
            randLo = num(m["lorand"], 0.0), randHi = num(m["hirand"], 1.0)
        )
        val len = int(m["seq_length"], 0)
        if (len > 1) z.seqPos = int(m["seq_position"], 1)
        val mode = m["loop_mode"]?.lowercase()
        if (mode == "loop_continuous" || mode == "loop_sustain") {
            z.loop = true
            z.loopStart = int(m["loop_start"], -1); z.loopEnd = int(m["loop_end"], -1).let { if (it >= 0) it + 1 else it }
        }
        return z
    }
}

internal fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)
