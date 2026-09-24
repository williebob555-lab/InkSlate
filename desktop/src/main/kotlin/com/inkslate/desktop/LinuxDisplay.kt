package com.inkslate.desktop

import java.io.File

/**
 * Getting the window's size right on a scaled KDE display.
 *
 * Java finds its display scale from `GDK_SCALE`, which KDE does not set. Under Plasma's Wayland
 * session with "legacy applications: apply scaling themselves" (the default), XWayland runs at the
 * panel's real resolution and a program is expected to read the scale from KWin's settings; one
 * that does not comes out at a third or a half of its proper size on a 150% laptop screen. So the
 * scale KWin gives XWayland is read and handed to the runtime, which must happen before the first
 * window exists - hence a call at the very top of `main`.
 *
 * Anything explicit wins: a `GDK_SCALE` in the environment, or a `-Dsun.java2d.uiScale` given on
 * the command line.
 */
object LinuxDisplay {

    fun prepare() {
        if (!AppDirs.isLinux) return
        if (System.getProperty("sun.java2d.uiScale") != null) return
        if (!System.getenv("GDK_SCALE").isNullOrBlank()) return
        val scale = kwinXwaylandScale() ?: return
        if (scale <= 1.01) return
        System.setProperty("sun.java2d.uiScale.enabled", "true")
        System.setProperty("sun.java2d.uiScale", "%.2f".format(java.util.Locale.ROOT, scale))
        EventLog.info("display", "Scaling the window by $scale, as KWin scales XWayland")
    }

    /** `[Xwayland] Scale=` in `~/.config/kwinrc`. */
    internal fun kwinXwaylandScale(
        config: File = File(
            System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: File(System.getProperty("user.home"), ".config").path,
            "kwinrc"
        )
    ): Double? = runCatching {
        var inSection = false
        for (line in config.readLines()) {
            val t = line.trim()
            if (t.startsWith("[")) {
                inSection = t == "[Xwayland]"
                continue
            }
            if (inSection && t.startsWith("Scale=")) return t.removePrefix("Scale=").toDoubleOrNull()
        }
        null
    }.getOrNull()
}
