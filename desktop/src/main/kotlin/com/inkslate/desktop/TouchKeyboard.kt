package com.inkslate.desktop

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * The on-screen keyboard, for when there is no other one.
 *
 * Windows shows it by itself for programs that describe their text boxes through the accessibility
 * layer. A Java program does not: the whole window is one drawing surface as far as Windows is
 * concerned, with no text box in it to notice, so tapping a field with a finger in tablet mode left
 * nowhere to type.
 *
 * Asking for it is a COM call rather than running a program - the keyboard's host process is always
 * running on Windows 11, so starting it again does nothing at all. The call toggles, so the current
 * state has to be known before asking, and the obvious test for that is wrong: the keyboard's window
 * stays "visible" once it has been created and is hidden by being cloaked by the shell instead.
 * Asking the window manager whether it is cloaked is the question that has a true answer.
 *
 * Everything here fails soft. No Windows, no COM, a future release that renames the window - the
 * keyboard does not appear, which is where this started, and nothing else is affected.
 */
object TouchKeyboard {

    /** Whether the machine is folded into a tablet, which is the case this exists for. */
    fun inTabletPosture(): Boolean =
        runCatching { user32.GetSystemMetrics(SM_CONVERTIBLESLATEMODE) == 0 }.getOrDefault(false)

    /** Whether there is a touchscreen or pen at all, so a laptop without one is left alone. */
    fun hasDigitiser(): Boolean = runCatching {
        (user32.GetSystemMetrics(SM_DIGITIZER) and (NID_INTEGRATED_TOUCH or NID_INTEGRATED_PEN)) != 0
    }.getOrDefault(false)

    /**
     * Whether the keyboard is on the screen now.
     *
     * Null when the question cannot be answered - the window has never been created, or it has been
     * renamed by a Windows release. A caller that does not know the state must not toggle blindly,
     * or asking for a keyboard takes one away.
     */
    fun shown(): Boolean? = runCatching {
        val hwnd = keyboardWindow() ?: return null
        val cloaked = com.sun.jna.ptr.IntByReference()
        val hr = dwm.DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, cloaked, 4)
        if (hr != 0) return null
        cloaked.value == 0
    }.getOrNull()

    fun show() {
        if (shown() == true) return
        toggle()
    }

    fun hide() {
        // Only when it is known to be up: toggling on a guess is how a keyboard appears just as
        // somebody finishes typing.
        if (shown() != true) return
        toggle()
    }

    private fun keyboardWindow(): Pointer? = runCatching {
        user32.FindWindowW(CORE_WINDOW, INPUT_EXPERIENCE)
            ?: user32.FindWindowW(LEGACY_WINDOW, null)
    }.getOrNull()

    /**
     * Ask the keyboard's host to toggle.
     *
     * The interface has no type library, so the call is made through the object's own function
     * table: the three inherited entries first, then the one method this interface adds.
     */
    private fun toggle() {
        runCatching {
            Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, COINIT_APARTMENTTHREADED)
            val created = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(
                Guid.CLSID(UI_HOST_NO_LAUNCH),
                null,
                CLSCTX_INPROC_HANDLER or CLSCTX_LOCAL_SERVER,
                Guid.IID(TIP_INVOCATION),
                created
            )
            if (hr.toInt() < 0) {
                EventLog.warn("keyboard", "The on-screen keyboard refused to start: $hr")
                return
            }
            val instance = created.value ?: return
            val table = instance.getPointer(0)
            try {
                Function.getFunction(table.getPointer(SLOT_TOGGLE * Native.POINTER_SIZE.toLong()))
                    .invokeInt(arrayOf(instance, user32.GetDesktopWindow()))
            } finally {
                Function.getFunction(table.getPointer(SLOT_RELEASE * Native.POINTER_SIZE.toLong()))
                    .invokeInt(arrayOf(instance))
            }
        }.onFailure {
            EventLog.warn("keyboard", "Could not reach the on-screen keyboard: ${it.message}")
        }
    }

    // ---- the bindings --------------------------------------------------------

    private const val SM_DIGITIZER = 94
    private const val SM_CONVERTIBLESLATEMODE = 0x2003
    private const val NID_INTEGRATED_TOUCH = 0x01
    private const val NID_INTEGRATED_PEN = 0x04

    /** Cloaked by the shell rather than hidden, which is how these windows are put away. */
    private const val DWMWA_CLOAKED = 14

    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val CLSCTX_INPROC_HANDLER = 0x2
    private const val CLSCTX_LOCAL_SERVER = 0x4

    private const val UI_HOST_NO_LAUNCH = "{4CE576FA-83DC-4F88-951C-9D0782B4E376}"
    private const val TIP_INVOCATION = "{37C994E7-432B-4834-A2F7-DCE1F13B834B}"

    /** After QueryInterface, AddRef and Release, which every interface begins with. */
    private const val SLOT_RELEASE = 2L
    private const val SLOT_TOGGLE = 3L

    private const val CORE_WINDOW = "Windows.UI.Core.CoreWindow"
    private const val INPUT_EXPERIENCE = "Windows Input Experience"

    /** What the same window was called before Windows 11. */
    private const val LEGACY_WINDOW = "IPTip_Main_Window"

    private interface User32Ex : StdCallLibrary {
        fun GetSystemMetrics(index: Int): Int
        fun GetDesktopWindow(): Pointer
        fun FindWindowW(className: String?, windowName: String?): Pointer?
    }

    private interface Dwm : StdCallLibrary {
        fun DwmGetWindowAttribute(
            hwnd: Pointer,
            attribute: Int,
            value: com.sun.jna.ptr.IntByReference,
            size: Int
        ): Int
    }

    private val user32: User32Ex by lazy {
        Native.load("user32", User32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }

    private val dwm: Dwm by lazy {
        Native.load("dwmapi", Dwm::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}
