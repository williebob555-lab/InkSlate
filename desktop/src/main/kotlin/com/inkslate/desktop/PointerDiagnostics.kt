package com.inkslate.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo

/**
 * Where the app thinks the pointer is, against where the machine says it is.
 *
 * For one specific complaint: ink landing a long way from the pen. That can only be a disagreement
 * about coordinates, and there are only a few candidates - the display's scaling applied twice, a
 * position measured from the screen rather than from the window, or a viewport that has drifted
 * from what is drawn. Each leaves a different signature in these numbers, and one stroke is enough
 * to tell which.
 *
 * Written at most once every few seconds, so drawing with it on costs nothing and it can simply be
 * there rather than being a mode somebody has to know to turn on.
 */
object PointerDiagnostics {

    private const val QUIET_MS = 4_000L

    @Volatile private var lastAt = 0L

    /** The most recent reading, for the settings screen to show without waiting for a log line. */
    @Volatile var latest: String = "Nothing drawn yet."
        private set

    fun note(type: PointerType, atComponent: Offset, viewport: Viewport, onPage: Offset) {
        val now = System.currentTimeMillis()
        val reading = buildString {
            val screen = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
            val scale = runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
            }.getOrDefault(1.0)

            append(type.toString()).append("  in window ")
            append("%.0f,%.0f".format(atComponent.x, atComponent.y))
            if (screen != null) append("  cursor on screen ${screen.x},${screen.y}")
            append("  window ")
            append("%.0fx%.0f".format(viewport.viewSize.width, viewport.viewSize.height))
            append("  display scale ").append("%.2f".format(scale))
            append("  zoom ").append("%.2f".format(viewport.scale))
            append("  lands on page ")
            append("%.0f,%.0f".format(onPage.x, onPage.y))
        }
        latest = reading
        if (now - lastAt < QUIET_MS) return
        lastAt = now
        EventLog.info("pointer", reading)
    }
}
