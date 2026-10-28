package com.inksheets.core.sampler

import com.inksheets.core.WavWriter
import com.inksheets.core.omr.Synth
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Shipped-pack sustain QA plus optional listening artifacts from the full source pack. */
class RecordedSustainTest {
    private val root = File("../build/recorded-defaults")
    private val rate = 24000

    @Test fun `every recorded sustain layer survives twenty second holds without silent loop seams`() {
        val bank=BundledInstruments.defaults
        assertTrue(bank.instruments.isNotEmpty())
        for (id in bank.instruments) {
            val patch=bank.patchFor(id)!!
            val inst=patch.sampled!!
            val zones=inst.groups.filter { it.art==Art.DEFAULT && it.trigger==Trigger.ATTACK }.flatMap { it.zones }
            assertTrue(id, zones.isNotEmpty())
            for(zone in zones) {
                val label="$id root=${zone.root} velocity=${zone.velLow}"
                assertTrue("$label needs a sustain loop",zone.loop)
                assertTrue("$label should retain a long natural section",zone.loopEnd-zone.loopStart>=rate)
                val velocity=(zone.velLow+zone.velHigh)/2f/107f
                val tone=Synth.Tone(zone.root,0,20L*rate,velocity,patch,layer=velocity)
                val voice=SampledVoice(inst,rate,tone)
                val block=FloatArray(rate/10)
                var firstEnergy=0.0;var lateMin=Double.POSITIVE_INFINITY
                repeat(200) { index ->
                    block.fill(0f)
                    assertFalse("$label died during a held note",voice.render(block,index.toLong()*block.size))
                    assertTrue("$label must be finite and unclipped",block.all { it.isFinite()&&abs(it)<.98f })
                    val rms=sqrt(block.sumOf { it.toDouble()*it }/block.size)
                    if(index in 10..29)firstEnergy+=rms/20
                    if(index>=150)lateMin=minOf(lateMin,rms)
                }
                assertTrue("$label dropped out after original recording ended: $lateMin / $firstEnergy",lateMin>firstEnergy*.08)
            }
        }
    }

    @Test fun `long recorded slurs and held notes render listening artifacts without cutouts`() {
        assumeTrue(File(root,"sustain-loops.json").isFile)
        val bank=BundledInstruments({ path -> File(root,path).takeIf { it.isFile }?.readBytes() })
        val output=File("build/recorded-examples").apply { mkdirs() }
        for((id,key)in listOf("trumpet" to 67,"trombone" to 53,"clarinet" to 67,"tenor-sax" to 60)) {
            val patch=bank.patchFor(id)!!
            val synth=Synth(rate)
            synth.add(listOf(Synth.Tone(key,0,6L*rate,.68f,patch,layer=.8f),
                Synth.Tone(key+2,6L*rate,8L*rate,.68f,patch,legato=true,from=key,layer=.8f)))
            val block=FloatArray(rate/10)
            WavWriter(File(output,"$id-long-slur.wav"),rate).use { writer ->
                repeat(145) { i ->
                    block.fill(0f);synth.fill(block)
                    assertTrue(block.all { it.isFinite()&&abs(it)<.98f })
                    if(i in 10..135)assertTrue("$id silence at ${i/10.0}s",block.any { abs(it)>.0001f })
                    writer.write(block)
                }
            }
        }
    }
}
