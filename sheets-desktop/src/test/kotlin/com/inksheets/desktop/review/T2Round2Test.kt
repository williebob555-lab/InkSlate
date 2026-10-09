package com.inksheets.desktop.review

import androidx.compose.ui.test.ExperimentalTestApi
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inkslate.desktop.SimulatedTouch
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Round 2/3: rapid and odd inputs. */
@OptIn(ExperimentalTestApi::class)
class T2Round2Test {
    private fun say(s: String) = println("T2 $s")

    @Test
    fun `T2 r2 rapid taps and double taps`() {
        assumeTrue(T2Lib.available())
        t2App("r2rapid") {
            sheets().stripCollapsed = true
            open("Party Medley"); waitPages()
            Perform.run(PerformAction.FIRST_PAGE); settle(40)
            SimulatedTouch.on = true
            say("start page ${page() + 1}/${count()}")
            for (gap in listOf(1, 3, 8, 12, 20)) {
                Perform.run(PerformAction.FIRST_PAGE); settle(60)
                val trace = StringBuilder()
                tap(0.9f, 0.5f, after = 0); trace.append("t1:${page() + 1} ")
                repeat(gap) { settle(1) }
                tap(0.9f, 0.5f, after = 0); trace.append("t2:${page() + 1} ")
                repeat(gap) { settle(1) }
                tap(0.9f, 0.5f, after = 0); trace.append("t3:${page() + 1} ")
                settle(60)
                say("finger taps right, $gap frames apart: $trace settled ${page() + 1}")
            }
            for (gap in listOf(1, 4, 12)) {
                Perform.run(PerformAction.FIRST_PAGE); settle(60)
                val trace = StringBuilder()
                repeat(3) { Perform.run(PerformAction.NEXT_PAGE); trace.append("${page() + 1} "); settle(gap) }
                settle(60)
                say("pedal NEXT x3, $gap frames apart: $trace settled ${page() + 1}")
            }
            for (gap in listOf(1, 8)) {
                Perform.run(PerformAction.FIRST_PAGE); settle(60)
                val trace = StringBuilder()
                repeat(3) { mouseClick(0.9f, 0.5f, after = gap); trace.append("${page() + 1} ") }
                settle(60)
                say("mouse clicks right, $gap frames apart: $trace settled ${page() + 1}")
            }
        }
    }

    @Test
    fun `T2 r2 turn timings and blank frames`() {
        assumeTrue(T2Lib.available())
        t2App("r2time") {
            sheets().stripCollapsed = true
            open("Party Medley"); waitPages()
            Perform.run(PerformAction.FIRST_PAGE); settle(60)
            SimulatedTouch.on = true
            val t0 = System.nanoTime(); repeat(10) { image(1) }
            say("capture+frame overhead: ${(System.nanoTime() - t0) / 10_000_000} ms per frame")
            // Pure state timing, minimal overhead.
            fun stateTime(name: String, input: () -> Unit) {
                val b = page(); val t = System.nanoTime(); input()
                var n = 0
                while (page() == b && n < 400) { settle(1); n++ }
                say("$name: page ${b + 1} -> ${page() + 1} after ${(System.nanoTime() - t) / 1_000_000} ms wall, $n frames")
            }
            stateTime("tap right") { tap(0.9f, 0.5f, after = 0) }
            settle(60)
            stateTime("tap left") { tap(0.1f, 0.5f, after = 0) }
            settle(60)
            stateTime("pedal NEXT_PAGE") { Perform.run(PerformAction.NEXT_PAGE) }
            settle(60)
            // Full timing incl. music visible: portrait scan song and landscape digital song, tap and pedal.
            for (rep in 1..2) {
                Perform.run(PerformAction.FIRST_PAGE); settle(60)
                say("Party Medley p1->p2 by tap #$rep: " + film { tap(0.9f, 0.5f, after = 0) })
                settle(60)
                say("Party Medley p2->p3 by pedal #$rep: " + film { Perform.run(PerformAction.NEXT_PAGE) })
                settle(60)
            }
            // A film of a tap turn: 10 frames.
            val frames = ArrayList<java.awt.image.BufferedImage>()
            val m = ArrayList<Double>()
            Perform.run(PerformAction.FIRST_PAGE); settle(60)
            tap(0.9f, 0.5f, after = 0)
            repeat(45) { val img = image(1); frames += img; m += music(img) }
            say("music fraction per frame after a tap turn (page 1->2): ${m.map { "%.3f".format(it) }}")
            val thumbs = frames.filterIndexed { i, _ -> i % 4 == 0 }.take(10)
            val tw = 400; val th = 250
            val sheet = java.awt.image.BufferedImage(tw * 5, th * 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = sheet.createGraphics()
            thumbs.forEachIndexed { i, im -> g.drawImage(im.getScaledInstance(tw, th, java.awt.Image.SCALE_SMOOTH), (i % 5) * tw, (i / 5) * th, null) }
            g.dispose()
            javax.imageio.ImageIO.write(sheet, "png", java.io.File(T2Lib.shots, "r2time-film-tap-turn.png"))
        }
    }
}
