package com.inkslate.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Linux halves that talk to the system, against the real thing.
 *
 * Skipped on Windows. On the build machine (Ubuntu) they run against a private D-Bus daemon and
 * the virtual X server the workflow starts, which is as close to a Fedora desktop as a build
 * machine gets: the same protocols, spoken to the same reference servers.
 */
class LinuxDesktopTest {

    private fun onPath(program: String) =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, program).canExecute() }

    /**
     * The screensaver request is well-formed: a private bus accepts the greeting and answers the
     * request itself with an error - there is no screensaver on it to say yes - rather than
     * hanging up, which is what a bus does with a message it cannot parse.
     */
    @Test
    fun `the bus understands the request to keep the display on`() {
        assumeTrue(AppDirs.isLinux && onPath("dbus-daemon"))
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--print-address", "--nofork")
            .redirectErrorStream(true).start()
        try {
            val address = daemon.inputStream.bufferedReader().readLine()
            val path = address.substringAfter("path=").substringBefore(',')
            assumeTrue("the daemon gave an abstract address", address.contains("path="))
            val refused = runCatching { ScreenInhibit.inhibit(path) }.exceptionOrNull()
            assertTrue(
                "expected a refusal from the bus, got $refused",
                refused is ScreenInhibit.Refused
            )
        } finally {
            daemon.destroy()
            daemon.waitFor(5, TimeUnit.SECONDS)
        }
    }

    /**
     * The native reader opens its connection, finds devices and reports the mouse when the
     * virtual X server's test pointer moves.
     */
    @Test
    fun `the X reader sees the pointer move`() {
        assumeTrue(AppDirs.isLinux && !System.getenv("DISPLAY").isNullOrBlank() && onPath("xdotool"))
        PenInput.pen(down = false, pressure = null, button = 0)
        X11Pointer.install(java.awt.Frame())
        assertTrue("the reader did not start", X11Pointer.active)
        repeat(20) { step ->
            ProcessBuilder("xdotool", "mousemove", "${100 + step * 7}", "${100 + step * 3}")
                .start().waitFor()
            if (PenInput.device == PenInput.Device.MOUSE) return@repeat
            Thread.sleep(50)
        }
        assertEquals(PenInput.Device.MOUSE, PenInput.device)
        X11Pointer.uninstall()
    }

    @Test
    fun `the app keeps its files under the XDG data folder`() {
        assumeTrue(AppDirs.isLinux)
        assertEquals("InkSlate", AppDirs.root.name)
        assertTrue(AppDirs.root.isDirectory)
    }
}
