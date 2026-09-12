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
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities
import kotlin.math.hypot

/**
 * Reading the pen and the glass, because the runtime will not.
 *
 * A desktop Java program on Windows is told nothing about what touched the screen. The toolkit
 * turns a pen and a single finger into mouse clicks and throws the rest away: no pressure, no way
 * to tell a pen from a left click, no second finger, no pinch. That is not a gap in this program -
 * there is no stylus or touch pointer type anywhere in the desktop stack to receive - so the only
 * place left to read them is where Windows delivers them, which is the window's own message loop.
 *
 * So this sits in front of that loop and reads the messages the toolkit discards, then passes every
 * one of them on untouched. Nothing is taken away from the toolkit: the mouse events it makes from
 * a pen still arrive and still draw the stroke. What is added is who is drawing it - [device] and
 * [pressure] - and the fingers it never mentions, which is what makes a pinch possible.
 *
 * It is additive on purpose. If the hook cannot be installed, or Windows is not the system, or any
 * part of it throws, the program behaves exactly as it did before: a pen is a mouse, and drawing
 * works. Nothing here is on the path that gets ink onto a page.
 */
object WindowsPointer {

    enum class Device { MOUSE, PEN, FINGER }

    /** What made the contact that is down now, or made the last one. */
    @Volatile
    var device: Device = Device.MOUSE
        private set

    /** 0..1 from a pen that reports it; null for anything that does not, including a mouse. */
    @Volatile
    var pressure: Float? = null
        private set

    /**
     * Which of the pen's buttons is held: 0 none, 1 the barrel, 2 the second one.
     *
     * Windows describes a pen's buttons as two separate things rather than a number - the barrel
     * switch, and the eraser end, which is what a second button on the shaft usually reports
     * itself as. They are kept apart here because the tablet gives each its own pen, with its own
     * colour, width and tool, and one button standing in for both would collapse the two.
     */
    @Volatile
    var penButton: Int = 0
        private set

    /** Fingers on the glass right now. */
    @Volatile
    var contacts: Int = 0
        private set

    /**
     * True while two or more fingers are down.
     *
     * A stroke that began under the first finger has to be abandoned when the second arrives -
     * otherwise a pinch leaves a line through the page - and this is what the drawing gesture
     * watches to know to drop it.
     */
    @Volatile
    var gesturing: Boolean = false
        private set

    /** True when the hook is in place, so the settings screen can say so rather than guess. */
    @Volatile
    var active: Boolean = false
        private set

    /** The last thing that happened, for Settings -> Diagnostics. */
    @Volatile
    var latest: String = "No pen or touch seen yet."
        private set

    /**
     * A two-finger movement, in screen pixels: how far the pair moved, and how much they spread.
     *
     * Delivered on the UI thread. The canvas turns this into a pan and a zoom about the midpoint,
     * which is the same gesture the tablet has.
     */
    var onGesture: ((centreX: Float, centreY: Float, dx: Float, dy: Float, zoom: Float) -> Unit)? =
        null

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

    private val fingers = ConcurrentHashMap<Int, FloatArray>()
    private var lastCentre: FloatArray? = null
    private var lastSpread = 0f

    /**
     * Whether the pen is on the glass.
     *
     * While it is, every contact is a palm and is ignored outright. A hand resting on a page is
     * the normal way to write, and without this it would read as a second finger - which cancels
     * the stroke being written, so writing with the hand down would delete its own line.
     */
    @Volatile
    private var penDown = false

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
        device = when {
            !stamped -> Device.MOUSE
            (extra and FROM_TOUCH) != 0L -> Device.FINGER
            else -> Device.PEN
        }
        if (device != Device.PEN) {
            pressure = null
            penButton = 0
            penDown = false
        }
        // A mouse moving is proof that no gesture is under way. Contacts are released by a message
        // that can be missed - a finger that leaves over the edge of the window, a window that
        // loses focus mid-pinch - and a contact that is never released would leave the program
        // believing two fingers are down, which is a program that has quietly stopped drawing.
        if (device == Device.MOUSE && fingers.isNotEmpty()) forgetContacts()
    }

    private fun forgetContacts() {
        fingers.clear()
        contacts = 0
        gesturing = false
        lastCentre = null
        lastSpread = 0f
    }

    private fun pointer(msg: Int, wParam: WPARAM, lParam: LPARAM) {
        val id = wParam.toInt() and 0xFFFF
        val kind = IntByReference()
        if (!user32.GetPointerType(id, kind)) return

        when (kind.value) {
            PT_PEN -> {
                device = Device.PEN
                penDown = msg != WM_POINTERUP
                if (penDown) forgetContacts()
                val info = POINTER_PEN_INFO()
                if (user32.GetPointerPenInfo(id, info)) {
                    info.read()
                    penButton = when {
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
                if (msg == WM_POINTERUP) {
                    pressure = null
                    penButton = 0
                }
                note(
                    "Pen  pressure ${pressure?.let { "%.2f".format(it) } ?: "none"}" +
                        when (penButton) {
                            1 -> "  barrel held"
                            2 -> "  second button held"
                            else -> ""
                        }
                )
            }

            PT_TOUCH -> {
                if (penDown) return
                device = Device.FINGER
                val x = (lParam.toInt() and 0xFFFF).toShort().toFloat()
                val y = ((lParam.toInt() shr 16) and 0xFFFF).toShort().toFloat()
                when (msg) {
                    WM_POINTERUP -> fingers.remove(id)
                    else -> fingers[id] = floatArrayOf(x, y)
                }
                regather()
            }

            PT_MOUSE -> device = Device.MOUSE
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
        if (count <= 0 || penDown) return
        val inputs = TOUCHINPUT().toArray(count).map { it as TOUCHINPUT }.toTypedArray()
        val handle = Pointer(lParam.toLong())
        if (!user32.GetTouchInputInfo(handle, count, inputs, inputs[0].size())) return

        device = Device.FINGER
        for (input in inputs) {
            input.read()
            // Hundredths of a pixel, in screen coordinates.
            val x = input.x / 100f
            val y = input.y / 100f
            if (input.dwFlags and TOUCHEVENTF_UP != 0) fingers.remove(input.dwID)
            else fingers[input.dwID] = floatArrayOf(x, y)
        }
        regather()
    }

    /** Work out what the fingers on the glass are collectively doing. */
    private fun regather() {
        val held = fingers.values.toList()
        contacts = held.size

        if (held.size < 2) {
            gesturing = false
            lastCentre = null
            lastSpread = 0f
            if (held.size == 1) note("One finger")
            return
        }

        val cx = held.sumOf { it[0].toDouble() }.toFloat() / held.size
        val cy = held.sumOf { it[1].toDouble() }.toFloat() / held.size
        // How far apart the fingers are, as a mean distance from their midpoint: the measure a
        // pinch changes and a two-finger drag leaves alone.
        val spread = held
            .sumOf { hypot((it[0] - cx).toDouble(), (it[1] - cy).toDouble()) }
            .let { (it / held.size).toFloat() }
            .let { if (it <= 0.01f) 0.01f else it }

        val previous = lastCentre
        val previousSpread = lastSpread
        lastCentre = floatArrayOf(cx, cy)
        lastSpread = spread

        // The first frame of a gesture only establishes where the fingers are; moving on it would
        // jump the page by however far apart they happened to land.
        if (previous == null || previousSpread <= 0f) {
            gesturing = true
            note("${held.size} fingers - gesture started")
            return
        }

        gesturing = true
        val dx = cx - previous[0]
        val dy = cy - previous[1]
        val zoom = (spread / previousSpread).coerceIn(0.5f, 2f)
        note("${held.size} fingers  moved %.0f,%.0f  zoom %.3f".format(dx, dy, zoom))

        val listener = onGesture ?: return
        SwingUtilities.invokeLater { runCatching { listener(cx, cy, dx, dy, zoom) } }
    }

    private fun note(what: String) {
        latest = what
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
