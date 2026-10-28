package com.inksheets.core.omr

import com.inksheets.core.WavWriter
import java.io.File
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class InstrumentIdentityTest {
    private val rate=24000
    private fun render(id:String,accent:Boolean=false):FloatArray {
        val patch=Synth.patchFor(id,null)
        val synth=Synth(rate)
        synth.add(listOf(Synth.Tone(60,0,rate.toLong(),.68f,patch,
            accent=if(accent).6f else 0f,art=if(accent)Synth.ART_ACCENT else 0,
            attack=if(accent)Synth.Attack.FIRM else Synth.Attack.NORMAL)))
        return FloatArray(rate).also{synth.fill(it)}
    }
    private fun spectrum(samples:FloatArray):DoubleArray {
        val start=rate/5;val end=rate/2
        val powers=DoubleArray(16){h->
            val w=2*PI*Synth.frequency(60.0)*(h+1)/rate
            var re=0.0;var im=0.0
            for(i in start until end){val window=.5-.5*cos(2*PI*(i-start)/(end-start-1));val x=samples[i]*window;re+=x*cos(w*i);im+=x*sin(w*i)}
            sqrt(re*re+im*im)
        }
        val norm=sqrt(powers.sumOf{it*it})
        return powers.map{it/norm}.toDoubleArray()
    }
    @Test fun `related instruments differ in sustained spectrum even on identical accented pitches`() {
        val pairs=listOf("flute" to "piccolo","oboe" to "english-horn","english-horn" to "bassoon",
            "bassoon" to "contrabassoon","clarinet" to "alto-clarinet","alto-clarinet" to "bass-clarinet",
            "bass-clarinet" to "contra-clarinet","soprano-sax" to "alto-sax","alto-sax" to "tenor-sax",
            "tenor-sax" to "bari-sax","euphonium" to "tuba","euphonium" to "baritone-bc",
            "trombone" to "bass-trombone","horn" to "mellophone","violin" to "viola",
            "viola" to "cello","cello" to "double-bass","guitar" to "piano","steel-pan" to "piano")
        for(accent in listOf(false,true)) for((a,b)in pairs){
            val x=spectrum(render(a,accent));val y=spectrum(render(b,accent))
            val distance=sqrt(x.indices.sumOf{(x[it]-y[it]).pow(2)})
            assertTrue("$a / $b accent=$accent spectral distance=$distance",distance>.035)
        }
    }
    @Test fun `instrument phrase examples have no clipped or nonfinite samples`() {
        val folder=File("build/instrument-examples").apply{mkdirs()}
        val instruments=linkedMapOf("flute" to 72,"piccolo" to 84,"oboe" to 67,"english-horn" to 60,
            "bassoon" to 48,"clarinet" to 64,"bass-clarinet" to 48,"soprano-sax" to 67,
            "alto-sax" to 60,"tenor-sax" to 55,"bari-sax" to 48,"trumpet" to 67,"horn" to 55,
            "trombone" to 53,"euphonium" to 53,"tuba" to 41,"violin" to 72,"viola" to 60,"cello" to 48,
            "guitar" to 55,"steel-pan" to 67)
        for((id,register)in instruments)for((label,root)in listOf("same-pitch" to 60,"register" to register)){
            val patch=Synth.patchFor(id,null);val synth=Synth(rate)
            val pitches=listOf(root,root+2,root+4,root+2,root)
            synth.add(pitches.mapIndexed{i,midi->Synth.Tone(midi,(i*.45*rate).toLong(),(.39*rate).toLong(),.65f,patch,
                accent=if(i==2).5f else 0f,art=if(i==2)Synth.ART_ACCENT else 0,
                attack=if(i==2)Synth.Attack.FIRM else Synth.Attack.NORMAL)})
            val block=FloatArray(240)
            var energy=0.0
            WavWriter(File(folder,"$id-$label.wav"),rate).use{out->repeat(280){
                block.fill(0f);synth.fill(block)
                assertTrue("$id $label",block.all{it.isFinite()&&abs(it)<.98f})
                energy+=block.sumOf{it.toDouble()*it};out.write(block)
            }}
            assertTrue("$id must be audible",energy>1.0)
        }
    }
}
