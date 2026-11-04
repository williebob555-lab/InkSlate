package com.inksheets.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import com.inkslate.core.Perform
import com.inksheets.core.AudioTrack
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.core.WavWriter
import com.inksheets.core.omr.*
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin

/** Viewer integration: an expanded category must replace its dock, never open across the score. */
@OptIn(ExperimentalTestApi::class)
class MusicDockInPlaceTest {
    @get:Rule val tmp=TemporaryFolder()
    private class Player:AudioPlayer {
        var plays=0
        val seeks=ArrayList<Long>()
        override fun load(file:File)=file.isFile
        override fun play() {plays++;playing=true}
        override fun pause() {playing=false}
        override var playing=false
        override fun seek(ms:Long) {positionMs=ms;seeks+=ms}
        override var positionMs=0L
        override val durationMs=2000L
        override var speed=1.0
        override var pitch=0
        override fun setLoop(startMs:Long?,endMs:Long?) {}
        override fun release() {}
    }
    private class Platform(override val startFolder:File):SheetsPlatform {
        val prefs=mutableMapOf("sheets_library" to startFolder.absolutePath,"sheets_strip_collapsed" to "false",
            "sheets_companion_transport" to "BLUETOOTH")
        var bottom=0f
        var rail=0f
        var right=false
        val player=Player()
        val recorded=FloatArray(48000) { i ->
            val envelope=.15+.65*(.5+.5*sin(2*PI*i/11000))
            (sin(2*PI*220*i/24000)*envelope).toFloat()
        }
        @Volatile var decodes=0
        override val deviceId="dock-in-place"
        override val deviceName="Test stand"
        override val localFolder=File(startFolder,".local")
        override val microphone:Microphone?=null
        override val audioOut:AudioOut?=null
        override val inForeground=false
        override fun audioPlayer():AudioPlayer=player
        override fun decodeAudio(file:File,onChunk:(FloatArray,Int)->Unit):Boolean {
            if(file.name!="rehearsal.wav")return false
            onChunk(recorded,24000);decodes++
            return true
        }
        override fun pref(key:String)=prefs[key]
        override fun setPref(key:String,value:String?) {if(value==null)prefs.remove(key)else prefs[key]=value}
        override fun openPart(song:Song,part:Part,file:File) {}
        override fun pageText(file:File,page:Int):String?=null
        override fun onMain(block:()->Unit)=block()
        override fun setMusicShelfHeight(dp:Float) {bottom=dp}
        override fun setStudioRailWidth(dp:Float) {rail=dp}
        override fun setStudioRailSide(onRight:Boolean) {right=onRight}
    }
    private data class Case(val name:String,val width:Int,val height:Int,val view:MusicDockView,val side:StudioRailSide)
    private fun setup(c:Case):Pair<SheetsState,Platform> {
        Perform.isOn=null
        ScoreTools.endCheck()
        SharedMetronome.engine=null;SharedMetronome.running=false
        val p=Platform(tmp.newFolder(c.name))
        val file=File(p.startFolder,"Nocturne.pdf").apply{writeText("fixture")}
        val state=SheetsState(p)
        state.change{addSong("Nocturne",listOf(Part(file=file.name,instrument="euphonium")))}
        state.current=state.library!!.songs.first{it.title=="Nocturne"}
        state.currentPath=file.absolutePath;state.pageShown=0 to 1
        state.homeInFront=false;state.stripCollapsed=false;state.readMusic=true
        state.shelf.chooseView(c.view);state.shelf.chooseRailSide(c.side)
        val score=Score(listOf(Measure(1,0,0,com.inksheets.core.omr.Box(0,0,400,40),10f,Clef.TREBLE,Key(0),TimeSig(4,4),
            listOf(Note(listOf(4),listOf(Pitch(0,4)),Duration(4),20f)))),1,listOf(800))
        ScoreTools.install(state);ScoreTools.scoreSource={score}
        ScoreTools.stop(state);Recording.end(state);Listener.active=false;Listener.follow=null
        return state to p
    }
    private fun ComposeUiTest.shot(name:String) {
        val dir=File("build/dock-in-place").apply{mkdirs()}
        ImageIO.write(onRoot().captureToImage().toAwtImage(),"png",File(dir,"$name.png"))
    }
    private fun pairRecording(state:SheetsState,p:Platform):AudioTrack {
        WavWriter(File(p.startFolder,"rehearsal.wav"),24000).use{it.write(p.recorded)}
        val track=AudioTrack("rehearsal.wav","Rehearsal take",loopStartMs=300,loopEndMs=1400)
        val songId=state.current!!.id
        state.change{editSong(songId){audio=listOf(track)}}
        state.current=state.library!!.song(songId)
        Recording.player=p.player;Recording.loadedFile=null
        assertTrue(Recording.load(state,track,songId))
        Click.setCountInBars(state,0)
        return track
    }
    private fun exercise(c:Case) {
        val (state,p)=setup(c)
        try {
            runDesktopComposeUiTest(width=c.width,height=c.height) {
                setContent{MaterialTheme{Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)){ActionStrip(state)}}}
                val rail=c.view==MusicDockView.STUDIO
                val dockTag=if(rail)"music-dock-rail"else"music-dock-shelf"
                val compact=onNodeWithTag(dockTag).fetchSemanticsNode().boundsInRoot
                val compactBottom=p.bottom
                val compactRail=p.rail
                shot("${c.name}-compact")
                onNodeWithContentDescription("Timing settings").performClick()
                for(category in listOf(ShelfWidget.TIMING,ShelfWidget.RECORDING,ShelfWidget.SCORE,ShelfWidget.DEVICES)) {
                    onNodeWithTag("music-category-${category.name}").performScrollTo().assertIsDisplayed().performClick()
                    waitForIdle()
                    val actual=onNodeWithTag(dockTag).fetchSemanticsNode().boundsInRoot
                    val body=onNodeWithTag("music-category-content").fetchSemanticsNode().boundsInRoot
                    runOnIdle {
                        assertEquals("${c.name}: category remains in the chosen dock",category,state.expandedMusicCategory)
                        assertEquals("${c.name}: rail reservation must not disappear",compactRail,p.rail,.01f)
                        assertEquals(c.side==StudioRailSide.RIGHT,p.right)
                        assertFalse("${c.name}: no second reader drawer",ReaderPanels.forState(state).open)
                        assertTrue("${c.name}: a usable content area, not a sliver: $body",body.height>=80f)
                        assertTrue("${c.name}: the score keeps usable height",c.height-p.bottom>=120f)
                        if(rail) {
                            assertEquals(compact.left,actual.left,.01f)
                            assertEquals(compact.right,actual.right,.01f)
                            assertEquals(compactBottom,p.bottom,.01f)
                        } else {
                            assertEquals(0f,p.rail,.01f)
                            val allowed=if(category==ShelfWidget.SCORE)40f else .01f
                            assertEquals("${c.name}: replace the shelf footprint",compactBottom,p.bottom,allowed)
                        }
                    }
                    if(category==ShelfWidget.SCORE) {
                        onNodeWithText("Playback sound").performScrollTo().assertIsDisplayed().performClick()
                        onNodeWithContentDescription("Back to category").assertIsDisplayed()
                        runOnIdle {assertEquals(ShelfWidget.SCORE,state.expandedMusicCategory);assertEquals(compactRail,p.rail,.01f)}
                        onNodeWithContentDescription("Back to category").performClick()
                        onNodeWithTag("score-tools-body").assertExists()
                        onNodeWithText("Read part again").performScrollTo().assertIsDisplayed()
                    }
                    shot("${c.name}-${category.name.lowercase()}")
                }
                onNodeWithContentDescription("Compact categories").performClick()
                waitForIdle()
                runOnIdle {assertEquals(compactRail,p.rail,.01f);assertEquals(compactBottom,p.bottom,.01f)}
            }
        } finally {
            ScoreTools.stop(state);ScoreTools.scoreSource=null
            Click.stop(state);SharedMetronome.engine=null
            state.companion.stopFollowing();state.companion.stopLeading()
            Perform.isOn=null
        }
    }

    @Test fun `rail categories stay on the left or right on landscape phones and both tablet orientations`() {
        for((name,width,height) in listOf(Triple("phone-landscape",640,360),Triple("tablet-portrait",800,1280),Triple("tablet-landscape",1280,800))) {
            for(side in StudioRailSide.entries) exercise(Case("$name-${side.name.lowercase()}",width,height,MusicDockView.STUDIO,side))
        }
    }
    @Test fun `phone shelf categories replace the compact footprint in both orientations`() {
        exercise(Case("phone-portrait-shelf",360,640,MusicDockView.SHELF,StudioRailSide.LEFT))
        exercise(Case("phone-landscape-shelf",640,360,MusicDockView.SHELF,StudioRailSide.LEFT))
    }

    @Test fun `selected recording exposes playback waveform loop handles and inline pairing on shelf and rail`() {
        val cases=listOf(
            Case("selected-phone-portrait",360,640,MusicDockView.SHELF,StudioRailSide.LEFT),
            Case("selected-phone-landscape",640,360,MusicDockView.SHELF,StudioRailSide.LEFT),
            Case("selected-phone-right-rail",640,360,MusicDockView.STUDIO,StudioRailSide.RIGHT),
            Case("selected-tablet-left",1280,800,MusicDockView.STUDIO,StudioRailSide.LEFT),
            Case("selected-tablet-right",800,1280,MusicDockView.STUDIO,StudioRailSide.RIGHT)
        )
        for(c in cases) {
            val (state,p)=setup(c)
            val previousPlayer=Recording.player
            pairRecording(state,p)
            try {
                runDesktopComposeUiTest(width=c.width,height=c.height) {
                    setContent{MaterialTheme{Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)){ActionStrip(state)}}}
                    onNodeWithContentDescription("Timing settings").performClick()
                    onNodeWithTag("music-category-RECORDING").performScrollTo().performClick()
                    waitUntil(timeoutMillis=3000){p.decodes>0}
                    val waveform=onNodeWithContentDescription("Recording waveform; tap to seek, hold to mark a practice loop")
                    // No scroll before either action: the selected take, not setup forms, is the landing.
                    onNodeWithContentDescription("Play or pause recording").assertIsDisplayed().performClick()
                    waitUntil(timeoutMillis=3000){p.player.plays>0}
                    waveform.assertIsDisplayed()
                    val body=onNodeWithTag("music-category-content").fetchSemanticsNode().boundsInRoot
                    val wave=waveform.getUnclippedBoundsInRoot()
                    assertTrue("${c.name}: waveform begins in the category without scrolling",wave.top.value>=body.top && wave.top.value<body.bottom)
                    assertTrue("${c.name}: the whole waveform fits the dock body",wave.bottom.value<=body.bottom+.1f)
                    assertTrue("${c.name}: waveform has enough room for touch handles",(wave.bottom-wave.top).value>=48f)
                    shot("${c.name}-playing-waveform")
                    waveform.performTouchInput {
                        down(Offset(width*.15f,8f))
                        moveTo(Offset(width*.20f,8f))
                        up()
                    }
                    runOnIdle {
                        val changed=state.library!!.song(state.current!!.id)!!.audio.single()
                        assertTrue("${c.name}: loop handle updates the saved track",changed.loopStartMs?.let{it in 380L..420L}==true)
                        assertEquals(1400L,changed.loopEndMs)
                    }
                    waveform.performTouchInput {click(Offset(width*.95f,8f))}
                    runOnIdle {assertTrue("${c.name}: tapping the waveform seeks",p.player.seeks.last()>1800)}
                    shot("${c.name}-edited-loop")
                    onNodeWithContentDescription("Recording settings and library").performClick()
                    onNodeWithText("Whole piece").performScrollTo().assertIsDisplayed().performClick()
                    shot("${c.name}-pairing-options")
                    onNodeWithText("Page range\u2026").performScrollTo().performClick()
                    onNodeWithText("First page",useUnmergedTree=true).performScrollTo().assertIsDisplayed()
                    shot("${c.name}-pair-pages")
                    onNodeWithContentDescription("Back to category").performClick()
                    onNodeWithContentDescription("Back to category").performClick()
                    onNodeWithContentDescription("Recording settings and library").assertIsDisplayed()
                    runOnIdle {
                        assertEquals(ShelfWidget.RECORDING,state.expandedMusicCategory)
                        assertFalse(ReaderPanels.forState(state).open)
                        assertEquals(if(c.view==MusicDockView.STUDIO)236f else 0f,p.rail,.01f)
                    }
                }
            } finally {
                Recording.end(state);Recording.player=previousPlayer;Recording.loadedFile=null
                ScoreTools.stop(state);ScoreTools.scoreSource=null
                Click.stop(state);SharedMetronome.engine=null;Perform.isOn=null
            }
        }
    }
}
