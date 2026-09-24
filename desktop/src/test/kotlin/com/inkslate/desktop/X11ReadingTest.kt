package com.inkslate.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the raw input events from the X server mean, fed in the shapes XWayland produces under
 * KDE. Runs on any machine: the native half only fetches these, it decides nothing.
 */
class X11ReadingTest {

    private val xy = listOf(
        X11Reading.Axis(0, "Abs X", 0.0, 65535.0),
        X11Reading.Axis(1, "Abs Y", 0.0, 65535.0)
    )
    private val stylus = X11Reading.Device.of(
        10, "xwayland-tablet stylus:11",
        xy + X11Reading.Axis(2, "Abs Pressure", 0.0, 65535.0), hasTouch = false
    )
    private val eraser = X11Reading.Device.of(
        11, "xwayland-tablet eraser:11",
        xy + X11Reading.Axis(2, "Abs Pressure", 0.0, 65535.0), hasTouch = false
    )
    private val touch = X11Reading.Device.of(
        12, "xwayland-touch:14",
        listOf(
            X11Reading.Axis(0, "Abs MT Position X", 0.0, 65535.0),
            X11Reading.Axis(1, "Abs MT Position Y", 0.0, 65535.0)
        ),
        hasTouch = true
    )
    private val mouse = X11Reading.Device.of(13, "xwayland-pointer:15", xy, hasTouch = false)

    private lateinit var reading: X11Reading

    @Before
    fun setUp() {
        reading = X11Reading(rootWidth = 2000, rootHeight = 1000)
        reading.devices = listOf(stylus, eraser, touch, mouse).associateBy { it.id }
        PenInput.mouse()
    }

    private fun raw(device: X11Reading.Device, detail: Int = 0, vararg axes: Pair<Int, Double>) =
        X11Reading.Raw(device.id, detail, 0, axes.toMap())

    @Test
    fun `devices are told apart by what XWayland calls them`() {
        assertEquals(X11Reading.Kind.PEN, stylus.kind)
        assertEquals(X11Reading.Kind.ERASER, eraser.kind)
        assertEquals(X11Reading.Kind.TOUCH, touch.kind)
        assertEquals(X11Reading.Kind.MOUSE, mouse.kind)
        assertEquals(
            X11Reading.Kind.IGNORE,
            X11Reading.Device.of(14, "xwayland-tablet-pad:16", emptyList(), false).kind
        )
        // A touchpad is a mouse, whatever the word "touch" in its name suggests.
        assertEquals(
            X11Reading.Kind.MOUSE,
            X11Reading.Device.of(15, "SYNA2393:00 06CB:7A13 Touchpad", xy, false).kind
        )
        // Under a plain X session the hardware's own names come through.
        assertEquals(
            X11Reading.Kind.PEN,
            X11Reading.Device.of(16, "Wacom HID 52C2 Pen stylus", xy, false).kind
        )
    }

    @Test
    fun `the pen tip carries pressure and a lift clears it`() {
        reading.handle(X11Pointer.XI_RAW_MOTION, raw(stylus, 0, 2 to 0.0))
        assertEquals(PenInput.Device.PEN, PenInput.device)
        assertFalse(PenInput.penDown)

        reading.handle(X11Pointer.XI_RAW_BUTTON_PRESS, raw(stylus, 1, 2 to 32767.5))
        assertTrue(PenInput.penDown)
        assertEquals(0.5f, PenInput.pressure!!, 0.001f)
        assertEquals(0, PenInput.penButton)

        reading.handle(X11Pointer.XI_RAW_MOTION, raw(stylus, 0, 2 to 65535.0))
        assertEquals(1f, PenInput.pressure!!, 0.001f)

        reading.handle(X11Pointer.XI_RAW_BUTTON_RELEASE, raw(stylus, 1, 2 to 0.0))
        assertFalse(PenInput.penDown)
        assertNull(PenInput.pressure)
    }

    @Test
    fun `the barrel and the eraser end are buttons one and two`() {
        reading.handle(X11Pointer.XI_RAW_BUTTON_PRESS, raw(stylus, 2))
        reading.handle(X11Pointer.XI_RAW_BUTTON_PRESS, raw(stylus, 1, 2 to 30000.0))
        assertEquals(1, PenInput.penButton)
        reading.handle(X11Pointer.XI_RAW_BUTTON_RELEASE, raw(stylus, 1))
        reading.handle(X11Pointer.XI_RAW_BUTTON_RELEASE, raw(stylus, 2))

        reading.handle(X11Pointer.XI_RAW_BUTTON_PRESS, raw(eraser, 1, 2 to 30000.0))
        assertEquals(2, PenInput.penButton)
    }

    @Test
    fun `two fingers make a gesture, and the palm is ignored while the pen writes`() {
        reading.handle(X11Pointer.XI_RAW_TOUCH_BEGIN, raw(touch, 1, 0 to 16383.75, 1 to 32767.5))
        assertEquals(PenInput.Device.FINGER, PenInput.device)
        assertEquals(1, PenInput.contacts)
        // An update that moves only one axis keeps the other where it was.
        reading.handle(X11Pointer.XI_RAW_TOUCH_UPDATE, raw(touch, 1, 0 to 17000.0))
        assertEquals(1, PenInput.contacts)
        reading.handle(X11Pointer.XI_RAW_TOUCH_BEGIN, raw(touch, 2, 0 to 40000.0, 1 to 32767.5))
        assertTrue(PenInput.gesturing)
        reading.handle(X11Pointer.XI_RAW_TOUCH_END, raw(touch, 2))
        reading.handle(X11Pointer.XI_RAW_TOUCH_END, raw(touch, 1))
        assertFalse(PenInput.gesturing)
        assertEquals(0, PenInput.contacts)

        reading.handle(X11Pointer.XI_RAW_BUTTON_PRESS, raw(stylus, 1, 2 to 30000.0))
        reading.handle(X11Pointer.XI_RAW_TOUCH_BEGIN, raw(touch, 3, 0 to 100.0, 1 to 100.0))
        assertEquals(PenInput.Device.PEN, PenInput.device)
        assertEquals(0, PenInput.contacts)
    }

    @Test
    fun `the mouse moving puts the device back to the mouse`() {
        reading.handle(X11Pointer.XI_RAW_BUTTON_PRESS, raw(stylus, 1, 2 to 30000.0))
        reading.handle(X11Pointer.XI_RAW_BUTTON_RELEASE, raw(stylus, 1))
        reading.handle(X11Pointer.XI_RAW_MOTION, raw(mouse, 0, 0 to 5.0))
        assertEquals(PenInput.Device.MOUSE, PenInput.device)
    }

    @Test
    fun `a pointer the server emulated from a finger is not mistaken for the mouse`() {
        reading.handle(X11Pointer.XI_RAW_TOUCH_BEGIN, raw(touch, 1, 0 to 100.0, 1 to 100.0))
        reading.handle(
            X11Pointer.XI_RAW_MOTION,
            X11Reading.Raw(mouse.id, 0, 1 shl 16, mapOf(0 to 5.0))
        )
        assertEquals(PenInput.Device.FINGER, PenInput.device)
        reading.handle(X11Pointer.XI_RAW_TOUCH_END, raw(touch, 1))
    }
}

class LinuxDisplayTest {
    @org.junit.Test
    fun `the XWayland scale is read from its own section of kwinrc`() {
        val f = java.io.File.createTempFile("kwinrc", "")
        f.writeText("[Compositing]\nScale=3\n\n[Xwayland]\nScale=1.5\n")
        org.junit.Assert.assertEquals(1.5, LinuxDisplay.kwinXwaylandScale(f)!!, 0.0001)
        f.writeText("[Compositing]\nScale=3\n")
        org.junit.Assert.assertNull(LinuxDisplay.kwinXwaylandScale(f))
        f.delete()
    }
}
