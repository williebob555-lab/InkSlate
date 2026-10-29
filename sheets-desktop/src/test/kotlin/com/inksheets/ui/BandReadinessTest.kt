package com.inksheets.ui

import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.core.omr.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BandReadinessTest {
    @get:Rule val tmp=TemporaryFolder()
    private class Platform(override val startFolder:File):SheetsPlatform {
        val prefs=mutableMapOf("sheets_library" to startFolder.absolutePath)
        override val deviceId="band-readiness"
        override val deviceName="Test stand"
        override val localFolder=File(startFolder,"local")
        override val audioOut:AudioOut?=null
        override val microphone:Microphone?=null
        override fun pref(key:String)=prefs[key]
        override fun setPref(key:String,value:String?) { if(value==null)prefs.remove(key)else prefs[key]=value }
        override fun openPart(song:Song,part:Part,file:File) {}
        override fun pageText(file:File,page:Int):String?=null
        override fun onMain(block:()->Unit)=block()
    }
    private fun measure(page:Int)=Measure(page+1,page,0,Box(0,0,300,40),10f,Clef.TREBLE,Key(0),TimeSig(4,4),
        listOf(Note(listOf(4),listOf(Pitch(0,4)),Duration(1),30f)))

    @Test fun `cached own page is insufficient for another part of the same PDF`() {
        val own=Part(file="pack.pdf",instrument="clarinet",firstPage=1,lastPage=1)
        val other=Part(file="pack.pdf",instrument="trumpet",firstPage=2,lastPage=3)
        val partial=Score(listOf(measure(0)),3,listOf(800,800,800),listOf(0))
        assertTrue(ScoreTools.bandPartReady(partial,own))
        assertFalse(ScoreTools.bandPartReady(partial,other))
        assertFalse(ScoreTools.bandPartReady(partial,Part(file="pack.pdf")))
        val oneOtherPage=partial.copy(measures=partial.measures+measure(1),readPages=listOf(0,1))
        assertFalse("Both pages assigned to the other part must be read",ScoreTools.bandPartReady(oneOtherPage,other))
        assertTrue(ScoreTools.bandPartReady(oneOtherPage.copy(readPages=null),other))
    }

    private fun cachedBand(name:String,pages:List<Int>,empty:Boolean=false):List<com.inksheets.core.omr.BandAudio.Voice> {
        val root=tmp.newFolder(name);val file=File(root,"pack.pdf").apply {writeText("fixture-$name")}
        val state=SheetsState(Platform(root))
        val own=Part(file="pack.pdf",instrument="clarinet",firstPage=1,lastPage=1)
        val other=Part(file="pack.pdf",instrument="trumpet",firstPage=2,lastPage=3)
        state.change {addSong("Band pack",listOf(own,other))}
        state.current=state.library!!.songs.first {it.title=="Band pack"}
        state.currentPath=file.absolutePath;state.pageShown=0 to 3
        val method=Transcriber::class.java.getDeclaredMethod("folder",SheetsState::class.java,File::class.java).apply {isAccessible=true}
        val dir=(method.invoke(Transcriber,state,file) as File).apply {mkdirs()}
        for(page in pages) File(dir,"p${page+1}.json").writeText(Scores.encode(
            Score(if(empty)emptyList()else listOf(measure(page)),3,listOf(800,800,800),listOf(page))))
        assertNotNull("Exercise a non-null real partial page cache",Transcriber.cached(state,file))
        return ScoreTools.bandVoices(state)
    }

    @Test fun `band excludes incomplete shared-file caches instead of counting silent parts`() {
        assertTrue(cachedBand("only-own",listOf(0)).isEmpty())
        assertTrue(cachedBand("other-incomplete",listOf(0,1)).isEmpty())
        val complete=cachedBand("all-pages",listOf(0,1,2))
        assertEquals(1,complete.size)
        assertEquals(listOf(1,2),complete.single().score.measures.map {it.number})
        assertEquals(listOf(1,2),complete.single().score.measures.map {it.page})
        assertTrue("A fully read but empty part is not a playable band voice",cachedBand("empty-pages",listOf(0,1,2),true).isEmpty())
    }
}
