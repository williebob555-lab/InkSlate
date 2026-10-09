package com.inksheets.desktop.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inksheets.core.Part
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.ScrollWheel
import com.inkslate.desktop.AppFlavor
import com.inkslate.desktop.AppRoot
import com.inkslate.desktop.InkSlateTheme
import com.inkslate.desktop.NavigationHooks
import com.inkslate.desktop.Shortcuts
import com.inkslate.desktop.SimulatedTouch
import com.inksheets.desktop.installInkSheets
import com.inksheets.ui.SheetsState
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** T2: reading and playing. Shared test library and input helpers (see docs/inksheets/TESTING.md). */
object T2Lib {
    init { System.setProperty("kotlinx.coroutines.test.default_timeout", "20m") }
    val real = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")
    val pep = File(real, "Imported/PEP BAND/Music")
    val dir = File("build/t2-library")
    val shots = File("build/t2-shots").also { it.mkdirs() }

    /** song -> (source file under pep, instrument) */
    val songs = listOf(
        "Party Medley" to listOf("Party Medley/Party Medley - Trombone 1.pdf" to "trombone", "Party Medley/Party Medley - Trombone 2.pdf" to "trombone2", "Party Medley/Party Medley - Euphonium.pdf" to "euphonium", "Party Medley/Party Medley - Full Score.pdf" to "score"),
        "24K Magic" to listOf("24K Magic/24K Magic - Trombone 1.pdf" to "trombone", "24K Magic/24K Magic - Baritone.pdf" to "euphonium", "24K Magic/24K Magic - Score.pdf" to "score"),
        "Toxic" to listOf("Toxic/Toxic - Trombone 1.pdf" to "trombone"),
        "Master of Puppets" to listOf("Master of Puppets/Master of Puppets (Pep) - Trombone 1.pdf" to "trombone"),
        "MSOM Shorts" to listOf("Shorts/MSOM SHORTS 2025 - Trombone 1.pdf" to "trombone")
    )

    fun available() = songs.all { (_, ps) -> ps.all { File(pep, it.first).isFile } }

    /** Copy the real files into build/t2-library. */
    fun build(): File {
        dir.deleteRecursively(); dir.mkdirs()
        for ((_, ps) in songs) for ((rel, _) in ps) {
            val src = File(pep, rel); val dst = File(dir, rel)
            dst.parentFile.mkdirs(); src.copyTo(dst, overwrite = true)
        }
        return dir
    }

    /** Make the songs and the setlist in the open library; returns the setlist id. */
    fun fill(state: SheetsState): String {
        val lib = state.library!!
        val ids = ArrayList<String>()
        for ((title, ps) in songs) {
            val s = lib.addSong(title, ps.map { (rel, ins) -> Part(file = rel, instrument = if (ins == "trombone2") "trombone" else ins, chair = if (ins == "trombone2") 2 else null) })
            ids += s.id
        }
        val set = lib.addSetlist("T2 Gig")
        ids.forEach { lib.addToSetlist(set.id, it) }
        state.refresh()
        return set.id
    }
}

@OptIn(ExperimentalTestApi::class)
class T2(val t: DesktopComposeUiTest, val sheets: () -> SheetsState, val w: Float, val h: Float, val tag: String) {
    var step = 0
    var setId = ""
    val root get() = t.onAllNodes(isRoot()).onFirst()
    fun settle(frames: Int = 12) = repeat(frames) { t.mainClock.advanceTimeBy(16); Thread.sleep(4) }
    fun image(frames: Int = 12): BufferedImage { settle(frames); return root.captureToImage().toAwtImage() }
    fun shot(name: String) { ImageIO.write(image(), "png", File(T2Lib.shots, "$tag-%02d-%s.png".format(++step, name))) }
    fun page() = sheets().pageShown.first
    fun count() = sheets().pageShown.second
    fun shown(text: String) = t.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    fun node(text: String) = t.onAllNodesWithText(text, useUnmergedTree = true).onFirst()
    fun waitPages(ms: Long = 30_000) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until && sheets().pageShown.second == 0) settle(4)
        settle(30)
    }
    fun waitFor(ms: Long = 10_000, cond: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until && !cond()) settle(2)
        return cond()
    }

    // ---- fingers (Windows style) ----
    fun at(p: Offset) { SimulatedTouch.stamp(); root.performMouseInput { moveTo(p) } }
    fun press() { SimulatedTouch.stamp(); root.performMouseInput { press() } }
    fun release() { SimulatedTouch.stamp(); root.performMouseInput { release() } }
    /** A finger tap: down for [holdMs] of real time (the app times taps with the wall clock), then up. */
    fun holdFor(ms: Long) { val t0 = System.nanoTime(); do settle(1) while ((System.nanoTime() - t0) / 1_000_000 < ms) }
    fun tapAt(p: Offset, holdMs: Long = 40, after: Int = 40) {
        at(p); press()
        holdFor(holdMs)
        release()
        settle(after)
    }
    fun tap(fx: Float, fy: Float, after: Int = 40, holdMs: Long = 40) = tapAt(Offset(w * fx, h * fy), holdMs = holdMs, after = after)
    fun tapNode(n: SemanticsNodeInteraction) = tapAt(n.fetchSemanticsNode().boundsInRoot.center)
    fun tapText(text: String) = tapNode(node(text))
    fun drag(fx1: Float, fy1: Float, fx2: Float, fy2: Float, frames: Int, after: Int = 40, perFrame: Int = 1) {
        at(Offset(w * fx1, h * fy1)); SimulatedTouch.stamp(); root.performMouseInput { press() }
        for (i in 1..frames) { at(Offset(w * (fx1 + (fx2 - fx1) * i / frames), h * (fy1 + (fy2 - fy1) * i / frames))); settle(perFrame) }
        SimulatedTouch.stamp(); root.performMouseInput { release() }
        settle(after)
    }
    fun pinch(from: Float, to: Float, steps: Int = 20) {
        for (i in 0..steps) { val half = from + (to - from) * i / steps; SimulatedTouch.finger(1, w / 2 - half, h / 2); SimulatedTouch.finger(2, w / 2 + half, h / 2); settle(1) }
        SimulatedTouch.lift(1); SimulatedTouch.lift(2); settle(30)
    }
    fun twoFingerPan(dx: Float, dy: Float, steps: Int = 15) {
        for (i in 0..steps) { val x = dx * i / steps; val y = dy * i / steps; SimulatedTouch.finger(1, w / 2 - 100 + x, h / 2 + y); SimulatedTouch.finger(2, w / 2 + 100 + x, h / 2 + y); settle(1) }
        SimulatedTouch.lift(1); SimulatedTouch.lift(2); settle(30)
    }

    // ---- mouse (not stamped as a finger) ----
    fun mouseClick(fx: Float, fy: Float, after: Int = 40, holdMs: Long = 40) {
        root.performMouseInput { moveTo(Offset(w * fx, h * fy)); press() }
        holdFor(holdMs); root.performMouseInput { release() }; settle(after)
    }
    fun wheel(notches: Float, shift: Boolean = false, ctrl: Boolean = false, sideways: Boolean = false) {
        if (ctrl) root.performKeyInput { keyDown(Key.CtrlLeft) }
        if (shift) root.performKeyInput { keyDown(Key.ShiftLeft) }
        root.performMouseInput {
            moveTo(Offset(w / 2, h / 2))
            scroll(notches, if (sideways) ScrollWheel.Horizontal else ScrollWheel.Vertical)
        }
        if (shift) root.performKeyInput { keyUp(Key.ShiftLeft) }
        if (ctrl) root.performKeyInput { keyUp(Key.CtrlLeft) }
        settle(30)
    }
    fun key(k: Key, ctrl: Boolean = false, shift: Boolean = false) {
        // The window's onKeyEvent (Main.kt) is not part of AppRoot, so a key is looked up in the same table and run as Main does.
        val stroke = com.inkslate.desktop.KeyStroke(k.keyCode, ctrl = ctrl, shift = shift)
        val bound = com.inkslate.desktop.KeyBindingStore.actionFor(stroke)
        lastKeyAction = bound?.name
        bound?.perform?.let { com.inkslate.core.Perform.run(it) }
        settle(8)
    }
    var lastKeyAction: String? = null

    /** Fraction of sampled pixels that are paper white: 0 means no page. */
    fun paper(img: BufferedImage = image(2)): Double {
        var n = 0; var tot = 0
        for (y in 0 until img.height step 4) for (x in 0 until img.width step 4) {
            tot++
            val p = img.getRGB(x, y); val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            if (r > 200 && g > 200 && b > 200) n++
        }
        return n.toDouble() / tot
    }

    /** Fraction of sampled pixels that are music: dark with paper beside them (so the dark background does not count). */
    fun music(img: BufferedImage): Double {
        var n = 0; var tot = 0
        fun lum(x: Int, y: Int): Int { if (x < 0 || y < 0 || x >= img.width || y >= img.height) return 0; val p = img.getRGB(x, y); return (((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255)) / 3 }
        for (y in (img.height * 0.12).toInt() until (img.height * 0.95).toInt() step 3) for (x in 0 until img.width step 3) {
            tot++
            if (lum(x, y) < 90 && (lum(x + 5, y) > 200 || lum(x - 5, y) > 200) && (lum(x, y + 5) > 200 || lum(x, y - 5) > 200)) n++
        }
        return n.toDouble() / tot
    }

    /**
     * What a turn or song change looked like: wall ms (and frames) from the input to the state changing, to a first picture
     * of paper, and to music visible; and how many frames showed paper with no music (a blank page) or nothing at all.
     * Wall time includes about [captureMs] per captured frame, so numbers after the state change are upper bounds.
     */
    data class Timing(val toState: Long, val toPaper: Long, val toMusic: Long, val blankPaperFrames: Int, val emptyFrames: Int, val pageBefore: Int, val pageAfter: Int, val song: String?) {
        override fun toString() = "state ${toState}ms, paper ${toPaper}ms, music ${toMusic}ms, blank-paper frames $blankPaperFrames, empty frames $emptyFrames, page ${pageBefore + 1}->${pageAfter + 1} ($song)"
    }
    var captureMs = 0L

    fun timed(maxMs: Long = 8000, input: () -> Unit): Timing {
        val before = page(); val songBefore = sheets().current?.id
        val t0 = System.nanoTime()
        input()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        var toState = -1L; var toPaper = -1L; var toMusic = -1L; var blank = 0; var empty = 0
        val limit = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < limit) {
            if (toState < 0 && (page() != before || sheets().current?.id != songBefore)) toState = ms()
            if (toState >= 0) {
                val img = image(1)
                val p = paper(img); val m = music(img)
                if (m > 0.004) { if (toMusic < 0) toMusic = ms() }
                else if (toMusic < 0) { if (p > 0.15) { blank++; if (toPaper < 0) toPaper = ms() } else empty++ }
                if (toPaper < 0 && p > 0.15) toPaper = ms()
                if (toMusic >= 0) break
            } else settle(1)
        }
        return Timing(toState, toPaper, toMusic, blank, empty, before, page(), sheets().current?.title)
    }

    /** What the screen did across a turn or song change: frames until it stopped changing, and how many showed (almost) no music. */
    data class Film(val frames: Int, val dipFrames: Int, val wallMs: Long, val finalMusic: Double, val series: List<Double>, val stateAfter: String) {
        override fun toString() = "$frames frames to settle (${frames * 16} ms of animation), $dipFrames frames with <25% of the final music, wall ${wallMs} ms; $stateAfter"
    }
    /** Run [input], then film frames (each 1 virtual frame) up to [maxFrames], until the picture is unchanged for 6 frames. */
    fun film(maxFrames: Int = 120, input: () -> Unit): Film {
        val t0 = System.nanoTime()
        input()
        val series = ArrayList<Double>(); var same = 0; var prev: BufferedImage? = null
        var settledAt = -1
        for (i in 0 until maxFrames) {
            val img = image(1)
            series += music(img)
            val p = prev
            if (p != null && sameImage(p, img)) same++ else same = 0
            prev = img
            if (same >= 6) { settledAt = i - 5; break }
        }
        val fin = series.lastOrNull() ?: 0.0
        val frames = if (settledAt >= 0) settledAt else series.size
        val dip = series.take(frames).count { it < fin * 0.25 }
        return Film(frames, dip, (System.nanoTime() - t0) / 1_000_000, fin, series, "${sheets().current?.title} page ${page() + 1}/${count()}")
    }
    fun sameImage(a: BufferedImage, b: BufferedImage): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        var d = 0L
        for (y in 0 until a.height step 7) for (x in 0 until a.width step 7) d += kotlin.math.abs((a.getRGB(x, y) and 255) - (b.getRGB(x, y) and 255))
        return d < 2000
    }

    fun strokes(path: String?) = path?.let { com.inkslate.core.Perform.inkOf?.invoke(it)?.pages?.values?.sumOf { s -> s.size } } ?: 0

    fun open(song: String) {
        // What tapping a song in the library does (openSong is internal to sheets-ui).
        val st = sheets()
        val s = st.library!!.songs.first { it.title == song }
        val part = st.partFor(s)!!
        st.stopPlaying()
        st.current = s
        st.noteOpened(s)
        val file = st.partFile(s, part)!!
        part.firstPage?.let { com.inkslate.core.Perform.requestPage(file.absolutePath, it - 1) }
        st.platform.openPart(s, part, file)
    }
}

fun resetFlavor() {
    SimulatedTouch.on = false
    AppFlavor.onHomeShown = null; AppFlavor.onHome = null; AppFlavor.settingsSection = null
    AppFlavor.musicView = false; AppFlavor.fingerPans = false; AppFlavor.alwaysFullscreen = false
    AppFlavor.home = null; AppFlavor.paneOverlay = null
    com.inkslate.core.Perform.onPosition = null
}

/** The app as it ships on a library of the T2 songs; [body] runs inside. */
@OptIn(ExperimentalTestApi::class)
fun t2App(tag: String, w: Int = 1600, h: Int = 1000, profile: String? = "trombone", body: T2.() -> Unit) {
    val lib = T2Lib.build()
    val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath); it.setPref("sheets_strip_collapsed", "false") }
    try {
        runDesktopComposeUiTest(width = w, height = h) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
            val x = T2(this, sheets, w.toFloat(), h.toFloat(), tag)
            x.settle(30)
            x.waitFor(20_000) { sheets().library != null }
            x.setId = T2Lib.fill(sheets())
            if (profile != null) sheets().chooseProfile(profile)
            x.settle(10)
            x.body()
        }
    } finally { resetFlavor() }
}
