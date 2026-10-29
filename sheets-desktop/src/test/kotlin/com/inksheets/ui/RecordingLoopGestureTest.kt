package com.inksheets.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import com.inksheets.core.AudioTrack
import com.inksheets.core.Part
import com.inksheets.core.Song
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalTestApi::class)
class RecordingLoopGestureTest {
    @get:Rule val tmp=TemporaryFolder()
    private class Player:AudioPlayer {
        val loops=ArrayList<LoopRegion?>()
        override fun load(file:File)=true
        override fun play() {playing=true}
        override fun pause() {playing=false}
        override var playing=false
        override fun seek(ms:Long) {positionMs=ms}
        override var positionMs=0L
        override val durationMs=10000L
        override var speed=1.0
        override var pitch=0
        override fun setLoop(startMs:Long?,endMs:Long?) {loops+=if(startMs!=null && endMs!=null)LoopRegion(startMs,endMs)else null}
        override fun release() {}
    }
    private class Platform(override val startFolder:File):SheetsPlatform {
        val prefs=mutableMapOf("sheets_library" to startFolder.absolutePath)
        override val deviceId="loop-gesture"
        override val deviceName="Test stand"
        override val localFolder=File(startFolder,"local")
        override val audioOut:AudioOut?=null
        override val microphone:Microphone?=null
        override fun pref(key:String)=prefs[key]
        override fun setPref(key:String,value:String?) {if(value==null)prefs.remove(key)else prefs[key]=value}
        override fun pageText(file:File,page:Int):String?=null
        override fun openPart(song:Song,part:Part,file:File) {}
        override fun onMain(block:()->Unit)=block()
    }

    @Test fun `a small handle adjustment follows the finger without slop and saves once on release`() {
        val root=tmp.newFolder();File(root,"exercise.wav").writeText("fixture")
        val state=SheetsState(Platform(root))
        val player=Player();val oldPlayer=Recording.player
        val saved=ArrayList<LoopRegion>()
        val marks=ArrayList<Long>()
        val track=AudioTrack("exercise.wav",loopStartMs=2500,loopEndMs=7500)
        Recording.player=player
        try {
            runDesktopComposeUiTest(width=400,height=260) {
                setContent {MaterialTheme {Column {
                    RecordingWaveform(state,track,0,10000,true,expanded=true,
                        onLoopChange={start,end->saved+=LoopRegion(start,end)},onLoopMark={marks+=it})
                }}}
                val waveform=onNodeWithContentDescription("Recording waveform; tap to seek, hold to mark a practice loop")
                waveform.performTouchInput {
                    down(Offset(width*.25f,40f))
                    moveTo(Offset(width*.25f+4f,40f))
                    moveTo(Offset(width*.25f+8f,40f))
                    up()
                }
                runOnIdle {
                    assertEquals("Only release persists the edit",1,saved.size)
                    assertEquals(2700L,saved.single().start)
                    assertEquals(7500L,saved.single().end)
                    assertEquals("The exact final preview reaches playback",saved.single(),player.loops.last())
                    assertEquals("A handle grab must not turn into tap-to-seek",0L,player.positionMs)
                }
                waveform.performTouchInput {
                    down(Offset(width*.5f,40f))
                    moveTo(Offset(width*.5f+40f,40f))
                    up()
                }
                runOnIdle {
                    assertEquals(2,saved.size)
                    assertEquals("Body drag preserves span and includes touch-slop distance",LoopRegion(3700,8500),saved.last())
                }
                waveform.performTouchInput {click(Offset(width*.05f,40f))}
                runOnIdle {assertEquals(500L,player.positionMs);assertEquals(2,saved.size)}
                waveform.performTouchInput {longClick(Offset(width*.1f,40f))}
                runOnIdle {assertEquals(listOf(1000L),marks);assertEquals(2,saved.size)}
            }
        } finally {Recording.player=oldPlayer}
    }
}
