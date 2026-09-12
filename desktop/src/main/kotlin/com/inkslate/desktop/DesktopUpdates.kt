package com.inkslate.desktop

import com.inkslate.core.UpdateCheck
import java.awt.Desktop
import java.io.File

/**
 * The Windows half of updating in place.
 *
 * The comparison and the download are `core/UpdateCheck`, shared with the tablet, so the two
 * builds cannot disagree about which release is newer. Only the last two steps differ: this one
 * asks a release for its `.msi` and then hands the file to Windows, where Android hands an APK to
 * the package installer.
 */
object DesktopUpdates {

    /**
     * The version this build believes it is.
     *
     * Set by the packaging step as a JVM property, from the same value the tag produced, so the
     * installer's version and the one the updater compares against cannot drift apart. Running
     * straight from Gradle there is no packaging step, hence the development fallback - which
     * compares older than any published release, so a check from a dev build always offers one.
     */
    fun installedVersion(): String = System.getProperty("inkslate.version")?.takeIf {
        it.isNotBlank()
    } ?: "0.0.0"

    /**
     * Which builds this machine is willing to be offered.
     *
     * Off by default. Switching it on is the only way a pre-release can be seen at all: the
     * endpoint the stable channel asks does not return them, so this is a genuine opt-in rather
     * than a filter applied afterwards.
     */
    fun channel(): UpdateCheck.Channel =
        if (testChannelEnabled()) UpdateCheck.Channel.TEST else UpdateCheck.Channel.STABLE

    fun testChannelEnabled(): Boolean = DesktopPrefs.get(K_TEST_CHANNEL) == "true"

    fun setTestChannel(on: Boolean) = DesktopPrefs.put(K_TEST_CHANNEL, on.toString())

    private const val K_TEST_CHANNEL = "update_test_channel"

    /**
     * Open a page in whatever the machine uses for the web.
     *
     * Falls back to handing the address to Explorer, which knows what to do with one, because
     * `Desktop.browse` is unsupported on some window managers and throwing there would turn a link
     * into a button that does nothing.
     */
    fun openInBrowser(url: String) {
        val opened = runCatching {
            if (Desktop.isDesktopSupported() &&
                Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
            ) {
                Desktop.getDesktop().browse(java.net.URI(url))
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (!opened) runCatching { ProcessBuilder("explorer.exe", url).start() }
    }

    /** Downloads land beside the working copies rather than in the user's Downloads folder. */
    fun downloadDir(): File {
        val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
        return File(base, "InkSlate/updates").apply { mkdirs() }
    }

    /**
     * Take the "downloaded from the internet" mark off a file.
     *
     * Windows attaches that mark to anything a browser saves, and then scans the whole file before
     * the installer's first window appears - which for a hundred-megabyte installer is minutes of
     * apparently nothing happening. A file written by this app has no mark to begin with, so this
     * is belt and braces: it costs a deleted stream and removes a class of "it is broken" that is
     * really "it is thinking".
     *
     * The mark is an NTFS alternate data stream, so removing it is deleting a file whose name has
     * the stream appended. Anywhere else that is simply a file that does not exist.
     */
    fun unblock(file: File) {
        runCatching { File(file.absolutePath + ":Zone.Identifier").delete() }
    }

    /** Hand the installer to Windows. It asks for its own confirmation before it does anything. */
    fun open(installer: File) {
        require(installer.isFile) { "The downloaded installer is no longer there." }
        unblock(installer)
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(installer)
        } else {
            // Some JDK/desktop-environment combinations report no support for OPEN even where it
            // works. Explorer will run an .msi as readily as a double-click does.
            ProcessBuilder("explorer.exe", installer.absolutePath).start()
        }
    }

    /** Show the file in Explorer, for when opening it is not what the person wants yet. */
    fun reveal(installer: File) {
        ProcessBuilder("explorer.exe", "/select,", installer.absolutePath).start()
    }
}
