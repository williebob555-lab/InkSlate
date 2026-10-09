package com.inksheets.desktop.review

import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inksheets.core.ControlBinding
import com.inksheets.core.ControlEvent
import com.inksheets.core.ControlRef
import com.inksheets.core.RemoteButton
import com.inksheets.ui.SharedMetronome
import com.inksheets.ui.SheetsState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/** Controllers: MIDI-style events through the real ControllerHub, driving the real actions. */
class T4ControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val actions = CopyOnWriteArrayList<Pair<PerformAction, Long>>()

    @After fun reset() { Perform.document = null; Perform.app = null }

    private fun device(withOut: Boolean = false): Triple<SheetsState, T4Platform, T4Input> {
        val input = T4Input()
        val platform = T4Platform(tmp.newFolder("Dev"), "Foot", out = if (withOut) T4Out() else null).also { it.input = input }
        val (state, _) = T4Lib.make(platform.startFolder, platform = platform)
        Perform.document = { a -> actions += a to System.nanoTime(); true }
        assertTrue(T4.waitFor(2000) { input.send != null })
        return Triple(state, platform, input)
    }

    private fun bind(s: SheetsState, ref: ControlRef, b: RemoteButton, every: Boolean = false, off: Boolean = false, sweep: Boolean = false) =
        javax.swing.SwingUtilities.invokeAndWait { s.controllers.bindings += ControlBinding(ref, b, continuous = sweep, everyMessage = every, whenOff = off) }

    private fun next() = RemoteButton.action("NEXT_PAGE")

    @Test
    fun `toggle modes - every press, when it lights, when it goes dark`() {
        val (s, _, pedal) = device()
        val ref1 = ControlRef("T4 Pedal", ControlEvent.CC, 1, 21)
        val ref2 = ControlRef("T4 Pedal", ControlEvent.CC, 1, 22)
        val ref3 = ControlRef("T4 Pedal", ControlEvent.CC, 1, 23)
        bind(s, ref1, next(), every = true)
        bind(s, ref2, next())                  // when it lights
        bind(s, ref3, next(), off = true)      // when it goes dark
        fun count(n: Int, vararg v: Int): Int {
            actions.clear()
            pedal.say(*v.map { pedal.cc(n, it) }.toTypedArray())
            return actions.size
        }
        T4.log("TOGGLE POD-style 127,0,127,0: every press=${count(21, 127, 0, 127, 0)} lights=${count(22, 127, 0, 127, 0)} dark=${count(23, 127, 0, 127, 0)} (expect 4,2,2)")
        // A footswitch that only ever sends 127 (a latching switch with no off): lights fires, dark never.
        T4.log("TOGGLE only 127,127,127: every press=${count(21, 127, 127, 127)} lights=${count(22, 127, 127, 127)} dark=${count(23, 127, 127, 127)}")
        // Odd values (a switch sending 100 / 5): CC is a switch above 63.
        T4.log("TOGGLE 100,5,100,5: every=${count(21, 100, 5, 100, 5)} lights=${count(22, 100, 5, 100, 5)} dark=${count(23, 100, 5, 100, 5)}")
        // A note pedal, note on then note off.
        val nref = ControlRef("T4 Pedal", ControlEvent.NOTE, 1, 60)
        bind(s, nref, next())
        actions.clear(); pedal.say(pedal.note(60, 100), pedal.note(60, 0), pedal.note(60, 100), pedal.note(60, 0))
        T4.log("TOGGLE note on/off/on/off: default fires ${actions.size} (expect 2)")
        // Program changes (a preset chosen): the default binding.
        val pref = ControlRef("T4 Pedal", ControlEvent.PROGRAM, 1, 3)
        bind(s, pref, next())
        actions.clear(); pedal.say(ControlEvent("T4 Pedal", ControlEvent.PROGRAM, 1, 3, 3), ControlEvent("T4 Pedal", ControlEvent.PROGRAM, 1, 3, 3), ControlEvent("T4 Pedal", ControlEvent.PROGRAM, 1, 3, 3))
        T4.log("TOGGLE program change x3 with a default (not 'every press') binding: fires ${actions.size} (expect 3 if each is a press; 2 if one-way)")
    }

    @Test
    fun `a pedal pressed twice quickly runs a sequence twice over itself`() {
        val (s, _, pedal) = device()
        val ref = ControlRef("T4 Pedal", ControlEvent.CC, 1, 30)
        val macro = RemoteButton(RemoteButton.MACRO, steps = listOf(next(), next(), next()))
        bind(s, ref, macro, every = true)
        actions.clear()
        val t0 = System.nanoTime()
        pedal.say(pedal.cc(30, 127))
        Thread.sleep(250)
        pedal.say(pedal.cc(30, 127))      // a second press while the first sequence is still running
        T4.waitFor(4000) { actions.size >= 6 }
        val times = actions.map { (it.second - t0) / 1_000_000 }
        T4.log("MACRO re-entry: 2 presses of a 3-step sequence -> ${actions.size} actions at ms $times (the two runs interleave; no 'busy' guard)")
    }

    @Test
    fun `a pedal sweep sets the tempo across its range - count and cost`() {
        val (s, _, pedal) = device(withOut = true)
        val ref = ControlRef("T4 Pedal", ControlEvent.CC, 1, 7)
        bind(s, ref, RemoteButton(RemoteButton.TEMPO_SET), sweep = true)
        val seen = CopyOnWriteArrayList<Int>()
        val t0 = System.nanoTime()
        pedal.say(*(0..127).map { pedal.cc(7, it) }.toTypedArray(), gapMs = 0)
        val took = (System.nanoTime() - t0) / 1_000_000
        seen += SharedMetronome.bpm.toInt()
        T4.log("SWEEP tempo: 128 events in $took ms (incl. 60 ms settle); bpm at 127 = ${SharedMetronome.bpm}")
        pedal.say(pedal.cc(7, 0)); T4.log("SWEEP tempo at 0 = ${SharedMetronome.bpm}")
        pedal.say(pedal.cc(7, 64)); T4.log("SWEEP tempo at 64 = ${SharedMetronome.bpm}")
        // Recording volume with nothing playing: does the pedal say so?
        val vref = ControlRef("T4 Pedal", ControlEvent.CC, 1, 11)
        bind(s, vref, RemoteButton(RemoteButton.AUDIO_VOLUME_SET), sweep = true)
        val before = (s.controllers.recent.size)
        pedal.say(pedal.cc(11, 100))
        T4.log("SWEEP volume with no recording playing: no error, no message (recent size $before -> ${s.controllers.recent.size}); platform log ${(s.platform as T4Platform).logs.takeLast(2)}")
    }

    @Test
    fun `learning swallows the pedal until someone cancels - and a dialog that goes away cancels nothing`() {
        val (s, _, pedal) = device()
        val ref = ControlRef("T4 Pedal", ControlEvent.CC, 1, 40)
        bind(s, ref, next(), every = true)
        javax.swing.SwingUtilities.invokeAndWait { s.controllers.learn(RemoteButton.action("PREVIOUS_PAGE")) }
        actions.clear()
        pedal.say(pedal.cc(40, 127), pedal.cc(40, 0))
        T4.log("LEARNING: bound pedal pressed while a learn is open fires ${actions.size} actions (swallowed); learned='${s.controllers.learned?.control}'")
        Thread.sleep(5000)
        T4.log("LEARNING: 5 s later still learning=${s.controllers.learning != null} - no timeout; nothing on screen if the dialog was closed some other way")
        javax.swing.SwingUtilities.invokeAndWait { s.controllers.cancelLearning() }
        actions.clear(); pedal.say(pedal.cc(40, 127))
        T4.log("LEARNING cancelled: pedal fires ${actions.size}")
        // Placing on the picture: the same hazard.
        javax.swing.SwingUtilities.invokeAndWait { s.controllers.place("fs1") }
        actions.clear(); pedal.say(pedal.cc(40, 127))
        T4.log("PLACING (POD Go picture): pedal while a spot is listening fires ${actions.size}")
        javax.swing.SwingUtilities.invokeAndWait { s.controllers.cancelPlacing() }
    }

    @Test
    fun `learn a switch the way a player does, and a momentary pedal that is let go slowly`() {
        val (s, _, pedal) = device()
        // Momentary: 127 on press, 0 on release 200 ms later.
        javax.swing.SwingUtilities.invokeAndWait { s.controllers.learn(next()) }
        pedal.say(pedal.cc(50, 127)); Thread.sleep(200); pedal.say(pedal.cc(50, 0))
        val l1 = s.controllers.learned
        T4.log("LEARN momentary pedal released after 200 ms: everyMessage=${l1?.everyMessage} (false = a press is the 127 only)")
        javax.swing.SwingUtilities.invokeAndWait { s.controllers.cancelLearning() }
        // Held 4 s before release.
        javax.swing.SwingUtilities.invokeAndWait { s.controllers.learn(next()) }
        pedal.say(pedal.cc(51, 127)); Thread.sleep(3300); pedal.say(pedal.cc(51, 0))
        val l2 = s.controllers.learned
        T4.log("LEARN momentary pedal held 3.3 s before release: everyMessage=${l2?.everyMessage} (true => 'every press' -> the release turns a page too)")
        javax.swing.SwingUtilities.invokeAndWait { s.controllers.keepLearned() }
        actions.clear(); pedal.say(pedal.cc(51, 127)); Thread.sleep(200); pedal.say(pedal.cc(51, 0))
        T4.log("LEARN then using that pedal normally: one press+release turns ${actions.size} page(s)")
    }

    @Test
    fun `a pedal heard from two ports with the same name or a different name`() {
        val (s, _, pedal) = device()
        bind(s, ControlRef("T4 Pedal", ControlEvent.CC, 1, 60), next(), every = true)
        actions.clear()
        pedal.say(ControlEvent("2- T4 Pedal", ControlEvent.CC, 1, 60, 127))
        T4.log("RENAMED PORT: same pedal arriving as '2- T4 Pedal' (another USB port) fires ${actions.size} (binding is to the exact device name)")
    }

    @Test
    fun `controllers off means silence, on again means they work with no restart`() {
        val (s, _, pedal) = device()
        bind(s, ControlRef("T4 Pedal", ControlEvent.CC, 1, 70), next(), every = true)
        javax.swing.SwingUtilities.invokeAndWait { s.controllers.turn(false) }
        T4.log("OFF: send handle after turning off = ${pedal.send}")
        javax.swing.SwingUtilities.invokeAndWait { s.controllers.turn(true) }
        T4.waitFor(1000) { pedal.send != null }
        actions.clear(); pedal.say(pedal.cc(70, 127))
        T4.log("ON again: fires ${actions.size} (started ${pedal.started} times)")
        assertEquals(1, actions.size)
    }

    @Test
    fun `midi bytes - running status, sysex, clock, odd packets`() {
        val bytes = byteArrayOf(0xB0.toByte(), 20, 127, 21, 0, 0xF8.toByte(), 0xC0.toByte(), 5, 0xF0.toByte(), 1, 2, 3, 0xF7.toByte(), 0x90.toByte(), 60, 0)
        val ev = ControlEvent.fromMidiBytes("d", bytes, 0, bytes.size)
        T4.log("MIDI BYTES: ${ev.map { it.kind + ":" + it.number + "=" + it.value }}")
        val bad = byteArrayOf(0xB0.toByte(), 20)
        T4.log("MIDI truncated message: ${ControlEvent.fromMidiBytes("d", bad, 0, bad.size).size} events (no crash)")
        val junk = ByteArray(5000) { (it * 31).toByte() }
        val t0 = System.nanoTime()
        T4.log("MIDI 5000 junk bytes: ${ControlEvent.fromMidiBytes("d", junk, 0, junk.size).size} events in ${(System.nanoTime() - t0) / 1000} us")
    }
}
