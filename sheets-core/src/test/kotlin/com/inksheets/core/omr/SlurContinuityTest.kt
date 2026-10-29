package com.inksheets.core.omr

import com.inksheets.core.WavWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/** Audio is rendered offline; no output device or subjective flag-only checks. */
class SlurContinuityTest {
    private val rate=24000
    private fun score(changing:Boolean=false,slur:Boolean=true,staccato:Boolean=false):Measure {
        val notes=(0..3).map { i -> Note(listOf(4),listOf(Pitch(if(changing && i%2==1) 2 else 0,4)),Duration(4),20f+i*70,
            articulations=if(staccato)listOf("staccato")else emptyList()) }
        return Measure(1,0,0,Box(0,0,300,40),10f,Clef.TREBLE,Key(0),TimeSig(4,4),notes,
            directions=if(slur)listOf(Direction("slur",10f,290f))else emptyList())
    }
    private fun play(m:Measure,patch:Synth.Patch=Synth.LOW_BRASS)=Performance.play(listOf(m),100.0,rate,0,patch)
    private fun render(p:Performance.Played,name:String):FloatArray {
        val out=FloatArray(p.length.toInt()+rate/4)
        val synth=Synth(rate);synth.add(p.tones)
        val block=FloatArray(240)
        var at=0
        while(at<out.size) {
            block.fill(0f);synth.fill(block)
            block.copyInto(out,at,0,minOf(block.size,out.size-at));at+=block.size
        }
        assertTrue(out.all { it.isFinite() })
        val dir=File("build/slur-continuity").apply { mkdirs() }
        WavWriter(File(dir,"$name.wav"),rate).use { it.write(out) }
        return out
    }
    private fun rms(x:FloatArray,center:Int,ms:Int=20):Double {
        val half=rate*ms/2000
        return sqrt((center-half until center+half).sumOf { x[it].toDouble()*x[it] }/(2*half))
    }
    private fun weakestJoin(x:FloatArray,at:Int):Double {
        val reference=minOf(rms(x,at-rate/10),rms(x,at+rate/10)).coerceAtLeast(1e-8)
        return (-40..40 step 5).minOf { ms -> rms(x,at+rate*ms/1000)/reference }
    }
    @Test fun `repeated pitches under a slur have one uninterrupted sounding voice`() {
        val m=score()
        val p=play(m)
        assertEquals("All four written notes still exist",4,m.events.size)
        assertTrue(m.events.filterIsInstance<Note>().none { it.tie })
        assertEquals("No rearticulation of repeated slurred pitches",1,p.tones.size)
        val audio=render(p,"slurred-repeated")
        for(beat in 1..3) assertTrue("No audible envelope notch at repeated beat $beat",
            weakestJoin(audio,rate*3*beat/5)>.75)
        val ordinary=play(score(slur=false))
        val detached=play(score(slur=false,staccato=true))
        assertEquals(4,ordinary.tones.size);assertEquals(4,detached.tones.size)
        val ordinaryAudio=render(ordinary,"ordinary-repeated")
        val detachedAudio=render(detached,"staccato-repeated")
        assertTrue("Ordinary unslurred notes still articulate",weakestJoin(ordinaryAudio,rate*3/5)<.7)
        val first=detached.tones[0];val next=detached.tones[1]
        assertTrue("Staccato note is shorter than its written interval",first.length < (next.start-first.start)*.7)
        val gap=(first.start+first.length+next.start)/2
        assertTrue("Staccato retains a real separation",rms(detachedAudio,gap.toInt()) < rms(detachedAudio,(first.start+first.length/2).toInt())*.3)
    }
    @Test fun `changing pitches under a slur crossfade without a gated gap or fresh attack`() {
        for((name,patch) in listOf("brass" to Synth.LOW_BRASS,"clarinet" to Synth.CLARINET)) {
            val p=play(score(changing=true),patch)
            assertEquals(4,p.tones.size)
            assertTrue(p.tones.drop(1).all { it.legato })
            for(i in 0..2) assertEquals("Hold reaches the next pitch",p.tones[i+1].start,p.tones[i].start+p.tones[i].length)
            val audio=render(p,"slurred-changing-$name")
            for(t in p.tones.drop(1)) assertTrue("$name continuous at ${t.start}",weakestJoin(audio,t.start.toInt())>.6)
        }
    }
    @Test fun `tenuto and marcato keep slurred sampled lines connected without fresh attacks`() {
        val patches=listOf("clarinet","tenor-sax","trumpet").map { id ->
            id to requireNotNull(com.inksheets.core.sampler.BundledInstruments.defaults.patchFor(id)) {
                "The production recorded default must be present: $id"
            }
        }
        for(mark in listOf("tenuto","marcato")) for((name,patch) in patches) {
            assertNotNull("Exercise recorded audio rather than fallback synthesis",patch.sampled)
            val m=score(changing=true).let { measure -> measure.copy(events=measure.events.map {
                (it as Note).copy(articulations=listOf(mark))
            }) }
            val p=play(m,patch)
            assertEquals(4,p.tones.size)
            for(i in 1 until p.tones.size) {
                val previous=p.tones[i-1];val current=p.tones[i]
                assertTrue("$name $mark must skip a new attack inside the slur",current.legato)
                assertEquals("$name $mark must hold to the following pitch",current.start,previous.start+previous.length)
            }
            val audio=render(p,"slurred-$mark-$name")
            for(t in p.tones.drop(1)) assertTrue("$name $mark must retain audio through the join at ${t.start}",
                weakestJoin(audio,t.start.toInt())>.25)
        }
    }
    @Test fun `inferred phrase boundaries cannot detach a long written slur`() {
        val bars=(1..12).map { n -> score(changing=true).copy(number=n) }
        val p=Performance.play(bars,100.0,rate,0,Synth.LOW_BRASS)
        assertEquals(48,p.tones.size)
        assertTrue("A long slur remains connected through inferred breathing points",p.tones.drop(1).all { it.legato })
        for(i in 1 until p.tones.size) assertEquals(p.tones[i].start,p.tones[i-1].start+p.tones[i-1].length)
    }

}
