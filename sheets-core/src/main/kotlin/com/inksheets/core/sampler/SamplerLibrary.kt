package com.inksheets.core.sampler

import com.inksheets.core.omr.Synth
import java.io.File
import java.util.Properties

/**
 * The sampled instruments in use: loaded from folders, and assigned to instrument names
 * ("euphonium", "trumpet" ... as [com.inksheets.core.omr.Midi.program] knows them). An assigned
 * instrument replaces the built-in synthesised voice for that part, in solo and band playback
 * ([Synth.patchFor] asks [active]); a part with none assigned keeps the synthesised voice.
 *
 * Assignments are kept in [store] (`assignments.properties`, name = instrument id, value = the
 * loaded instrument's name) - by default the folder the instruments themselves live in, so they
 * travel with the library.
 */
class SamplerLibrary(val store: File) {
    private val loadedBy = LinkedHashMap<String, SampledInstrument>()
    private val assigned = LinkedHashMap<String, String>()
    private val patches = HashMap<String, Synth.Patch>()
    private val file get() = File(store, "assignments.properties")

    init { readAssignments() }

    /** The instruments loaded, by name. */
    @Synchronized fun list(): List<SampledInstrument> = loadedBy.values.toList()

    @Synchronized fun get(name: String): SampledInstrument? = loadedBy[name]

    /** Loads the instrument in [folder] (or a .dspreset / .sfz file) and keeps it under [name] (default: the folder's name). */
    @Synchronized
    fun load(folder: File, name: String? = null): SampledInstrument {
        val inst0 = SampledInstrument.load(folder)
        val inst = if (name == null || name == inst0.name) inst0
            else SampledInstrument(name, inst0.folder, inst0.masterDb, inst0.random, inst0.groups, inst0.warnings)
        loadedBy[inst.name] = inst
        patches.clear()
        return inst
    }

    /** Loads every subfolder of [root] that holds a preset (the library's instruments folder); returns those loaded. Bad ones are skipped. */
    @Synchronized
    fun scan(root: File): List<SampledInstrument> {
        val out = ArrayList<SampledInstrument>()
        for (d in root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()) {
            if (SampledInstrument.presetIn(d) == null) continue
            runCatching { load(d) }.getOrNull()?.let { out += it }
        }
        return out
    }

    @Synchronized
    fun unload(name: String) {
        loadedBy.remove(name)
        assigned.values.removeAll { it == name }
        patches.clear(); writeAssignments()
    }

    /** Plays instrument id [instrument] with the loaded instrument [sampled] from now on (null: back to the synthesised voice). */
    @Synchronized
    fun assign(instrument: String, sampled: String?) {
        val key = norm(instrument)
        if (sampled == null) assigned.remove(key) else assigned[key] = sampled
        patches.clear(); writeAssignments()
    }

    /** The loaded instrument's name assigned to [instrument], if any. */
    @Synchronized fun assignedTo(instrument: String?): String? = assigned[norm(instrument)]

    @Synchronized fun assignments(): Map<String, String> = LinkedHashMap(assigned)

    /** The patch for [instrument]: its assigned recordings, or null (use the synthesised voice). */
    @Synchronized
    fun patchFor(instrument: String?): Synth.Patch? {
        val key = norm(instrument)
        val name = assigned[key] ?: return null
        val inst = loadedBy[name] ?: return null
        return patches.getOrPut(name) { Synth.sampledPatch(inst) }
    }

    private fun norm(s: String?) = (s ?: "").substringBefore('|').trim().lowercase()

    private fun readAssignments() {
        val f = file
        if (!f.isFile) return
        runCatching {
            val p = Properties()
            f.inputStream().use { p.load(it) }
            for (k in p.stringPropertyNames().sorted()) assigned[k] = p.getProperty(k)
        }
    }

    private fun writeAssignments() {
        runCatching {
            store.mkdirs()
            val p = Properties()
            for ((k, v) in assigned) p.setProperty(k, v)
            file.outputStream().use { p.store(it, "InkSheets sampled instruments: instrument id = loaded instrument") }
        }
    }

    companion object {
        /** The library playback consults ([Synth.patchFor]); set by the app once at start. */
        @Volatile var active: SamplerLibrary? = null
    }
}
