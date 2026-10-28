package com.inksheets.core.omr

/** Instrument identities for the portable fallback synth. These are voiced approximations,
 * not recorded instruments. Keep identity separate from MIDI's coarser export programs. */
internal object BuiltInVoices {
    private fun reed(formant:Double,slope:Double,attack:Double,breath:Float,vibrato:Double=5.0,odd:Double=0.0,gain:Float=.21f)=
        Synth.Patch(FloatArray(1),attack,.09,.90f,.09, spectral=true,
            slopeSoft=slope+.45,slopeLoud=slope-.35,formantHz=formant,formantGain=2.0,
            oddBelow=odd,breath=breath,chiff=breath*.8f,vibratoHz=vibrato,
            vibratoCents=if(vibrato==0.0)0.0 else 5.0,gain=gain,
            slur=if(odd>0)Synth.Slur.CLARINET else Synth.Slur.OBOE)
    private fun brass(formant:Double,soft:Double,loud:Double,attack:Double,cutoff:Double,gain:Float=.23f)=
        Synth.Patch(FloatArray(1),attack,.12,.9f,.13,spectral=true,brass=true,
            slopeSoft=soft,slopeLoud=loud,formantHz=formant,formantGain=2.0,
            cutoffSoft=cutoff*.35,cutoffLoud=cutoff,bloom=.45f,gain=gain,
            breath=.004f,scoop=7.0,vibratoHz=4.5,vibratoCents=3.0,vibDelay=.45)
    private fun bowed(slope:Double,body:Double,attack:Double,release:Double)=
        Synth.Patch(FloatArray(20){h->
            val frequency=(h+1).toDouble()
            (1.0/Math.pow(frequency,slope)*(1.0+body*kotlin.math.exp(-Math.pow((frequency-3.0)/1.8,2.0)))).toFloat()
        },attack,.12,.88f,release,vibratoHz=5.2,vibratoCents=9.0,slur=Synth.Slur.BOWED)

    val sopranoSax=reed(2250.0,1.25,.022,.014f,5.6)
    val altoSax=Synth.SAX
    val tenorSax=reed(1050.0,1.05,.041,.025f,4.9)
    val bariSax=reed(650.0,.90,.052,.030f,4.5)
    val oboe=Synth.DOUBLE_REED
    val englishHorn=reed(800.0,1.55,.043,.012f,5.0)
    val bassoon=reed(460.0,1.15,.035,.009f,4.6)
    val contrabassoon=reed(280.0,1.05,.060,.013f,3.9)
    val altoClarinet=reed(1100.0,1.8,.038,.014f,0.0,1400.0)
    val bassClarinet=reed(700.0,1.65,.045,.018f,0.0,1100.0)
    val contraClarinet=reed(400.0,1.55,.060,.020f,0.0,850.0)
    val piccolo=Synth.Patch(FloatArray(1),.025,.06,.94f,.065,spectral=true,
        slopeSoft=2.9,slopeLoud=1.9,formantHz=2400.0,formantGain=1.5,
        breath=.018f,chiff=.025f,gain=.20f,vibratoHz=5.8,vibratoCents=5.0,slur=Synth.Slur.FLUTE)
    val tuba=brass(240.0,.85,.25,.075,1900.0,.26f)
    val baritone=brass(600.0,.60,.16,.045,3500.0)
    val bassTrombone=brass(350.0,.48,.10,.050,3900.0,.25f)
    val mellophone=brass(650.0,.65,.18,.040,3300.0)
    val violin=bowed(.85,.15,.045,.095)
    val viola=bowed(1.05,.65,.065,.12)
    val cello=bowed(1.30,1.15,.080,.16)
    val doubleBass=bowed(1.65,1.65,.105,.19)
    val guitar=Synth.Patch(floatArrayOf(1f,.48f,.22f,.12f,.05f,.07f,.025f),.006,.18,.14f,.09,
        fade=.48,gain=.25f)
    val steelPan=Synth.Patch(floatArrayOf(1f,.05f,.60f,.02f,.22f,.01f,.08f),.003,.18,.05f,.22,
        fade=.60,gain=.21f)

    fun forId(id:String?):Synth.Patch?=when(id?.substringBefore('|')?.trim()?.lowercase()){
        "soprano-sax"->sopranoSax;"alto-sax"->altoSax;"tenor-sax"->tenorSax;"bari-sax"->bariSax
        "oboe"->oboe;"english-horn"->englishHorn;"bassoon"->bassoon;"contrabassoon"->contrabassoon
        "alto-clarinet"->altoClarinet;"bass-clarinet"->bassClarinet;"contra-clarinet"->contraClarinet
        "piccolo"->piccolo;"tuba","sousaphone"->tuba
        "baritone","baritone-tc","baritone-bc"->baritone;"bass-trombone"->bassTrombone;"mellophone"->mellophone
        "violin"->violin;"viola"->viola;"cello"->cello;"double-bass","string-bass"->doubleBass
        "guitar"->guitar;"steel-pan"->steelPan;"vocals"->Synth.VOICE
        else->null
    }
}
