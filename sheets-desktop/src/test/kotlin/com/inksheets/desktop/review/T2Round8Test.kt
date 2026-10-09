package com.inksheets.desktop.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performMouseInput
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inkslate.desktop.SimulatedTouch
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Focused repros that came out of rounds 1-7. */
@OptIn(ExperimentalTestApi::class)
class T2Round8Test {
    private fun say(s: String) = println("T2 $s")
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

    @Test
    fun `T2 r8 eraser trap, vertical drag at zoom`() {
        assumeTrue(T2Lib.available())
        t2App("r8a") {
            sheets().stripCollapsed = false
            open("Toxic"); waitPages()
            SimulatedTouch.on = true
            val path = sheets().currentPath
            stylusStroke(0.3f, 0.3f, 0.6f, 0.35f)
            say("stylus stroke #1 (fresh): strokes=${strokes(path)}")
            tapText("Eraser"); settle(20)
            tapText("Eraser"); settle(20)
            say("Eraser switched on then off again. Strip lit state: labels Pen/Eraser shown ${listOf("Pen", "Eraser").filter { shown(it) }}")
            shot("after-eraser-on-off")
            val b = strokes(path)
            stylusStroke(0.3f, 0.5f, 0.6f, 0.55f)
            say("stylus stroke #2 after Eraser on/off: strokes $b -> ${strokes(path)} (0 added = the stylus is still an eraser)")
            tapText("Pen"); settle(20); tapText("Pen"); settle(20)
            val c = strokes(path)
            stylusStroke(0.3f, 0.6f, 0.6f, 0.65f)
            say("stylus stroke #3 after Pen on/off: strokes $c -> ${strokes(path)}")
            // Finger drag with pen switch off: no mark. Eraser switch on: finger erases the first stroke?
            tapText("Eraser"); settle(20)
            val d = strokes(path)
            drag(0.25f, 0.325f, 0.65f, 0.325f, 12)
            say("Eraser switch on, finger across the first stroke: strokes $d -> ${strokes(path)}")
            tapText("Eraser"); settle(20)
            // Zoom and vertical drag, tools away
            sheets().stripCollapsed = true; settle(20)
            pinch(60f, 300f); settle(20)
            val a1 = image(); shot("zoomed")
            drag(0.5f, 0.75f, 0.5f, 0.35f, 8)
            val a2 = image(); shot("zoomed-after-vertical-drag")
            say("zoomed, tools away, one finger up 40% of the height: picture unchanged = ${sameImage(a1, a2)}")
            drag(0.7f, 0.5f, 0.3f, 0.5f, 8)
            val a3 = image()
            say("zoomed, tools away, sideways flick: picture unchanged = ${sameImage(a2, a3)} page ${page() + 1}")
            twoFingerPan(0f, -250f)
            val a4 = image()
            say("two-finger pan up: picture unchanged = ${sameImage(a3, a4)}")
        }
    }

    @Test
    fun `T2 r8 part switch timing, bookmark, panel swipe, set tab close`() {
        assumeTrue(T2Lib.available())
        t2App("r8b") {
            sheets().stripCollapsed = false
            open("Party Medley"); waitPages(); Perform.run(PerformAction.FIRST_PAGE); settle(40)
            SimulatedTouch.on = true
            // Part switch timing: the tap on the menu item, then the film.
            tapText("Trombone 1"); settle(30)
            val f = film(150) { tapAt(t.onAllNodesWithText("Euphonium", useUnmergedTree = true).fetchSemanticsNodes().maxByOrNull { it.boundsInRoot.top }!!.boundsInRoot.center, after = 0) }
            say("part switch Trombone 1 -> Euphonium (this song only): $f")
            say("   -> path ${sheets().currentPath?.substringAfterLast('\\')} strip buttons: ${listOf("Euphonium", "Trombone 1").filter { shown(it) }}")
            tapText("Euphonium"); settle(30); shot("part-menu-after-pick")
            say("menu after a pick: items ${listOf("This song only", "Back to Trombone's part", "All songs").filter { shown(it) }}")
            val n = t.onAllNodesWithText("Back to", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size
            say("  'Back to ...' entries: $n")
            sheets().partPicker = false; settle(20)
            // Bookmark via the strip
            sheets().clearThisSong(); settle(60)
            Perform.run(PerformAction.NEXT_PAGE); settle(40)
            say("before bookmark: bookmarkHere=${sheets().bookmarkHere() != null}")
            tapText("Bookmark"); settle(30)
            say("strip Bookmark tapped on page ${page() + 1}: bookmarkHere=${sheets().bookmarkHere() != null}; any 'Bookmark' confirmation text: ${t.onAllNodesWithText("ookmark", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size} nodes"); shot("bookmark-on")
            Perform.run(PerformAction.NEXT_PAGE); settle(40)
            say("next page bookmarkHere=${sheets().bookmarkHere() != null}")
            Perform.run(PerformAction.PREVIOUS_PAGE); settle(40)
            tapText("Bookmark"); settle(30)
            say("tap again on the bookmarked page: bookmarkHere=${sheets().bookmarkHere() != null} (removes it with no undo/confirm)")
            // Tuner panel: header swipe down and X
            sheets().tunerOpen = true; settle(40); shot("tuner")
            val closeBtns = t.onAllNodes(hasContentDescription("Close")).fetchSemanticsNodes()
            say("Tuner panel: Close buttons found ${closeBtns.size}; X at ${closeBtns.joinToString { "${it.boundsInRoot.center.x.toInt()},${it.boundsInRoot.center.y.toInt()}" }}")
            run {
                val c = closeBtns.first().boundsInRoot.center
                val from = Offset(c.x - 150f, c.y)
                at(from); press(); for (i in 1..8) { at(Offset(from.x, from.y + i * 25f)); settle(1) }; release(); settle(30)
                say("swipe down 200px on the Tuner header: tunerOpen=${sheets().tunerOpen} (spec: swipe-down closes every panel)")
                shot("tuner-after-swipe")
            }
            sheets().tunerOpen = true; settle(20)
            run {
                val c = t.onAllNodes(hasContentDescription("Close")).fetchSemanticsNodes().first().boundsInRoot.center
                tapAt(c); say("tap X: tunerOpen=${sheets().tunerOpen}")
            }
            // Set tab closed from its menu, then stepping
            sheets().playSetlist(setId, 0); waitPages(); settle(60)
            val tab = t.onAllNodesWithText("24K Magic", useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.center
            root.performMouseInput { moveTo(tab); press(androidx.compose.ui.test.MouseButton.Secondary); release(androidx.compose.ui.test.MouseButton.Secondary) }; settle(30)
            tapText("Close"); settle(60)
            say("closed the 24K Magic tab of the set: tabs left: " + listOf("Party Medley", "24K Magic", "Toxic", "Master of Puppets", "MSOM Shorts").filter { shown(it) } + "; playing=${sheets().playing?.second}")
            say("NEXT_SONG from song 1: " + film(150) { Perform.run(PerformAction.NEXT_SONG) })
            say("   tabs now: " + listOf("Party Medley", "24K Magic", "Toxic", "Master of Puppets", "MSOM Shorts").filter { shown(it) })
            shot("after-step-past-closed-tab")
        }
    }

    @Test
    fun `T2 r8 night mode, crop and the tools bar`() {
        assumeTrue(T2Lib.available())
        t2App("r8c") {
            sheets().stripCollapsed = true
            sheets().playSetlist(setId, 0); waitPages(); SimulatedTouch.on = true
            // All tools -> the app bar's menu
            tap(0.5f, 0.93f); settle(40)
            val dots = t.onAllNodes(hasContentDescription("More", substring = true, ignoreCase = true)).fetchSemanticsNodes()
            say("All tools open. 'More' buttons: ${dots.size} at " + dots.joinToString { "${it.boundsInRoot.center.x.toInt()},${it.boundsInRoot.center.y.toInt()}" })
            val top = dots.filter { it.boundsInRoot.center.y < 120f }
            if (top.isNotEmpty()) {
                tapAt(top.last().boundsInRoot.center); settle(30); shot("app-bar-menu")
                say("app bar menu items: ${listOf("Night", "Sepia", "Greyscale", "High contrast", "Normal", "Fit page", "Fit width", "Reset view", "Clear this page").filter { shown(it) || shown("✓  $it") || shown("      $it") }}")
                tapText("      Night"); settle(30)
                tap(0.5f, 0.4f); settle(40); shot("night-song1")
                say("night mode on song 1 (tools put away): paper white fraction ${"%.2f".format(paper(image(1)))}")
                Perform.run(PerformAction.NEXT_SONG); settle(120); shot("night-song2")
                say("song 2 after night on song 1: paper white fraction ${"%.2f".format(paper(image(1)))} (high = back to normal)")
                Perform.run(PerformAction.PREVIOUS_SONG); settle(120)
                say("back on song 1: paper white fraction ${"%.2f".format(paper(image(1)))}")
            } else say("no top-bar menu found")
        }
    }
}
