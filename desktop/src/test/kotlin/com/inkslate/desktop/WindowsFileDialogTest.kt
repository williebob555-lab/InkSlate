package com.inkslate.desktop

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Windows pickers open, titled as asked, and a cancel comes back as nothing chosen. Opens real
 * windows on the desktop, so only on asking (-Dinkslate.picker=true).
 */
class WindowsFileDialogTest {

    private fun cancelWhenSeen(title: String, button: Int = 2): () -> Boolean {
        var seen = false
        val watcher = Thread {
            repeat(100) {
                val hwnd = User32.INSTANCE.FindWindow(null, title)
                if (hwnd != null) {
                    seen = true
                    User32.INSTANCE.PostMessage(hwnd, 0x0111, WinDef.WPARAM(button.toLong()), WinDef.LPARAM(0))
                    return@Thread
                }
                Thread.sleep(100)
            }
        }
        watcher.start()
        return { watcher.join(); seen }
    }

    @Test
    fun `the folder picker and the file picker open and cancel`() {
        assumeTrue(System.getProperty("inkslate.picker") == "true" && WindowsFileDialog.available)
        val home = File(System.getProperty("java.io.tmpdir"))

        val folderSeen = cancelWhenSeen("Pick a folder test")
        assertEquals(null, WindowsFileDialog.folder("Pick a folder test", home))
        assertTrue("the folder picker never appeared", folderSeen())

        // OK in the folder picker takes the folder it opened in.
        val start = File(home, "picker-start").apply { mkdirs() }
        val okSeen = cancelWhenSeen("Choose a folder test", button = 1)
        assertEquals(start.canonicalPath, WindowsFileDialog.folder("Choose a folder test", start)?.canonicalPath)
        assertTrue(okSeen())

        val fileSeen = cancelWhenSeen("Pick files test")
        assertEquals(emptyList<File>(), WindowsFileDialog.files("Pick files test", home, listOf("PDF" to "*.pdf", "All files" to "*.*"), many = true))
        assertTrue("the file picker never appeared", fileSeen())
    }
}
