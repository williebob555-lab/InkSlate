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

    /** Open a page in whatever the machine uses for the web. */
    fun openInBrowser(url: String) {
        runCatching { SystemShell.openUrl(url) }
    }

    /** Which files this system installs from. */
    val platform: UpdateCheck.Platform =
        if (AppDirs.isLinux) UpdateCheck.Platform.LINUX else UpdateCheck.Platform.WINDOWS

    /** Downloads land beside the working copies rather than in the user's Downloads folder. */
    fun downloadDir(): File {
        return AppDirs.dir("updates")
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

    /**
     * Hand the installer to Windows. It asks for its own confirmation before it does anything.
     *
     * Three ways of saying the same thing, tried in turn, because the first one has been seen to
     * fail on a perfectly good file: `Desktop.open` reported "Unsupported URI content" for a 103MB
     * installer that Explorer opened without complaint. It is the runtime's own shell integration
     * and there is nothing to fix at this end, so this stops depending on it.
     *
     * Windows Installer first, since an `.msi` is its file and it needs no association to be
     * registered; then the shell, which is what a double-click does; then the runtime, for a
     * platform where neither of the first two exists. The error reported is the last one, because
     * by then it is the only one left to report.
     */
    fun open(installer: File) {
        if (installer.extension.equals("rpm", ignoreCase = true)) {
            installRpm(installer)
            return
        }
        require(installer.isFile) { "The downloaded installer is no longer there." }
        unblock(installer)

        val ways = buildList<() -> Unit> {
            if (installer.extension.equals("msi", ignoreCase = true)) {
                add { ProcessBuilder("msiexec", "/i", installer.absolutePath).start() }
            }
            add { ProcessBuilder("explorer.exe", installer.absolutePath).start() }
            add {
                check(Desktop.isDesktopSupported()) { "This system has no desktop integration." }
                Desktop.getDesktop().open(installer)
            }
        }

        var last: Throwable? = null
        for (way in ways) {
            val result = runCatching { way() }
            if (result.isSuccess) {
                EventLog.info("update", "Handed ${installer.name} to Windows")
                return
            }
            last = result.exceptionOrNull()
            EventLog.warn("update", "Could not open ${installer.name}: ${last?.message}")
        }
        throw last ?: IllegalStateException("Windows would not open ${installer.name}")
    }

    /**
     * Keep only the installer just fetched.
     *
     * One is a hundred megabytes and nothing ever comes back for the last one, so an app that
     * updates often otherwise fills a disk with installers it has already run. Done after the
     * download rather than before it, so a failed download leaves the previous one to fall back on.
     */
    fun keepOnly(installer: File) {
        val dir = installer.parentFile ?: return
        runCatching {
            dir.listFiles().orEmpty().forEach { other ->
                val stale = other.isFile && other != installer &&
                    (other.extension.equals("msi", ignoreCase = true) ||
                        other.extension.equals("rpm", ignoreCase = true))
                if (stale && other.delete()) {
                    EventLog.info("update", "Removed the old installer ${other.name}")
                }
            }
        }
    }

    /** Show the file in the file manager, for when opening it is not what the person wants yet. */
    fun reveal(installer: File) = SystemShell.reveal(installer)

    /**
     * Install a downloaded package on Fedora, and wait for it.
     *
     * `pkexec` puts up the desktop's own password prompt, so nothing here needs a terminal. The
     * package replaces the files under /opt while this copy is still running, which is safe: the
     * running program keeps the files it opened, and the next launch reads the new ones. If there
     * is no pkexec - a system without polkit - the file goes to whatever installs packages by
     * double-click, which on KDE is Discover.
     */
    private fun installRpm(rpm: File) {
        val pkexec = listOf("/usr/bin/pkexec", "/bin/pkexec").firstOrNull { File(it).canExecute() }
        if (pkexec == null) {
            SystemShell.open(rpm)
            EventLog.info("update", "Handed ${rpm.name} to the package installer")
            return
        }
        val process = ProcessBuilder(pkexec, "dnf", "install", "-y", rpm.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        when (val code = process.waitFor()) {
            0 -> EventLog.info("update", "Installed ${rpm.name}")
            // 126 and 127 are pkexec's own: the password prompt was dismissed, or not allowed.
            126, 127 -> throw IllegalStateException("The install was cancelled.")
            else -> {
                EventLog.warn("update", "dnf failed ($code): ${output.takeLast(600)}")
                throw IllegalStateException(
                    output.lines().lastOrNull { it.isNotBlank() } ?: "The package would not install."
                )
            }
        }
    }
}
