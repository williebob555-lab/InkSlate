package com.inksheets.desktop.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.performMouseInput
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inkslate.desktop.SimulatedTouch
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage

/** Zoom and pan with tools away and out, trackpad, half pages. */
@OptIn(ExperimentalTestApi::class)
class T2Round3Test {
    private fun say(s: String) = println("T2 $s")
    private fun T2.goto(n: Int) { Perform.run(PerformAction.FIRST_PAGE); settle(40); repeat(n) { Perform.run(PerformAction.NEXT_PAGE); settle(30) }; settle(60) }

    /** How far the picture moved sideways/up: compare the centre rows of two images, best shift. */
    private fun shift(a: BufferedImage, b: BufferedImage): Pair<Int, Int> {
        fun lum(i: BufferedImage, x: Int, y: Int) = (((i.getRGB(x, y) shr 16) and 255) + ((i.getRGB(x, y) shr 8) and 255) + (i.getRGB(x, y) and 255)) / 3
        var best = Long.MAX_VALUE; var bx = 0; var by = 0
        for (dy in -300..300 step 20) for (dx in -600..600 step 20) {
            var d = 0L
            for (y in 300 until 700 step 8) for (x in 500 until 1100 step 8) {
                val x2 = x + dx; val y2 = y + dy
                if (x2 !in 0 until b.width || y2 !in 0 until b.height) { d += 255; continue }
                d += kotlin.math.abs(lum(a, x, y) - lum(b, x2, y2))
            }
            if (d < best) { best = d; bx = dx; by = dy }
        }
        return bx to by
    }

    @Test
    fun `T2 r3 pinch zoom and finger pan with tools away and out`() {
        assumeTrue(T2Lib.available())
        t2App("r3z") {
            sheets().stripCollapsed = true
            open("Party Medley"); waitPages(); goto(1)
            SimulatedTouch.on = true
            val fit = image()
            shot("fit")
            pinch(60f, 400f)
            val zoomed = image(); shot("pinched-tools-away")
            say("pinch zoom with tools away: image shift vs fit ${shift(fit, zoomed)} (zoomed picture differs: ${paper(fit)} vs ${paper(zoomed)})")
            val p0 = page()
            // Slow one-finger sideways drag at zoom: must not pan.
            val before = image()
            drag(0.65f, 0.5f, 0.50f, 0.5f, 30, perFrame = 4)
            val after = image()
            say("zoomed, tools away: slow finger drag 15% left (4 s): page ${p0 + 1} -> ${page() + 1}, picture shift ${shift(before, after)}")
            shot("after-slow-drag-zoomed")
            val b2 = image()
            drag(0.7f, 0.5f, 0.45f, 0.5f, 6)
            say("zoomed, tools away: quick flick left: page ${p0 + 1} -> ${page() + 1}, picture shift ${shift(b2, image())}")
            // Vertical finger drag at zoom (reads the lower part).
            goto(1); pinch(60f, 400f)
            val b3 = image()
            drag(0.5f, 0.7f, 0.5f, 0.4f, 8)
            say("zoomed, tools away: vertical drag up: picture shift ${shift(b3, image())}, page ${page() + 1}")
            shot("zoomed-vertical-drag")
            // Two-finger pan sideways at zoom, tools away.
            val b4 = image(); twoFingerPan(-250f, 0f)
            say("zoomed, tools away: two-finger pan 250px left: picture shift ${shift(b4, image())}, page ${page() + 1}")
            shot("zoomed-2f-pan")
            val b5 = image(); twoFingerPan(0f, -200f)
            say("zoomed: two-finger pan 200px up: picture shift ${shift(b5, image())}")
            // Tap right third at zoom: turns? And refits?
            val pz = page(); tap(0.93f, 0.5f); say("zoomed: tap right third: page ${pz + 1} -> ${page() + 1}"); shot("zoomed-tap-right")
            // Tools out: pinch then finger pan.
            tap(0.5f, 0.92f); settle(30); shot("tools-out")
            say("tools out? ${shown("Hide tools")} (All tools label gone)")
            goto(1)
            val t0 = image()
            pinch(60f, 300f); val t1 = image()
            say("tools out: pinch shift ${shift(t0, t1)}")
            drag(0.65f, 0.5f, 0.5f, 0.5f, 10); val t2 = image()
            say("tools out: finger drag 15% left at zoom: page ${page() + 1} shift ${shift(t1, t2)}")
            shot("tools-out-zoomed-after-drag")
            // Close tools: refits?
            tap(0.5f, 0.4f); settle(40); val t3 = image(); shot("tools-closed-refit")
            say("after middle tap closing tools: fit restored? shift vs fit ${shift(fit, t3)}")
        }
    }

    @Test
    fun `T2 r3 trackpad and half page`() {
        assumeTrue(T2Lib.available())
        t2App("r3t") {
            sheets().stripCollapsed = true
            open("Party Medley"); waitPages(); goto(1)
            fun swipe(name: String, total: Float, events: Int, gapMs: Long = 16, vertical: Float = 0f) {
                goto(1); val p0 = page()
                val b = image(2)
                repeat(events) {
                    root.performMouseInput { moveTo(Offset(w / 2, h / 2)); scroll(total / events, ScrollWheel.Horizontal); if (vertical != 0f) scroll(vertical / events, ScrollWheel.Vertical) }
                    settle(1); Thread.sleep(gapMs)
                }
                settle(40)
                say("trackpad $name (sideways $total in $events events): page ${p0 + 1} -> ${page() + 1}, shift ${if (vertical != 0f) shift(b, image(2)) else ""}")
            }
            swipe("tiny swipe", 1.0f, 5)
            swipe("small swipe", 2.0f, 8)
            swipe("normal swipe", 4.0f, 12)
            swipe("big swipe", 12.0f, 20)
            swipe("big swipe left", -12.0f, 20)
            swipe("diagonal swipe (down 3)", 4.0f, 12, vertical = 3f)
            swipe("vertical swipe only", 0.0f, 12, vertical = 5f)
            // Coasting: events trailing on after the turn for 600 ms.
            run {
                goto(1); val p0 = page()
                repeat(60) { i -> root.performMouseInput { moveTo(Offset(w / 2, h / 2)); scroll((if (i < 12) 0.6f else 0.15f), ScrollWheel.Horizontal) }; settle(1); Thread.sleep(20) }
                settle(40); say("trackpad swipe with 1.2 s of coasting: page ${p0 + 1} -> ${page() + 1}")
            }
            // Two swipes the same way, 400 ms apart: each a page.
            run {
                goto(0); val p0 = page()
                repeat(2) { repeat(10) { root.performMouseInput { moveTo(Offset(w / 2, h / 2)); scroll(0.5f, ScrollWheel.Horizontal) }; settle(1); Thread.sleep(16) }; Thread.sleep(400); settle(2) }
                settle(40); say("two trackpad swipes right 400 ms apart: page ${p0 + 1} -> ${page() + 1}")
            }
            // Half page
            goto(1); val img0 = image()
            val ok = Perform.run(PerformAction.HALF_PAGE_FORWARD); settle(40)
            val img1 = image(); shot("half-page-forward-on-fitted")
            say("HALF_PAGE_FORWARD on a fitted landscape page: returned $ok, page ${page() + 1}, picture shift ${shift(img0, img1)}")
            val ok2 = Perform.run(PerformAction.HALF_PAGE_FORWARD); settle(40); shot("half-page-forward-twice")
            say("second HALF_PAGE_FORWARD: $ok2 page ${page() + 1} shift vs start ${shift(img0, image())}")
            Perform.run(PerformAction.HALF_PAGE_BACK); Perform.run(PerformAction.HALF_PAGE_BACK); settle(40); shot("half-page-back-twice")
            // Does a page turn refit after a half-page push?
            Perform.run(PerformAction.NEXT_PAGE); settle(60); shot("after-turn-following-half-pages")
            say("after NEXT_PAGE following half-pages: shift vs fit of previous ${shift(img0, image())}")
        }
    }
}
