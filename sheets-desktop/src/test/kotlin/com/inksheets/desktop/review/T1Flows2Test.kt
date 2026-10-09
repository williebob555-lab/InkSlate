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
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inkslate.desktop.AppFlavor
import com.inkslate.desktop.AppRoot
import com.inkslate.desktop.InkSlateTheme
import com.inkslate.desktop.NavigationHooks
import com.inkslate.desktop.Shortcuts
import com.inksheets.core.Bookmark
import com.inksheets.desktop.UiAudit
import com.inksheets.desktop.installInkSheets
import com.inksheets.desktop.review.T1.say
import org.junit.After
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** T1 round 2/3 in the real Home: bookmarks, reorder by hold-drag, long names, empty library, a backup card, live folder changes. */
@OptIn(ExperimentalTestApi::class)
class T1Flows2Test {
    private val shots = File("build/t1shots").also { it.mkdirs() }

    @After
    fun reset() {
        AppFlavor.home = null; AppFlavor.paneOverlay = null; AppFlavor.onHomeShown = null; AppFlavor.onHome = null; AppFlavor.settingsSection = null
        AppFlavor.musicView = false; AppFlavor.fingerPans = false; AppFlavor.alwaysFullscreen = false
    }

    private fun DesktopComposeUiTest.settle(frames: Int = 12) = repeat(frames) { mainClock.advanceTimeBy(16); Thread.sleep(4) }

    private fun run(tag: String, w: Int, h: Int, prepare: (File) -> Unit = {}, body: DesktopComposeUiTest.(com.inksheets.ui.SheetsState, File, (String) -> Unit, (String, () -> Unit) -> Unit) -> Unit) {
        val lib = T1.copyLib("f2-$tag")
        prepare(lib)
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }
        runDesktopComposeUiTest(width = w, height = h) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
            settle(40)
            val until = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < until && (sheets().library == null || sheets().library!!.songs.size < 29)) settle(8)
            settle(60)
            var n = 0
            val shot = { name: String ->
                settle(10)
                ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(shots, "$tag-g%02d-%s.png".format(++n, name)))
                val issues = UiAudit.check(this, tag, "g-$name", w, h)
                if (issues.isNotEmpty()) say("AUDIT $tag/$name: ${issues.joinToString(" | ").take(500)}")
            }
            val scene = { name: String, block: () -> Unit ->
                runCatching(block).onFailure { say("SCENE $tag/$name FAILED: ${it.toString().replace('\n', ' ').take(300)}") }
                Unit
            }
            body(sheets(), lib, shot, scene)
        }
    }

    private fun DesktopComposeUiTest.has(text: String, sub: Boolean = true) = onAllNodesWithText(text, substring = sub).fetchSemanticsNodes().isNotEmpty()
    private fun DesktopComposeUiTest.click(text: String, i: Int = 0, sub: Boolean = false) { onAllNodesWithText(text, substring = sub)[i].performClick(); settle(10) }

    @Test fun `bookmarks laptop`() = bookmarks("laptop", 1600, 1000)
    @Test fun `bookmarks phone`() = bookmarks("phone", 390, 844)

    private fun bookmarks(tag: String, w: Int, h: Int) = run(tag + "-bm", w, h) { s, _, shot, scene ->
        val lib = s.library!!
        val songs = lib.songs.filter { it.parts.isNotEmpty() }.take(5)
        songs.forEachIndexed { i, song ->
            lib.editSong(song.id) { bookmarks = listOf(Bookmark("Coda $i", song.parts.first().id, 2 + i, color = if (i % 2 == 0) 0xFFE53935.toInt() else null), Bookmark("Solo", song.parts.first().id, 1)) }
        }
        s.change { }; settle(30)
        scene("bookmarks tab") {
            click("Bookmarks"); shot("bookmarks")
            for (label in listOf("Title", "Recent", "Colour")) { runCatching { click(label); shot("bm-sort-" + label.lowercase()) } }
        }
        scene("bookmarks hold-drag reorder") {
            click("Manual", 0)
            val before = lib.songs.flatMap { it.bookmarks }.map { it.label }
            // drag the third row up over the first by a held finger
            val rows = onAllNodesWithText("Solo", substring = true)
            val third = rows[2].fetchSemanticsNode().boundsInRoot.center
            val first = rows[0].fetchSemanticsNode().boundsInRoot.center
            onAllNodes(isRoot()).onFirst().performTouchInput { down(third) }
            mainClock.advanceTimeBy(500); settle(5)
            onAllNodes(isRoot()).onFirst().performTouchInput { moveTo(Offset(third.x, (third.y + first.y) / 2)); moveTo(Offset(third.x, first.y - 10f)) }
            settle(10)
            onAllNodes(isRoot()).onFirst().performTouchInput { up() }
            settle(20); shot("bm-after-drag")
            say("$tag: bookmark order after hold-drag of 3rd row to top changed=${before != s.library!!.songs.flatMap { it.bookmarks }.map { it.label }}")
        }
    }

    @Test fun `setlist reorder laptop`() = reorder("laptop", 1600, 1000)
    @Test fun `setlist reorder phone`() = reorder("phone", 390, 844)

    private fun reorder(tag: String, w: Int, h: Int) = run(tag + "-ro", w, h) { s, _, shot, scene ->
        val lib = s.library!!
        val set = lib.addSetlist("Game 1")
        val songs = lib.songs.take(8)
        songs.forEach { lib.addToSetlist(set.id, it.id) }
        s.change { }; settle(10)
        scene("open set") { click("Setlists"); click("Game 1"); shot("set-open") }
        scene("hold-drag") {
            val titles = songs.map { it.title }
            val before = lib.setlist(set.id)!!.entries.map { lib.song(it.songId)!!.title }
            val a = onAllNodesWithText(titles[4], substring = false)[0].fetchSemanticsNode().boundsInRoot.center
            val b = onAllNodesWithText(titles[1], substring = false)[0].fetchSemanticsNode().boundsInRoot.center
            onAllNodes(isRoot()).onFirst().performTouchInput { down(a) }
            // too short a hold: should scroll/tap, not lift
            mainClock.advanceTimeBy(500); settle(5)
            onAllNodes(isRoot()).onFirst().performTouchInput { moveTo(Offset(a.x, (a.y + b.y) / 2)); moveTo(Offset(a.x, b.y - 8f)) }
            settle(10); shot("set-lifted")
            onAllNodes(isRoot()).onFirst().performTouchInput { up() }
            settle(30); shot("set-dropped")
            val after = lib.setlist(set.id)!!.entries.map { lib.song(it.songId)!!.title }
            say("$tag: set reorder by hold-drag: before=${before.take(5)} after=${after.take(5)} changed=${before != after}")
        }
        scene("quick swipe must not lift") {
            val before = lib.setlist(set.id)!!.entries.map { it.id }
            onAllNodes(isRoot()).onFirst().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.3f, durationMillis = 120) }
            settle(20)
            say("$tag: a quick swipe leaves order alone = ${before == lib.setlist(set.id)!!.entries.map { it.id }}")
        }
        scene("right click on a song row") {
            click("Songs"); settle(10)
            val c = onAllNodesWithText("17")[0].fetchSemanticsNode().boundsInRoot.center
            onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(c); rightClick() }
            settle(20); shot("right-click-row")
            say("$tag: right-click on a Songs row opens a menu = ${has("Details and parts")}")
        }
        scene("delete setlist has no undo") {
            click("Setlists"); settle(10)
            val before = lib.setlists.size
            onAllNodesWithContentDescription("Options")[0].performClick(); settle(10); shot("setlist-menu")
            click("Delete"); settle(20)
            say("$tag: setlist Delete: setlists $before -> ${lib.setlists.size}; confirm asked=${has("Delete Game 1", true)}; Undo shown=${has("Undo")}")
            shot("after-setlist-delete")
        }
    }

    @Test fun `long names laptop`() = longNames("laptop", 1600, 1000)
    @Test fun `long names phone`() = longNames("phone", 390, 844)

    private fun longNames(tag: String, w: Int, h: Int) = run(tag + "-ln", w, h) { s, _, shot, scene ->
        val lib = s.library!!
        val a = lib.songs.first { it.title == "Chester" }
        lib.editSong(a.id) { title = "Concerto for Trombone and Wind Ensemble in B-flat Major, Op. 99, No. 7 (Arranged for Pep Band) – Revised Edition 第一番"; composers = listOf("Nikolai Andreyevich Rimsky-Korsakov", "Wolfgang Amadeus Mozart"); notes = "Watch the director on the second repeat and play the cutoff after the fermata; trombones double the euphonium line at bar 42 only when the soloist rests." ; key = "Bb"; tempo = 132 }
        val b = lib.songs.first { it.title == "Colonial Song" }
        lib.editSong(b.id) { notes = "short note" }
        s.change { }; settle(30)
        scene("long title row") { onNode(hasSetTextAction()).performTextInput("Concerto"); settle(20) }
        scene("shot") { shot("long-names") }
        scene("details") { onAllNodesWithContentDescription("Song options")[0].performClick(); settle(5); shot("long-menu") }
    }

    @Test fun `empty library and welcome`() {
        run("empty", 390, 844, prepare = { root -> root.listFiles()!!.forEach { it.deleteRecursively() } }) { s, _, shot, scene ->
            scene("empty") { shot("empty-songs"); click("Setlists"); shot("empty-setlists") }
            scene("add music empty") { onAllNodesWithContentDescription("Add music")[0].performClick(); settle(10); shot("empty-add-music") }
        }
    }

    @Test
    fun `backup card`() = run("card", 1000, 700, prepare = { root ->
        File(root, "Old Backup.msb").writeBytes(ByteArray(1000) { 3 })
    }) { s, lib, shot, scene ->
        scene("backup card") {
            shot("backup-card")
            say("live: backup card shown=${has("Found a MobileSheets backup")}")
            click("Import it"); settle(60); shot("backup-fail")
            say("live: fake .msb import outcome text: ${if (has("could not be read")) "error shown" else "no error text"}; Close/Done offered=${has("Close", false)}")
            runCatching { click("Close") }
            say("live: after Close, scan paused counter importing=${s.importing}")
        }
    }

    @Test
    fun `live folder changes`() = run("live", 1000, 700) { s, lib, shot, scene ->
        scene("live add rename delete") {
            // A new file arrives underneath while Home is shown. The watcher scans every ~10 s of real time.
            val file = File(lib, "MobileSheets/Live Tune - Trombone 1.pdf")
            File(lib, "MobileSheets/Chester.pdf").copyTo(file); file.appendBytes(ByteArray(333) { 9 })
            val t0 = System.currentTimeMillis()
            var seen = -1L
            while (System.currentTimeMillis() - t0 < 14_000 && seen < 0) { settle(25); if (s.library!!.songs.any { it.title.contains("Live Tune") }) seen = System.currentTimeMillis() - t0 }
            say("live: new file shown on Home after $seen ms (-1 = not within 14 s); library has it=${s.library!!.songs.any { it.title.contains("Live Tune") }} importing=${s.importing} lastScan.added=${s.lastScan?.added?.size} version=${s.version} libVersion=${s.library!!.version}")
            shot("live-added")
            file.renameTo(File(lib, "MobileSheets/Livelier Tune - Trombone 1.pdf"))
            val t1 = System.currentTimeMillis(); var seen2 = -1L
            while (System.currentTimeMillis() - t1 < 14_000 && seen2 < 0) { settle(25); if (s.library!!.songs.any { it.title.contains("Livelier") }) seen2 = System.currentTimeMillis() - t1 }
            say("live: rename shown after $seen2 ms; old title still shown=${has("Live Tune")}")
            File(lib, "MobileSheets/Livelier Tune - Trombone 1.pdf").delete()
            val t2 = System.currentTimeMillis(); var seen3 = -1L
            while (System.currentTimeMillis() - t2 < 14_000 && seen3 < 0) { settle(25); if (s.library!!.songs.none { it.title.contains("Livelier") }) seen3 = System.currentTimeMillis() - t2 }
            say("live: delete shown after $seen3 ms")
            shot("live-after-delete")
        }
    }

    @Test
    fun `save open tabs and instruments dialogs`() = run("misc", 390, 844) { s, lib, shot, scene ->
        scene("save tabs") {
            s.savingTabs = listOf(File(lib, "MobileSheets/Chester.pdf"), File(lib, "MobileSheets/Chesapeake.pdf"), File(lib, "Scans/6 35 PM Wed Sep 30.pdf"), File(lib, "MobileSheets/Chester.pdf"))
            settle(20); shot("save-tabs")
            onAllNodes(hasSetTextAction()).onLast().performTextInput("Tabs set"); settle(10)
            onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Enter) }; settle(10)
            say("misc: Enter in the Save-as-setlist name field confirms = ${s.library!!.setlists.isNotEmpty()}")
            if (s.library!!.setlists.isEmpty()) click("Save"); settle(20)
            val made = s.library!!.setlists.firstOrNull()
            say("misc: save-as-setlist from 4 tabs (one a duplicate, one a scan): setlist='${made?.name}' entries=${made?.entries?.size}")
        }
        scene("instruments") {
            s.savingTabs = null; settle(10); click("All instruments"); settle(10); shot("instr-menu"); click("Edit instruments..."); shot("profiles"); click("All instruments..."); shot("all-instruments")
            click("Add an instrument"); shot("add-instrument")
        }
    }
}
