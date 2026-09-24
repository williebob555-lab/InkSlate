package com.inkslate.desktop

import java.io.File

/**
 * Where this app keeps what it remembers about a machine: settings, the event log, working
 * copies, downloaded updates.
 *
 * One folder, `InkSlate`, under whatever the system calls the place for it - `%LOCALAPPDATA%` on
 * Windows, `$XDG_DATA_HOME` (normally `~/.local/share`) on Linux. Every path in the program is
 * built from here, so the two systems cannot end up with half their files in each other's layout.
 *
 * `LOCALAPPDATA` is honoured on any system because the tests set it to a sandbox of their own:
 * a test run must never touch the real app's settings on the machine it runs on.
 */
object AppDirs {

    val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    val isLinux: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Linux", ignoreCase = true)

    /** The app's own folder, created if it is not there yet. */
    val root: File by lazy { File(base(), "InkSlate").apply { mkdirs() } }

    /** A folder inside [root], created if it is not there yet. */
    fun dir(name: String): File = File(root, name).apply { mkdirs() }

    private fun base(): String {
        System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { return it }
        val home = System.getProperty("user.home")
        if (isWindows) return home
        System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { return it }
        return File(home, ".local/share").path
    }
}
