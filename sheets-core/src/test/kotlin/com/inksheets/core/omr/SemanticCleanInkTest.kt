package com.inksheets.core.omr

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

/** Exercises retained-print extraction, not only the new engraving's list of glyphs. */
class SemanticCleanInkTest {
    @Test fun `recognized native slur ink is removed while nearby cue text survives`() {
        val ink=Ink(260,200)
        val arc=Printed.Arc(40f,65f,180f,65f,-8f)
        for (x in 40..180) {
            val u=(x-40)/140f
            val y=(65-32*u*(1-u)).roundToInt()
            for (dy in -1..1) ink.bits[(y+dy)*ink.width+x]=true
        }
        // A rehearsal/cue character near the curve, deliberately not a recognized direction.
        for (x in 90..102) for (y in 37..48)
            if (x in listOf(90,91,101,102) || y in listOf(37,38,47,48)) ink.bits[y*ink.width+x]=true
        val staff=Recognizer.Staff(0,259,Array(5){l->FloatArray(260){80f+l*10}},10f)
        val m=Measure(1,0,0,Box(20,80,220,120),10f,Clef.TREBLE,Key(0),TimeSig(4,4),emptyList(),
            directions=listOf(Direction("slur",40f,180f,above=true,step=-3,step2=-3)))
        val reader=Recognizer()
        Recognizer::class.java.getDeclaredField("printed").apply { isAccessible=true }
            .set(reader,Printed(260f,200f,emptyList(),emptyList(),emptyList(),arcs=listOf(arc)))
        val method=Recognizer::class.java.getDeclaredMethod("keptIn",Ink::class.java,Recognizer.Staff::class.java,
            Measure::class.java,List::class.java,List::class.java).apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST")
        val kept=(method.invoke(reader,ink,staff,m,listOf(m),arrayListOf<SourceDecoration>()) as Pair<List<IntArray>,List<Int>>).first
        assertTrue("Rehearsal/cue contours remain",kept.isNotEmpty())
        assertTrue("Only the cue is retained; the original curve cannot duplicate the new slur",
            kept.all { contour -> (contour.indices step 2).all { contour[it] in 89..104 && contour[it+1] in 36..50 } })
        assertEquals(1,Engraver.aligned(m).marks.filterIsInstance<Engraver.Slab>().size)
    }
}
