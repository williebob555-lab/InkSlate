package com.inksheets.desktop.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.IntSize
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inkslate.desktop.SimulatedTouch
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Part switch, bookmarks, tabs and split, night mode, resizing, panels. */
@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class T2Round5Test {
    private fun say(s: String) = println("T2 $s")
    private fun T2.texts(vararg t: String) = t.filter { shown(it) }

    @Test
    fun `T2 r5 part menu, bookmarks, panels, night mode`() {
        assumeTrue(T2Lib.available())
        t2App("r5a") {
            sheets().stripCollapsed = false
            open("Party Medley"); waitPages(); Perform.run(PerformAction.FIRST_PAGE); settle(40)
            SimulatedTouch.on = true
            // Part button, named for the part showing.
            say("Part button label 'Trombone 1' shown: ${shown("Trombone 1")}; part shown = ${sheets().partShown()?.file}")
            tapText("Trombone 1"); settle(30); shot("part-menu")
            say("part menu items: ${texts("This song only", "Find a part", "Trombone 1", "Trombone 2", "Trombone 1 - Party Medley - Trombone 1", "Euphonium", "Full Score", "All songs", "Trombone", "Baritone", "Euphonium / Baritone", "All instruments", "Select instrument...", "Back to Trombone's part")}")
            // Pick Euphonium for this song only.
            val pathBefore = sheets().currentPath
            val tm = timed(15000) { tapText("Euphonium") }
            settle(60); shot("part-switched-euph")
            say("switch to Euphonium (this song): $tm; path ${pathBefore?.substringAfterLast('\\')} -> ${sheets().currentPath?.substringAfterLast('\\')}, page ${page() + 1}/${count()}, strip label now: ${texts("Euphonium", "Trombone 1")}")
            // Same part switch to Full Score
            tapText("Euphonium"); settle(30)
            say("menu again: has 'Back to Trombone's part'? ${shown("Back to Trombone's part")} / any 'Back to' ${t.onAllNodesWithText("Back to", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size}")
            tapText("Full Score"); settle(80); shot("part-full-score")
            say("Full Score: page ${page() + 1}/${count()} path ${sheets().currentPath?.substringAfterLast('\\')}")
            // Back
            Perform.run(PerformAction.SWITCH_PART); settle(10)
            sheets().clearThisSong(); settle(80)
            say("cleared the pick: path ${sheets().currentPath?.substringAfterLast('\\')}")
            // Bookmarks
            Perform.run(PerformAction.NEXT_PAGE); settle(40)
            val on = sheets().toggleBookmark(); settle(20)
            say("bookmark page ${page() + 1}: toggled on=$on; bookmarkHere=${sheets().bookmarkHere()?.let { "yes" }}; strip lit label 'Bookmark' shown=${shown("Bookmark")}")
            shot("bookmarked")
            Perform.run(PerformAction.NEXT_PAGE); settle(40)
            say("next page: bookmarkHere=${sheets().bookmarkHere()?.let { "yes" } ?: "no"}")
            // Metronome panel: X and swipe down.
            tapText("Metronome"); settle(40); shot("metronome-panel")
            say("metronome panel open: ${sheets().metronomeOpen}; texts: ${texts("Metronome", "Close")}")
            // Drag the panel title downwards 150px.
            val title = t.onAllNodesWithText("Metronome", useUnmergedTree = true).fetchSemanticsNodes()
            say("Metronome nodes: ${title.size} " + title.joinToString { "${it.boundsInRoot.left.toInt()},${it.boundsInRoot.top.toInt()}" })
            run {
                val n = title.maxByOrNull { it.boundsInRoot.top }!!
                val c = n.boundsInRoot.center
                at(c); press(); for (i in 1..8) { at(Offset(c.x, c.y + i * 25f)); settle(1) }; release(); settle(30)
                say("after a swipe down from the panel title: metronomeOpen=${sheets().metronomeOpen}"); shot("metronome-after-swipe-down")
            }
            sheets().metronomeOpen = false; settle(10)
            // Tuner
            tapText("Tuner"); settle(40); shot("tuner-panel")
            say("tuner open: ${sheets().tunerOpen}")
            sheets().tunerOpen = false; settle(10)
            // Together
            tapText("Together"); settle(40); shot("together-panel")
            sheets().companionOpen = false; settle(10)
            // Recordings
            tapText("Recordings"); settle(40); shot("recordings-panel")
            sheets().audioOpen = false; settle(10)
            // More menu
            tapText("More"); settle(30); shot("more-menu")
            say("More menu: ${texts("Switch part", "Fit the page to the screen", "Next song", "Previous song", "Tuner", "Recordings...", "Play together (lead or follow)...", "Notes...", "Reminder...", "Clear all markings on this part...", "Show in a window", "Cover the whole screen", "Buttons on the strip...", "Customise the buttons...")}")
            // All tools -> top bar menu -> night mode
            sheets().stripCollapsed = true
            tap(0.5f, 0.92f); settle(40); shot("all-tools")
            val dots = t.onAllNodesWithText("Party Medley - Trombone 1.pdf", useUnmergedTree = true).fetchSemanticsNodes()
            say("top bar title present: ${dots.size}")
        }
    }

    @Test
    fun `T2 r5 tabs, split, set tabs, Home`() {
        assumeTrue(T2Lib.available())
        t2App("r5t") {
            sheets().stripCollapsed = true
            open("Toxic"); waitPages()
            SimulatedTouch.on = true
            shot("toxic")
            say("tab X shown for a song opened from the list: ${t.onAllNodesWithText("", useUnmergedTree = true).fetchSemanticsNodes().size >= 0}")
            val closeBtn = t.onAllNodes(androidx.compose.ui.test.hasContentDescription("Close Toxic - Trombone 1.pdf")).fetchSemanticsNodes().size
            say("X buttons for Toxic tab: $closeBtn")
            // Now a set
            sheets().playSetlist(setId, 0); waitPages(); settle(60); shot("set-tabs")
            val xs = t.onAllNodes(androidx.compose.ui.test.hasContentDescription("Close", substring = true)).fetchSemanticsNodes().size
            say("after opening the set: current ${sheets().current?.title}; tab X buttons: $xs; open files ${sheets().openFiles.size}; tabs are: " + listOf("Party Medley", "24K Magic", "Toxic", "Master of Puppets", "MSOM Shorts").filter { shown(it) })
            // Right-click a set tab: close offered?
            val tabNode = t.onAllNodesWithText("24K Magic", useUnmergedTree = true).onFirst()
            val c = tabNode.fetchSemanticsNode().boundsInRoot.center
            root.performMouseInput { moveTo(c); press(androidx.compose.ui.test.MouseButton.Secondary); release(androidx.compose.ui.test.MouseButton.Secondary) }; settle(30); shot("set-tab-context-menu")
            say("right-click on a set tab menu: ${texts("Close", "Close other tabs", "Close all tabs", "Open to the side", "Close split view")} / side: " + t.onAllNodesWithText("side", substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().size)
            // Choose Close in the context menu: what happens to the set?
            val before = sheets().openFiles.size
            val cl = t.onAllNodesWithText("Close", useUnmergedTree = true).fetchSemanticsNodes()
            say("Close entries: ${cl.size}")
            tapText("Close"); settle(60)
            say("after Close on a set tab: open files $before -> ${sheets().openFiles.size}; playing=${sheets().playing}; current ${sheets().current?.title}")
            shot("after-closing-set-tab")
            // Step with the hole: next song from song 1
            Perform.run(PerformAction.FIRST_PAGE)
            // Home: closes all tabs?
            tapText("Home"); settle(60); shot("home-from-set")
            say("after tapping Home: open files ${sheets().openFiles.size}; playing=${sheets().playing}; homeInFront=${sheets().homeInFront}")
        }
    }

    @Test
    fun `T2 r5 resize mid-song`() {
        assumeTrue(T2Lib.available())
        t2App("r5r") {
            sheets().stripCollapsed = true
            open("Toxic"); waitPages()
            SimulatedTouch.on = true
            shot("1600x1000")
            val sizes = listOf(1100 to 700, 700 to 1000, 390 to 844, 844 to 390, 1600 to 1000)
            for ((sw, sh) in sizes) {
                try {
                    t.scene.size = IntSize(sw, sh)
                } catch (e: Throwable) { say("scene.size failed: $e"); break }
                settle(60); shot("resized-${sw}x$sh")
                val m = music(image(1)); say("resized to ${sw}x$sh: music fraction ${"%.3f".format(m)} page ${page() + 1}")
            }
        }
    }
}
