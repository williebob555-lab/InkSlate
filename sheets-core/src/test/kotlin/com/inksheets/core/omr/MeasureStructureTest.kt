package com.inksheets.core.omr

import org.junit.Assert.*
import org.junit.Test

class MeasureStructureTest {
    private fun note(x: Float,tie: Boolean=false)=Note(listOf(4),listOf(Pitch(0,5)),Duration(2),x,tie=tie,
        geometry=NoteGeometry(heads=listOf(SourceHead(4,SourcePoint(x,30f),8f,6f))),tiedPitches=if(tie) listOf(72) else emptyList())
    private fun bar()=Measure(7,1,2,Box(100,20,500,60),10f,Clef.TREBLE,Key(-1),TimeSig(4,4),
        listOf(note(130f),note(230f,true),note(330f),Rest(Duration(2),430f)),showsClef=true,repeatStart=true,repeatEnd=true,
        lines=List(10) { if(it<5) 20f+it*10 else 22f+(it-5)*10 },
        graces=listOf(note(320f)),maybe=listOf(note(420f)),directions=listOf(Direction("cresc",150f,450f)),
        kept=listOf(intArrayOf(145,10,150,10,150,15)),keptShade=listOf(30))

    @Test fun splitPreservesEventsTiesAnchorsAndStaffSlope() {
        val source=bar();val parts=MeasureStructure.split(source,listOf(300f))
        assertEquals(listOf(7,8),parts.map { it.number })
        assertEquals(source.events,parts.flatMap { it.events })
        assertSame(source.events[1],parts[0].events[1])
        assertEquals(listOf(72),(parts[0].events[1] as Note).tiedPitches)
        assertEquals(source.graces,parts[1].graces)
        assertEquals(source.maybe,parts[1].maybe)
        assertEquals(21f,parts[0].lines[5],0f)
        assertEquals(21f,parts[1].lines[0],0f)
        assertTrue(parts[0].repeatStart);assertFalse(parts[0].repeatEnd)
        assertFalse(parts[1].showsClef);assertTrue(parts[1].repeatEnd)
        assertEquals(300f,parts[0].directions.single().x2,0f)
        assertEquals(300f,parts[1].directions.single().x,0f)
        assertEquals(1,parts.sumOf { it.kept.size })
    }
    @Test fun persistenceRereadAndAlreadySplitReaderPreserveCoverage() {
        val original=Score(listOf(bar(),bar().copy(number=8,box=Box(520,20,700,60))),3,listOf(1000,1000,1000),listOf(1))
        val edit=MeasureStructure.correction(bar(),1000,listOf(300f))
        val saved=MeasureStructure.decode(MeasureStructure.encode(listOf(edit)))
        val reopened=MeasureStructure.apply(Scores.decode(Scores.encode(original))!!,saved)
        assertEquals(listOf(7,8,9),reopened.measures.map { it.number })
        assertEquals(original.readPages,reopened.readPages)
        assertEquals(original.pageWidths,reopened.pageWidths)
        // A newer reader already sees this barline: no duplicate measures or renumbering.
        val correctReader=original.copy(measures=MeasureStructure.split(bar(),listOf(300f))+original.measures[1].copy(number=9))
        assertEquals(correctReader,MeasureStructure.apply(correctReader,saved))
    }
    @Test fun correctionScalesWithRereadResolution() {
        val edit=MeasureStructure.correction(bar(),1000,listOf(300f))
        val large=bar().copy(box=Box(200,40,1000,120),events=listOf(note(260f),note(460f),note(660f),note(860f)))
        val result=MeasureStructure.apply(Score(listOf(large),2,listOf(2000,2000)),listOf(edit))
        assertEquals(600,result.measures[0].box.right)
        assertEquals(listOf(2,2),result.measures.map { it.events.size })
    }
    @Test fun invalidBoundariesDoNotDeleteEventsAndNewBarsRemainInDoubt() {
        val parts=MeasureStructure.split(bar(),listOf(-1f,300f,300.1f,Float.NaN,999f,400f))
        assertEquals(3,parts.size)
        assertEquals(bar().events,parts.flatMap { it.events })
        assertTrue(parts.all { it.box.width>0 })
        assertTrue(parts[1].doubts.any { it.contains("beats found") })
    }
}
