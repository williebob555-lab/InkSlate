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

    // ---- four kinds of pointer, four pens ---------------------------------------

    /**
     * Which pen a pointer belongs to.
     *
     * The tablet can tell a stylus from a finger and says so; a desktop program cannot - Windows
     * hands it a pen, a finger and a mouse identically - so what it can tell apart is the button,
     * and that is what picks the pen there.
     */
    @Test
    fun `a stylus and a finger each get their own pen`() {
        assertEquals(
            InputMode.PEN,
            InputMode.forPointer(isStylus = true, isTouch = false, secondaryButton = false)
        )
        assertEquals(
            InputMode.TOUCH,
            InputMode.forPointer(isStylus = false, isTouch = true, secondaryButton = false)
        )
    }

    @Test
    fun `the mouse buttons are two pens rather than one pen and a modifier`() {
        assertEquals(
            InputMode.MOUSE,
            InputMode.forPointer(isStylus = false, isTouch = false, secondaryButton = false)
        )
        assertEquals(
            InputMode.MOUSE_RIGHT,
            InputMode.forPointer(isStylus = false, isTouch = false, secondaryButton = true)
        )
    }

    /** A barrel button still outranks everything, and only for a pointer known to be a stylus. */
    @Test
    fun `a held barrel button wins, and only for a stylus`() {
        assertEquals(
            InputMode.BUTTON_1,
            InputMode.forPointer(true, isTouch = false, secondaryButton = true, heldStylusButton = 1)
        )
        assertEquals(
            InputMode.BUTTON_2,
            InputMode.forPointer(true, isTouch = false, secondaryButton = false, heldStylusButton = 2)
        )
        // The same secondary press from something that is not a stylus is the right-mouse pen.
        assertEquals(
            InputMode.MOUSE_RIGHT,
            InputMode.forPointer(false, isTouch = false, secondaryButton = true, heldStylusButton = 1)
        )
    }

    /** The switch steps between the two of a kind, and never wanders into another kind. */
    @Test
    fun `the switch stays within a kind of pointer`() {
        assertEquals(InputMode.MOUSE_RIGHT, InputMode.nextOnTap(InputMode.MOUSE))
        assertEquals(InputMode.MOUSE, InputMode.nextOnTap(InputMode.MOUSE_RIGHT))
        assertEquals(InputMode.TOUCH, InputMode.nextOnTap(InputMode.PEN))
        assertEquals(InputMode.PEN, InputMode.nextOnTap(InputMode.TOUCH))
    }
}
