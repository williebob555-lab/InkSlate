package com.inksheets.core.sampler

import com.inksheets.core.WavWriter
import com.inksheets.core.omr.*
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional listening artifacts, made from the pinned CC0 source pack. No sound device is used. */
class RecordedPrototypeTest {
    @Test fun `render recorded prototype through score interpretation with accents shorts slurs and ties`() {
        val root = File("../build/recorded-defaults")
        assumeTrue("Run scripts/build_recorded_defaults.py to prepare real recordings", File(root,"manifest.properties").isFile)
        val bank = BundledInstruments({ path -> File(root,path).takeIf { it.isFile }?.readBytes() })
        val out = File("build/recorded-examples").apply { mkdirs() }
        val rate = 24000
        for ((id,base) in listOf("trumpet" to 67,"trombone" to 53,"clarinet" to 67,"tenor-sax" to 60)) {
            val patch = bank.patchFor(id)!!
            assertNotNull(patch.sampled)
            assertTrue(patch.sampled!!.warnings.isEmpty())
            for(styled in listOf(false,true)) {
                fun pitch(midi:Int):Pitch {
                    val steps=intArrayOf(0,0,1,1,2,3,3,4,4,5,5,6)
                    val alters=intArrayOf(0,1,0,1,0,0,1,0,1,0,1,0)
                    return Pitch(steps[midi%12],midi/12-1,alters[midi%12])
                }
                val bars=(0..1).map { b ->
                    val offsets=if(b==0)listOf(0,2,4,2) else listOf(0,0,2,0)
                    val notes=offsets.mapIndexed { i,d ->
                        Note(listOf(0),listOf(pitch(base+d)),Duration(4),20f+i*60,
                            tie=styled&&b==1&&i==0,
                            articulations=if(styled&&b==0)when(i){1->listOf("accent");3->listOf("staccato");else->emptyList()}else emptyList())
                    }
                    Measure(b+1,0,0,Box(0,0,300,40),10f,Clef.TREBLE,Key(0),TimeSig(4,4),notes,
                        directions=if(styled&&b==0)listOf(Direction("slur",140f,200f))else emptyList())
                }
                val plan=Performance.play(bars,100.0,rate,0,patch)
                val synth=Synth(rate).apply{add(plan.tones)}
                val block=FloatArray(240)
                val filename="$id-${if(styled)"styled" else "plain"}.wav"
                var energy=0.0
                WavWriter(File(out,filename),rate).use { writer -> repeat(600) {
                    block.fill(0f);synth.fill(block)
                    assertTrue("$filename must be finite and unclipped",block.all{it.isFinite()&&abs(it)<.98f})
                    energy+=block.sumOf{it.toDouble()*it};writer.write(block)
                } }
                assertTrue("$filename must contain recorded audio",energy>1.0)
                if(id=="trumpet"&&styled) {
                    // Compare recorded timbres at equal overall loudness, rather than
                    // mistaking a louder file for a better open trumpet.
                    for((label,sound,layer) in listOf(
                        Triple("trumpet-open-bright",patch,1f),
                        Triple("trumpet-dark",patch,.45f),
                        Triple("trumpet-straight-mute",bank.patchFor("trumpet-muted")!!,1f))) {
                        val voice=Synth(rate)
                        voice.add(plan.tones.map{t->Synth.Tone(t.midi,t.start,t.length,t.velocity,sound,t.accent,
                            t.endVelocity,t.legato,t.from,t.fromVelocity,t.ramp,t.art,layer,t.vib,t.attack)})
                        val audio=FloatArray(rate*6)
                        val chunk=FloatArray(240)
                        for(start in audio.indices step chunk.size) {
                            chunk.fill(0f);voice.fill(chunk);chunk.copyInto(audio,start)
                        }
                        val rms=kotlin.math.sqrt(audio.sumOf{it.toDouble()*it}/audio.size)
                        val peak=audio.maxOf{abs(it)}
                        val gain=minOf(.08/rms,.9/peak)
                        for(i in audio.indices)audio[i]=(audio[i]*gain).toFloat()
                        assertTrue(audio.all{it.isFinite()&&abs(it)<.98f})
                        WavWriter(File(out,"$label.wav"),rate).use{it.write(audio)}
                    }
                }
            }
        }
    }
}
