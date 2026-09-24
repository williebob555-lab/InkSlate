package com.inkslate.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Component
import java.awt.Container
import java.awt.Window

/**
 * Reading the pen and the glass on Windows, because the runtime will not.
 *
 * The toolkit turns a pen and a single finger into mouse clicks and throws the rest away: no
 * pressure, no way to tell a pen from a left click, no second finger, no pinch. The only place
 * left to read them is where Windows delivers them, which is the window's own message loop.
 *
 * So this sits in front of that loop and reads the messages the toolkit discards, then passes every
 * one of them on untouched, and reports what it read to [PenInput]. Nothing is taken away from the
 * toolkit: the mouse events it makes from a pen still arrive and still draw the stroke.
 *
 * It is additive on purpose. If the hook cannot be installed, or Windows is not the system, or any
 * part of it throws, the program behaves exactly as it did before: a pen is a mouse, and drawing
 * works. Nothing here is on the path that gets ink onto a page.
 */
object WindowsPointer {

    /** True when the hook is in place. */
    @Volatile
    var active: Boolean = false
        private set

    // ---- installing ----------------------------------------------------------

    private val hooks = mutableListOf<Hook>()

    private class Hook(val hwnd: HWND, val previous: Pointer, val proc: WndProc)

    /**
     * Put the hook in front of every window that can receive pointer messages.
     *
     * Every heavyweight component is tried, not just the frame: the drawing surface is a child
     * window of its own and that is where contact messages are delivered, while some arrive at the
     * frame. Hooking both costs nothing - each message is passed straight on - and means this does
     * not depend on which component the toolkit happens to render into this year.
     */
    fun install(window: Window) {
        if (!isWindows() || active) return
        runCatching {
            val targets = heavyweight(window).mapNotNull { component ->
                runCatching { Native.getComponentPointer(component) }.getOrNull()
            }.distinct()

            for (target in targets) {
                val hwnd = HWND(target)
                val proc = object : WndProc {
                    override fun callback(
                        hWnd: HWND,
                        msg: Int,
                        wParam: WPARAM,
                        lParam: LPARAM
                    ): LRESULT {
                        // Whatever happens in here, the toolkit must still get its message: a
                        // window whose messages stop arriving is a window that stops working.
                        runCatching { read(msg, wParam, lParam) }
                        val hook = hooks.firstOrNull { it.hwnd.pointer == hWnd.pointer }
                        return if (hook != null) {
                            user32.CallWindowProcW(hook.previous, hWnd, msg, wParam, lParam)
                        } else {
                            LRESULT(0)
                        }
                    }
                }
                val previous = user32.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, proc)
                if (previous != null && Pointer.nativeValue(previous) != 0L) {
                    // The callback has to be reachable from Kotlin for as long as Windows may
                    // call it. Letting it be collected is a crash inside the message loop.
                    hooks += Hook(hwnd, previous, proc)
                }
            }

            active = hooks.isNotEmpty()
            EventLog.info(
                "pointer",
                if (active) "Reading pen and touch directly (${hooks.size} windows)"
                else "Could not read pen and touch; pointers stay as mouse"
            )
        }.onFailure {
            EventLog.warn("pointer", "Pen and touch stay as mouse: ${it.message}")
        }
    }

    fun uninstall() {
        runCatching {
            hooks.forEach { user32.SetWindowLongPtrW(it.hwnd, GWLP_WNDPROC, it.previous) }
        }
        hooks.clear()
        active = false
    }

    private fun heavyweight(root: Component): List<Component> = buildList {
        if (!root.isLightweight) add(root)
        if (root is Container) root.components.forEach { addAll(heavyweight(it)) }
    }

    private fun isWindows() =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    // ---- reading -------------------------------------------------------------

    private fun read(msg: Int, wParam: WPARAM, lParam: LPARAM) {
        when (msg) {
            WM_POINTERDOWN, WM_POINTERUPDATE, WM_POINTERUP -> pointer(msg, wParam, lParam)
            WM_TOUCH -> touch(wParam, lParam)
            WM_MOUSEMOVE, WM_LBUTTONDOWN, WM_RBUTTONDOWN -> promoted()
        }
    }

    /**
     * Which device a mouse message was made from.
     *
     * Windows stamps every mouse message it promotes out of pen or touch input with a signature,
     * and the stamp is readable only while that message is the one being handled - which is exactly
     * where this runs. It matters for two reasons: it is what puts the device back to the mouse
     * when the pen is set down, and it identifies a pen even where contact messages never arrive,
     * so the profile still follows the device on a machine that sends nothing but promoted clicks.
     */
    private fun promoted() {
        val extra = runCatching { user32.GetMessageExtraInfo().toLong() }.getOrNull() ?: return
        val stamped = (extra and SIGNATURE_MASK) == MI_WP_SIGNATURE
        when {
            !stamped -> PenInput.mouse()
            (extra and FROM_TOUCH) != 0L -> PenInput.touchedSomewhere()
            else -> PenInput.device = PenInput.Device.PEN
        }
    }

    private fun pointer(msg: Int, wParam: WPARAM, lParam: LPARAM) {
        val id = wParam.toInt() and 0xFFFF
        val kind = IntByReference()
        if (!user32.GetPointerType(id, kind)) return

        when (kind.value) {
            PT_PEN -> {
                var button = 0
                var pressure: Float? = null
                val info = POINTER_PEN_INFO()
                if (user32.GetPointerPenInfo(id, info)) {
                    info.read()
                    button = when {
                        (info.penFlags and (PEN_FLAG_ERASER or PEN_FLAG_INVERTED)) != 0 -> 2
                        (info.penFlags and PEN_FLAG_BARREL) != 0 -> 1
                        else -> 0
                    }
                    pressure = if (info.penMask and PEN_MASK_PRESSURE != 0 && info.pressure > 0) {
                        (info.pressure / PEN_PRESSURE_MAX).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                }
                PenInput.pen(down = msg != WM_POINTERUP, pressure = pressure, button = button)
            }

            PT_TOUCH -> {
                if (PenInput.penDown) return
                val x = (lParam.toInt() and 0xFFFF).toShort().toFloat()
                val y = ((lParam.toInt() shr 16) and 0xFFFF).toShort().toFloat()
                if (msg == WM_POINTERUP) PenInput.fingerUp(id) else PenInput.finger(id, x, y)
            }

            PT_MOUSE -> PenInput.device = PenInput.Device.MOUSE
        }
    }

    /**
     * The other way the same fingers can arrive.
     *
     * The toolkit registers its windows for the older touch message so that it can turn one finger
     * into a mouse, and a window registered that way is sent those instead of contact messages. So
     * both are read, and whichever Windows chooses to send is the one that works.
     *
     * The handle is deliberately not closed here. Whoever handles this message is required to close
     * it, and that is the toolkit, which still has to read the message after this returns - closing
     * it first would take the toolkit's own touch handling away, and with it the one finger that
     * currently draws.
     */
    private fun touch(wParam: WPARAM, lParam: LPARAM) {
        val count = wParam.toInt() and 0xFFFF
        if (count <= 0 || PenInput.penDown) return
        val inputs = TOUCHINPUT().toArray(count).map { it as TOUCHINPUT }.toTypedArray()
        val handle = Pointer(lParam.toLong())
        if (!user32.GetTouchInputInfo(handle, count, inputs, inputs[0].size())) return

        for (input in inputs) {
            input.read()
            // Hundredths of a pixel, in screen coordinates.
            val x = input.x / 100f
            val y = input.y / 100f
            if (input.dwFlags and TOUCHEVENTF_UP != 0) PenInput.fingerUp(input.dwID)
            else PenInput.finger(input.dwID, x, y)
        }
    }

    // ---- the bindings --------------------------------------------------------

    private const val GWLP_WNDPROC = -4
    private const val WM_TOUCH = 0x0240
    private const val WM_POINTERUPDATE = 0x0245
    private const val WM_POINTERDOWN = 0x0246
    private const val WM_POINTERUP = 0x0247

    private const val PT_TOUCH = 2
    private const val PT_PEN = 3
    private const val PT_MOUSE = 4

    private const val PEN_FLAG_BARREL = 0x00000001
    private const val PEN_FLAG_INVERTED = 0x00000002
    private const val PEN_FLAG_ERASER = 0x00000004
    private const val PEN_MASK_PRESSURE = 0x00000001

    /** What Windows calls full pressure. */
    private const val PEN_PRESSURE_MAX = 1024f

    private const val TOUCHEVENTF_UP = 0x0004

    private const val WM_MOUSEMOVE = 0x0200
    private const val WM_LBUTTONDOWN = 0x0201
    private const val WM_RBUTTONDOWN = 0x0204

    /** What Windows stamps on a mouse message it made out of pen or touch input. */
    private const val MI_WP_SIGNATURE = 0xFF515700L
    private const val SIGNATURE_MASK = 0xFFFFFF00L
    private const val FROM_TOUCH = 0x80L

    interface WndProc : StdCallLibrary.StdCallCallback {
        fun callback(hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
    }

    private interface User32Ex : StdCallLibrary {
        fun SetWindowLongPtrW(hWnd: HWND, index: Int, proc: WndProc): Pointer?
        fun SetWindowLongPtrW(hWnd: HWND, index: Int, proc: Pointer): Pointer?
        fun CallWindowProcW(
            previous: Pointer,
            hWnd: HWND,
            msg: Int,
            wParam: WPARAM,
            lParam: LPARAM
        ): LRESULT

        fun GetMessageExtraInfo(): LPARAM
        fun GetPointerType(pointerId: Int, pointerType: IntByReference): Boolean
        fun GetPointerPenInfo(pointerId: Int, penInfo: POINTER_PEN_INFO): Boolean
        fun GetTouchInputInfo(
            handle: Pointer,
            count: Int,
            inputs: Array<TOUCHINPUT>,
            size: Int
        ): Boolean
    }

    private val user32: User32Ex by lazy {
        Native.load("user32", User32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }

    @Structure.FieldOrder("x", "y")
    class POINT : Structure() {
        @JvmField var x: Int = 0
        @JvmField var y: Int = 0
    }

    @Structure.FieldOrder(
        "pointerType", "pointerId", "frameId", "pointerFlags", "sourceDevice", "hwndTarget",
        "ptPixelLocation", "ptHimetricLocation", "ptPixelLocationRaw", "ptHimetricLocationRaw",
        "dwTime", "historyCount", "inputData", "dwKeyStates", "performanceCount", "buttonChangeType"
    )
    class POINTER_INFO : Structure() {
        @JvmField var pointerType: Int = 0
        @JvmField var pointerId: Int = 0
        @JvmField var frameId: Int = 0
        @JvmField var pointerFlags: Int = 0
        @JvmField var sourceDevice: Pointer? = null
        @JvmField var hwndTarget: Pointer? = null
        @JvmField var ptPixelLocation: POINT = POINT()
        @JvmField var ptHimetricLocation: POINT = POINT()
        @JvmField var ptPixelLocationRaw: POINT = POINT()
        @JvmField var ptHimetricLocationRaw: POINT = POINT()
        @JvmField var dwTime: Int = 0
        @JvmField var historyCount: Int = 0
        @JvmField var inputData: Int = 0
        @JvmField var dwKeyStates: Int = 0
        @JvmField var performanceCount: Long = 0
        @JvmField var buttonChangeType: Int = 0
    }

    @Structure.FieldOrder(
        "pointerInfo", "penFlags", "penMask", "pressure", "rotation", "tiltX", "tiltY"
    )
    class POINTER_PEN_INFO : Structure() {
        @JvmField var pointerInfo: POINTER_INFO = POINTER_INFO()
        @JvmField var penFlags: Int = 0
        @JvmField var penMask: Int = 0
        @JvmField var pressure: Int = 0
        @JvmField var rotation: Int = 0
        @JvmField var tiltX: Int = 0
        @JvmField var tiltY: Int = 0
    }

    @Structure.FieldOrder(
        "x", "y", "hSource", "dwID", "dwFlags", "dwMask", "dwTime", "dwExtraInfo",
        "cxContact", "cyContact"
    )
    class TOUCHINPUT : Structure() {
        @JvmField var x: Int = 0
        @JvmField var y: Int = 0
        @JvmField var hSource: Pointer? = null
        @JvmField var dwID: Int = 0
        @JvmField var dwFlags: Int = 0
        @JvmField var dwMask: Int = 0
        @JvmField var dwTime: Int = 0
        @JvmField var dwExtraInfo: Pointer? = null
        @JvmField var cxContact: Int = 0
        @JvmField var cyContact: Int = 0
    }
}
