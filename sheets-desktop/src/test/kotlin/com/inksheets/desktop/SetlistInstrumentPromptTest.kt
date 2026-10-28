package com.inksheets.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalTestApi::class)
class SetlistInstrumentPromptTest {
    @get:Rule val tmp=TemporaryFolder()
    private class Platform(override val startFolder:File):SheetsPlatform {
        val prefs=mutableMapOf("sheets_library" to startFolder.absolutePath)
        override val deviceId="set-instrument-test"
        override val deviceName="Test stand"
        override val localFolder=File(startFolder,".local")
        override val audioOut:AudioOut?=null
        override val microphone:Microphone?=null
        override fun pref(key:String)=prefs[key]
        override fun setPref(key:String,value:String?) { if(value==null)prefs.remove(key)else prefs[key]=value }
        override fun onMain(block:()->Unit)=block()
        override fun pageText(file:File,page:Int):String?=null
        override fun openPart(song:Song,part:Part,file:File) {}
    }

    @Test fun `fallback instrument still prompts and accepting selects that instrument`() {
        val state=SheetsState(Platform(tmp.newFolder()))
        state.chooseProfile("trombone") // Bass trombone is a fallback, not the active primary choice.
        state.change {
            val set=addSetlist("Low brass")
            editSetlist(set.id) { defaultInstrument="bass-trombone" }
        }
        state.setlistShown=state.library!!.setlists.single().id
        runDesktopComposeUiTest(width=1000,height=800) {
            setContent { MaterialTheme { SetlistsPane(state) } }
            onNodeWithText("Keep current instrument").assertIsDisplayed()
            onNode(hasText("Switch to",substring=true)).performClick()
            runOnIdle { assertEquals("bass-trombone",state.profile!!.instruments.first()) }
            onNodeWithText("Keep current instrument").assertDoesNotExist()
        }
    }
}
