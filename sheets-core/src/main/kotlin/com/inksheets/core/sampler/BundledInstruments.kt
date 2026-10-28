package com.inksheets.core.sampler

import com.inksheets.core.omr.Midi
import com.inksheets.core.omr.Synth
import java.util.Properties

/** Recorded defaults live in application resources, independently of the user's library.
 * Only instruments actually used are decoded. Eviction drops cache references; voices already
 * playing retain their recordings, so changing sounds cannot cut an existing note short. */
class BundledInstruments(
    private val read: (String) -> ByteArray?,
    private val maxCacheBytes: Long = 32L * 1024 * 1024
) {
    private val paths: Map<String, String> by lazy {
        val bytes = read("manifest.properties") ?: return@lazy emptyMap()
        val props = Properties().apply { bytes.inputStream().use { load(it) } }
        props.stringPropertyNames().associateWith { props.getProperty(it) }
    }
    private data class Loaded(val patch: Synth.Patch, val bytes: Long)
    private val cache = LinkedHashMap<String, Loaded>(8, .75f, true)

    val instruments: Set<String> get() = paths.keys
    val cachedBytes: Long @Synchronized get() = cache.values.sumOf { it.bytes }

    @Synchronized
    fun patchFor(instrument: String?): Synth.Patch? {
        val id = instrument?.substringBefore('|')?.trim()?.lowercase() ?: return null
        val presetPath = paths[id] ?: return null
        cache[presetPath]?.let { return it.patch }
        val data = read(safePath(presetPath)) ?: return null
        val text = data.toString(Charsets.UTF_8)
        val preset = if (presetPath.endsWith(".sfz", true)) Presets.parseSfz(text) else Presets.parseDecentSampler(text)
        val directory = presetPath.substringBeforeLast('/', "")
        val audio = HashMap<String, Pcm?>()
        val built = SampledInstrument.build(preset, null) { relative ->
            val path = safePath(listOf(directory, relative.replace('\\', '/')).filter { it.isNotEmpty() }.joinToString("/"))
            audio.getOrPut(path) { read(path)?.let(Wav::decode) }
        }
        // A damaged pack must not silently replace a usable fallback with missing notes.
        if (built.zoneCount == 0 || built.warnings.isNotEmpty()) return null
        val bytes = audio.values.filterNotNull().sumOf { it.frames.toLong() * 4 }
        val muted = if (id == "trumpet") patchFor("trumpet-muted") else null
        val patch = Synth.sampledPatch(built,
            tonguedSlurredRepeats = Synth.patchFor(Midi.program(id.removeSuffix("-muted"))).tonguedSlurredRepeats,
            mutedVariant = muted)
        val variantBytes = muted?.sampled?.groups.orEmpty().flatMap { it.zones }.map { it.pcm }.distinct().sumOf { it.frames.toLong()*4 }
        cache[presetPath] = Loaded(patch, bytes + variantBytes)
        val entries = cache.entries.iterator()
        var total = cache.values.sumOf { it.bytes }
        while (total > maxCacheBytes && cache.size > 1 && entries.hasNext()) {
            total -= entries.next().value.bytes
            entries.remove()
        }
        return patch
    }

    private fun safePath(path: String): String {
        require(!path.startsWith('/') && ':' !in path && path.split('/').none { it == ".." }) { "invalid bundled sample path" }
        return path
    }

    companion object {
        val defaults by lazy {
            BundledInstruments({ path -> BundledInstruments::class.java
                .getResourceAsStream("/instruments/defaults/$path")?.use { it.readBytes() } })
        }
    }
}
