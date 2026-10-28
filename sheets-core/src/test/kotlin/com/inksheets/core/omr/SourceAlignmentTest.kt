package com.inksheets.core.omr
import org.junit.Assert.*
import org.junit.Test

class SourceAlignmentTest {
    @Test fun `preserved original decoration retains hollow counters`() {
        val direction=Direction("dynamic",10f,text="p",above=true)
        val decoration=SourceDecoration(listOf(
            intArrayOf(10,-40,30,-40,30,-20,10,-20),
            intArrayOf(15,-35,15,-25,25,-25,25,-35)),direction=direction)
        val m=Measure(1,0,0,Box(0,0,120,40),10f,Clef.TREBLE,Key(0),TimeSig(4,4),emptyList(),directions=listOf(direction),sourceDecorations=listOf(decoration))
        val ink=Ink(160,130)
        Engraver.paint(ink,Engraver.aligned(m),10f,20f,60f)
        assertTrue(ink[32,22])
        assertFalse("Original dynamic's hollow counter remains paper",ink[40,30])
    }
    @Test fun `source decorations follow their semantic owner and corrections replace original marks`() {
        val note=Note(listOf(4),listOf(Pitch(0,4)),Duration(4),30f,articulations=listOf("accent"))
        val direction=Direction("cresc",10f,100f)
        val accent=SourceDecoration(listOf(intArrayOf(30,-10,40,-10,40,-8,30,-8)),note.x,note.steps,note.articulations)
        val wedge=SourceDecoration(listOf(intArrayOf(10,60,100,55,100,56,10,60)),direction=direction)
        val m=Measure(1,0,0,Box(0,0,120,40),10f,Clef.TREBLE,Key(0),TimeSig(4,4),listOf(note),directions=listOf(direction),sourceDecorations=listOf(accent,wedge))
        val expected=AlignedGeometry(m).decoration(accent).single() as Engraver.Slab
        assertTrue(Engraver.aligned(m).marks.filterIsInstance<Engraver.Slab>().any{it.points.contentEquals(expected.points)})
        val corrected=m.copy(events=listOf(note.copy(articulations=emptyList())),directions=emptyList())
        assertFalse(Engraver.aligned(corrected).marks.filterIsInstance<Engraver.Slab>().any{it.points.contentEquals(expected.points)})
        assertTrue(Engraver.line(listOf(m)).marks.filterIsInstance<Engraver.Symbol>().any{it.name.startsWith("articAccent")})
    }
    @Test fun `displaced chord heads retain individual observed centers and sizes only in overlay`() {
        val n=Note(listOf(3,4),listOf(Pitch(1,4,0),Pitch(0,4,0)),Duration(4),30f,
            geometry=NoteGeometry(heads=listOf(SourceHead(3,SourcePoint(38f,15f),16f,10f),SourceHead(4,SourcePoint(51f,20f),16f,10f)),duration=Duration(4)))
        val m=Measure(1,0,0,Box(0,0,120,40),10f,Clef.TREBLE,Key(0),TimeSig(4,4),listOf(n))
        val heads=Engraver.aligned(m).marks.filterIsInstance<Engraver.Symbol>().filter{it.name=="noteheadBlack"}
        assertEquals(2,heads.size)
        val centers=heads.map {h->val b=MusicGlyphs[h.name].bounds;(h.x+(b[0]+b[2])*.5f*h.scaleX)*10f}.sorted()
        assertEquals(38f,centers[0],.01f);assertEquals(51f,centers[1],.01f)
        assertTrue(Engraver.line(listOf(m)).marks.filterIsInstance<Engraver.Symbol>().filter{it.name=="noteheadBlack"}.none{it.scaleX!=1f})
        val edited=m.copy(events=listOf(n.copy(steps=listOf(5,6))))
        val moved=Engraver.aligned(edited).marks.filterIsInstance<Engraver.Symbol>().filter{it.name=="noteheadBlack"}
        assertTrue(moved.all{it.y>=2f})
    }
    @Test fun `source glyph bounds survive page scaling`() {
        val b=Printed.Builder(200f,300f)
        b.char("Bravura",0xE0A4,"\uE0A4",20f,40f,12f,40f,listOf(19f,36f,33f,44f))
        repeat(8){i->b.char("Bravura",0xE0A4,"\uE0A4",40f+i*10,40f,12f,40f)}
        b.char("Bravura",0xE4E5,"\uE4E5",140f,40f,12f,40f)
        val p=b.build()!!
        assertEquals(listOf(38f,72f,66f,88f),p.scaled(400).symbols.first().bounds)
    }
}
