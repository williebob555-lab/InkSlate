package com.inkslate.desktop

import java.awt.Window
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities
import kotlin.math.hypot

/**
 * Who is touching the screen - pen, finger or mouse - and how hard, on any desktop system.
 *
 * A desktop Java program is told none of this by its toolkit: a pen and a single finger arrive as
 * mouse clicks, with no pressure and no second finger. So each system has a reader that goes
 * round the toolkit to where the system itself reports them - [WindowsPointer] reads the window's
 * own message loop, [X11Pointer] asks the X server's input extension (which is also how a program
 * like this one sees the pen under KDE's Wayland session, through XWayland) - and both report here.
 *
 * Everything the rest of the program reads is in this object, so drawing, palm rejection and the
 * two-finger pinch work the same whichever reader is supplying it. And it is additive: with no
 * reader running, a pen is a mouse and drawing works exactly as it did before.
 */
object PenInput {

    enum class Device { MOUSE, PEN, FINGER }

    /** What made the contact that is down now, or made the last one. */
    @Volatile
    var device: Device = Device.MOUSE
        internal set

    /** 0..1 from a pen that reports it; null for anything that does not, including a mouse. */
    @Volatile
    var pressure: Float? = null
        internal set

    /**
     * Which of the pen's buttons is held: 0 none, 1 the barrel, 2 the second one (or the eraser
     * end). The tablet gives each its own pen, with its own colour, width and tool.
     */
    @Volatile
    var penButton: Int = 0
        internal set

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

    /** True when a reader is running, so the settings screen can say so rather than guess. */
    val active: Boolean
        get() = WindowsPointer.active || X11Pointer.active

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

    fun install(window: Window) {
        when {
            AppDirs.isWindows -> WindowsPointer.install(window)
            AppDirs.isLinux -> X11Pointer.install(window)
        }
    }

    fun uninstall() {
        WindowsPointer.uninstall()
        X11Pointer.uninstall()
    }

    // ---- what the readers report ---------------------------------------------

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
    internal var penDown = false
        private set

    /** A pen reading: whether its tip is down, how hard, and which button is held. */
    internal fun pen(down: Boolean, pressure: Float?, button: Int) {
        device = Device.PEN
        penDown = down
        if (down) forgetContacts()
        this.pressure = if (down) pressure else null
        penButton = if (down) button else 0
        note(
            "Pen  pressure ${this.pressure?.let { "%.2f".format(it) } ?: "none"}" +
                when (penButton) {
                    1 -> "  barrel held"
                    2 -> "  second button held"
                    else -> ""
                }
        )
    }

    /** The pen moving above the glass, which is enough to say the pen is what is in use. */
    internal fun penHovering(button: Int) {
        device = Device.PEN
        if (!penDown) penButton = button
    }

    /**
     * Something other than the pen or the glass moved the pointer.
     *
     * A mouse moving is proof that no gesture is under way. Contacts are released by a message
     * that can be missed - a finger that leaves over the edge of the window, a window that loses
     * focus mid-pinch - and a contact that is never released would leave the program believing
     * two fingers are down, which is a program that has quietly stopped drawing.
     */
    internal fun mouse() {
        device = Device.MOUSE
        pressure = null
        penButton = 0
        penDown = false
        if (fingers.isNotEmpty()) forgetContacts()
    }

    /** A finger down or moving at a point on the screen, in screen pixels. */
    internal fun finger(id: Int, x: Float, y: Float) {
        if (penDown) return
        device = Device.FINGER
        fingers[id] = floatArrayOf(x, y)
        regather()
    }

    internal fun fingerUp(id: Int) {
        if (fingers.remove(id) != null) regather()
    }

    /** A promoted mouse message stamped as coming from touch: a finger, whatever else is known. */
    internal fun touchedSomewhere() {
        device = Device.FINGER
        pressure = null
        penButton = 0
        penDown = false
    }

    private fun forgetContacts() {
        owedX = 0f
        owedY = 0f
        owedZoom = 1f
        fingers.clear()
        contacts = 0
        gesturing = false
        lastCentre = null
        lastSpread = 0f
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

        post(cx, cy, dx, dy, zoom)
    }

    // ---- handing a gesture to the screen -------------------------------------

    private val waiting = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var owedX = 0f
    @Volatile private var owedY = 0f
    @Volatile private var owedZoom = 1f
    @Volatile private var owedCentreX = 0f
    @Volatile private var owedCentreY = 0f

    /**
     * Collect the movement and hand it over once, rather than once per contact message.
     *
     * Ten fingers' worth of contacts arrive together and a touchscreen reports far faster than a
     * screen redraws, so forwarding each one separately asked the page to move a hundred times
     * between two frames - every one of them a repaint that nobody ever saw. The movement adds up
     * and the zoom multiplies, so collecting them loses nothing: what arrives is the same gesture
     * with the same result, delivered once for each frame that can show it.
     */
    private fun post(cx: Float, cy: Float, dx: Float, dy: Float, zoom: Float) {
        owedX += dx
        owedY += dy
        owedZoom *= zoom
        owedCentreX = cx
        owedCentreY = cy
        if (onGesture == null) return
        if (waiting.getAndSet(true)) return

        SwingUtilities.invokeLater {
            waiting.set(false)
            val x = owedX
            val y = owedY
            val z = owedZoom
            owedX = 0f
            owedY = 0f
            owedZoom = 1f
            runCatching { onGesture?.invoke(owedCentreX, owedCentreY, x, y, z) }
        }
    }

    internal fun note(what: String) {
        latest = what
    }
}
