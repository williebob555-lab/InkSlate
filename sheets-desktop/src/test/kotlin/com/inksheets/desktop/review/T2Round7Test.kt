package com.inksheets.desktop.review

import androidx.compose.ui.test.ExperimentalTestApi
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inkslate.desktop.SimulatedTouch
import com.inksheets.desktop.UiAudit
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The reading screen at phone, landscape phone and tablet sizes: strip each side, All tools, Part menu, panels. */
@OptIn(ExperimentalTestApi::class)
class T2Round7Test {
    private fun say(s: String) = println("T2 $s")

    @Test
    fun `T2 r7 reading screen at device sizes`() {
        assumeTrue(T2Lib.available())
        val sizes = listOf(Triple("phone", 390, 844), Triple("phone-land", 844, 390), Triple("phone-small-land", 640, 360), Triple("tablet10", 800, 1280), Triple("tablet10-land", 1280, 800), Triple("laptop-short", 1280, 720))
        for ((name, w, h) in sizes) {
            t2App("r7-$name", w, h) {
                sheets().stripCollapsed = false
                sheets().stripOnLeft = false
                sheets().playSetlist(setId, 0); waitPages(); SimulatedTouch.on = true
                shot("strip-right")
                var n = UiAudit.check(t, name, "t2-strip-right", w, h).size
                say("$name strip right: audit problems $n; music fraction ${"%.3f".format(music(image(1)))}")
                sheets().stripOnLeft = true; settle(40); shot("strip-left")
                n = UiAudit.check(t, name, "t2-strip-left", w, h).size
                say("$name strip left: audit problems $n")
                sheets().stripOnLeft = false; settle(20)
                // Part menu
                val label = listOf("Trombone 1").firstOrNull { shown(it) }
                if (label != null) { tapText(label); settle(30); shot("part-menu"); n = UiAudit.check(t, name, "t2-part-menu", w, h).size; say("$name part menu: audit problems $n"); Perform.run(PerformAction.SWITCH_PART); settle(10); sheets().partPicker = false; settle(20) }
                else say("$name: Part button label not found; labels: ${listOf("Pen", "Eraser", "Undo", "Together", "More", "All tools").filter { shown(it) }}")
                // All tools
                sheets().stripCollapsed = true; settle(20)
                tap(0.5f, 0.93f); settle(40); shot("all-tools")
                n = UiAudit.check(t, name, "t2-all-tools", w, h).size
                say("$name all tools: audit problems $n; 'Hide tools' shown=${shown("Hide tools")}")
                // Closed again: the strip is at the corner
                tap(0.5f, 0.4f); settle(40); shot("tools-closed")
                // Pages sheet
                sheets().stripCollapsed = false; settle(20)
                if (shown("Pages")) { tapText("Pages"); settle(40); shot("pages-sheet"); n = UiAudit.check(t, name, "t2-pages", w, h).size; say("$name Pages sheet: audit problems $n") }
                else say("$name: no Pages button (strip too short?)")
                // Panels over the music
                for ((pn, open) in listOf<Pair<String, (Boolean) -> Unit>>("tuner" to { v -> sheets().tunerOpen = v }, "metronome" to { v -> sheets().metronomeOpen = v }, "recordings" to { v -> sheets().audioOpen = v }, "together" to { v -> sheets().companionOpen = v })) {
                    open(true); settle(40); shot("panel-$pn")
                    n = UiAudit.check(t, name, "t2-panel-$pn", w, h).size
                    say("$name panel $pn: audit problems $n")
                    open(false); settle(20)
                }
            }
        }
    }
}
