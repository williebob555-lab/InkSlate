package com.inksheets.desktop

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.core.omr.Printed
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real Transcriber cache transactions on isolated files, with deterministic page-boundary input. */
class BackgroundReadingTest {
    @get:Rule val tmp = TemporaryFolder()
    private class Stand(override val startFolder: File, val count: Map<String, Int>) : SheetsPlatform {
        private val prefs = mutableMapOf("sheets_library" to startFolder.absolutePath)
        val pages = Collections.synchronizedList(ArrayList<String>())
        var blockFile: String? = null
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val blocked = AtomicBoolean()
        override val deviceId = "background-reading-test"
        override val deviceName = "Background test"
        override val localFolder = File(startFolder, "local")
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { if (value == null) prefs.remove(key) else prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = block()
        override fun peek(file: File): PagePeek = object : PagePeek {
            override val pageCount = count[file.name] ?: 1
            override fun close() {}
            override fun render(index: Int, widthPx: Int): ImageBitmap {
                if (file.name == blockFile && index == 0 && blocked.compareAndSet(false, true)) {
                    started.countDown(); check(release.await(10, TimeUnit.SECONDS))
                }
                return ImageBitmap(320, 420).also { bitmap ->
                    Canvas(bitmap).drawRect(Rect(0f, 0f, 320f, 420f), Paint().apply { color = Color.White })
                }
            }
            override fun printed(index: Int): Printed {
                pages += "${file.name}:$index"
                return Printed(320f, 420f, emptyList(), emptyList(), emptyList())
            }
        }
    }
    private fun document(root: File, name: String) = File(root, name).apply { writeText("%PDF-1.4\n$name\n%%EOF") }
    private fun awaitReading(state: SheetsState, file: File, count: Int) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            val read = Transcriber.cached(state, file)
            if (read != null && (0 until count).all { read.hasRead(it) }) return
            Thread.sleep(20)
        }
        fail("${file.name} did not finish its cached pages")
    }

    @Test fun `automatic reading prioritizes current piece and preserves stable corrections without opening a panel`() {
        val root = tmp.newFolder()
        val a = document(root, "current-a.pdf")
        val b = document(root, "current-b.pdf")
        val other = document(root, "library.pdf")
        val platform = Stand(root, mapOf(a.name to 2, b.name to 1, other.name to 1))
        val state = SheetsState(platform)
        lateinit var current: Song
        state.change {
            current = addSong("Current piece", listOf(Part("a", a.name, instrument = "euphonium"), Part("b", b.name, instrument = "trombone")))
            addSong("Other piece", listOf(Part("other", other.name, instrument = "tuba")))
        }
        state.current = current; state.currentPath = a.absolutePath; state.homeInFront = false; state.readMusic = true
        val fix = "{\"7\":[]}"
        Transcriber.keepEdit(state, a, "sheets_fixed", fix)
        Transcriber.keepEdit(state, a, "sheets_structure", "a structural edit owned by another tool")
        try {
            Transcriber.background(state)
            awaitReading(state, other, 1)
            assertEquals(listOf("${a.name}:0", "${a.name}:1", "${b.name}:0", "${other.name}:0"), platform.pages)
            assertEquals(fix, Transcriber.keptEdit(state, a, "sheets_fixed"))
            assertEquals("a structural edit owned by another tool", Transcriber.keptEdit(state, a, "sheets_structure"))
            assertFalse(state.readMusicOpen)
            assertNull(Transcriber.busy)
            Transcriber.background(state)
            Thread.sleep(150)
            assertEquals("Already cached pages must not be read again", 4, platform.pages.size)
            Transcriber.stopBackground(state)
            val reread = CountDownLatch(1)
            Transcriber.read(state, a, again = true) { reread.countDown() }
            assertTrue(reread.await(10, TimeUnit.SECONDS))
            assertEquals("Explicit fresh recognition still preserves the musician's correction", fix,
                Transcriber.keptEdit(state, a, "sheets_fixed"))
            assertEquals("a structural edit owned by another tool", Transcriber.keptEdit(state, a, "sheets_structure"))
        } finally { Transcriber.stopBackground(state) }
    }

    @Test fun `foreground request interrupts background only after durable page save and keeps its shown file`() {
        val root = tmp.newFolder()
        val a = document(root, "background.pdf")
        val selected = document(root, "selected.pdf")
        val platform = Stand(root, mapOf(a.name to 3, selected.name to 1)).apply { blockFile = a.name }
        val state = SheetsState(platform)
        lateinit var song: Song
        state.change {
            song = addSong("Background", listOf(Part("a", a.name, instrument = "euphonium")))
            addSong("Selected", listOf(Part("b", selected.name, instrument = "trombone")))
        }
        state.current = song; state.currentPath = a.absolutePath; state.homeInFront = false; state.readMusic = true
        try {
            Transcriber.background(state)
            assertTrue(platform.started.await(10, TimeUnit.SECONDS))
            state.currentPath = selected.absolutePath
            val done = CountDownLatch(1)
            Transcriber.read(state, selected) { done.countDown() }
            platform.release.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue("Background page was saved before yielding", Transcriber.cached(state, a)?.hasRead(0) == true)
            awaitReading(state, a, 3)
            assertEquals(listOf("${a.name}:0", "${selected.name}:0", "${a.name}:1", "${a.name}:2"), platform.pages)
            assertEquals(selected.absolutePath, Transcriber.shown?.first?.absolutePath)
            assertFalse(state.readMusicOpen)
        } finally { platform.release.countDown(); Transcriber.stopBackground(state) }
    }
}
