package com.inkslate.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How big everything is drawn: the screen's scale, or the player's own.
 *
 * Java on Linux scales a window only by whole numbers - `sun.java2d.uiScale=1.5` is taken as 1 -
 * so on a laptop screen at 150% the app came out at two thirds of its size, however the scale was
 * handed over. So the size is set here instead, in Compose's density, which takes any number:
 * the scale the desktop is set to (read from its settings, [desktopScale]), or the one chosen in
 * Settings. Whatever Java did scale by is divided out, so nothing is scaled twice.
 */
object UiScale {
    private const val KEY = "ui_scale"
    const val MIN = 0.5f
    const val MAX = 3f
    const val STEP = 0.1f

    /** The size chosen in Settings (1.5 for 150%), or null to follow the screen. */
    var chosen by mutableStateOf(DesktopPrefs.get(KEY)?.toFloatOrNull()?.coerceIn(MIN, MAX))
        private set

    fun choose(scale: Float?) {
        val s = scale?.let { (it.coerceIn(MIN, MAX) * 100).roundToInt() / 100f }
        chosen = s
        DesktopPrefs.put(KEY, s?.toString())
        EventLog.info("display", if (s == null) "Size: automatic" else "Size: ${(s * 100).roundToInt()}%")
    }

    /** One step bigger or smaller than [now]. */
    fun step(now: Float, by: Int) = choose(((now / STEP).roundToInt() + by) * STEP)

    /** The desktop's own scale on Linux, from its settings; null elsewhere or when none is found. */
    val desktopScale: Double? by lazy { if (AppDirs.isLinux) detect() else null }

    /** The size used when none is chosen, given the scale Java drew at. */
    fun automatic(javaScale: Float): Float = desktopScale?.toFloat() ?: javaScale

    /** The scale Java draws the main screen at. */
    fun javaScale(): Float = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toFloat()
    }.getOrDefault(1f)

    /** The size things are drawn at now. */
    fun current(javaScale: Float): Float = chosen ?: automatic(javaScale)

    /** Where the automatic size came from, for Settings. */
    val source: String by lazy { if (AppDirs.isLinux) found ?: "not found" else "Windows" }
    @Volatile private var found: String? = null

    private fun detect(): Double? {
        fun take(from: String, v: Double?): Double? = v?.takeIf { it in 0.5..4.0 }?.also {
            found = from
            EventLog.info("display", "The desktop's scale is $it ($from)")
        }
        val env = System.getenv()
        env["INKSLATE_SCALE"]?.toDoubleOrNull()?.let { take("INKSLATE_SCALE", it) }?.let { return it }
        val gdk = env["GDK_SCALE"]?.toDoubleOrNull()
        if (gdk != null) take("GDK_SCALE", gdk * (env["GDK_DPI_SCALE"]?.toDoubleOrNull() ?: 1.0))?.let { return it }
        env["QT_SCALE_FACTOR"]?.toDoubleOrNull()?.let { take("QT_SCALE_FACTOR", it) }?.let { return it }
        val config = File(env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() } ?: File(System.getProperty("user.home"), ".config").path)
        take("KWin, Xwayland", LinuxDisplay.kwinXwaylandScale(File(config, "kwinrc")))?.let { return it }
        take("KWin, the screen", kwinOutputScale(File(config, "kwinoutputconfig.json")))?.let { return it }
        take("KDE, X11", iniValue(File(config, "kdeglobals"), "KScreen", "ScaleFactor"))?.let { return it }
        take("Xft.dpi", xftDpi()?.let { it / 96.0 })?.let { return it }
        EventLog.info("display", "The desktop's scale was not found; drawing at the runtime's")
        return null
    }

    /** Plasma 6 on Wayland: the first screen's `"scale"` in `kwinoutputconfig.json`. */
    internal fun kwinOutputScale(file: File): Double? = runCatching {
        Regex("\"scale\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").findAll(file.readText())
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .firstOrNull { it > 0 }
    }.getOrNull()

    internal fun iniValue(file: File, section: String, key: String): Double? = runCatching {
        var inSection = false
        for (line in file.readLines()) {
            val t = line.trim()
            if (t.startsWith("[")) { inSection = t == "[$section]"; continue }
            if (inSection && t.startsWith("$key=")) return t.removePrefix("$key=").toDoubleOrNull()
        }
        null
    }.getOrNull()

    private fun xftDpi(): Double? = runCatching {
        val p = ProcessBuilder("xrdb", "-query").redirectErrorStream(true).start()
        if (!p.waitFor(1, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        p.inputStream.bufferedReader().readLines()
            .firstOrNull { it.startsWith("Xft.dpi:") }
            ?.substringAfter(':')?.trim()?.toDoubleOrNull()
    }.getOrNull()
}

/** Everything inside drawn at [UiScale]'s size. */
@Composable
fun UiScaled(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val want = UiScale.current(base.density)
    if (abs(want - base.density) < 0.005f) content()
    else CompositionLocalProvider(LocalDensity provides Density(want, base.fontScale), content = content)
}
