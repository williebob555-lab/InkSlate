package com.inksheets.core.sampler

import com.inksheets.core.WavWriter
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BundledInstrumentsTest {
    @Test fun `shipped defaults contain playable recorded zones loops and a separate trumpet mute`() {
        val bank=BundledInstruments.defaults
        assertTrue(bank.instruments.containsAll(listOf("trumpet","trumpet-muted","trombone","clarinet","tenor-sax")))
        for(id in bank.instruments) {
            val patch=bank.patchFor(id)!!
            val instrument=patch.sampled!!
            assertTrue("$id has complete sample references",instrument.warnings.isEmpty())
            assertTrue("$id has playable recordings",instrument.zoneCount>0)
            assertTrue("$id sustains without a source-length cutoff",instrument.groups.filter{it.art==Art.DEFAULT}.flatMap{it.zones}.all{it.loop})
            assertTrue(instrument.groups.flatMap{it.zones}.all{z->z.pcm.data.all{it.isFinite()}})
        }
        assertNotNull(bank.patchFor("trumpet")!!.mutedVariant?.sampled)
        assertNull("Missing instruments retain fallback; no misleading recorded alias",bank.patchFor("euphonium"))
    }
    private fun audio(): ByteArray {
        val file = Files.createTempFile("recorded-default", ".wav").toFile()
        return try {
            WavWriter(file, 24000).use { it.write(FloatArray(2400) { i -> kotlin.math.sin(i * .03).toFloat() * .2f }) }
            file.readBytes()
        } finally { file.delete() }
    }
    private val preset = """<DecentSampler><groups><group name="Sustain"><sample path="tone.wav" rootNote="60"/></group></groups></DecentSampler>"""

    @Test fun `recordings decode lazily aliases share a patch and cache eviction keeps live patch playable`() {
        val files = mapOf("manifest.properties" to "flute=flute/main.dspreset\npiccolo=flute/main.dspreset\nclarinet=clarinet/main.dspreset".toByteArray(),
            "flute/main.dspreset" to preset.toByteArray(), "clarinet/main.dspreset" to preset.toByteArray(),
            "flute/tone.wav" to audio(), "clarinet/tone.wav" to audio())
        val reads = mutableListOf<String>()
        val bank = BundledInstruments({ path -> reads += path; files[path] }, maxCacheBytes = 10000)
        assertEquals(3, bank.instruments.size)
        assertEquals(listOf("manifest.properties"), reads)
        val flute = bank.patchFor("flute")!!
        assertSame(flute, bank.patchFor("piccolo"))
        assertEquals(1, reads.count { it == "flute/tone.wav" })
        assertNotNull(bank.patchFor("clarinet"))
        assertTrue(bank.cachedBytes <= 10000)
        assertEquals(2400, flute.sampled!!.groups.single().zones.single().pcm.frames)
        assertNotSame(flute, bank.patchFor("flute"))
        assertNull(bank.patchFor("euphonium"))
    }

    @Test fun `missing recordings leave fallback available instead of returning a partial instrument`() {
        val bank = BundledInstruments({ path -> when(path) {
            "manifest.properties" -> "flute=flute/main.dspreset".toByteArray()
            "flute/main.dspreset" -> preset.toByteArray()
            else -> null
        } })
        assertNull(bank.patchFor("flute"))
    }
}
