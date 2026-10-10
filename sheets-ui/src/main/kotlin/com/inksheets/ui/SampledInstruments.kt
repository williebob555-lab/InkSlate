package com.inksheets.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inksheets.core.omr.Synth
import com.inksheets.core.sampler.SampledInstrument
import com.inksheets.core.sampler.SamplerLibrary
import java.io.File

/**
 * The player's own sampled instruments (DecentSampler .dspreset or SFZ, as their sampler app
 * exports them): kept in the library's `.inksheets/instruments`, so every device has them; each
 * playing the parts it is set to (a euphonium for Euphonium and Baritone parts), and offered in
 * the music strip's Sound menu to try for one part.
 */
internal object SampledInstruments {
    /** Bumped when the instruments or what they play change, for what shows them. */
    var version by mutableIntStateOf(0)
        private set

    /** What adding one said last ("Added My Euphonium", or why not). */
    var said by mutableStateOf<String?>(null)

    private var loadedFor: String? = null

    /** The instruments the parts can be played by, the same list as the Sound menu's. */
    val instruments = listOf("piano" to "Piano", "flute" to "Flute", "clarinet" to "Clarinet", "alto-sax" to "Alto sax", "tenor-sax" to "Tenor sax",
        "trumpet" to "Trumpet", "horn" to "Horn", "trombone" to "Trombone", "euphonium" to "Euphonium", "baritone" to "Baritone", "tuba" to "Tuba",
        "violin" to "Violin", "cello" to "Cello", "electric-bass" to "Bass guitar")

    private fun store(state: SheetsState): File =
        state.root?.let { File(it, ".inksheets/instruments") } ?: File(state.platform.localFolder, "instruments")

    /**
     * The library of them for [state]'s music. Their recordings load once, off the screen's thread:
     * until they have, the parts play with the built-in voices ([version] goes up when they are in).
     */
    fun ensure(state: SheetsState): SamplerLibrary {
        val dir = store(state)
        val have = SamplerLibrary.active
        if (have != null && loadedFor == dir.path) return have
        val lib = SamplerLibrary(dir)
        SamplerLibrary.active = lib
        loadedFor = dir.path
        Thread({
            runCatching { lib.scan(dir) }.onFailure { state.platform.log("Instruments: ${it.message}") }
            state.platform.onMain { version++ }
        }, "load-instruments").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        return lib
    }

    /** The patch [id] plays with: "sampled:<name>" (one chosen in the Sound menu), else its assigned instrument, else the synthesised one. */
    fun patchFor(state: SheetsState, id: String?): Synth.Patch {
        val lib = ensure(state)
        if (id != null && id.startsWith(PICKED)) lib.get(id.removePrefix(PICKED))?.let { return Synth.sampledPatch(it) }
        // A part's seat ("euphonium-bc", "baritone-tc") plays as its instrument does.
        val base = id?.replace(Regex("-(tc|bc)$"), "")
        return lib.patchFor(id) ?: lib.patchFor(base) ?: Synth.patchFor(com.inksheets.core.omr.Midi.program(id))
    }

    const val PICKED = "sampled:"

    /** Adds the instrument in [picked] - a .dspreset or .sfz beside its samples, or a zip of the export - copied into the library. */
    fun add(state: SheetsState, picked: File) {
        said = "Adding ${picked.nameWithoutExtension}..."
        Thread({
            val result = runCatching {
                val lib = ensure(state)
                val from = if (picked.extension.equals("zip", true)) unzipped(state, picked) else picked.parentFile
                val preset = (if (picked.extension.equals("zip", true)) null else picked) ?: SampledInstrument.presetIn(from)
                    ?: from.walkTopDown().maxDepth(3).firstOrNull { it.extension.lowercase() in setOf("dspreset", "sfz") }
                    ?: error("no .dspreset or .sfz in it")
                val name = preset.nameWithoutExtension
                val dest = File(store(state), safe(name))
                dest.deleteRecursively(); dest.mkdirs()
                // The preset and what it plays: its folder, as exported.
                preset.parentFile.listFiles().orEmpty().forEach { f ->
                    if (f.isDirectory) f.copyRecursively(File(dest, f.name), overwrite = true)
                    else if (f == preset || f.extension.lowercase() in setOf("wav", "flac", "aif", "aiff")) f.copyTo(File(dest, f.name), overwrite = true)
                }
                val inst = lib.load(dest, name)
                if (inst.zoneCount == 0) { lib.unload(name); dest.deleteRecursively(); error("it has no samples that could be read") }
                "Added $name" + if (inst.warnings.isNotEmpty()) " (${inst.warnings.size} sample${if (inst.warnings.size > 1) "s" else ""} missing)" else ""
            }.getOrElse { "Couldn't add ${picked.nameWithoutExtension}: ${it.message}" }
            state.platform.onMain { said = result; version++ }
        }, "add-instrument").apply { isDaemon = true; start() }
    }

    fun remove(state: SheetsState, name: String) {
        val lib = ensure(state)
        lib.unload(name)
        runCatching { File(store(state), safe(name)).deleteRecursively() }
        if (ScoreTools.soundAs == PICKED + name) ScoreTools.soundAs = null
        version++
    }

    /** [name] plays [instrument]'s parts (or stops doing so). */
    fun setPlays(state: SheetsState, name: String, instrument: String, on: Boolean) {
        val lib = ensure(state)
        if (on) lib.assign(instrument, name) else if (lib.assignedTo(instrument) == name) lib.assign(instrument, null)
        version++
    }

    private fun unzipped(state: SheetsState, zip: File): File {
        val out = File(state.platform.cacheFolder, "instrument-${System.nanoTime()}").apply { mkdirs() }
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(out, e.name)
                if (!f.canonicalPath.startsWith(out.canonicalPath)) continue
                if (e.isDirectory) f.mkdirs() else { f.parentFile.mkdirs(); f.outputStream().use { z.copyTo(it) } }
            }
        }
        return out
    }

    private fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "instrument" }
}

/** Settings: the instruments added, what each plays, and adding another. */
@Composable
internal fun InstrumentsSettings(state: SheetsState) {
    val v = SampledInstruments.version
    val lib = remember(v) { SampledInstruments.ensure(state) }
    val loaded = remember(v) { lib.list() }
    val plays = remember(v) { lib.assignments() }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 6.dp)) {
        Text("Instruments", style = MaterialTheme.typography.titleSmall)
        Text("Your own sampled instruments (DecentSampler or SFZ) play the parts you set them to.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (inst in loaded) {
            var choosing by remember { mutableStateOf(false) }
            val mine = plays.filterValues { it == inst.name }.keys
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(inst.name, modifier = Modifier.weight(1f), maxLines = 1)
                Box {
                    TextButton(onClick = { choosing = true }) {
                        Text("Plays: " + (SampledInstruments.instruments.filter { it.first in mine }.joinToString { it.second }.ifEmpty { "nothing yet" }) + "  ▾", maxLines = 1)
                    }
                    DropdownMenu(choosing, onDismissRequest = { choosing = false }) {
                        for ((id, label) in SampledInstruments.instruments) {
                            val on = id in mine
                            DropdownMenuItem(text = { Text((if (on) "✓  " else "     ") + label) },
                                onClick = { SampledInstruments.setPlays(state, inst.name, id, !on) })
                        }
                    }
                }
                IconButton(onClick = { SampledInstruments.remove(state, inst.name) }) { Icon(Icons.Default.Close, "Remove ${inst.name}") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { state.platform.pickInstrument { f -> if (f != null) SampledInstruments.add(state, f) } }) {
                Icon(Icons.Default.Add, null); Text("  Add an instrument...")
            }
            SampledInstruments.said?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** For tests: adding one and waiting for it, and whether a part plays sampled. */
object SampledInstrumentsTesting {
    fun add(state: SheetsState, picked: File) {
        SampledInstruments.add(state, picked)
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until && SampledInstruments.said?.startsWith("Adding") == true) Thread.sleep(20)
    }
    fun sampled(state: SheetsState, id: String) = SampledInstruments.patchFor(state, id).sampled != null
}
