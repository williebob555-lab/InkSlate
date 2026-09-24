package com.inkslate.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/**
 * Reading the pen and the glass on Linux.
 *
 * The toolkit talks to the X server - under KDE's Wayland session that is XWayland, which KWin
 * feeds the tablet and the touchscreen to - and asks it for the core pointer and nothing else, so
 * a pen arrives as a mouse with no pressure and a second finger never arrives at all. The X
 * server's input extension (XInput 2) knows every device separately, with its pressure axis and
 * its touch points, and will tell any program that asks.
 *
 * So this opens a second, private connection to the same server and asks for the *raw* events of
 * every device. Raw events are a copy: selecting them takes nothing from the toolkit, which still
 * gets the pointer events that draw the stroke. (Selecting ordinary touch events would, because
 * the server stops making a mouse out of a finger for a window someone is taking touches from.)
 * What is read goes to [PenInput], exactly as the Windows reader's does.
 *
 * Everything fails soft: no X server, no extension, a library missing - the pen is a mouse, and
 * drawing works as it did.
 */
object X11Pointer {

    /** True when the connection is open and events are being read. */
    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    private var running = false

    /** What the reader has seen, for Settings -> Diagnostics and for the tests. */
    @Volatile
    var summary: String = "Not started."
        private set

    @Volatile
    private var seen = 0L

    @Volatile
    private var lastDevice = ""

    fun install(@Suppress("UNUSED_PARAMETER") window: Window) {
        if (active) return
        if (System.getenv("DISPLAY").isNullOrBlank()) {
            EventLog.info("pointer", "No X display; pen and touch stay as mouse")
            return
        }
        runCatching {
            val display = x11.XOpenDisplay(null) ?: error("could not open the display")

            val opcode = IntByReference()
            val ignored = IntByReference()
            if (x11.XQueryExtension(display, "XInputExtension", opcode, ignored, IntByReference()) == 0) {
                error("the X server has no input extension")
            }
            val major = IntByReference(2)
            val minor = IntByReference(2)
            if (xi.XIQueryVersion(display, major, minor) != 0) error("XInput 2 is not available")
            val touch = major.value > 2 || minor.value >= 2

            val root = x11.XDefaultRootWindow(display)
            val screen = x11.XDefaultScreen(display)
            val reading = X11Reading(
                rootWidth = x11.XDisplayWidth(display, screen),
                rootHeight = x11.XDisplayHeight(display, screen)
            )
            reading.devices = queryDevices(display)

            select(display, root, touch)
            x11.XFlush(display)

            running = true
            active = true
            Thread({ loop(display, opcode.value, reading) }, "x11-pointer").apply {
                isDaemon = true
                start()
            }
            summary = "XInput ${major.value}.${minor.value}: " +
                reading.devices.values.joinToString { "${it.id} ${it.name} (${it.kind.name.lowercase()})" }
            EventLog.info("pointer", "Reading pen and touch through $summary")
        }.onFailure {
            active = false
            EventLog.warn("pointer", "Pen and touch stay as mouse: ${it.message}")
        }
    }

    /** A line saying how many events have arrived and from what, for diagnosing a machine. */
    val status: String
        get() = "$summary; $seen events, last from $lastDevice"

    fun uninstall() {
        // The reading thread is parked inside the X library waiting for the next event and cannot
        // be woken from here; it stops at that event, and it is a daemon, so it never holds the
        // program open.
        running = false
        active = false
    }

    private fun select(display: Pointer, root: NativeLong, touch: Boolean) {
        val events = mutableListOf(
            XI_HIERARCHY_CHANGED, XI_RAW_BUTTON_PRESS, XI_RAW_BUTTON_RELEASE, XI_RAW_MOTION
        )
        if (touch) events += listOf(XI_RAW_TOUCH_BEGIN, XI_RAW_TOUCH_UPDATE, XI_RAW_TOUCH_END)

        val maskLen = (XI_LAST_EVENT shr 3) + 1
        val bits = Memory(maskLen.toLong()).apply { clear() }
        for (event in events) {
            val at = (event shr 3).toLong()
            bits.setByte(at, (bits.getByte(at).toInt() or (1 shl (event and 7))).toByte())
        }
        val mask = Memory(16).apply {
            clear()
            setInt(0, XI_ALL_DEVICES)
            setInt(4, maskLen)
            setPointer(8, bits)
        }
        xi.XISelectEvents(display, root, mask, 1)
    }

    private fun loop(display: Pointer, opcode: Int, reading: X11Reading) {
        // An XEvent is a union of 24 longs; the generic-event cookie sits inside it.
        val event = Memory(24L * 8).apply { clear() }
        while (running) {
            runCatching {
                x11.XNextEvent(display, event)
                if (event.getInt(0) != GENERIC_EVENT || event.getInt(32) != opcode) return@runCatching
                if (x11.XGetEventData(display, event) == 0) return@runCatching
                try {
                    val type = event.getInt(36)
                    val data = event.getPointer(48) ?: return@runCatching
                    if (type == XI_HIERARCHY_CHANGED) {
                        reading.devices = queryDevices(display)
                    } else {
                        val raw = raw(data)
                        seen++
                        lastDevice = reading.devices[raw.sourceId]?.let { "${it.name} (${it.kind.name.lowercase()})" }
                            ?: "unknown device ${raw.sourceId}"
                        reading.handle(type, raw)
                    }
                } finally {
                    x11.XFreeEventData(display, event)
                }
            }.onFailure {
                EventLog.warn("pointer", "Dropped an input event: ${it.message}")
            }
        }
    }

    /** Unpack an XIRawEvent: which device, which button or touch, and the axes it carries. */
    private fun raw(p: Pointer): X11Reading.Raw {
        val maskLen = p.getInt(64)
        val mask = p.getPointer(72)
        val values = p.getPointer(80)
        val axes = HashMap<Int, Double>()
        if (mask != null && values != null) {
            var k = 0L
            for (i in 0 until maskLen * 8) {
                if ((mask.getByte((i shr 3).toLong()).toInt() and (1 shl (i and 7))) != 0) {
                    axes[i] = values.getDouble(8 * k++)
                }
            }
        }
        return X11Reading.Raw(
            sourceId = p.getInt(52),
            detail = p.getInt(56),
            flags = p.getInt(60),
            axes = axes
        )
    }

    /** Every device the server knows, with the axes that matter here. */
    private fun queryDevices(display: Pointer): Map<Int, X11Reading.Device> {
        val count = IntByReference()
        val info = xi.XIQueryDevice(display, XI_ALL_DEVICES, count) ?: return emptyMap()
        try {
            val found = HashMap<Int, X11Reading.Device>()
            for (i in 0 until count.value) {
                val d = info.share(i * 40L)
                val id = d.getInt(0)
                val name = d.getPointer(8)?.getString(0).orEmpty()
                val use = d.getInt(16)
                if (use == XI_MASTER_POINTER || use == XI_MASTER_KEYBOARD) continue
                val classes = d.getPointer(32)
                val axes = ArrayList<X11Reading.Axis>()
                var touch = false
                for (j in 0 until d.getInt(28)) {
                    val c = classes?.getPointer(j * 8L) ?: continue
                    when (c.getInt(0)) {
                        XI_VALUATOR_CLASS -> axes += X11Reading.Axis(
                            number = c.getInt(8),
                            label = atomName(display, c.getNativeLong(16)),
                            min = c.getDouble(24),
                            max = c.getDouble(32)
                        )
                        XI_TOUCH_CLASS -> touch = true
                    }
                }
                found[id] = X11Reading.Device.of(id, name, axes, touch)
            }
            return found
        } finally {
            xi.XIFreeDeviceInfo(info)
        }
    }

    private fun atomName(display: Pointer, atom: NativeLong): String {
        if (atom.toLong() == 0L) return ""
        val p = x11.XGetAtomName(display, atom) ?: return ""
        return try { p.getString(0) } finally { x11.XFree(p) }
    }

    // ---- the bindings --------------------------------------------------------

    private const val GENERIC_EVENT = 35
    private const val XI_ALL_DEVICES = 0
    private const val XI_MASTER_POINTER = 1
    private const val XI_MASTER_KEYBOARD = 2
    private const val XI_VALUATOR_CLASS = 2
    private const val XI_TOUCH_CLASS = 8

    private const val XI_HIERARCHY_CHANGED = 11
    const val XI_RAW_BUTTON_PRESS = 15
    const val XI_RAW_BUTTON_RELEASE = 16
    const val XI_RAW_MOTION = 17
    const val XI_RAW_TOUCH_BEGIN = 22
    const val XI_RAW_TOUCH_UPDATE = 23
    const val XI_RAW_TOUCH_END = 24
    private const val XI_LAST_EVENT = 26

    @Suppress("FunctionName")
    private interface X11 : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XDefaultRootWindow(display: Pointer): NativeLong
        fun XDefaultScreen(display: Pointer): Int
        fun XDisplayWidth(display: Pointer, screen: Int): Int
        fun XDisplayHeight(display: Pointer, screen: Int): Int
        fun XQueryExtension(
            display: Pointer, name: String,
            opcode: IntByReference, event: IntByReference, error: IntByReference
        ): Int
        fun XNextEvent(display: Pointer, event: Pointer): Int
        fun XGetEventData(display: Pointer, cookie: Pointer): Int
        fun XFreeEventData(display: Pointer, cookie: Pointer)
        fun XGetAtomName(display: Pointer, atom: NativeLong): Pointer?
        fun XFree(data: Pointer): Int
        fun XFlush(display: Pointer): Int
    }

    @Suppress("FunctionName")
    private interface Xi : Library {
        fun XIQueryVersion(display: Pointer, major: IntByReference, minor: IntByReference): Int
        fun XISelectEvents(display: Pointer, window: NativeLong, masks: Pointer, count: Int): Int
        fun XIQueryDevice(display: Pointer, device: Int, count: IntByReference): Pointer?
        fun XIFreeDeviceInfo(info: Pointer)
    }

    // By their versioned names: the bare `libX11.so` only exists where the development
    // package is installed, which on an ordinary desktop it is not.
    private val x11: X11 by lazy { Native.load("libX11.so.6", X11::class.java) }
    private val xi: Xi by lazy { Native.load("libXi.so.6", Xi::class.java) }
}
