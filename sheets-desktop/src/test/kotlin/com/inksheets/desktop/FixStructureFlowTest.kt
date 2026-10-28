package com.inksheets.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.core.omr.*
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalTestApi::class)
class FixStructureFlowTest {
    @get:Rule val tmp=TemporaryFolder()
    private class Platform(override val startFolder: File): SheetsPlatform {
        private val prefs=mutableMapOf("sheets_library" to startFolder.absolutePath)
        override val deviceId="structure-test"
        override val deviceName="Structure test"
        override val localFolder=File(startFolder,"local")
        override fun pref(key: String)=prefs[key]
        override fun setPref(key: String,value: String?) { if(value==null) prefs.remove(key) else prefs[key]=value }
        override fun openPart(song: Song,part: Part,file: File) {}
        override fun pageText(file: File,page: Int): String?=null
        override val audioOut: AudioOut?=null
        override val microphone: Microphone?=null
        override fun onMain(block: ()->Unit)=block()
    }
    private fun note(x: Float,pitch: Int=0)=Note(listOf(4),listOf(Pitch(pitch,5)),Duration(2),x)
    private fun state(): SheetsState {
        val root=tmp.newFolder()
        val file=File(root,"music.pdf").apply { writeText("%PDF-1.4\n%%EOF") }
        return SheetsState(Platform(root)).apply { currentPath=file.absolutePath;pageShown=0 to 1;ScoreTools.install(this) }
    }
    private fun bar()=Measure(1,0,0,Box(100,40,500,80),10f,Clef.TREBLE,Key(0),TimeSig(4,4),
        listOf(note(130f),note(230f),note(330f),note(430f)),doubts=listOf("8 beats found, 4 expected"))

    @Test fun notOneBarPreviewsThenPersistsRealSplitAndPreservesLaterManualFix() {
        val state=state();val path=state.currentPath!!
        val original=Score(listOf(bar(),bar().copy(number=2,box=Box(520,40,800,80),events=listOf(note(560f)))),1,listOf(1000),listOf(0))
        ScoreTools.scoreSource={original}
        try {
            val manual=listOf(note(560f,4))
            ScoreTools.fix(path,2,manual)
            assertTrue(ScoreTools.startCheck(state))
            runDesktopComposeUiTest(width=1200,height=1000) {
                setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { BarCheck(state,900.dp) } } }
                onNodeWithText("Not one bar").performClick()
                onNodeWithText("Preview: 2 bars").assertIsDisplayed()
                runOnIdle { assertEquals(2,ScoreTools.scoreHere(state)!!.measures.size) }
                onNodeWithText("Use 2 bars").performClick()
                runOnIdle {
                    val corrected=ScoreTools.scoreHere(state)!!
                    assertEquals(listOf(1,2,3),corrected.measures.map { it.number })
                    assertEquals(manual,corrected.measures.last().events)
                    assertEquals(listOf(2,2),corrected.measures.take(2).map { it.events.size })
                }
            }
            val saved=Transcriber.keptEdit(state,File(path),"sheets_structure")
            assertEquals(1,MeasureStructure.decode(saved).size)
            ScoreTools.endCheck()
            ScoreTools.scoreSource={Scores.decode(Scores.encode(original))}
            assertEquals(3,ScoreTools.scoreHere(state)!!.measures.size)
            assertEquals(manual,ScoreTools.scoreHere(state)!!.measures.last().events)
        } finally { ScoreTools.endCheck();ScoreTools.scoreSource=null;ScoreTools.close(state) }
    }

    @Test fun wholeBarRestCanCorrectAMultipleRestBackToOneAndRenumberFollowingBar() {
        val state=state();val path=state.currentPath!!
        val rest=bar().copy(events=listOf(Rest(Duration(1),250f)),bars=8,doubts=listOf("rest of how many"))
        ScoreTools.scoreSource={Score(listOf(rest,bar().copy(number=9,box=Box(520,40,800,80))),1,listOf(1000))}
        try {
            val manual=listOf(note(560f,4))
            ScoreTools.fix(path,9,manual)
            assertTrue(ScoreTools.startCheck(state))
            ScoreTools.setRestCount(state,1,1)
            val corrected=ScoreTools.scoreHere(state)!!
            assertEquals(listOf(1,2),corrected.measures.map { it.number })
            assertEquals(1,corrected.measures.first().bars)
            assertEquals(manual,corrected.measures.last().events)
            assertTrue(Transcriber.keptEdit(state,File(path),"sheets_rests")!!.contains("1=1"))
        } finally { ScoreTools.endCheck();ScoreTools.scoreSource=null;ScoreTools.close(state) }
    }
}
