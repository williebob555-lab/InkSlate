package com.inkslate.desktop

import java.io.File
import java.util.Properties

/**
 * The desktop counterpart of Android's `SharedPreferences`.
 *
 * A properties file rather than [java.util.prefs.Preferences], for one reason: the JDK's
 * preference store caps a single value at 8 KB, and the library is a newline-joined list of
 * absolute paths. Forty recent documents in a deeply nested OneDrive folder can pass that, and
 * the failure mode is a silent refusal to save - the list simply stops growing. A file has no
 * such ceiling, and it can be read by a human when something looks wrong.
 *
 * Kept beside the working copies in `%LOCALAPPDATA%\InkSlate` so everything this app remembers
 * about a machine is in one directory.
 */
object DesktopPrefs {

    private val file: File by lazy {
        val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
        File(base, "InkSlate").apply { mkdirs() }.let { File(it, "settings.properties") }
    }

    private val props: Properties by lazy {
        Properties().apply {
            runCatching { if (file.isFile) file.inputStream().use { load(it) } }
        }
    }

    @Synchronized
    fun get(key: String, fallback: String? = null): String? = props.getProperty(key) ?: fallback

    @Synchronized
    fun put(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        // Written through on every change: these are a handful of keys, and losing which folders
        // are on Home because the app was closed from the taskbar would be its own small betrayal.
        runCatching {
            val tmp = File(file.parentFile, "." + file.name + ".tmp")
            tmp.outputStream().use { props.store(it, "InkSlate") }
            if (!tmp.renameTo(file)) {
                file.outputStream().use { props.store(it, "InkSlate") }
                tmp.delete()
            }
        }
    }

    /** Path lists are stored one per line; a path cannot contain a newline. */
    fun getList(key: String): List<String> =
        get(key).orEmpty().split('\n').filter { it.isNotBlank() }

    fun putList(key: String, values: List<String>) = put(key, values.joinToString("\n"))
}
