package com.inksheets.desktop.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performMouseInput
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inkslate.desktop.SimulatedTouch
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Round 1: reading a song the obvious way, with fingers, then mouse, keys and the strip. */
@OptIn(ExperimentalTestApi::class)
class T2Round1Test {

    private fun say(s: String) = println("T2 $s")
    private fun T2.goto(n: Int) { Perform.run(PerformAction.FIRST_PAGE); settle(40); repeat(n) { Perform.run(PerformAction.NEXT_PAGE); settle(30) }; settle(60) }
    private fun T2.probe(name: String, from: Int = 1, act: () -> Unit) { goto(from); val p0 = page(); act(); settle(40); say("$name: page ${p0 + 1} -> ${page() + 1}") }

    @Test
    fun `T2 r1 fingers - thirds, flicks, strip, all tools`() {
        assumeTrue(T2Lib.available())
        t2App("r1f") {
            sheets().stripCollapsed = false
            open("Party Medley"); waitPages()
            SimulatedTouch.on = true
            goto(0)
            say("opened: page ${page() + 1}/${count()} strip labels: ${listOf("Pen", "Highlight", "Eraser", "Undo", "Metronome", "Tuner", "Together", "Bookmark", "All tools", "Recordings", "More", "Pages").filter { shown(it) }}")
            shot("opened")
            probe("tap right third (x=.93)") { tap(0.93f, 0.5f) }
            probe("tap left third (x=.07)") { tap(0.07f, 0.5f) }
            probe("tap just inside right third (x=.68)") { tap(0.68f, 0.5f) }
            probe("tap just outside right third (x=.62) = centre") { tap(0.62f, 0.5f) }
            sheets().stripCollapsed = false; settle(30)
            probe("tap just inside left third (x=.30) strip lane right") { tap(0.30f, 0.5f) }
            probe("tap on the Pen button") { tapText("Pen"); tapText("Pen") }
            probe("tap on the page number (1/4 Pages)") { tapText("Pages") }
            shot("pages-button")
            // Wiggle, hold.
            fun wiggle(dpx: Float) = probe("wiggle ${dpx}px during a right-third tap") { at(Offset(w * 0.8f, h * 0.5f)); press(); holdFor(40); at(Offset(w * 0.8f + dpx, h * 0.5f + dpx / 2)); settle(1); release() }
            wiggle(6f); wiggle(10f); wiggle(14f); wiggle(24f)
            probe("slow tap (press held 640 ms)") { tapAt(Offset(w * 0.8f, h * 0.5f), holdMs = 640) }
            probe("tap held 250 ms") { tapAt(Offset(w * 0.8f, h * 0.5f), holdMs = 250) }
            probe("tap held 400 ms") { tapAt(Offset(w * 0.8f, h * 0.5f), holdMs = 400) }
            // Flicks.
            say("flick left timing: " + run { goto(1); timed { drag(0.7f, 0.5f, 0.25f, 0.5f, 6, after = 0) } })
            say("flick right timing: " + run { goto(1); timed { drag(0.25f, 0.5f, 0.7f, 0.5f, 6, after = 0) } })
            probe("short quick flick 40px left") { drag(0.52f, 0.5f, 0.52f - 40f / w, 0.5f, 3) }
            probe("short quick flick 70px left") { drag(0.52f, 0.5f, 0.52f - 70f / w, 0.5f, 3) }
            probe("slow drag 40% left over 3 s") { drag(0.7f, 0.5f, 0.3f, 0.5f, 30, perFrame = 8) }
            probe("slow drag 12% left over 3 s") { drag(0.6f, 0.5f, 0.48f, 0.5f, 30, perFrame = 8) }
            probe("slow drag 17% left over 3 s") { drag(0.6f, 0.5f, 0.43f, 0.5f, 30, perFrame = 8) }
            probe("diagonal flick (dx 300, dy 250)") { drag(0.7f, 0.4f, 0.7f - 300f / w, 0.4f + 250f / h, 6) }
            probe("diagonal flick (dx 300, dy 150)") { drag(0.7f, 0.4f, 0.7f - 300f / w, 0.4f + 150f / h, 6) }
            probe("mostly vertical flick (dx 120, dy 400)") { drag(0.7f, 0.3f, 0.7f - 120f / w, 0.3f + 400f / h, 6) }
            probe("flick starting at the strip (x=.97) leftwards") { drag(0.97f, 0.5f, 0.6f, 0.5f, 6) }
            probe("flick left then back right without lifting (dx -300 then +300)") { at(Offset(w * 0.7f, h * 0.5f)); press(); for (i in 1..6) { at(Offset(w * 0.7f - i * 50f, h * 0.5f)); settle(1) }; for (i in 1..6) { at(Offset(w * 0.7f - 300f + i * 50f, h * 0.5f)); settle(1) }; release() }
            // Middle tap: strip folds; again unfolds. Page must not move.
            probe("centre tap folds strip") { val a = shown("Pen"); tap(0.5f, 0.4f); say("  strip before=$a after=${shown("Pen")}"); shot("centre-1") }
            probe("centre tap again unfolds") { tap(0.5f, 0.4f); say("  strip after=${shown("Pen")}"); shot("centre-2") }
            // Bottom-middle: All tools.
            tap(0.5f, 0.4f); settle(30)
            probe("bottom-middle tap (All tools)") { tap(0.5f, 0.92f); shot("bottom-middle"); say("  after: labels ${listOf("Pen","Highlight","Eraser","Undo","Metronome","Tuner","All tools","Hide tools").filter { shown(it) }}; tab strip row visible: ${shown("Home")}") }
            probe("middle tap again closes+refits") { tap(0.5f, 0.4f); shot("middle-closes"); say("  after: Hide tools=${shown("Hide tools")} Pen=${shown("Pen")}") }
        }
    }

    @Test
    fun `T2 r1 mouse, wheel, trackpad and keys`() {
        assumeTrue(T2Lib.available())
        t2App("r1m") {
            sheets().stripCollapsed = true
            open("Party Medley"); waitPages(); goto(0)
            probe("mouse click right third") { mouseClick(0.9f, 0.5f) }
            probe("mouse click left third") { mouseClick(0.1f, 0.5f) }
            probe("mouse click middle") { mouseClick(0.5f, 0.5f); say("  strip shown=${shown("Pen")}"); mouseClick(0.5f, 0.5f) }
            sheets().stripCollapsed = true; settle(30)
            probe("mouse click on right half but inside middle third (x=.6)") { mouseClick(0.6f, 0.5f) }
            sheets().stripCollapsed = true; settle(30)
            probe("mouse drag across left 40% (real mouse)") { performMouseDrag(this, 0.7f, 0.3f, 6) }
            probe("wheel one notch down") { wheel(1f) }
            probe("wheel one notch up") { wheel(-1f) }
            probe("wheel 5 notches down quickly") { wheel(5f) }
            probe("wheel two notches 100ms apart") { wheel(1f); wheel(1f) }
            probe("shift+wheel down") { wheel(1f, shift = true) }
            probe("ctrl+wheel (zoom)") { wheel(-3f, ctrl = true); shot("ctrl-wheel-zoom") }
            probe("wheel after zoom (page turn or scroll?)") { wheel(1f) }
            Perform.run(PerformAction.FIRST_PAGE); settle(60)
            // Keys, through the same table the window uses.
            val keys = listOf("PageDown" to Key.PageDown, "PageUp" to Key.PageUp, "ArrowRight" to Key.DirectionRight, "ArrowLeft" to Key.DirectionLeft, "ArrowDown" to Key.DirectionDown, "ArrowUp" to Key.DirectionUp,
                "Space" to Key.Spacebar, "Home" to Key.MoveHome, "End" to Key.MoveEnd, "Enter" to Key.Enter, "Escape" to Key.Escape, "Backspace" to Key.Backspace, "N" to Key.N, "P" to Key.P, "F" to Key.F, "F11" to Key.F11, "Tab" to Key.Tab)
            for ((n, k) in keys) { goto(1); val p0 = page(); key(k); say("key $n: action=${lastKeyAction} page ${p0 + 1} -> ${page() + 1}") }
            goto(1); key(Key.PageDown, ctrl = true); say("Ctrl+PageDown action=$lastKeyAction")
            goto(1); key(Key.PageUp, shift = true); say("Shift+PageUp action=$lastKeyAction")
            for ((n, k) in listOf("Equals" to Key.Equals, "Minus" to Key.Minus, "Zero" to Key.Zero)) { key(k, ctrl = true); say("Ctrl+$n action=$lastKeyAction") }
            // Half page on a fitted page.
            run { goto(1); val p0 = page(); val ok = Perform.run(PerformAction.HALF_PAGE_FORWARD); settle(40); say("HALF_PAGE_FORWARD on a fitted page: returned $ok page ${p0 + 1} -> ${page() + 1} song ${sheets().current?.title}") }
        }
    }

    private fun performMouseDrag(t: T2, fx: Float, fy: Float, frames: Int) {
        t.root.performMouseInput {
            moveTo(Offset(t.w * fx, t.h * fy)); press()
            for (i in 1..frames) moveTo(Offset(t.w * fx - i * t.w * 0.4f / frames, t.h * fy))
            release()
        }
        t.settle(40)
    }
}
