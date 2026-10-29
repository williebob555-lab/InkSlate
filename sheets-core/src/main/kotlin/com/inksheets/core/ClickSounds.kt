package com.inksheets.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class ClickSound(val label: String) { TONE("Tone"),WOODBLOCK("Woodblock"),RIM("Rim click"),SOFT("Soft tick") }

/** Short deterministic built-in voices shared by the metronome and score click. Cached arrays are read-only. */
object ClickSounds {
    private data class Key(val sound: ClickSound,val accent: Metronome.Accent,val rate: Int)
    private val cache=object: LinkedHashMap<Key,FloatArray>(16,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key,FloatArray>?)=size>48
    }
    fun samples(sound: ClickSound,accent: Metronome.Accent,sampleRate: Int): FloatArray {
        if(accent==Metronome.Accent.SILENT) return FloatArray(0)
        val rate=sampleRate.coerceIn(1,384000)
        val key=Key(sound,accent,rate)
        return synchronized(cache) { cache.getOrPut(key) { make(sound,accent,rate) } }
    }
    private fun make(sound: ClickSound,accent: Metronome.Accent,rate: Int): FloatArray {
        val strong=accent==Metronome.Accent.STRONG
        val sub=accent==Metronome.Accent.SUB
        val seconds=when(sound) {
            ClickSound.TONE -> if(strong) .035 else if(sub) .02 else .03
            ClickSound.WOODBLOCK -> .045
            ClickSound.RIM -> .024
            ClickSound.SOFT -> .03
        }
        val gain=if(sound==ClickSound.TONE) { if(sub) .45 else 1.0 } else if(strong) .9 else if(sub) .3 else .65
        var random=12345L+accent.ordinal*997L
        var last=0.0;var low=0.0
        val count=(rate*seconds).toInt().coerceAtLeast(1)
        return FloatArray(count) { i ->
            val t=i.toDouble()/rate
            random=random*6364136223846793005L+1442695040888963407L
            val noise=(random ushr 33).toDouble()/(1L shl 31)-1.0
            val high=noise-last;last=noise;low+=(noise-low)*.16
            val attack=1-exp(-t*9000)
            val tail=((count-1-i)/(rate*.003)).coerceIn(0.0,1.0)
            val value=when(sound) {
                // Keep the original pitched metronome voice as the default.
                ClickSound.TONE -> sin(2*PI*(if(strong) 1760.0 else if(sub) 990.0 else 1320.0)*t)*exp(-t*90)
                ClickSound.WOODBLOCK -> {
                    val hz=if(strong) 1550.0 else if(sub) 900.0 else 1150.0
                    (sin(2*PI*hz*t)*.7+sin(2*PI*hz*2.73*t)*.3)*exp(-t*105)*attack*tail
                }
                ClickSound.RIM -> (high*.28+sin(2*PI*(if(strong) 3300.0 else 2700.0)*t)*.5)*exp(-t*230)*attack*tail
                ClickSound.SOFT -> (low*.65+sin(2*PI*(if(strong) 780.0 else 570.0)*t)*.35)*exp(-t*170)*attack*tail
            }
            (value*gain).toFloat().coerceIn(-1f,1f)
        }
    }
}
