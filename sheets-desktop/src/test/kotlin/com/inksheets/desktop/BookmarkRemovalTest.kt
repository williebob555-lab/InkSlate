package com.inksheets.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inksheets.core.Bookmark
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.AudioOut
import com.inksheets.ui.ActionStrip
import com.inksheets.ui.BookmarksPane
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalTestApi::class)
class BookmarkRemovalTest {
    @get:Rule val tmp=TemporaryFolder()

    private class Platform(override val startFolder:File):SheetsPlatform {
        private val prefs=mutableMapOf("sheets_library" to startFolder.absolutePath)
        override val deviceId="bookmark-removal"
        override val deviceName="Bookmark removal test"
        override val localFolder=File(startFolder,"local")
        override fun pref(key:String)=prefs[key]
        override fun setPref(key:String,value:String?) {if(value==null)prefs.remove(key)else prefs[key]=value}
        override fun openPart(song:Song,part:Part,file:File) {}
        override fun pageText(file:File,page:Int):String?=null
        override val audioOut:AudioOut?=null
        override val microphone:Microphone?=null
        override fun onMain(block:()->Unit)=block()
    }

    @Test fun `reader footer bookmarks the page directly without opening an editor`() {
        val root=tmp.newFolder("reader")
        val file=File(root,"nocturne.pdf").apply {writeText("fixture")}
        val state=SheetsState(Platform(root))
        lateinit var song:Song
        state.change {song=addSong("Nocturne",listOf(Part(id="part",file=file.name)))}
        state.current=song;state.currentPath=file.absolutePath;state.pageShown=1 to 4
        state.homeInFront=false;state.stripCollapsed=false
        runDesktopComposeUiTest(width=900,height=700) {
            setContent {MaterialTheme {Box(Modifier.fillMaxSize()) {ActionStrip(state)}}}
            onNodeWithContentDescription("Bookmark this page").performClick()
            runOnIdle {assertTrue(state.library!!.song(song.id)!!.bookmarks.single().page==2)}
            onNodeWithContentDescription("Remove bookmark from this page").performClick()
            runOnIdle {assertTrue(state.library!!.song(song.id)!!.bookmarks.isEmpty())}
        }
    }

    @Test fun `leaving bookmarks commits a removal without waiting for its fade`() {
        val state=SheetsState(Platform(tmp.newFolder("library")))
        lateinit var song:Song
        state.change {
            song=addSong("Nocturne",listOf(Part(id="part",file="nocturne.pdf")))
            editSong(song.id) {bookmarks=listOf(Bookmark("Page 2","part",2))}
        }
        val mounted=mutableStateOf(true)
        runDesktopComposeUiTest(width=900,height=700) {
            mainClock.autoAdvance=false
            setContent {MaterialTheme {if(mounted.value)BookmarksPane(state,state.library!!.songs)}}
            onNodeWithContentDescription("Take the bookmark off").performClick()
            runOnIdle {assertTrue("The bookmark remains during its undo fade",state.library!!.song(song.id)!!.bookmarks.isNotEmpty())}
            runOnIdle {mounted.value=false}
            mainClock.advanceTimeByFrame()
            runOnIdle {assertTrue("Disposing the tab commits the pending removal",state.library!!.song(song.id)!!.bookmarks.isEmpty())}
        }
    }
}
