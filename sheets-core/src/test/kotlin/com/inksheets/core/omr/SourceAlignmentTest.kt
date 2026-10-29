package com.inksheets.core.omr
import org.junit.Assert.*
import org.junit.Test

class SourceAlignmentTest {
    @Test fun `clean overlays engrave semantic dynamics instead of copying traced source ink`() {
        val direction=Direction("dynamic",10f,text="p",above=true,seen=true)
        val decoration=SourceDecoration(listOf(intArrayOf(10,-40,30,-40,30,-20,10,-20)),direction=direction)
        val m=Measure(1,0,0,Box(0,0,120,40),10f,Clef.TREBLE,Key(0),TimeSig(4,4),emptyList(),directions=listOf(direction),sourceDecorations=listOf(decoration))
        val drawing=Engraver.aligned(m)
        assertTrue(drawing.marks.filterIsInstance<Engraver.Symbol>().any { it.name=="dynamicPiano" })
        assertFalse(drawing.marks.any { it is Engraver.Slab })
        val corrected=Engraver.aligned(m.copy(directions=listOf(direction.copy(text="f"))))
        assertTrue(corrected.marks.filterIsInstance<Engraver.Symbol>().any { it.name=="dynamicForte" })
        assertFalse(corrected.marks.filterIsInstance<Engraver.Symbol>().any { it.name=="dynamicPiano" })
    }
    @Test fun `recognized scanned articulations and curves redraw and corrections remove them`() {
        val note=Note(listOf(4),listOf(Pitch(0,4)),Duration(4),30f,articulations=listOf("accent"),marksSeen=true,tie=true)
        val next=note.copy(x=80f,tie=false,articulations=emptyList())
        val direction=Direction("cresc",10f,100f,seen=true)
        val slur=Direction("slur",20f,105f,above=true,step=4,step2=4,seen=true)
        val accent=SourceDecoration(listOf(intArrayOf(30,-10,40,-10,40,-8,30,-8)),note.x,note.steps,note.articulations)
        val m=Measure(1,0,0,Box(0,0,120,40),10f,Clef.TREBLE,Key(0),TimeSig(4,4),listOf(note,next),directions=listOf(direction,slur),sourceDecorations=listOf(accent))
        val drawing=Engraver.aligned(m)
        assertTrue(drawing.marks.filterIsInstance<Engraver.Symbol>().any{it.name.startsWith("articAccent")})
        assertEquals("One newly engraved tie and one slur",2,drawing.marks.filterIsInstance<Engraver.Slab>().size)
        val noWedge=Engraver.aligned(m.copy(directions=listOf(slur)))
        assertEquals("Hairpin has two engraved strokes",2,drawing.marks.filterIsInstance<Engraver.Stroke>().size-noWedge.marks.filterIsInstance<Engraver.Stroke>().size)
        val corrected=m.copy(events=listOf(note.copy(articulations=emptyList(),tie=false),next),directions=emptyList())
        assertFalse(Engraver.aligned(corrected).marks.filterIsInstance<Engraver.Symbol>().any{it.name.startsWith("articAccent")})
        assertTrue(Engraver.aligned(corrected).marks.filterIsInstance<Engraver.Slab>().isEmpty())
    }
    @Test fun `partial chord ties redraw only the recognized tied pitch`() {
        val n=Note(listOf(2,4),listOf(Pitch(2,4),Pitch(0,4)),Duration(4),20f,tie=true,tiedPitches=listOf(Pitch(0,4).midi))
        val m=Measure(1,0,0,Box(0,0,120,40),10f,Clef.TREBLE,Key(0),TimeSig(4,4),listOf(n,n.copy(x=80f,tie=false)))
        assertEquals(1,Engraver.aligned(m).marks.filterIsInstance<Engraver.Slab>().size)
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
