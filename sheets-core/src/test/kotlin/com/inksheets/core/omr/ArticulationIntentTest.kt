package com.inksheets.core.omr

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import com.inksheets.core.WavWriter

class ArticulationIntentTest {
    private fun note(x:Float,tie:Boolean=false,arts:List<String> = emptyList())=Note(listOf(4),listOf(Pitch(0,3,0)),Duration(4),x,tie=tie,articulations=arts)
    private fun tones(slur:Boolean=false,tie:Boolean=false,arts:List<String> = emptyList(),patch:Synth.Patch=Synth.LOW_BRASS):List<Synth.Tone> {
        val notes=listOf(note(20f,tie,arts),note(90f,arts=arts),note(160f,arts=arts),note(230f,arts=arts))
        val bar=Measure(1,0,0,Box(0,0,300,40),10f,Clef.BASS,Key(0),TimeSig(4,4),notes,
            directions=if(slur)listOf(Direction("slur",20f,260f,step=4,step2=4))else emptyList())
        return Performance.play(listOf(bar),100.0,48000,0,patch).tones
    }
    @Test fun `repeated slurred notes sustain continuously while detached articulations remain distinct`() {
        val connected=tones(slur=true)
        assertEquals(1,connected.size)
        assertTrue(connected.single().length>48000*2)
        assertEquals(3,tones(tie=true).size)
        assertTrue(tones(arts=listOf("accent")).all{it.attack==Synth.Attack.FIRM})
        assertTrue(tones(slur=true,arts=listOf("staccato")).drop(1).none{it.attack==Synth.Attack.SOFT || it.legato})
        assertTrue(tones(arts=listOf("staccatissimo")).all{it.art and Synth.ART_STACCATO!=0})
    }
    @Test fun `string slurs retain their existing connection policy`() {
        val strings=Synth.Patch(floatArrayOf(1f),.02,.05,1f,.1,tonguedSlurredRepeats=false)
        assertEquals(1,tones(slur=true,patch=strings).size)
    }
    @Test fun `a tie on one chord pitch does not swallow the other pitches next attack`() {
        val first=Note(listOf(0,2),listOf(Pitch(0,4),Pitch(2,4)),Duration(4),20f,tie=true,tiedPitches=listOf(60))
        val second=first.copy(x=100f,tie=false,tiedPitches=emptyList())
        val bar=Measure(1,0,0,Box(0,0,300,40),10f,Clef.TREBLE,Key(0),TimeSig(2,4),listOf(first,second))
        val tones=Performance.play(listOf(bar),100.0,48000,0,Synth.LOW_BRASS).tones
        assertEquals(1,tones.count{it.midi==60})
        assertEquals(2,tones.count{it.midi==64})
        assertTrue(tones.single{it.midi==60}.length>tones.first{it.midi==64}.length)
    }
    @Test fun `offline articulation examples render finite audio without opening a sound device`() {
        val folder=File("build/articulation-examples").apply{mkdirs()}
        for((name,notes)in listOf("rounded-da" to tones(),"slurred-soft-la" to tones(slur=true),"firm-accent-ta" to tones(arts=listOf("accent")),"true-tie" to tones(tie=true))) {
            val synth=Synth(48000);synth.add(notes)
            val block=FloatArray(480)
            // Synth adds into the destination so it can mix with other audio. Each new
            // time block must start empty, rather than accumulating the preceding block.
            WavWriter(File(folder,"$name-clean.wav"),48000).use {writer->repeat(300){
                block.fill(0f)
                synth.fill(block)
                assertTrue(block.all{it.isFinite() && kotlin.math.abs(it)<0.98f})
                writer.write(block)
            }}
        }
    }
}
