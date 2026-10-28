package com.inksheets.core.omr

import org.junit.Assert.*
import org.junit.Test

class MuteInstructionTest {
    @Test fun `split PDF words retain a single cancelling instruction`() {
        fun word(text:String,x:Float,y:Float=10f)=Printed.Symbol(Printed.Kind.WORD,x,y,20f,12f,name=text)
        val result=musicTextDirections(listOf(word("without",10f),word("mute",35f),word("dolce",100f),word("con",150f),word("sord.",175f)),10f,40f)
        assertEquals(listOf("without mute","dolce","con sord."),result.map{it.text})
        assertEquals(listOf(false,null,true),result.map{Interpretation.muteInstruction(it.text)})
    }
    @Test fun `mute instructions select recorded variant carry across rests then return to open`() {
        val muted=Synth.Patch(floatArrayOf(1f),.01,.1,1f,.1)
        val open=Synth.Patch(floatArrayOf(1f,.5f),.01,.1,1f,.1,mutedVariant=muted)
        fun bar(n:Int, directions:List<Direction> = emptyList(),rest:Boolean=false)=Measure(n,0,0,Box(0,0,300,40),10f,Clef.TREBLE,Key(0),TimeSig(4,4),
            if(rest)listOf(Rest(Duration(1),20f))else(0..3).map{i->Note(listOf(0),listOf(Pitch(0,4)),Duration(4),20f+i*60)},directions=directions)
        val bars=listOf(bar(1,listOf(Direction("text",80f,text="con sord."))),
            bar(2,rest=true),bar(3,listOf(Direction("text",140f,text="senza sordino"))))
        val tones=Performance.play(bars,100.0,24000,0,open).tones
        assertEquals(8,tones.size)
        assertSame(open,tones[0].patch)
        assertTrue(tones.subList(1,6).all{it.patch===muted})
        assertTrue(tones.drop(6).all{it.patch===open})
        assertEquals(false,Interpretation.muteInstruction("without mute"))
        assertEquals(true,Interpretation.muteInstruction("straight mute"))
        assertNull(Interpretation.muteInstruction("dolce"))
    }
}
