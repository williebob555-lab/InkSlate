package com.inkslate.desktop

import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import com.inkslate.core.InputButton
import com.inkslate.core.InputDevice

/**
 * Naming what just touched the screen, so the table can be asked about it.
 *
 * The naming and the deciding are deliberately separate. Everything platform-specific and
 * uncertain - Windows telling a desktop program nothing, a pen's barrel arriving as a right click,
 * a device that reports as something it is not - is confined here, and what happens as a result is
 * a lookup in a table anybody can read and change. Before this the two were the same chain of
 * conditions, which is why every new device changed what an old one did.
 */
object InputSignal {

    fun deviceOf(down: PointerInputChange): InputDevice = when {
        // Windows hands a desktop program nothing but mouse events, so where the window's own
        // messages are being read, that is the only thing that knows. See WindowsPointer.
        WindowsPointer.active -> when (WindowsPointer.device) {
            WindowsPointer.Device.PEN -> InputDevice.PEN
            WindowsPointer.Device.FINGER -> InputDevice.FINGER
            WindowsPointer.Device.MOUSE -> InputDevice.MOUSE
        }

        down.type == PointerType.Stylus -> InputDevice.PEN
        down.type == PointerType.Touch -> InputDevice.FINGER
        down.type == PointerType.Mouse -> InputDevice.MOUSE
        else -> InputDevice.UNKNOWN
    }

    /**
     * Which button, in the terms the table is written in.
     *
     * A pen is asked about its own buttons first, because on Windows a barrel press arrives as a
     * right click and would otherwise be indistinguishable from one - which is exactly the kind of
     * collision that used to be settled by the order of a chain of ifs.
     */
    fun buttonOf(device: InputDevice, buttons: PointerButtons): InputButton {
        if (device == InputDevice.PEN) {
            val held = if (WindowsPointer.active) WindowsPointer.penButton else 0
            return when {
                held >= 2 -> InputButton.PEN_TWO
                held == 1 -> InputButton.PEN_ONE
                buttons.isSecondaryPressed -> InputButton.SECONDARY
                else -> InputButton.NONE
            }
        }
        if (device == InputDevice.FINGER) return InputButton.NONE
        return when {
            buttons.isPrimaryPressed -> InputButton.PRIMARY
            buttons.isSecondaryPressed -> InputButton.SECONDARY
            buttons.isTertiaryPressed -> InputButton.MIDDLE
            buttons.isBackPressed -> InputButton.BACK
            buttons.isForwardPressed -> InputButton.FORWARD
            else -> InputButton.NONE
        }
    }
}
