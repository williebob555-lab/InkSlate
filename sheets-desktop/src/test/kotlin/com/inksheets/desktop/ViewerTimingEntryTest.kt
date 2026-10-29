package com.inksheets.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.inkslate.core.Perform
import com.inksheets.core.ClickSound
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Enters through the actual viewer dock; never mounts the timing dialogs in isolation. */
@OptIn(ExperimentalTestApi::class)
class ViewerTimingEntryTest {
    @get:Rule val tmp=TemporaryFolder()

    private class Platform(override val startFolder:File):SheetsPlatform {
        val prefs=mutableMapOf("sheets_library" to startFolder.absolutePath,"sheets_strip_collapsed" to "false")
        var audioStarts=0
        override val deviceId="viewer-timing"
        override val deviceName="Test stand"
        override val localFolder=File(startFolder,".local")
        override val microphone:Microphone?=null
        override val audioOut:AudioOut=object:AudioOut {
            override val sampleRate=48000
            override fun start(fill:(FloatArray)->Unit) {audioStarts++}
            override fun stop() {}
        }
        override fun pref(key:String)=prefs[key]
        override fun setPref(key:String,value:String?) {if(value==null)prefs.remove(key)else prefs[key]=value}
        override fun onMain(block:()->Unit)=block()
        override fun pageText(file:File,page:Int):String?=null
        override fun openPart(song:Song,part:Part,file:File) {}
    }

    private fun setup(name:String):Pair<SheetsState,Platform> {
        Perform.isOn=null // This fixture has no annotation editor or its fullscreen callback.
        ScoreTools.endCheck()
        SharedMetronome.running=false
        SharedMetronome.engine=null
        val root=tmp.newFolder(name)
        val file=File(root,"Nocturne.pdf").apply{writeText("fixture")}
        val platform=Platform(root)
        val state=SheetsState(platform)
        state.change{addSong("Nocturne",listOf(Part(file=file.name,instrument="euphonium")))}
        state.current=state.library!!.songs.first{it.title=="Nocturne"}
        state.currentPath=file.absolutePath
        state.pageShown=0 to 3
        state.homeInFront=false
        state.stripCollapsed=false
        state.readMusic=false // No real PDF/recognition worker in an entry-point regression.
        state.shelf.chooseView(MusicDockView.SHELF)
        return state to platform
    }

    private fun cleanup(state:SheetsState) {
        Click.stop(state)
        Ears.stop("tuner")
        SharedMetronome.engine=null
        SharedMetronome.running=false
        Perform.isOn=null
    }

    @Test fun `labelled footer Tuner opens the tuner from the viewer in both phone orientations`() {
        for((name,width,height) in listOf(Triple("portrait",360,640),Triple("landscape",640,360))) {
            val (state,_)=setup("tuner-$name")
            try {
                runDesktopComposeUiTest(width=width,height=height) {
                    setContent{MaterialTheme{Box(Modifier.fillMaxSize()){ActionStrip(state)}}}
                    // The Timing widget also has a tuner icon. Require the labelled footer control.
                    onNode(hasContentDescription("Tuner") and hasText("Tuner"))
                        .assertIsDisplayed().performClick()
                    runOnIdle {
                        assertEquals(ShelfWidget.TIMING,state.expandedMusicCategory)
                        assertEquals("tuner",state.musicCategoryPage)
                        assertFalse("The command is consumed by the viewer category",state.tunerOpen)
                    }
                    // Noninteractive text merges into the scroll container. Use its leaf node
                    // so performScrollTo targets the actual scrollable ancestor, not the container itself.
                    onNodeWithText("Play a note",useUnmergedTree=true).performScrollTo().assertIsDisplayed()
                    onNodeWithText("A = 440 Hz",useUnmergedTree=true).performScrollTo().assertIsDisplayed()
                    onNodeWithContentDescription("Back to category").performClick()
                    runOnIdle {assertNull(state.musicCategoryPage)}
                    onNodeWithContentDescription("Compact categories").performClick()
                    runOnIdle {assertNull(state.expandedMusicCategory)}
                }
            } finally {cleanup(state)}
        }
    }

    @Test fun `Timing sound choices can be reached selected and reopened through the phone dock`() {
        for((name,width,height) in listOf(Triple("portrait",360,640),Triple("landscape",640,360))) {
            val (state,platform)=setup("sounds-$name")
            try {
                runDesktopComposeUiTest(width=width,height=height) {
                    setContent{MaterialTheme{Box(Modifier.fillMaxSize()){ActionStrip(state)}}}
                    onNodeWithContentDescription("Timing settings").assertIsDisplayed().performClick()
                    runOnIdle {assertEquals(ShelfWidget.TIMING,state.expandedMusicCategory)}
                    for(sound in listOf(ClickSound.TONE,ClickSound.WOODBLOCK,ClickSound.RIM,ClickSound.SOFT)) {
                        onNodeWithText(sound.label).performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
                        runOnIdle {
                            assertEquals(sound.name,platform.pref("sheets_click_sound"))
                            assertEquals(sound,SharedMetronome.engine!!.settings.sound)
                        }
                    }
                    onNodeWithContentDescription("Preview metronome sound").performScrollTo().assertIsDisplayed().assertIsEnabled()
                    onNodeWithText("Metronome during score playback").performScrollTo().assertIsDisplayed()
                    onNodeWithContentDescription("Compact categories").performClick()
                    onNodeWithContentDescription("Timing settings").assertIsDisplayed().performClick()
                    onNodeWithText(ClickSound.SOFT.label).performScrollTo().assertIsDisplayed().assertIsSelected()
                    runOnIdle {
                        assertEquals(0,platform.audioStarts) // Selecting/opening must never audition automatically.
                        assertEquals(ClickSound.SOFT,Click.sound(state))
                    }
                    onNodeWithContentDescription("Compact categories").performClick()
                }
            } finally {cleanup(state)}
        }
    }
}
