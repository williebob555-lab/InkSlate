package com.inksheets.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.inkslate.core.PageMark
import com.inkslate.core.Perform
import com.inkslate.core.ToolConfig
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.core.omr.*
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Uses the production dock buttons and gesture bridge, not direct Clean/Mask toggle calls. */
@OptIn(ExperimentalTestApi::class)
class ViewerCleanEntryTest {
    @get:Rule val tmp=TemporaryFolder()
    private class Platform(override val startFolder:File):SheetsPlatform {
        val prefs=mutableMapOf("sheets_library" to startFolder.absolutePath,"sheets_strip_collapsed" to "false")
        override val deviceId="viewer-clean"
        override val deviceName="Test stand"
        override val localFolder=File(startFolder,".local")
        override val audioOut:AudioOut?=null
        override val microphone:Microphone?=null
        override fun pref(key:String)=prefs[key]
        override fun setPref(key:String,value:String?) {if(value==null)prefs.remove(key)else prefs[key]=value}
        override fun onMain(block:()->Unit)=block()
        override fun pageText(file:File,page:Int):String?=null
        override fun openPart(song:Song,part:Part,file:File) {}
    }
    @Test fun `viewer Clean off then Mask paints only the brush and preserves print elsewhere`() {
        for((name,width,height) in listOf(Triple("portrait",360,640),Triple("landscape",640,360))) {
            Perform.isOn=null
            ScoreTools.endCheck()
            val root=tmp.newFolder(name)
            val file=File(root,"score.pdf").apply {writeText("%PDF-1.4\nfixture\n%%EOF")}
            val state=SheetsState(Platform(root))
            state.change {addSong("Score",listOf(Part(file=file.name,instrument="flute")))}
            state.current=state.library!!.songs.first {it.title=="Score"}
            state.currentPath=file.absolutePath;state.pageShown=0 to 1
            state.homeInFront=false;state.readMusic=true;state.stripCollapsed=false
            state.shelf.chooseView(MusicDockView.SHELF)
            val score=Score(listOf(Measure(1,0,0,com.inksheets.core.omr.Box(20,40,240,80),10f,
                Clef.TREBLE,Key(0),TimeSig(4,4),emptyList())),1,listOf(800))
            ScoreTools.install(state);ScoreTools.scoreSource={if(it==file.absolutePath)score else null}
            try {
                runDesktopComposeUiTest(width=width,height=height) {
                    setContent {MaterialTheme {Box(Modifier.fillMaxSize()){ActionStrip(state)}}}
                    onNodeWithText("Score",substring=false).performClick()
                    onNodeWithContentDescription("Clean view").assertIsNotSelected().performClick().assertIsSelected()
                    runOnIdle {assertEquals(Color.White,raster(ScoreTools.marks(file.absolutePath,0,800f,800f).orEmpty())[150,55])}
                    onNodeWithContentDescription("Clean view").performClick().assertIsNotSelected()
                    onNodeWithContentDescription("Clean mask brush").performClick().assertIsSelected()
                    onNodeWithContentDescription("Clean view").assertIsNotSelected()
                    // Selecting an empty brush must not strand the explicit Clean switch.
                    onNodeWithContentDescription("Clean view").performClick().assertIsSelected()
                    onNodeWithContentDescription("Clean mask brush").assertIsNotSelected()
                    runOnIdle {assertEquals(Color.White,raster(ScoreTools.marks(file.absolutePath,0,800f,800f).orEmpty())[150,55])}
                    onNodeWithContentDescription("Clean view").performClick().assertIsNotSelected()
                    onNodeWithContentDescription("Clean mask brush").performClick().assertIsSelected()
                    runOnIdle {
                        assertTrue("Brush selection must arm the production canvas bridge",Perform.musicTool)
                        assertEquals(PRINT,raster(ScoreTools.marks(file.absolutePath,0,800f,800f).orEmpty())[150,55])
                        Perform.musicBrush=ToolConfig(strokeWidth=20f)
                        Perform.musicGesture!!.invoke(file.absolutePath,0,floatArrayOf(50f,55f,120f,55f),true)
                        val marks=ScoreTools.marks(file.absolutePath,0,800f,800f).orEmpty()
                        val pixels=raster(marks)
                        assertEquals(Color.White,pixels[70,55])
                        assertEquals("Old whole-clean bars must remain outside the brush",PRINT,pixels[150,55])
                        assertTrue(marks.filter {it.mask!=null}.all {!it.mask!!.baseFull})
                    }
                    onNodeWithContentDescription("Clean view").assertIsSelected()
                    onNodeWithContentDescription("Clean mask brush").performClick().assertIsNotSelected()
                    runOnIdle {assertEquals(PRINT,raster(ScoreTools.marks(file.absolutePath,0,800f,800f).orEmpty())[150,55])}
                }
            } finally {ScoreTools.close(state);ScoreTools.scoreSource=null;Perform.musicBrush=ToolConfig();Perform.isOn=null}
        }
    }
    @Test fun `profile dense page preview through gesture marks and native mask rendering`() {
        val root=tmp.newFolder("dense-page")
        val file=File(root,"dense.pdf").apply {writeText("%PDF-1.4\nfixture\n%%EOF")}
        val state=SheetsState(Platform(root))
        state.currentPath=file.absolutePath;state.pageShown=0 to 1
        val measures=(0 until 40).map { i ->
            val left=20+(i%4)*190;val top=40+(i/4)*70
            val contours=(0 until 60).map { c ->
                val x=left+4+(c%20)*8;val y=top-12+(c/20)*5
                intArrayOf(x,y,x+3,y,x+3,y+3,x,y+3)
            }
            Measure(i+1,0,i/4,com.inksheets.core.omr.Box(left,top,left+180,top+40),10f,
                Clef.TREBLE,Key(0),TimeSig(4,4),
                listOf(Note(listOf(0),listOf(Pitch(0,5)),Duration(1),left+40f)),kept=contours)
        }
        val score=Score(measures,1,listOf(800))
        ScoreTools.install(state);ScoreTools.scoreSource={if(it==file.absolutePath)score else null}
        try {
            ScoreTools.marks(file.absolutePath,0,800f,800f)
            ScoreTools.toggleMaskBrush(state);Perform.musicBrush=ToolConfig(strokeWidth=12f)
            repeat(120) { i ->
                val x=25f+(i%12)*60;val y=50f+(i/12)*65
                Perform.musicGesture!!.invoke(file.absolutePath,0,floatArrayOf(x,y,x+30,y+5),true)
            }
            val gestureTimes=ArrayList<Double>();val marksTimes=ArrayList<Double>();val nativeTimes=ArrayList<Double>()
            // Completion bypasses preview throttling; the next event begins a new moving stroke.
            // Force a fresh retained-shape pass as each accepted live stroke would, without sleeps.
            repeat(25) { i ->
                CleanMasks.cancel(state,file.absolutePath)
                val a=System.nanoTime()
                Perform.musicGesture!!.invoke(file.absolutePath,0,floatArrayOf(100f,80f,130f+i,85f),false)
                val b=System.nanoTime()
                val marks=ScoreTools.marks(file.absolutePath,0,800f,800f).orEmpty()
                val c=System.nanoTime()
                raster(marks)
                val d=System.nanoTime()
                if(i>=5) {gestureTimes+=(b-a)/1e6;marksTimes+=(c-b)/1e6;nativeTimes+=(d-c)/1e6}
            }
            fun stats(values:List<Double>):String {
                val sorted=values.sorted()
                return "p50=${sorted[sorted.size/2]} ms, p95=${sorted[(sorted.size*.95).toInt().coerceAtMost(sorted.lastIndex)]} ms"
            }
            File("build/clean-dense-preview.txt").writeText(
                "40 measures, 60 retained contours per measure, 120 committed brush strokes, 20 measured live previews after 5 warm-ups.\n"+
                "Gesture/outline: ${stats(gestureTimes)}\nScoreTools.marks: ${stats(marksTimes)}\nNative render at 800x800: ${stats(nativeTimes)}\n"+
                "Native time includes mask union/difference and draw; no timing threshold is asserted.\n")
        } finally {ScoreTools.close(state);ScoreTools.scoreSource=null;Perform.musicBrush=ToolConfig()}
    }
    private fun raster(marks:List<PageMark>):androidx.compose.ui.graphics.PixelMap {
        val draw=Class.forName("com.inkslate.desktop.DocumentCanvasKt").declaredMethods.single {
            it.name=="drawPageMarks" && it.parameterCount==2
        }.apply {isAccessible=true}
        val bitmap=ImageBitmap(800,800)
        CanvasDrawScope().draw(Density(1f),LayoutDirection.Ltr,Canvas(bitmap),Size(800f,800f)) {
            drawRect(PRINT);draw.invoke(null,this,marks)
        }
        return bitmap.toPixelMap()
    }
    companion object {private val PRINT=Color(0xFFBA3348)}
}
