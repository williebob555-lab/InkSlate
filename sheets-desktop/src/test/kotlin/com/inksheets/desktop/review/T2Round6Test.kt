package com.inksheets.desktop.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performMouseInput
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inkslate.desktop.SimulatedTouch
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Stylus, pen/eraser switches, undo, annotating in fullscreen. */
@OptIn(ExperimentalTestApi::class)
class T2Round6Test {
    private fun say(s: String) = println("T2 $s")

    /** PenInput.pen is internal to the desktop module; reached by reflection (its name is mangled). */
    private fun penDown(down: Boolean, pressure: Float? = 0.5f, button: Int = 0) {
        val c = Class.forName("com.inkslate.desktop.PenInput")
        val inst = c.getField("INSTANCE").get(null)
        val m = c.declaredMethods.first { it.name.startsWith("pen") && !it.name.startsWith("penHover") && it.parameterCount == 3 }
        m.isAccessible = true
        m.invoke(inst, down, pressure, button)
    }

    private fun T2.stylusTap(fx: Float, fy: Float) {
        val p = Offset(w * fx, h * fy)
        penDown(false); root.performMouseInput { moveTo(p) }; penDown(true)
        root.performMouseInput { press() }; settle(3)
        penDown(false); root.performMouseInput { release() }; settle(30)
    }
    private fun T2.stylusStroke(fx1: Float, fy1: Float, fx2: Float, fy2: Float) {
        val a = Offset(w * fx1, h * fy1)
        penDown(false); root.performMouseInput { moveTo(a) }; penDown(true)
        root.performMouseInput { press() }
        for (i in 1..12) { root.performMouseInput { moveTo(Offset(w * (fx1 + (fx2 - fx1) * i / 12), h * (fy1 + (fy2 - fy1) * i / 12))) }; settle(1) }
        penDown(false); root.performMouseInput { release() }; settle(30)
    }

    @Test
    fun `T2 r6 stylus, pen switch, eraser, undo`() {
        assumeTrue(T2Lib.available())
        t2App("r6") {
            sheets().stripCollapsed = false
            open("Toxic"); waitPages()
            SimulatedTouch.on = true
            val path = sheets().currentPath
            val s0 = strokes(path)
            say("start: strokes=$s0 page ${page() + 1}/${count()}")
            // Stylus tap in the right third: must not turn (single page though: check strip untouched).
            stylusTap(0.9f, 0.5f)
            say("stylus tap right third: strokes ${s0} -> ${strokes(path)} (a dot is fine) page ${page() + 1}")
            // Stylus stroke across the page in fullscreen with tools away.
            stylusStroke(0.3f, 0.3f, 0.6f, 0.35f)
            say("stylus stroke with tools away: strokes -> ${strokes(path)}")
            shot("stylus-stroke")
            // Finger does not write.
            val before = strokes(path)
            drag(0.3f, 0.6f, 0.6f, 0.62f, 8)
            say("finger drag with tools away: strokes $before -> ${strokes(path)}")
            // Strip Pen switch: finger now writes.
            tapText("Pen"); settle(20)
            shot("pen-switch-on")
            val b2 = strokes(path)
            drag(0.3f, 0.65f, 0.6f, 0.66f, 8)
            say("after Pen switch: finger drag -> strokes $b2 -> ${strokes(path)}")
            // Tap right third with finger while pen switch on: writes a dot, not a turn.
            val b3 = strokes(path); val p3 = page()
            tap(0.9f, 0.5f)
            say("pen switch on, finger tap at right third: strokes $b3 -> ${strokes(path)} page ${p3 + 1} -> ${page() + 1}")
            // Undo via strip.
            val b4 = strokes(path)
            tapText("Undo"); settle(20)
            say("strip Undo: strokes $b4 -> ${strokes(path)}")
            tapText("Undo"); tapText("Undo"); settle(20)
            say("two more Undo: strokes -> ${strokes(path)}")
            // Eraser switch: finger erases.
            drag(0.3f, 0.7f, 0.6f, 0.71f, 8)
            tapText("Eraser"); settle(20); shot("eraser-switch-on")
            say("Eraser switch: Pen lit? labels ${listOf("Pen", "Eraser", "Highlight").filter { shown(it) }}")
            val b5 = strokes(path)
            drag(0.25f, 0.3f, 0.65f, 0.36f, 12)
            say("finger with eraser across the stylus stroke: strokes $b5 -> ${strokes(path)}")
            // Switch off: finger turns pages again
            tapText("Eraser"); settle(20)
            val b6 = strokes(path)
            drag(0.3f, 0.8f, 0.6f, 0.82f, 8)
            say("eraser switched off: finger drag strokes $b6 -> ${strokes(path)}")
            // Highlighter is not on the strip by default.
            say("Highlight on the strip by default: ${shown("Highlight")}")
            // Annotation at zoom in fullscreen: stylus writes after a pinch (pen/finger)
            pinch(60f, 300f); settle(20)
            val b7 = strokes(path)
            stylusStroke(0.4f, 0.4f, 0.5f, 0.45f)
            say("stylus stroke while zoomed (tools away): strokes $b7 -> ${strokes(path)}"); shot("stylus-zoomed")
            // Does the ink survive a turn away and back? (single-page song: step song instead)
            // Pen stroke then pedal turns: undo order
            Perform.run(PerformAction.UNDO); settle(20)
            say("pedal UNDO: strokes -> ${strokes(path)}")
        }
    }
}
