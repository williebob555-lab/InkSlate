package com.inkslate.desktop

import androidx.compose.ui.geometry.Offset

/**
 * For tests of the whole app without a touchscreen: what Windows hands a desktop program when
 * fingers are on the glass, played through the same doors the pointer reader uses. A finger's tap
 * or drag reaches the program as mouse events stamped as touch ([stamp] before each), and more than
 * one finger as contacts ([finger], [lift]) in window pixels.
 */
object SimulatedTouch {
    /** On: the program believes the window's messages are being read; the window taken to be at the screen's top left. */
    var on: Boolean
        get() = PenInput.simulated
        set(value) {
            PenInput.simulated = value
            PointerDiagnostics.windowAtForTests = if (value) Offset.Zero else null
        }

    /** The next mouse event comes from a finger. */
    fun stamp() = PenInput.touchedSomewhere()

    /** Finger [id] down or moved to ([x], [y]). */
    fun finger(id: Int, x: Float, y: Float) = PenInput.finger(id, x, y)

    /** Finger [id] off the glass. */
    fun lift(id: Int) = PenInput.fingerUp(id)
}
