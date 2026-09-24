package com.inkslate.desktop

import java.awt.Desktop
import java.io.File

/**
 * Handing things to the system's own programs: a link to the browser, a file to the file manager.
 *
 * Windows and Linux do each of these differently, and the runtime's own `Desktop` class is the
 * least reliable way on both - it has refused perfectly good files on Windows, and on a Wayland
 * desktop it depends on GNOME libraries that a KDE machine need not have. So each system is asked
 * in its own words first, and `Desktop` is only the last resort.
 */
object SystemShell {

    /** Open a web page in whatever the machine uses for the web. */
    fun openUrl(url: String) {
        firstThatWorks(
            "open $url",
            { desktop(Desktop.Action.BROWSE) { browse(java.net.URI(url)) } },
            { run(if (AppDirs.isWindows) listOf("explorer.exe", url) else listOf("xdg-open", url)) }
        )
    }

    /**
     * Show [file] in the file manager, selected, so it can be dragged somewhere.
     *
     * On Linux that is the freedesktop file-manager interface, which Dolphin answers and so does
     * every other file manager worth the name; failing that, the folder is opened without the
     * selection, which is still the right place to be.
     */
    fun reveal(file: File) {
        if (AppDirs.isWindows) {
            run(listOf("explorer.exe", "/select,", file.absolutePath))
            return
        }
        firstThatWorks(
            "show ${file.name}",
            {
                val uri = file.absoluteFile.toURI().toASCIIString()
                val done = ProcessBuilder(
                    "dbus-send", "--session", "--print-reply",
                    "--dest=org.freedesktop.FileManager1", "--type=method_call",
                    "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowItems",
                    "array:string:$uri", "string:"
                ).redirectErrorStream(true).start().waitFor()
                check(done == 0) { "no file manager answered" }
            },
            { run(listOf("xdg-open", (file.parentFile ?: file).absolutePath)) }
        )
    }

    /** Open [file] with whatever the system opens that kind of file with. */
    fun open(file: File) {
        firstThatWorks(
            "open ${file.name}",
            { run(if (AppDirs.isWindows) listOf("explorer.exe", file.absolutePath) else listOf("xdg-open", file.absolutePath)) },
            { desktop(Desktop.Action.OPEN) { open(file) } }
        )
    }

    private fun run(command: List<String>) {
        ProcessBuilder(command).redirectErrorStream(true).start()
    }

    private fun desktop(action: Desktop.Action, what: Desktop.() -> Unit) {
        check(Desktop.isDesktopSupported()) { "no desktop integration" }
        val desktop = Desktop.getDesktop()
        check(desktop.isSupported(action)) { "$action unsupported" }
        desktop.what()
    }

    private fun firstThatWorks(what: String, vararg ways: () -> Unit) {
        var last: Throwable? = null
        for (way in ways) {
            val result = runCatching { way() }
            if (result.isSuccess) return
            last = result.exceptionOrNull()
        }
        EventLog.warn("shell", "Could not $what: ${last?.message}")
        throw last ?: IllegalStateException("Could not $what")
    }
}
