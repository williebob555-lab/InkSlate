package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the input switch does when it is tapped.
 *
 * The barrel-button profile used to sit in the same cycle as the pen and the finger, so switching
 * between those two would land on it - on a device whose pen may not even have a button - and
 * getting back out meant cycling past it again. It is not a third thing in a loop; it is the pen
 * with a button held, and holding that button is the only way in.
 */
class InputModeTest {

    @Test
    fun `a plain tap moves between the pen and the finger`() {
        assertEquals(InputMode.TOUCH, InputMode.nextOnTap(InputMode.PEN))
        assertEquals(InputMode.PEN, InputMode.nextOnTap(InputMode.TOUCH))
    }

    @Test
    fun `no number of plain taps ever reaches a button profile`() {
        var mode = InputMode.PEN
        repeat(50) {
            mode = InputMode.nextOnTap(mode)
            assertFalse(
                "a plain tap must never select ${mode.name}",
                mode.isStylusButton
            )
        }
    }

    /** The way out of a button profile is the pen, not the next step of a loop. */
    @Test
    fun `a plain tap from a button profile returns to the pen`() {
        assertEquals(InputMode.PEN, InputMode.nextOnTap(InputMode.BUTTON_1))
        assertEquals(InputMode.PEN, InputMode.nextOnTap(InputMode.BUTTON_2))
    }

    @Test
    fun `holding a barrel button is the only way to a button profile`() {
        assertEquals(InputMode.BUTTON_1, InputMode.forHeldButton(1))
        assertEquals(InputMode.BUTTON_2, InputMode.forHeldButton(2))
        // A pen reporting some higher button number still means "the second one".
        assertEquals(InputMode.BUTTON_2, InputMode.forHeldButton(3))
    }

    @Test
    fun `the two button profiles know what they are`() {
        assertTrue(InputMode.BUTTON_1.isStylusButton)
        assertTrue(InputMode.BUTTON_2.isStylusButton)
        assertFalse(InputMode.PEN.isStylusButton)
        assertFalse(InputMode.TOUCH.isStylusButton)
    }
}
