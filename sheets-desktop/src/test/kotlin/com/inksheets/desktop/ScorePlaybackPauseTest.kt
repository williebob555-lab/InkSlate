package com.inksheets.desktop

import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.core.omr.*
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ScorePlaybackPauseTest {
    @get:Rule val tmp=TemporaryFolder()
    private class Output: AudioOut {
        override val sampleRate=8000
        var starts=0;var stops=0
        var callback: ((FloatArray)->Unit)?=null
        override fun start(fill: (FloatArray)->Unit) { starts++;callback=fill }
        override fun stop() { stops++;callback=null }
        fun pull(count: Int)=FloatArray(count).also { callback?.invoke(it) }
    }
    private class Platform(override val startFolder: File): SheetsPlatform {
        val prefs=mutableMapOf("sheets_library" to startFolder.absolutePath)
        override val deviceId="pause-test"
        override val deviceName="Silent pause test"
        override val localFolder=File(startFolder,"local")
        override val audioOut=Output()
        override val microphone: Microphone?=null
        override fun pref(key: String)=prefs[key]
        override fun setPref(key: String,value: String?) { if(value==null) prefs.remove(key) else prefs[key]=value }
        override fun pageText(file: File,page: Int): String?=null
        override fun openPart(song: Song,part: Part,file: File) {}
        override fun onMain(block: ()->Unit)=block()
    }
    private fun await(condition: ()->Boolean) {
        val deadline=System.nanoTime()+2_000_000_000L
        while(!condition() && System.nanoTime()<deadline) Thread.sleep(2)
        assertTrue("Condition did not become ready",condition())
    }

    @Test fun pausedScoreKeepsQueuedSamplesWhileAnotherSourceKeepsOutputOpen() {
        val platform=Platform(tmp.newFolder());val state=SheetsState(platform)
        val generated=AtomicInteger()
        val score="pause-score-test";val click="pause-click-test"
        try {
            assertTrue(Sound.play(state,score,ahead=true) { samples -> samples.indices.forEach { samples[it]=generated.getAndIncrement()/100000f } })
            assertTrue(Sound.play(state,click) { it.fill(.1f) })
            await { generated.get()>=1920 }
            assertArrayEquals(FloatArray(100) { .1f+it/100000f },platform.audioOut.pull(100),0f)
            assertTrue(Sound.pause(score))
            val frozen=generated.get()
            assertFalse(Sound.playing(score))
            assertTrue(Sound.playing(click))
            assertEquals(0,platform.audioOut.stops)
            repeat(3) { assertArrayEquals(FloatArray(100) { .1f },platform.audioOut.pull(100),0f) }
            Thread.sleep(40)
            assertEquals(frozen,generated.get())
            assertTrue(Sound.resume(state,score))
            assertArrayEquals(FloatArray(100) { .1f+(it+100)/100000f },platform.audioOut.pull(100),0f)
            assertEquals("Resume must reuse the still-open output",1,platform.audioOut.starts)
        } finally { Sound.stop(score);Sound.stop(click) }
    }

    @Test fun scorePauseResumeRetainsWithinBarProgressInsteadOfRestartingTheBar() {
        val platform=Platform(tmp.newFolder());val state=SheetsState(platform)
        state.currentPath=File(platform.startFolder,"score.pdf").apply { writeText("fixture") }.absolutePath
        state.pageShown=0 to 1
        ScoreTools.install(state)
        val bar=Measure(1,0,0,Box(0,0,300,40),10f,Clef.TREBLE,Key(0),TimeSig(4,4),
            listOf(Note(listOf(4),listOf(Pitch(0,4)),Duration(1),30f)))
        ScoreTools.scoreSource={Score(listOf(bar,bar.copy(number=2)),1,listOf(400))}
        val tempo=SharedMetronome.bpm
        try {
            SharedMetronome.bpm=120.0
            ScoreTools.play(state)
            await { ScoreTools.playbackProgress>.02f }
            ScoreTools.pause(state)
            val progress=ScoreTools.playbackProgress
            assertTrue(progress>.02f)
            Thread.sleep(150)
            assertNull(ScoreTools.playing)
            assertEquals(1,ScoreTools.paused)
            assertEquals(progress,ScoreTools.playbackProgress,0f)
            ScoreTools.resume(state)
            assertNull(ScoreTools.paused)
            assertNotNull(ScoreTools.playing)
            assertEquals("Resume retains the same rail position",progress,ScoreTools.playbackProgress,0f)
        } finally { ScoreTools.stop(state);ScoreTools.scoreSource=null;SharedMetronome.bpm=tempo;ScoreTools.close(state) }
    }
}
