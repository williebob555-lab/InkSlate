package com.inksheets.core.omr

/**
 * Perceived section balance before the ensemble's fixed headroom budget.
 *
 * Four-partial fallback tones have similar electrical RMS, but they are not similarly audible:
 * a piccolo sits in the ear's most sensitive range while fundamentals from euphonium and tuba are
 * easily masked by a full upper band. These weights correct that psychoacoustic difference; the
 * ensemble normalises their combined energy afterwards, so this changes balance without clipping.
 */
object EnsembleBalance {
    fun default(instrument: String?): Float = when (instrument?.substringBefore('|')?.lowercase()) {
        "piccolo" -> .32f
        "flute" -> .62f
        "oboe", "english-horn" -> .72f
        "clarinet", "alto-clarinet" -> .76f
        "soprano-sax", "alto-sax" -> .78f
        "trumpet", "cornet" -> .72f
        "horn", "mellophone", "tenor-sax" -> 1.05f
        "bassoon", "bass-clarinet", "bari-sax" -> 1.55f
        "contrabassoon", "contra-clarinet", "string-bass", "double-bass", "bass-guitar" -> 2.0f
        "trombone" -> 2.2f
        "bass-trombone" -> 2.55f
        "baritone", "baritone-tc", "baritone-bc", "euphonium-tc", "euphonium", "euphonium-bc" -> 2.55f
        "tuba", "sousaphone" -> 2.8f
        "drums", "drumline", "percussion" -> .55f
        else -> 1f
    }
}
