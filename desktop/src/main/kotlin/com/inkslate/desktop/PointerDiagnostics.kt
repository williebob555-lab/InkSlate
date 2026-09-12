package com.inkslate.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import kotlin.math.abs

/**
 * Where the app thinks the pointer is, against where the machine says it is.
 *
 * For one specific complaint: ink landing a long way from the pen. That can only be a disagreement
 * about coordinates, and there are only a few candidates - the display's scaling applied twice, a
 * position measured from the screen rather than from the window, or a viewport that has drifted
 * from what is drawn.
 *
 * The number that settles it is [Reading.offBy]: the app's idea of the pointer, carried out to the
 * screen through the window's own position, minus where Windows says the cursor is. Zero means the
 * app is seeing the pointer exactly where it is and the fault is elsewhere; a few pixels is the
 * window frame being measured slightly differently at each end; hundreds is the fault itself.
 *
 * Several readings are kept rather than one, because a desktop program cannot tell a pen from a
 * mouse - AWT reports both as [PointerType.Mouse] and there is no device behind the event to ask.
 * Drawing one mark with each and comparing the two lines is the only way to attribute a fault to
 * the pen, so the panel keeps enough history for that to be a single look.
 */
object PointerDiagnostics {

    private const val QUIET_MS = 4_000L

    /** Enough to draw one mark with each device and still see both. */
    private const val KEEP = 5

    /**
     * Below this the two ends are not disagreeing about anything.
     *
     * The pointer's position is the one the event carried and the cursor's is read a moment later,
     * so a pointer that was moving when it landed shows tens of pixels of difference from nothing
     * worse than the gap between the two readings. The fault this is here to catch is hundreds.
     */
    private const val FRAME_SLACK = 80.0

    @Volatile private var lastAt = 0L

    /** Where the drawing surface sits inside the window, so a reading can reach screen space. */
    @Volatile private var canvasInWindow: Offset = Offset.Zero

    private val readings = ArrayDeque<String>()

    /** Told by the canvas as it is laid out; see [canvasInWindow]. */
    fun canvasAt(inWindow: Offset) {
        canvasInWindow = inWindow
    }

    /** The recent readings, newest first, for the settings screen to show as it finds them. */
    val latest: String
        get() = synchronized(readings) {
            if (readings.isEmpty()) "Nothing drawn yet." else readings.joinToString("\n")
        }

    fun note(type: PointerType, atComponent: Offset, viewport: Viewport, onPage: Offset) {
        val scale = displayScale()

        val reading = buildString {
            val what =
                if (WindowsPointer.active) WindowsPointer.device.name.lowercase().replaceFirstChar {
                    it.uppercase()
                } else type.toString()
            append(what).append("  in window ")
            append("%.0f,%.0f".format(atComponent.x, atComponent.y))

            val off = offBy(atComponent, scale)
            if (off == null) {
                append("  (no cursor to compare against)")
            } else {
                append("  off by ").append("%.0f,%.0f".format(off.x, off.y))
                append(if (withinFrame(off)) " ok" else " WRONG")
            }

            append("  zoom ").append("%.2f".format(viewport.scale))
            append("  scale ").append("%.2f".format(scale))
            append("  lands on ")
            append("%.0f,%.0f".format(onPage.x, onPage.y))
        }

        synchronized(readings) {
            readings.addFirst(reading)
            while (readings.size > KEEP) readings.removeLast()
        }

        val now = System.currentTimeMillis()
        if (now - lastAt < QUIET_MS) return
        lastAt = now
        EventLog.info("pointer", reading)
    }

    /**
     * How far the app's pointer is from the machine's cursor, in screen pixels.
     *
     * The window says where its frame is in AWT's own units, which are the scaled ones, so both
     * that and the cursor are multiplied up to real pixels - the units the canvas and the pointer
     * events are already in. Everything here can fail on a window that is closing, and a missing
     * reading is better than a crash in a diagnostic.
     */
    private fun offBy(atComponent: Offset, scale: Double): Offset? = runCatching {
        val cursor = MouseInfo.getPointerInfo()?.location ?: return null
        val canvas = canvasOriginOnScreen() ?: return null
        Offset(
            (canvas.x + atComponent.x - cursor.x * scale).toFloat(),
            (canvas.y + atComponent.y - cursor.y * scale).toFloat()
        )
    }.getOrNull()

    /**
     * Where the drawing surface's top-left sits on the screen, in real pixels.
     *
     * The window reports its frame in the scaled units the toolkit works in, so both that and the
     * inset are multiplied up to the units the canvas, the pointer events and Windows' own contact
     * messages are already in. Shared with the touch handling, which has to put a finger reported
     * against the screen onto the page.
     */
    fun canvasOriginOnScreen(): Offset? = runCatching {
        val scale = displayScale()
        val frame = Frame.getFrames().firstOrNull { it.isShowing } ?: return null
        val origin = frame.locationOnScreen
        val insets = frame.insets
        Offset(
            ((origin.x + insets.left) * scale + canvasInWindow.x).toFloat(),
            ((origin.y + insets.top) * scale + canvasInWindow.y).toFloat()
        )
    }.getOrNull()

    fun displayScale(): Double = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
    }.getOrDefault(1.0)

    /** Whether any kept reading disagrees, for the panel to lead with the answer. */
    val verdict: String
        get() {
            val lines = synchronized(readings) { readings.toList() }
            if (lines.isEmpty()) return "Draw one mark with the mouse and one with the pen."
            return if (lines.any { it.contains("WRONG") }) {
                "A mark landed away from the cursor. The \"off by\" figure on that line is how far."
            } else {
                "Every mark landed under the cursor."
            }
        }

    private fun withinFrame(off: Offset): Boolean =
        abs(off.x) <= FRAME_SLACK && abs(off.y) <= FRAME_SLACK
}
