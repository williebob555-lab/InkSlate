package com.inksheets.desktop.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import com.inkslate.desktop.AppFlavor
import com.inkslate.desktop.AppRoot
import com.inkslate.desktop.InkSlateTheme
import com.inkslate.desktop.NavigationHooks
import com.inkslate.desktop.Shortcuts
import com.inksheets.desktop.UiAudit
import com.inksheets.desktop.installInkSheets
import com.inksheets.desktop.review.T1.say
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** T1: player flows through Home - remove and restore, reorder by hold-drag, bookmarks, imports, details. */
@OptIn(ExperimentalTestApi::class)
class T1FlowsTest {
    private val shots = File("build/t1shots").also { it.mkdirs() }

    @After
    fun reset() {
        AppFlavor.home = null; AppFlavor.paneOverlay = null; AppFlavor.onHomeShown = null; AppFlavor.onHome = null; AppFlavor.settingsSection = null
        AppFlavor.musicView = false; AppFlavor.fingerPans = false; AppFlavor.alwaysFullscreen = false
    }

    private fun DesktopComposeUiTest.settle(frames: Int = 12) = repeat(frames) { mainClock.advanceTimeBy(16); Thread.sleep(4) }

    private fun flows(tag: String, w: Int, h: Int, libName: String = "flow-$tag") {
        val lib = T1.copyLib(libName)
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }
        runDesktopComposeUiTest(width = w, height = h) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
            settle(40)
            val until = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < until && (sheets().library == null || sheets().library!!.songs.size < 29)) settle(8)
            settle(60)
            val s = sheets()
            var n = 0
            fun shot(name: String) {
                settle(10)
                ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(shots, "$tag-f%02d-%s.png".format(++n, name)))
                val issues = UiAudit.check(this, tag, "f-$name", w, h)
                if (issues.isNotEmpty()) say("AUDIT $tag/$name: ${issues.joinToString(" | ")}")
            }
            fun scene(name: String, block: () -> Unit) {
                runCatching(block).onFailure { say("SCENE $tag/$name FAILED: ${it.toString().replace('\n', ' ').take(300)}") }
            }
            fun click(text: String, index: Int = 0, sub: Boolean = false) {
                onAllNodesWithText(text, substring = sub)[index].performClick(); settle(10)
            }
            fun clickDesc(desc: String, index: Int = 0) { onAllNodesWithContentDescription(desc)[index].performClick(); settle(10) }
            fun has(text: String, sub: Boolean = false) = onAllNodesWithText(text, substring = sub).fetchSemanticsNodes().isNotEmpty()
            fun type(text: String) { onNode(hasSetTextAction()).performTextInput(text); settle(10) }
            fun esc() { onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Escape) }; settle(10) }
            say("$tag: songs=${s.library!!.songs.size}")

            scene("expand") { click("parts", 0, sub = true); shot("expanded-parts") ; click("parts", 0, sub = true) }
            scene("menu") { clickDesc("Song options", 0); shot("song-menu"); esc() }
            scene("details") {
                clickDesc("Song options", 1); click("Details and parts"); shot("details-top")
                esc()
            }
            scene("details multi-part") {
                // 24K Magic (18 parts) is the second row
                clickDesc("Song options", 1); click("Details and parts"); settle(10)
                shot("details-24k"); esc()
            }
            scene("remove flow") {
                val before = s.library!!.songs.size
                val victim = s.library!!.songs.first { it.title == "Chester" }
                // find Chester's row menu: open via state shortcut: menu index = position among rows
                val idx = s.library!!.songs.indexOfFirst { it.title == "Chester" }
                // scroll is not trivial: use search to bring it up
                type("Chester"); settle(10)
                clickDesc("Song options", 0)
                click("Remove from library"); shot("remove-tap1")
                click("Tap again", sub = true); settle(20)
                shot("after-remove")
                say("$tag: after Remove: songs ${before} -> ${s.library!!.songs.size}; Undo shown=${has("Undo", true)}; Restore shown=${has("Restore", true)}; trash entries=${s.trash()?.entries()?.size}")
                // restore from settings
                clickDesc("More"); click("Settings"); settle(30)
                shot("settings-top")
                runCatching {
                    // scroll to Trash: find node by text
                    onAllNodesWithText("Trash (1)", substring = true).onFirst().performClick(); settle(10)
                    shot("settings-trash-open")
                }.onFailure { say("$tag: Trash row not reachable: ${it.toString().take(150)}") }
            }
        }
    }

    @Test fun `laptop flows`() = flows("laptop", 1600, 1000)
    @Test fun `phone flows`() = flows("phone", 390, 844)

    /** Typing in the search box and opening Home with 300 songs on the real UI. */
    @Test
    fun `r2 home with three hundred songs`() {
        val dir = File("build/t1work/big-ui").apply { deleteRecursively(); mkdirs() }
        val rnd = java.util.Random(11)
        val words = listOf("Star", "Liberty", "Bell", "Night", "Dance", "Blue", "Gold", "Fire", "River", "Moon", "Stars", "March", "Rhapsody", "Hymn", "Jazz", "Funk", "Brass", "Wind", "Glory", "Suite")
        val inst = listOf("Trombone 1", "Trombone 2", "Euphonium", "Electric Bass", "Tuba", "Trumpet 1", "Flute 1", "Clarinet 1", "Alto Sax 1", "Score")
        var serial = 0
        val titles = LinkedHashSet<String>()
        while (titles.size < 300) titles += listOf(words[rnd.nextInt(20)], words[rnd.nextInt(20)], words[rnd.nextInt(20)]).distinct().joinToString(" ")
        titles.forEachIndexed { i, t ->
            val folder = if (i % 3 == 0) "MobileSheets" else if (i % 3 == 1) "Imported/Pack ${i / 30}/$t" else "Scans"
            val k = if (i % 3 == 2) 1 else 2 + rnd.nextInt(5)
            for (j in 0 until k) {
                val name = if (i % 3 == 2) "$t.pdf" else "$t - ${inst[(j + i) % 10]}.pdf"
                val f = File(dir, "$folder/$name"); f.parentFile.mkdirs(); T1.pool.copyTo(f); f.appendBytes(ByteArray(1 + (serial++)) { (it * 31 + serial).toByte() })
            }
        }
        val sheets = installInkSheets { it.setPref("sheets_library", dir.absolutePath) }
        val t0 = System.nanoTime()
        runDesktopComposeUiTest(width = 1600, height = 1000) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
            var firstRows = -1.0; var allSongs = -1.0
            val end = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < end && allSongs < 0) {
                settle(2)
                val ms = (System.nanoTime() - t0) / 1e6
                val lib = sheets().library
                if (lib != null && firstRows < 0 && lib.songs.isNotEmpty()) firstRows = ms
                if (lib != null && lib.songs.size >= 300) allSongs = ms
            }
            say("big UI: first songs visible in the library after ${"%.0f".format(firstRows)} ms; all 300 after ${"%.0f".format(allSongs)} ms (test clock, includes scan)")
            settle(60)
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(shots, "big-home.png"))
            val field = onNode(hasSetTextAction())
            val times = ArrayList<Double>()
            for (c in "starlight") {
                val t = System.nanoTime(); field.performTextInput(c.toString()); settle(3); times += (System.nanoTime() - t) / 1e6
            }
            say("big UI: per-keystroke (typing 'starlight', incl. 3 frames of settle): ${times.map { "%.0f".format(it) }} ms")
            // Sort switches
            for (label in listOf("Recently opened", "Composer", "A to Z")) {
                val t = System.nanoTime(); onAllNodesWithText(label).onFirst().performClick(); settle(3); say("big UI: sort '$label' ${"%.0f".format((System.nanoTime() - t) / 1e6)} ms")
            }
            // Setlists tab with 200 setlists
            val lib = sheets().library!!
            val f = lib.addFolder("Many")
            repeat(150) { lib.addSetlist("Setlist number $it", f.id) }
            sheets().change { }
            val t = System.nanoTime(); onAllNodesWithText("Setlists").onFirst().performClick(); settle(10)
            say("big UI: Setlists tab ${"%.0f".format((System.nanoTime() - t) / 1e6)} ms")
        }
    }

    /** Import a zip dropped on the window (BulkImport) and a single file dialog, photographed. */
    @Test
    fun `r1 dropped zip and mp3`() = dropped("laptop", 1600, 1000)

    @Test
    fun `r1 dropped zip and mp3 phone`() = dropped("phone", 390, 844)

    private fun dropped(tag: String, w: Int, h: Int) {
        val lib = T1.copyLib("drop-$tag")
        val outside = File("build/t1work/drop-outside-$tag").apply { deleteRecursively(); mkdirs() }
        val zip = File(outside, "Pep Band Pack.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            File("../build/t1lib/Imported/PEP BAND/Music/Boogie Down").listFiles()!!.forEach { f ->
                z.putNextEntry(ZipEntry("Pep Band Pack/Game 1/Boogie Down/" + f.name)); z.write(f.readBytes()); z.closeEntry()
            }
            File("../build/t1lib/MobileSheets/Chester.pdf").let { f -> z.putNextEntry(ZipEntry("Pep Band Pack/Game 1/Chester.pdf")); z.write(f.readBytes()); z.closeEntry() }
        }
        val mp3 = File(outside, "Some Recording.mp3").also { it.writeBytes(ByteArray(3000)) }
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }
        runDesktopComposeUiTest(width = w, height = h) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
            settle(40)
            val until = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < until && (sheets().library == null || sheets().library!!.songs.size < 29)) settle(8)
            settle(40)
            val s = sheets()
            var n = 0
            fun shot(name: String) {
                settle(10)
                ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(shots, "$tag-d%02d-%s.png".format(++n, name)))
                val issues = UiAudit.check(this, tag, "d-$name", w, h)
                if (issues.isNotEmpty()) say("AUDIT $tag/$name: ${issues.joinToString(" | ")}")
            }
            // Dropping a recording alone: what is made?
            val before = s.library!!.songs.size
            s.offer(listOf(mp3)); settle(30); shot("mp3-alone")
            onAllNodesWithText("Add").onFirst().performClick(); settle(60)
            val made = s.library!!.songs.firstOrNull { it.title.contains("Some Recording") }
            say("$tag: dropped lone mp3 -> song made=${made?.title} parts=${made?.parts?.size} audio=${made?.audio?.size} (songs $before -> ${s.library!!.songs.size}); clicking it opens: partFor=${made?.let { s.partFor(it) }}")
            // The zip plus a loose pdf at once
            s.offer(listOf(zip, mp3)); settle(60)
            shot("zip-and-mp3-at-once")
            say("$tag: zip + mp3 dropped together: downloadWaiting=${s.downloadWaiting != null} incoming=${s.incoming?.size}")
            s.incoming = null; settle(10)
            shot("zip-review")
            runCatching {
                val end = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < end && onAllNodesWithText("Add", substring = true).fetchSemanticsNodes().isEmpty()) settle(10)
                shot("zip-review-ready")
            }
        }
    }
}
