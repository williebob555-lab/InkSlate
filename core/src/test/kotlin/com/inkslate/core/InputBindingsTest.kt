package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table that replaced the chain of special cases.
 *
 * Two things have to hold for this to be an improvement rather than a rewrite. What it does out of
 * the box has to be exactly what the chain did, because anything else is a behaviour change nobody
 * asked for. And a combination nobody wrote down has to do something sensible, because that is the
 * whole reason for it: the combinations do not stop arriving.
 */
class InputBindingsTest {

    private val fresh = InputBindings()

    @Test
    fun `out of the box, every device does what it always did`() {
        assertEquals(InputAction.DRAW_PEN, fresh.actionFor(InputDevice.PEN, InputButton.NONE))
        assertEquals(InputAction.DRAW_PEN_ONE, fresh.actionFor(InputDevice.PEN, InputButton.PEN_ONE))
        assertEquals(InputAction.DRAW_PEN_TWO, fresh.actionFor(InputDevice.PEN, InputButton.PEN_TWO))
        assertEquals(InputAction.DRAW_FINGER, fresh.actionFor(InputDevice.FINGER, InputButton.NONE))
        assertEquals(InputAction.DRAW_MOUSE, fresh.actionFor(InputDevice.MOUSE, InputButton.PRIMARY))
        assertEquals(
            InputAction.DRAW_MOUSE_RIGHT,
            fresh.actionFor(InputDevice.MOUSE, InputButton.SECONDARY)
        )
        assertEquals(InputAction.PAN, fresh.actionFor(InputDevice.MOUSE, InputButton.MIDDLE))
    }

    /** The pen's barrel reaches Windows as a right click, and must still be the barrel pen. */
    @Test
    fun `a pen reporting a right click is still the pen`() {
        assertEquals(
            InputAction.DRAW_PEN_ONE,
            fresh.actionFor(InputDevice.PEN, InputButton.SECONDARY)
        )
    }

    /** The bug that started this: allowing the right button to draw enrolled the thumb buttons. */
    @Test
    fun `thumb buttons do not draw`() {
        assertFalse(fresh.actionFor(InputDevice.MOUSE, InputButton.BACK).draws)
        assertFalse(fresh.actionFor(InputDevice.MOUSE, InputButton.FORWARD).draws)
    }

    @Test
    fun `something nobody described still does something sensible`() {
        // A pen with a third button, reported as a thumb button.
        assertTrue(fresh.actionFor(InputDevice.PEN, InputButton.FORWARD).draws)
        // A digitiser the platform will not name.
        assertTrue(fresh.actionFor(InputDevice.UNKNOWN, InputButton.MIDDLE).draws)
        // And a finger is a finger whatever it claims to be pressing.
        assertEquals(
            InputAction.DRAW_FINGER,
            fresh.actionFor(InputDevice.FINGER, InputButton.PRIMARY)
        )
    }

    @Test
    fun `a row can be changed and put back`() {
        val bound = fresh.with(InputDevice.MOUSE, InputButton.SECONDARY, InputAction.ERASE)

        assertEquals(InputAction.ERASE, bound.actionFor(InputDevice.MOUSE, InputButton.SECONDARY))
        assertTrue(bound.isChanged(InputDevice.MOUSE, InputButton.SECONDARY))
        assertEquals(
            InputAction.DRAW_MOUSE_RIGHT,
            bound.without(InputDevice.MOUSE, InputButton.SECONDARY)
                .actionFor(InputDevice.MOUSE, InputButton.SECONDARY)
        )
    }

    @Test
    fun `only what was changed is written down`() {
        val bound = fresh.with(InputDevice.MOUSE, InputButton.BACK, InputAction.PAN)

        assertEquals("MOUSE:BACK=PAN", bound.toText())
        assertEquals("", fresh.toText())
    }

    @Test
    fun `what was written down comes back`() {
        val bound = fresh
            .with(InputDevice.MOUSE, InputButton.BACK, InputAction.PAN)
            .with(InputDevice.PEN, InputButton.PEN_TWO, InputAction.ERASE)

        val read = InputBindings.fromText(bound.toText())

        assertEquals(InputAction.PAN, read.actionFor(InputDevice.MOUSE, InputButton.BACK))
        assertEquals(InputAction.ERASE, read.actionFor(InputDevice.PEN, InputButton.PEN_TWO))
    }

    /**
     * A table from a later build must not take the program down with it, and a row it cannot show
     * must not sit there invisibly overriding a default.
     */
    @Test
    fun `rows this build cannot understand are dropped, not kept`() {
        val read = InputBindings.fromText(
            "MOUSE:BACK=PAN;SPACEBALL:KNOB=DRAW_PEN;MOUSE:PRIMARY=TELEPORT;rubbish;=;"
        )

        assertEquals(InputAction.PAN, read.actionFor(InputDevice.MOUSE, InputButton.BACK))
        assertEquals(InputAction.DRAW_MOUSE, read.actionFor(InputDevice.MOUSE, InputButton.PRIMARY))
        assertEquals("MOUSE:BACK=PAN", read.toText())
    }

    @Test
    fun `nothing written down is nothing at all`() {
        assertEquals(InputAction.DRAW_PEN, InputBindings.fromText(null).actionFor(InputDevice.PEN, InputButton.NONE))
        assertEquals(InputAction.DRAW_PEN, InputBindings.fromText("").actionFor(InputDevice.PEN, InputButton.NONE))
    }

    @Test
    fun `every row shown can be acted on`() {
        for ((device, button) in InputBindings.rows()) {
            assertTrue(
                "$device $button has no action",
                fresh.actionFor(device, button) in InputAction.entries
            )
        }
    }
}
