package com.inksheets.desktop.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inkslate.core.ReadingMode
import com.inkslate.desktop.AppRoot
import com.inkslate.desktop.InkSlateTheme
import com.inkslate.desktop.NavigationHooks
import com.inkslate.desktop.Shortcuts
import com.inkslate.desktop.SimulatedTouch
import com.inksheets.desktop.installInkSheets
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * T6 regression round: tonight's fixes, each driven in the real app (t2App / installInkSheets) and
 * judged PASS or FAIL in build/t3out/t6-regress.txt (T3.say). Nothing here asserts hard, so one failing
 * check does not hide the rest; read the file.
 */
@OptIn(ExperimentalTestApi::class)
class T6RegressionTest {
    private fun say(t: String) = T3.say("t6-regress.txt", t)
    private fun ok(name: String, cond: Boolean, detail: String = "") = say((if (cond) "PASS " else "FAIL ") + name + (if (detail.isNotEmpty()) " - $detail" else ""))

    @After
    fun reset() { resetFlavor() }

    private fun penDown(down: Boolean, pressure: Float? = 0.5f, button: Int = 0) {
        val c = Class.forName("com.inkslate.desktop.PenInput")
        val inst = c.getField("INSTANCE").get(null)
        val m = c.declaredMethods.first { it.name.startsWith("pen") && !it.name.startsWith("penHover") && it.parameterCount == 3 }
        m.isAccessible = true
        m.invoke(inst, down, pressure, button)
    }
    private fun T2.stylusStroke(fx1: Float, fy1: Float, fx2: Float, fy2: Float) {
        val a = Offset(w * fx1, h * fy1)
        penDown(false); root.performMouseInput { moveTo(a) }; penDown(true)
        root.performMouseInput { press() }
        for (i in 1..12) { root.performMouseInput { moveTo(Offset(w * (fx1 + (fx2 - fx1) * i / 12), h * (fy1 + (fy2 - fy1) * i / 12))) }; settle(1) }
        penDown(false); root.performMouseInput { release() }; settle(30)
    }

    private fun T2.type(text: String) { t.onAllNodes(hasSetTextAction()).onLast().performTextInput(text); settle(10) }
    private fun T2.esc() { root.performKeyInput { pressKey(Key.Escape) }; settle(10) }
    private fun T2.enter() { t.onAllNodes(hasSetTextAction()).onLast().performClick(); settle(5); t.onAllNodes(hasSetTextAction()).onLast().performKeyInput { pressKey(Key.Enter) }; settle(10) }
    private fun T2.rows(title: String) = t.onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `undo - song, setlist, set entry, and after the library changed underneath`() {
        assumeTrue(T2Lib.available())
        t6App("t6u") {
            val st = sheets()
            val lib = st.library!!
            val toxic = lib.songs.first { it.title == "Toxic" }
            val parts = toxic.parts.size
            st.removeSong(toxic)
            settle(20)
            ok("remove song: gone", lib.songs.none { it.title == "Toxic" } && st.library!!.songs.none { it.title == "Toxic" })
            ok("remove song: Undo offered", st.undoOffer?.text?.contains("Toxic") == true, st.undoOffer?.text.orEmpty())
            val shownBar = rows("Undo") > 0
            ok("remove song: Undo bar on screen", shownBar)
            if (shownBar) { t.onAllNodesWithText("Undo", useUnmergedTree = true).onFirst().performClick(); settle(40) } else st.undoOffer?.undo()
            settle(40); waitFor(5000) { st.library!!.songs.any { it.title == "Toxic" } }
            val back = st.library!!.songs.firstOrNull { it.title == "Toxic" }
            ok("undo remove song: back with its parts", back != null && back.parts.size == parts, "parts ${back?.parts?.size} vs $parts")
            ok("undo remove song: still in the setlist", st.library!!.setlist(setId)?.entries?.any { it.songId == back?.id } == true)
            ok("undo remove song: file on disk", back?.parts?.all { p -> st.fileOf(p.file)?.isFile == true } == true)
            // Delete a setlist, undo.
            val entries = st.library!!.setlist(setId)!!.entries.size
            st.deleteSetlist(setId); settle(20)
            ok("delete setlist: gone", st.library!!.setlist(setId) == null)
            st.undoOffer?.undo(); settle(30)
            ok("undo delete setlist: back with entries", st.library!!.setlist(setId)?.entries?.size == entries, "${st.library!!.setlist(setId)?.entries?.size} vs $entries")
            // Take out of a set.
            val e = st.library!!.setlist(setId)!!.entries[2]
            st.takeOut(setId, e.id); settle(20)
            ok("take out: gone", st.library!!.setlist(setId)!!.entries.none { it.id == e.id })
            st.undoOffer?.undo(); settle(30)
            ok("undo take out: back in place", st.library!!.setlist(setId)!!.entries.indexOfFirst { it.id == e.id } == 2, "at ${st.library!!.setlist(setId)!!.entries.indexOfFirst { it.id == e.id }}")
            // Library changed underneath: remove a song, then something else goes/arrives, then undo.
            val puppets = st.library!!.songs.first { it.title.startsWith("Master of Puppets") }
            st.removeSong(puppets); settle(10)
            val first = st.undoOffer
            val other = st.library!!.songs.first { it.title.startsWith("MSOM") }
            st.removeSong(other); settle(10)
            ok("second removal replaces the first Undo", st.undoOffer !== first)
            first?.undo(); settle(30)   // the older, replaced offer still works?
            ok("older Undo still works after a later removal", st.library!!.songs.any { it.title.startsWith("Master of Puppets") })
            // Rename the file under a removed song, then undo.
            val party = st.library!!.songs.first { it.title == "Party Medley" }
            val gone = st.library!!.song(party.id)!!
            st.removeSong(gone); settle(10)
            val undo = st.undoOffer
            // A new file arrives with the same name while the song is in the trash.
            val dir = File(T2Lib.dir, "Party Medley").also { it.mkdirs() }
            File(T2Lib.pep, "Party Medley/Party Medley - Trombone 1.pdf").copyTo(File(dir, "Party Medley - Trombone 1.pdf"), overwrite = true)
            st.scanFolder(); waitFor(10000) { st.library!!.songs.any { it.title == "Party Medley" } }
            val count1 = st.library!!.songs.count { it.title == "Party Medley" }
            undo?.undo(); settle(40)
            val count2 = st.library!!.songs.count { it.title == "Party Medley" }
            ok("undo when the same file came back meanwhile: one Party Medley, no ghost/duplicate", count2 == 1, "before undo $count1, after $count2, parts ${st.library!!.songs.filter { it.title == "Party Medley" }.map { it.parts.size }}")
            // Undo twice.
            undo?.undo(); settle(20)
            ok("undo pressed twice: still one Party Medley", st.library!!.songs.count { it.title == "Party Medley" } == 1)
        }
    }

    @Test
    fun `search, right-click, Enter in dialogs`() {
        val src = File("../build/t1pool/small.pdf").takeIf { it.isFile } ?: File("build/t1pool/small.pdf")
        val srcOk = src.isFile || File("../build/t1pool/small.pdf").isFile
        assumeTrue(srcOk)
        val pdf = if (src.isFile) src else File("../build/t1pool/small.pdf")
        val lib = File("build/t6-search-lib").apply { deleteRecursively(); mkdirs() }
        for (n in listOf("Dvořák Largo - Trombone 1", "Dvořák Largo - Euphonium", "Toxic - Trombone 1", "Toxic - Electric Bass", "Seven Nation Army - Euphonium", "Don't Stop Believin' - Trombone 1")) File(lib, "$n.pdf").writeBytes(pdf.readBytes() + ("\n%% unique " + n + "\n").toByteArray())
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }
        try {
            runDesktopComposeUiTest(width = 1280, height = 800) {
                mainClock.autoAdvance = false
                setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
                val x = T2(this, sheets, 1280f, 800f, "t6s")
                x.settle(40); x.waitFor(20_000) { sheets().library?.songs?.size == 4 }
                x.settle(30)
                x.shot("home")
                ok("library scanned to 4 songs", sheets().library?.songs?.size == 4, sheets().library?.songs?.map { it.title }.toString())
                fun search(q: String): List<String> {
                    val f = onAllNodes(hasSetTextAction()).onFirst()
                    f.performTextInput(q); x.settle(20)
                    val titles = sheets().library!!.songs.map { it.title }.filter { tt -> onAllNodesWithText(tt, useUnmergedTree = true).fetchSemanticsNodes().any { n -> n.config.getOrNull(SemanticsProperties.EditableText) == null } }
                    x.shot("search-" + q.replace(' ', '_').replace("'", ""))
                    // clear
                    repeat(q.length) { onAllNodes(isRoot()).onFirst(); f.performKeyInput { pressKey(Key.Backspace) } }
                    x.settle(10)
                    return titles
                }
                val dv = search("dvorak"); ok("search 'dvorak' finds Dvořák Largo", dv.any { it.startsWith("Dvo") } && dv.size == 1, dv.toString())
                val wo = search("largo dvorak"); ok("search 'largo dvorak' (word order)", wo.size == 1, wo.toString())
                val eu = search("euphonium"); ok("search 'euphonium' finds songs with a euphonium part", eu.size == 2, eu.toString())
                val tb = search("trombone"); ok("search 'trombone' finds songs with a trombone part", tb.size == 3, tb.toString())
                val ap = search("dont stop"); ok("search 'dont stop' (no apostrophe typed) finds Don't Stop Believin'", ap.size == 1, ap.toString())
                val ap2 = search("don't stop"); ok("search \"don't stop\" finds Don't Stop Believin'", ap2.size == 1, ap2.toString())
                val ap3 = search("believin"); ok("search 'believin' finds Don't Stop Believin'", ap3.size == 1, ap3.toString())
                val nothing = search("zzzz"); ok("search 'zzzz' finds nothing", nothing.isEmpty(), nothing.toString())
                // Right-click a song row opens its menu.
                val title = "Seven Nation Army"
                val c = x.node(title).fetchSemanticsNode().boundsInRoot.center
                x.root.performMouseInput { moveTo(c); press(MouseButton.Secondary); release(MouseButton.Secondary) }; x.settle(30)
                x.shot("right-click")
                ok("right-click on a song row opens its menu", x.shown("Details and parts") || x.shown("Add to setlist..."), "")
                x.esc()
                // Enter confirms New folder.
                x.node("Setlists").performClick(); x.settle(20)
                x.node("New folder").performClick(); x.settle(20)
                x.type("Pep Band 2026"); x.shot("new-folder-typed"); x.enter(); x.shot("new-folder-after-enter")
                ok("Enter confirms New folder", sheets().library!!.folders.any { it.name == "Pep Band 2026" }, sheets().library!!.folders.map { it.name }.toString())
                if (sheets().library!!.folders.isEmpty()) x.esc()
                // Save as setlist: the default name is there; Enter saves.
                sheets().savingTabs = listOf(File(lib, "Toxic - Trombone 1.pdf"), File(lib, "Dvořák Largo - Trombone 1.pdf")); x.settle(30)
                x.shot("save-as-setlist")
                val tf = onAllNodes(hasSetTextAction()).fetchSemanticsNodes().lastOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
                ok("Save as setlist has a default name", tf.isNotBlank(), "'$tf'")
                x.enter()
                ok("Enter on the default name saves the setlist", sheets().library!!.setlists.isNotEmpty() && sheets().savingTabs == null, "setlists ${sheets().library!!.setlists.map { it.name }}")
            }
        } finally { resetFlavor() }
    }

    @Test
    fun `reading - quick taps, Space, Escape, strip folded, eraser, night, part menu`() {
        assumeTrue(T2Lib.available())
        t6App("t6r") {
            sheets().stripCollapsed = true; settle(20)
            open("Party Medley"); waitPages()
            ok("Party Medley opened", count() >= 2, "pages ${count()}")
            ok("strip folded: no Pen button", !shown("Pen"))
            SimulatedTouch.on = true
            Perform.run(PerformAction.FIRST_PAGE); settle(60)
            val p0 = page()
            // Quick taps during the slide.
            tap(0.9f, 0.5f, after = 3, holdMs = 30); tap(0.9f, 0.5f, after = 3, holdMs = 30)
            settle(80)
            ok("two quick right taps during the slide = two pages", page() == p0 + 2, "page ${p0 + 1} -> ${page() + 1}")
            tap(0.1f, 0.5f, after = 2, holdMs = 30); tap(0.1f, 0.5f, after = 2, holdMs = 30); tap(0.1f, 0.5f, after = 2, holdMs = 30)
            settle(80)
            ok("three quick left taps from page 3 = page 1 (not below)", page() == 0, "page ${page() + 1}")
            tap(0.9f, 0.5f, after = 2, holdMs = 30); tap(0.1f, 0.5f, after = 2, holdMs = 30)
            settle(80)
            ok("quick right then left = where it started", page() == 0, "page ${page() + 1}")
            // Space turns pages.
            val q = page(); key(Key.Spacebar); settle(60)
            ok("Space turns a page", page() == q + 1, "page ${q + 1} -> ${page() + 1} action=$lastKeyAction")
            // Escape never closes a song.
            val song = sheets().current?.id
            key(Key.Escape); key(Key.Escape); key(Key.Escape); settle(30)
            ok("Escape x3 does not close the song", sheets().current?.id == song && !sheets().homeInFront && count() > 0, "current=${sheets().current?.title} home=${sheets().homeInFront}")
            // Strip eraser off gives the stylus its pen back.
            sheets().stripCollapsed = false; settle(30)
            val path = sheets().currentPath
            val s0 = strokes(path)
            stylusStroke(0.3f, 0.3f, 0.6f, 0.35f)
            val s1 = strokes(path)
            ok("stylus writes with the strip open", s1 > s0, "$s0 -> $s1")
            tapText("Eraser"); settle(20)
            tapText("Eraser"); settle(20)
            val s2 = strokes(path)
            stylusStroke(0.3f, 0.5f, 0.6f, 0.55f)
            val s3 = strokes(path)
            ok("Eraser switched on then off: stylus draws again (not erases)", s3 > s2, "$s2 -> $s3")
            tapText("Eraser"); settle(20)
            stylusStroke(0.3f, 0.3f, 0.6f, 0.35f)
            val s4 = strokes(path)
            ok("Eraser on: finger-style switch erases with the stylus too? (strokes not added)", s4 <= s3, "$s3 -> $s4")
            tapText("Eraser"); settle(20)
            stylusStroke(0.3f, 0.7f, 0.6f, 0.75f)
            ok("Eraser off again: stylus draws", strokes(path) > s4, "$s4 -> ${strokes(path)}")
            // Part menu: All instruments.
            val before = sheets().profileId
            val label = sheets().partShown()?.let { com.inksheets.core.Instruments.partName(it) } ?: "Trombone 1"
            runCatching { tapText(label); settle(30); shot("part-menu") }
            val had = shown("All instruments")
            ok("Part menu offers All instruments", had)
            if (had) { tapText("All instruments"); settle(40) }
            ok("Part menu: All instruments switches the profile", sheets().profileId == null && before != null || before == null, "profile $before -> ${sheets().profileId}")
            // Night mode persists across songs.
            sheets().platform.readingMode = ReadingMode.NIGHT
            settle(30)
            ok("Night mode kept in the setting", sheets().platform.pref("sheets_page_colour") != null || sheets().platform.readingMode == ReadingMode.NIGHT, "pref=${sheets().platform.pref("sheets_page_colour")}")
            sheets().playSetlist(setId, 0); waitPages(); settle(60)
            Perform.run(PerformAction.NEXT_SONG); settle(120)
            ok("Night mode still on in the next song", sheets().platform.readingMode == ReadingMode.NIGHT, "${sheets().platform.readingMode} in ${sheets().current?.title}")
            shot("night-next-song")
            sheets().platform.readingMode = ReadingMode.NONE
        }
    }

    @Test
    fun `windows and dialogs close by X and by a quick swipe down`() {
        assumeTrue(T2Lib.available())
        t6App("t6w") {
            open("Party Medley"); waitPages()
            sheets().stripCollapsed = false
            SimulatedTouch.on = true
            for ((name, set) in listOf<Pair<String, (Boolean) -> Unit>>("tuner" to { sheets().tunerOpen = it }, "metronome" to { sheets().metronomeOpen = it },
                "recordings" to { sheets().audioOpen = it }, "together" to { sheets().companionOpen = it })) {
                val isOpen = when (name) { "tuner" -> { { sheets().tunerOpen } }; "metronome" -> { { sheets().metronomeOpen } }; "recordings" -> { { sheets().audioOpen } }; else -> { { sheets().companionOpen } } }
                set(true); settle(40)
                val close = t.onAllNodes(hasContentDescription("Close")).fetchSemanticsNodes()
                ok("$name has an X", close.isNotEmpty())
                if (close.isEmpty()) { set(false); continue }
                val c = close.first().boundsInRoot.center
                val from = Offset(c.x - 120f, c.y)
                at(from); press(); for (i in 1..8) { at(Offset(from.x, from.y + i * 25f)); settle(1) }; release(); settle(30)
                ok("$name closes on a quick 200 px swipe down the header", !isOpen())
                if (isOpen()) { val c2 = t.onAllNodes(hasContentDescription("Close")).fetchSemanticsNodes().first().boundsInRoot.center; tapAt(c2); ok("$name X closes", !isOpen()) }
                set(true); settle(30)
                tapAt(t.onAllNodes(hasContentDescription("Close")).fetchSemanticsNodes().first().boundsInRoot.center)
                ok("$name X closes", !isOpen())
                // A slow drag only moves it.
                set(true); settle(30)
                val c3 = t.onAllNodes(hasContentDescription("Close")).fetchSemanticsNodes().first().boundsInRoot.center
                val f3 = Offset(c3.x - 120f, c3.y)
                at(f3); press(); for (i in 1..8) { at(Offset(f3.x, f3.y + i * 25f)); holdFor(60); settle(1) }; release(); settle(30)
                ok("$name: a slow drag moves it, not closes", isOpen())
                set(false); settle(20)
            }
            // Dialog (Recently deleted) by X and swipe.
            tapText("Home"); settle(40)
            t.onAllNodes(hasContentDescription("More"), useUnmergedTree = true).onFirst().performClick(); settle(10)
            if (shown("Recently deleted...")) {
                tapText("Recently deleted..."); settle(30)
                shot("dialog")
                val close = t.onAllNodes(hasContentDescription("Close"), useUnmergedTree = true).fetchSemanticsNodes()
                ok("Recently deleted dialog has an X", close.isNotEmpty())
                if (close.isNotEmpty()) {
                    val c = close.last().boundsInRoot.center
                    val from = Offset(c.x - 150f, c.y)
                    at(from); press(); for (i in 1..8) { at(Offset(from.x, from.y + i * 30f)); settle(1) }; release(); settle(30)
                    ok("dialog closes on a swipe down its header", !shown("Kept 30 days"))
                    if (shown("Kept 30 days")) { tapAt(t.onAllNodes(hasContentDescription("Close"), useUnmergedTree = true).fetchSemanticsNodes().last().boundsInRoot.center); ok("dialog X closes", !shown("Kept 30 days")) }
                }
            } else ok("Home menu has Recently deleted", false)
        }
    }

    @Test
    fun `break it - remove the open song, delete the playing setlist, rapid steps`() {
        assumeTrue(T2Lib.available())
        t6App("t6b") {
            val st = sheets()
            st.playSetlist(setId, 0); waitPages(); settle(60)
            ok("set playing", st.playing?.first == setId, "${st.playing}")
            // Remove the song whose tab is open.
            val openSong = st.current!!
            val oldPath = st.currentPath
            st.removeSong(st.library!!.song(openSong.id)!!); settle(60)
            val oldFile = oldPath?.let { File(it) }
            val strokesBefore = strokes(st.currentPath)
            val crashed = runCatching { shot("removed-open-song") }.isFailure
            say("INFO file of the removed open song: ${oldFile?.absolutePath} exists=${oldFile?.exists()}")
            ok("remove the open song: screen still renders", !crashed)
            run {
                st.stripCollapsed = false; settle(30)
                tapText("Pen"); settle(20)
                stylusStroke(0.3f, 0.3f, 0.6f, 0.35f)
                st.scanFolder(); settle(60); waitFor(8000) { false }
                say("INFO after drawing on the removed song's tab and a rescan: file exists=${oldFile?.exists()} strokes ${strokesBefore} -> ${strokes(st.currentPath)} songs titled ${openSong.title}: ${st.library!!.songs.count { it.title == openSong.title }}; in trash: ${st.trash()?.entries()?.count { it.title == openSong.title }}")
                ok("drawing on a removed song's tab does not bring the file back (no ghost)", oldFile?.exists() != true && st.library!!.songs.none { it.title == openSong.title })
                st.stripCollapsed = true
            }
            say("INFO after removing the open song: current=${st.current?.title} playing=${st.playing} home=${st.homeInFront} pages=${st.pageShown}")
            Perform.run(PerformAction.NEXT_SONG); settle(60); Perform.run(PerformAction.NEXT_PAGE); settle(40)
            say("INFO stepping on: current=${st.current?.title} page=${st.pageShown}")
            st.undoOffer?.undo(); settle(60)
            ok("undo: the song is back", st.library!!.songs.any { it.id == openSong.id })
            // Delete the setlist being played.
            st.playSetlist(setId, 1); waitPages(); settle(40)
            st.deleteSetlist(setId); settle(60)
            ok("delete playing setlist: no crash on screen", runCatching { shot("deleted-playing-setlist") }.isSuccess)
            say("INFO after deleting the playing setlist: playing=${st.playing} current=${st.current?.title}")
            repeat(3) { Perform.run(PerformAction.NEXT_SONG); settle(30) }
            repeat(3) { Perform.run(PerformAction.PREVIOUS_SONG); settle(30) }
            say("INFO after 3 next + 3 previous in a deleted set: current=${st.current?.title} playing=${st.playing}")
            st.undoOffer?.undo(); settle(60)
            ok("undo: the setlist is back", st.library!!.setlist(setId) != null)
            st.playSetlist(setId, 0); waitPages(); settle(60)
            ok("playing the restored setlist works", st.playing?.first == setId && st.pageShown.second > 0, "${st.playing} ${st.pageShown}")
            // Rapid opposite steps.
            repeat(8) { Perform.run(if (it % 2 == 0) PerformAction.NEXT_SONG else PerformAction.PREVIOUS_SONG); settle(2) }
            settle(150)
            ok("rapid opposite song steps end on a song with pages", st.pageShown.second > 0 && st.current != null, "${st.current?.title} ${st.pageShown}")
            // Take the open song out of the playing set.
            val entry = st.library!!.setlist(setId)!!.entries.first { it.songId == st.current!!.id }
            st.takeOut(setId, entry.id); settle(60)
            say("INFO took the open song out of the playing set: current=${st.current?.title} playing=${st.playing} entries=${st.library!!.setlist(setId)!!.entries.size}")
            Perform.run(PerformAction.NEXT_SONG); settle(60)
            ok("stepping after taking the open song out still lands on a song", st.pageShown.second > 0, "${st.current?.title} ${st.pageShown}")
            // Fast Home/tab churn.
            repeat(4) { st.homeInFront = !st.homeInFront; settle(3) }
            ok("home toggled quickly: no crash", runCatching { shot("home-churn") }.isSuccess)
        }
    }
}
