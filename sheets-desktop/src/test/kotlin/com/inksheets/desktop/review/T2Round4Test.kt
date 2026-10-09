package com.inksheets.desktop.review

import androidx.compose.ui.test.ExperimentalTestApi
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inkslate.desktop.SimulatedTouch
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Stepping through a setlist. */
@OptIn(ExperimentalTestApi::class)
class T2Round4Test {
    private fun say(s: String) = println("T2 $s")
    private fun T2.where() = "${sheets().current?.title} p${page() + 1}/${count()} playing=${sheets().playing?.second}"

    @Test
    fun `T2 r4 setlist stepping`() {
        assumeTrue(T2Lib.available())
        t2App("r4s") {
            sheets().stripCollapsed = false
            sheets().playSetlist(setId, 0)
            waitPages()
            SimulatedTouch.on = true
            say("setlist opened: ${where()}")
            shot("set-song1")
            say("strip 'Song 1/5' shown=${shown("Song 1/5")}; tab names shown: " + listOf("Party Medley", "24K Magic", "Toxic", "Master of Puppets", "MSOM Shorts").map { it to shown(it) })
            Perform.run(PerformAction.LAST_PAGE); settle(60)
            say("song 1 left on its last page: ${where()}")
            // Immediate step (before the preload has run).
            say("step 1->2 immediately after opening: " + film { Perform.run(PerformAction.NEXT_SONG) })
            say("   -> ${where()}")
            shot("set-song2")
            // Immediately again, before song 3 is read.
            say("step 2->3 straight away: " + film { Perform.run(PerformAction.NEXT_SONG) })
            say("   -> ${where()}")
            // Let the preload run (700ms + 400ms per song).
            Thread.sleep(2000); repeat(30) { settle(5) }
            say("step 3->4 after waiting for the read-ahead: " + film { Perform.run(PerformAction.NEXT_SONG) })
            say("   -> ${where()}")
            Thread.sleep(2000); repeat(30) { settle(5) }
            say("step 4->5 after waiting: " + film { Perform.run(PerformAction.NEXT_SONG) })
            say("   -> ${where()}")
            shot("set-song5")
            say("NEXT_SONG at the end returns ${Perform.run(PerformAction.NEXT_SONG)}; ${where()}")
            Perform.run(PerformAction.LAST_PAGE); settle(60)
            tap(0.9f, 0.5f); settle(60)
            say("tap right on the last page of the last song: ${where()}")
            // Back through the set.
            for (i in 1..4) { say("PREVIOUS_SONG #$i: " + film { Perform.run(PerformAction.PREVIOUS_SONG) }); say("   -> ${where()}") }
            // Left on page 1 of song 1: tap left
            Perform.run(PerformAction.FIRST_PAGE); settle(60)
            tap(0.1f, 0.5f); settle(60)
            say("tap left on page 1 of song 1: ${where()}")
            // A tap right on the last page of song 1 goes to song 2 at its page 1.
            Perform.run(PerformAction.LAST_PAGE); settle(60)
            say("tap right on last page of song 1: " + film { tap(0.9f, 0.5f, after = 0) })
            say("   -> ${where()}")
            // Rapid pedal
            repeat(4) { Perform.run(PerformAction.PREVIOUS_SONG); settle(2) }
            settle(150)
            say("4 rapid PREVIOUS_SONG: ${where()}")
            repeat(4) { Perform.run(PerformAction.NEXT_SONG); settle(2) }
            settle(150)
            say("4 rapid NEXT_SONG: ${where()}")
            shot("after-rapid")
            // Bounce: next/prev/next with no gaps.
            repeat(6) { i -> Perform.run(if (i % 2 == 0) PerformAction.PREVIOUS_SONG else PerformAction.NEXT_SONG); settle(1) }
            settle(150)
            say("alternating 6 steps with no gap: ${where()}")
            // Strip's page label vs reality
            say("strip Song x/5 label: " + (1..5).filter { shown("Song $it/5") })
        }
    }
}
