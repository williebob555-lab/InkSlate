package com.inkslate.desktop

/**
 * What the X server's raw input events mean, kept apart from the native calls that fetch them so
 * that it can be tested on any machine.
 *
 * [X11Pointer] hands over each event with the device it came from; this decides whether that was
 * the pen's tip, its barrel button, a finger or the mouse, and tells [PenInput].
 */
class X11Reading(private val rootWidth: Int, private val rootHeight: Int) {

    enum class Kind { PEN, ERASER, TOUCH, MOUSE, IGNORE }

    data class Axis(val number: Int, val label: String, val min: Double, val max: Double) {
        /** Where [value] falls between the ends of this axis, 0..1. */
        fun fraction(value: Double): Double =
            if (max > min) ((value - min) / (max - min)).coerceIn(0.0, 1.0) else value
    }

    data class Device(
        val id: Int,
        val name: String,
        val kind: Kind,
        val pressure: Axis?,
        val x: Axis?,
        val y: Axis?
    ) {
        companion object {
            /**
             * Name a device from what the server says about it.
             *
             * Under KDE's Wayland session every device is XWayland's: `xwayland-tablet stylus:N`,
             * `xwayland-tablet eraser:N`, `xwayland-touch:N`, `xwayland-pointer:N`. Under a plain
             * X session they carry the hardware's own names ("Wacom HID 52C2 Pen stylus",
             * "ELAN Touchscreen"), so the axes are consulted too, not only the words.
             */
            fun of(id: Int, name: String, axes: List<Axis>, hasTouch: Boolean): Device {
                val n = name.lowercase()
                val pressure = axes.firstOrNull { it.label == "Abs Pressure" }
                val kind = when {
                    hasTouch || ("touch" in n && "touchpad" !in n) -> Kind.TOUCH
                    "eraser" in n -> Kind.ERASER
                    "pad" in n && "touchpad" !in n && pressure == null -> Kind.IGNORE
                    pressure != null || "stylus" in n || Regex("""\bpen\b""").containsMatchIn(n) ->
                        Kind.PEN
                    "keyboard" in n && axes.isEmpty() -> Kind.IGNORE
                    else -> Kind.MOUSE
                }
                fun axis(number: Int, vararg labels: String) =
                    axes.firstOrNull { it.label in labels } ?: axes.firstOrNull { it.number == number }
                return Device(
                    id = id,
                    name = name,
                    kind = kind,
                    pressure = pressure ?: if (kind == Kind.PEN || kind == Kind.ERASER) {
                        axes.firstOrNull { it.number == 2 }
                    } else null,
                    x = axis(0, "Abs MT Position X", "Abs X"),
                    y = axis(1, "Abs MT Position Y", "Abs Y")
                )
            }
        }
    }

    /** One raw event: the device it came from, its button or touch id, and the axes it moved. */
    data class Raw(val sourceId: Int, val detail: Int, val flags: Int, val axes: Map<Int, Double>)

    @Volatile
    var devices: Map<Int, Device> = emptyMap()

    private var tipDown = false
    private var held = 0
    private var lastPressure: Float? = null

    fun handle(type: Int, raw: Raw) {
        val device = devices[raw.sourceId] ?: return
        // A pointer the server made up out of a finger; the finger itself arrives as a touch.
        if (raw.flags and POINTER_EMULATED != 0) return

        when (device.kind) {
            Kind.PEN, Kind.ERASER -> pen(device, type, raw)
            Kind.TOUCH -> touch(device, type, raw)
            Kind.MOUSE -> if (type == X11Pointer.XI_RAW_MOTION ||
                type == X11Pointer.XI_RAW_BUTTON_PRESS
            ) PenInput.mouse()
            Kind.IGNORE -> Unit
        }
    }

    private fun pen(device: Device, type: Int, raw: Raw) {
        device.pressure?.let { axis ->
            raw.axes[axis.number]?.let { value ->
                lastPressure = axis.fraction(value).toFloat().takeIf { it > 0f }
            }
        }
        val eraser = device.kind == Kind.ERASER
        var lifted = false
        when (type) {
            X11Pointer.XI_RAW_BUTTON_PRESS -> when (raw.detail) {
                1 -> tipDown = true
                2 -> held = 1
                3 -> held = 2
            }
            X11Pointer.XI_RAW_BUTTON_RELEASE -> when (raw.detail) {
                1 -> { tipDown = false; lifted = true }
                2, 3 -> held = 0
            }
        }
        val button = if (eraser) 2 else held
        when {
            tipDown -> PenInput.pen(down = true, pressure = lastPressure, button = button)
            lifted -> PenInput.pen(down = false, pressure = null, button = 0)
            else -> PenInput.penHovering(button)
        }
    }

    /** Where each finger last was, since an update carries only the axes that moved. */
    private val fingerAt = HashMap<Int, DoubleArray>()

    private fun touch(device: Device, type: Int, raw: Raw) {
        when (type) {
            X11Pointer.XI_RAW_TOUCH_END -> {
                fingerAt.remove(raw.detail)
                PenInput.fingerUp(raw.detail)
            }
            X11Pointer.XI_RAW_TOUCH_BEGIN, X11Pointer.XI_RAW_TOUCH_UPDATE -> {
                val xAxis = device.x ?: return
                val yAxis = device.y ?: return
                val at = fingerAt.getOrPut(raw.detail) { doubleArrayOf(Double.NaN, Double.NaN) }
                raw.axes[xAxis.number]?.let { at[0] = onScreen(xAxis, it, rootWidth) }
                raw.axes[yAxis.number]?.let { at[1] = onScreen(yAxis, it, rootHeight) }
                if (at[0].isNaN() || at[1].isNaN()) return
                PenInput.finger(raw.detail, at[0].toFloat(), at[1].toFloat())
            }
        }
    }

    /**
     * A touch axis in screen pixels. XWayland scales its touch axes over the whole X screen; an
     * axis with no range is taken to be in pixels already.
     */
    private fun onScreen(axis: Axis, value: Double, extent: Int): Double =
        if (axis.max > axis.min) axis.fraction(value) * extent else value

    private companion object {
        /** XIPointerEmulated. */
        const val POINTER_EMULATED = 1 shl 16
    }
}
