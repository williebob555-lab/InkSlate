package com.inksheets.desktop

import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inksheets.core.AudioTrack
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingExcerptPlaybackTest {
    @get:Rule val tmp=TemporaryFolder()
    private class Player: AudioPlayer {
        override fun load(file: File)=true
        override fun play() { playing=true }
        override fun pause() { playing=false }
        override var playing=false
        override fun seek(ms: Long) { positionMs=ms }
        override var positionMs=0L
        override val durationMs=30000L
        override var speed=1.0
        override var pitch=0
        override fun setLoop(startMs: Long?,endMs: Long?) {}
        override fun release() {}
    }
    private class Platform(override val startFolder: File): SheetsPlatform {
        private val prefs=mutableMapOf("sheets_library" to startFolder.absolutePath,"sheets_count_in_bars" to "0")
        override val deviceId="excerpt-playback"
        override val deviceName="Silent excerpt playback test"
        override val localFolder=File(startFolder,"local")
        override val audioOut: AudioOut?=null
        override val microphone: Microphone?=null
        override fun pref(key: String)=prefs[key]
        override fun setPref(key: String,value: String?) { if(value==null) prefs.remove(key) else prefs[key]=value }
        override fun openPart(song: Song,part: Part,file: File) {}
        override fun pageText(file: File,page: Int): String?=null
        override fun onMain(block: ()->Unit)=block()
    }
    private fun setup(otherPart: Boolean=false): Triple<SheetsState,AudioTrack,Player> {
        val root=tmp.newFolder()
        File(root,"book.pdf").writeText("fixture");File(root,"other.pdf").writeText("fixture")
        val state=SheetsState(Platform(root))
        val shown=Part(file="book.pdf",instrument="flute")
        val other=Part(file="other.pdf",instrument="clarinet")
        val track=AudioTrack("excerpt.wav",excerptPart=if(otherPart) other.id else shown.id,excerptStartPage=5,excerptEndPage=7,excerptTitle="Study 12",
            partTurns=mapOf(shown.id to listOf(-1L,-1,-1,-1,1000,2000)))
        val song=state.library!!.addSong("Methodbook",listOf(shown,other)) { audio=listOf(track) }
        state.current=song;state.currentPath=File(root,"book.pdf").absolutePath;state.pageShown=0 to 20
        val player=Player();Recording.player=player;Recording.loadedFile=track.file
        PlayAlong.load(state)
        return Triple(state,track,player)
    }
    @Test fun excerptStartsOnItsPageAndLearnedTurnsNeverLeaveItsRange() {
        val (state,track,player)=setup()
        val jump=Perform.jumpTo;val document=Perform.document
        var jumps=0;var turns=0
        try {
            state.pageShown=3 to 20
            PlayAlong.pageChanged(state,state.currentPath!!,3)
            Perform.jumpTo={ path,page -> jumps++;state.pageShown=page to 20;PlayAlong.pageChanged(state,path,page) }
            Perform.document={ action ->
                val step=when(action) { PerformAction.NEXT_PAGE -> 1;PerformAction.PREVIOUS_PAGE -> -1;else -> 0 }
                if(step!=0) { turns++;state.pageShown=(state.pageShown.first+step) to 20;PlayAlong.pageChanged(state,state.currentPath!!,state.pageShown.first) }
                true
            }
            Recording.play(state,track,state.current)
            assertEquals(4,state.pageShown.first)
            assertEquals(1,jumps)
            assertEquals(track.partTurns,state.library!!.song(state.current!!.id)!!.audio.single().partTurns)
            PlayAlong.tick(state)
            assertTrue("Only excerpt turns need to be learned",PlayAlong.learned)
            player.positionMs=10000;PlayAlong.tick(state)
            assertEquals(6,state.pageShown.first)
            assertEquals(2,turns)
            player.positionMs=20000;PlayAlong.tick(state)
            assertEquals(6,state.pageShown.first)
            assertEquals(2,turns)
            Recording.pause(state);Recording.play(state,track,state.current)
            assertEquals("Resume must retain the page already reached",6,state.pageShown.first)
            assertEquals(1,jumps)
        } finally { Recording.end(state);Recording.player=null;Recording.loadedFile=null;Perform.jumpTo=jump;Perform.document=document }
    }
    @Test fun excerptForAnotherInstrumentNeverJumpsOrAutomaticallyTurnsTheViewedPart() {
        val (state,track,player)=setup(otherPart=true)
        val jump=Perform.jumpTo;val document=Perform.document
        var moved=0
        try {
            Perform.jumpTo={ _,_ -> moved++ };Perform.document={ moved++;true }
            Recording.play(state,track,state.current)
            PlayAlong.tick(state);player.positionMs=12000;PlayAlong.tick(state)
            assertEquals(0,moved)
            assertEquals(0,state.pageShown.first)
            assertFalse(PlayAlong.learned)
            assertEquals("Recording belongs to another part",PlayAlong.hint)
        } finally { Recording.end(state);Recording.player=null;Recording.loadedFile=null;Perform.jumpTo=jump;Perform.document=document }
    }
    @Test fun microphoneFollowingDoesNotGuessExcerptTimingAcrossTheWholeBook() {
        val (state,_,_)=setup()
        try {
            Listener.start(state)
            assertFalse(Listener.active)
            assertEquals("Excerpt recording: use Play Along for its page turns",Listener.status)
        } finally { Listener.stop(state);Recording.end(state);Recording.player=null;Recording.loadedFile=null }
    }
    @Test fun wholePieceTimingAndInvalidExcerptBoundsRemainSafe() {
        val whole=AudioTrack("whole.wav",turnsMs=listOf(1000L,2000L))
        assertEquals(0..2,recordingPages(whole,null,3))
        assertEquals(listOf(1000L,2000L),recordingTurns(whole,null,3,0..2))
        assertNull(recordingPages(whole.copy(excerptStartPage=30),null,20))
        assertNull(recordingPages(whole.copy(excerptStartPage=8,excerptEndPage=5),null,20))
        assertEquals(17..19,recordingPages(whole.copy(excerptStartPage=18,excerptEndPage=99),null,20))
        val excerpt=whole.copy(excerptStartPage=5,excerptEndPage=7,turnsMs=listOf(-1,-1,-1,-1,2000,1000))
        assertNull("Reversed learned cues must not trigger wrong seeks",recordingTurns(excerpt,null,20,4..6))
        assertNull(recordingTurns(excerpt.copy(turnsMs=listOf(-1,-1,-1,-1,1000,1000)),null,20,4..6))
        assertEquals(listOf(1000L,2000L),recordingTurns(excerpt.copy(turnsMs=listOf(-1,-1,-1,-1,1000,2000)),null,20,4..6))
    }
}
