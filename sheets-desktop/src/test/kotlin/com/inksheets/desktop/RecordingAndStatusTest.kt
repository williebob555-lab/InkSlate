package com.inksheets.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.inkslate.core.PageMark
import com.inksheets.core.*
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalTestApi::class)
class RecordingAndStatusTest {
    @Test fun `recording controls dock outside score and collapse to status when a take starts`() {
        val (state,_)=setup("record-drawer")
        try {
            state.audioOpen=true
            runDesktopComposeUiTest(width=640,height=360) {
                setContent {MaterialTheme{Box(Modifier.fillMaxSize()){ActionStrip(state)}}}
                waitForIdle()
                runOnIdle {
                    assertEquals(ShelfWidget.RECORDING,state.expandedMusicCategory)
                    assertFalse(ReaderPanels.open)
                    assertTrue("Score must retain visible space; shelf=${state.shelfHeight}",state.shelfHeight<240f)
                }
                onNodeWithText("Record yourself").performClick()
                waitForIdle()
                runOnIdle {
                    assertTrue(SelfRecorder.recording)
                    assertFalse(state.audioOpen)
                    assertNull(state.expandedMusicCategory)
                    assertFalse(ReaderPanels.open)
                    assertTrue("Taking a recording leaves most of the score visible",state.shelfHeight<120f)
                }
            }
        } finally {cleanup(state)}
    }
    @get:Rule val tmp = TemporaryFolder()
    private class Player : AudioPlayer {
        var readable = true
        var loadStarted: java.util.concurrent.CountDownLatch? = null
        var finishLoad: java.util.concurrent.CountDownLatch? = null
        override fun load(file: File): Boolean {
            loadStarted?.countDown()
            finishLoad?.await(3,java.util.concurrent.TimeUnit.SECONDS)
            return readable
        }
        override var playing = false
        override var interruptionReason: String? = null
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seek(ms: Long) { positionMs = ms }
        override var positionMs = 84_000L
        override val durationMs = 180_000L
        override var speed = 1.0
        override var pitch = 0
        override fun setLoop(startMs: Long?, endMs: Long?) {}
        override fun release() {}
    }
    private class Mic : Microphone {
        override val sampleRate = 48_000
        var receive: ((FloatArray) -> Unit)? = null
        override fun start(onChunk: (FloatArray) -> Unit): Boolean { receive = onChunk; return true }
        override fun stop() { receive = null }
    }
    private class Platform(val root: File) : SheetsPlatform {
        val prefs = mutableMapOf("sheets_library" to root.absolutePath, "sheets_strip_collapsed" to "false")
        val player = Player()
        val mic = Mic()
        override val deviceId = "status-test"
        override val deviceName = "Test stand"
        override val startFolder = root
        override val localFolder = File(root, ".local")
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { if (value == null) prefs.remove(key) else prefs[key] = value }
        override val audioOut: AudioOut = object : AudioOut {
            override val sampleRate = 48_000
            override fun start(fill: (FloatArray) -> Unit) {}
            override fun stop() {}
        }
        override val microphone: Microphone = mic
        override fun audioPlayer(): AudioPlayer = player
        override fun pageText(file: File, page: Int): String? = null
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun onMain(block: () -> Unit) = block()
    }
    private fun setup(name: String): Pair<SheetsState, Platform> {
        val p = Platform(tmp.newFolder(name))
        File(p.root, "Nocturne.pdf").writeText("fixture")
        File(p.root, "band.wav").writeText("fixture")
        val state = SheetsState(p)
        state.change { addSong("Nocturne", listOf(Part(file = "Nocturne.pdf", instrument = "euphonium"))) {
            audio = listOf(AudioTrack("band.wav", "Band rehearsal", speed = .85))
        } }
        state.current = state.library!!.songs.first { it.title == "Nocturne" }
        state.currentPath = File(p.root, "Nocturne.pdf").absolutePath
        state.pageShown = 0 to 3; state.homeInFront = false; state.stripCollapsed = false
        state.listenTurns = true
        Recording.player = p.player; Recording.loadedFile = null; Recording.dismissIssue()
        Click.setCountInBars(state,0)
        return state to p
    }
    private fun cleanup(state: SheetsState) {
        if (SelfRecorder.recording) SelfRecorder.stop(state)
        Recording.end(state); Recording.player = null
        Listener.active = false; Listener.follow = null
        Click.stop(state); Ears.stop("record")
    }
    @Test fun `cancelled slow load cannot restart playback or display a late error`() {
        val (state,p)=setup("cancel-load")
        try {
            p.player.readable=false
            p.player.loadStarted=java.util.concurrent.CountDownLatch(1)
            p.player.finishLoad=java.util.concurrent.CountDownLatch(1)
            Recording.requestPlay(state,state.current!!.audio.first(),state.current)
            assertTrue(p.player.loadStarted!!.await(3,java.util.concurrent.TimeUnit.SECONDS))
            Recording.end(state)
            p.player.finishLoad!!.countDown()
            // Acquiring load's monitor waits for the pending decoder to finish publishing.
            synchronized(Recording) { assertNull(Recording.issue) }
            assertFalse(Recording.playing)
            assertFalse(Recording.loading)
            assertFalse(p.player.playing)
        } finally {p.player.finishLoad?.countDown();cleanup(state)}
    }
    @Test fun `waveform seeks and marks an excerpt loop without opening the recording panel`() {
        val (state,p)=setup("waveform-loop")
        try {
            val track=state.current!!.audio.first()
            assertTrue(Recording.load(state,track))
            Recording.play(state,track,state.current)
            runDesktopComposeUiTest(width=400,height=100) {
                setContent { MaterialTheme { RecordingWaveform(state,track,0,180_000,true) } }
                val waveform=onNodeWithContentDescription("Recording waveform; tap to seek, hold to mark a practice loop")
                waveform.performTouchInput { click(androidx.compose.ui.geometry.Offset(width*.75f,height*.5f)) }
                assertEquals(135_000L,p.player.positionMs)
                waveform.performTouchInput { longClick(androidx.compose.ui.geometry.Offset(width*.25f,height*.5f)) }
                waveform.performTouchInput { longClick(androidx.compose.ui.geometry.Offset(width*.5f,height*.5f)) }
                runOnIdle {
                    val latest=state.library!!.song(state.current!!.id)!!.audio.first()
                    assertEquals(45_000L,latest.loopStartMs)
                    assertEquals(90_000L,latest.loopEndMs)
                    assertFalse(state.audioOpen)
                }
            }
        } finally {cleanup(state)}
    }
    @Test fun `reading progress and Stop stay reachable when music widgets are folded`() {
        val (state,_)=setup("reading-status")
        try {
            Transcriber.busy="Reading page 2 of 10…"
            Transcriber.stopAsked=false
            state.stripCollapsed=true
            runDesktopComposeUiTest(width=360,height=640) {
                setContent { MaterialTheme { Box(Modifier.fillMaxSize()){ActionStrip(state)} } }
                onNodeWithText("Reading page 2 of 10…").assertIsDisplayed()
                onNodeWithText("Stop reading").performClick()
                runOnIdle {assertTrue(Transcriber.stopAsked)}
            }
        } finally {
            Transcriber.busy=null;Transcriber.stopAsked=false
            cleanup(state)
        }
    }
    @Test fun `finishing a reading immediately enables score controls and clears unread status`() {
        val (state,_)=setup("read-transition")
        var read:com.inksheets.core.omr.Score?=null
        state.readMusic=true
        ScoreTools.install(state)
        ScoreTools.scoreSource={read}
        try {
            runDesktopComposeUiTest(width=360,height=640) {
                setContent { MaterialTheme { Box(Modifier.fillMaxSize()){ActionStrip(state)} } }
                onNodeWithText("Score").performClick()
                onNodeWithContentDescription("Review recognized notes").assertIsNotEnabled()
                runOnIdle {
                    read=com.inksheets.core.omr.Score(emptyList(),1,listOf(800))
                    Transcriber.shown=File(state.currentPath!!) to read!!
                }
                waitForIdle()
                onNodeWithContentDescription("Review recognized notes").assertIsEnabled()
                onNodeWithContentDescription("Clean view").assertIsEnabled()
                onNodeWithText("Page 1 · READ").assertIsDisplayed()
            }
        } finally {ScoreTools.scoreSource=null;Transcriber.shown=null;cleanup(state)}
    }
    @Test fun `missing and unreadable recordings give recoverable actions at every size`() {
        for ((size, w, h) in UiAudit.SIZES) {
            val (state, p) = setup("failure-$size")
            try {
                File(p.root, "band.wav").delete()
                assertFalse(Recording.load(state, state.current!!.audio.first()))
                runDesktopComposeUiTest(width = w, height = h) {
                    setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { ActionStrip(state) } } }
                    onNodeWithText("Locate recording").assertIsDisplayed()
                    onNodeWithText("Choose another recording").assertIsDisplayed()
                    val problems = UiAudit.check(this, size, "recording-failure", w, h)
                    assertTrue("$size: $problems", problems.isEmpty())
                    File(p.root, "band.wav").writeText("returned by sync")
                    onNodeWithText("Retry playback").performClick()
                    waitUntil(timeoutMillis=3000) { p.player.playing }
                    assertNull(Recording.issue)
                    runOnIdle { Recording.end(state); p.player.readable = false; Recording.loadedFile = null }
                    assertFalse(Recording.load(state, state.current!!.audio.first()))
                    assertTrue(Recording.issue!!.message.contains("could not be read"))
                    runOnIdle { Recording.dismissIssue() }
                }
            } finally { cleanup(state) }
        }
    }
    @Test fun `active music status is reserved clear of notation at every size`() {
        for ((size, w, h) in UiAudit.SIZES) {
            val (state, p) = setup("active-$size")
            try {
                Recording.play(state, state.current!!.audio.first(), state.current)
                Listener.active = true; Ears.deaf = false
                Listener.follow = Listener.Follow(20_000,0,45_000,true,"learned",0,3,found=true,sure=.9f)
                Click.countOff(state,1); SharedMetronome.beat = 0
                runDesktopComposeUiTest(width = w, height = h) {
                    setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { ActionStrip(state) } } }
                    waitForIdle()
                    onNodeWithText("Count in").assertIsDisplayed()
                    onNodeWithText("Playing recording").assertIsDisplayed()
                    assertTrue("$size: shelf too tall", state.shelfHeight < h * .45f)
                    val problems = UiAudit.check(this,size,"music-status",w,h)
                    assertTrue("$size: $problems",problems.isEmpty())
                }
            } finally { cleanup(state) }
        }
    }
    @Test fun `recording is paired from the first sample and quick repeated takes never overwrite`() {
        val (state, p) = setup("takes")
        try {
            val original = state.current!!.audio.size
            assertTrue(SelfRecorder.start(state,state.current!!))
            p.mic.receive!!(FloatArray(1234) { .2f })
            val take = state.library!!.song(state.current!!.id)!!.audio.last()
            assertEquals(original + 1,state.library!!.song(state.current!!.id)!!.audio.size)
            val active = SelfRecorder.activeFile!!
            assertEquals(0,RecordingRecovery(p.root,p.deviceId).recover(state.library!!,active).recovered)
            SelfRecorder.stop(state)
            assertTrue(SelfRecorder.start(state,state.current!!))
            p.mic.receive!!(FloatArray(1234) { .3f }); SelfRecorder.stop(state)
            val takes = state.library!!.song(state.current!!.id)!!.audio
            assertEquals(original + 2,takes.size)
            assertNotEquals(take.file,takes.last().file)
            assertTrue(File(p.root,take.file).isFile)
        } finally { cleanup(state) }
    }
    @Test fun `list editor preserves remote additions and untouched metadata while retaining reorder`() {
        val a = AudioTrack("a.wav","A",speed=1.0)
        val b = AudioTrack("b.wav","B")
        val c = AudioTrack("c.wav","new on phone")
        val latest = listOf(a.copy(volume=.4),b,c)
        val result = mergeAudioEdits(listOf(a,b),listOf(b,a.copy(speed=.7)),latest)
        assertEquals(listOf("b.wav","a.wav","c.wav"),result.map { it.file })
        assertEquals(.4,result[1].volume,0.0); assertEquals(.7,result[1].speed,0.0)
        assertEquals(listOf(b,c),mergeAudioEdits(listOf(a,b),listOf(b),latest))
    }
    @Test fun `listening never promises a countdown while finding lost unsure or estimated`() {
        val follow = Listener.Follow(20_000,0,40_000,true,"learned",0,3)
        assertNotNull(ListeningStatus.of(follow,false).countdown)
        for (f in listOf(follow.copy(found=false),follow.copy(found=false,previouslyFound=true),follow.copy(suspect=true),follow.copy(turnSafe=false),follow.copy(source="guessed"),follow.copy(music=false))) {
            assertNull(ListeningStatus.of(f,false).countdown)
        }
        assertEquals("Lost your place",ListeningStatus.of(follow.copy(found=false,previouslyFound=true),false).title)
        assertTrue(ListeningStatus.of(follow,true).warning)
    }
    @Test fun `indicator advances outside notation and distinguishes a paused bar`() {
        val early = PlaybackIndicator.marks(20f,40f,220f,80f,10f,.25f)
        val late = PlaybackIndicator.marks(20f,40f,220f,80f,10f,.75f)
        assertTrue(late.last().contours.first()[0] > early.last().contours.first()[0])
        for (mark in early) for (points in mark.contours) for (i in points.indices step 2) {
            assertTrue("Indicator crosses notation",points[i] < 20f || points[i+1] < 40f || points[i+1] > 80f)
        }
        assertTrue(early.count { it.kind == PageMark.Kind.FILL } <= 1)
        assertNotEquals(early.first().color,PlaybackIndicator.marks(20f,40f,220f,80f,10f,.25f,true).first().color)
    }
}
