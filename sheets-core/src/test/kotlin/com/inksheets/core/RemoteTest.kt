package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RemoteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun freePort() = java.net.ServerSocket(0).use { it.localPort }

    private inline fun <reified T : RemoteLink.Line> LinkedBlockingQueue<RemoteLink.Line>.next(ms: Long = 3000): T? {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            val line = poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (line is T) return line
        }
        return null
    }

    private fun waitFor(ms: Long = 3000, check: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        var ok = check()
        while (!ok && System.currentTimeMillis() < until) { Thread.sleep(20); ok = check() }
        assertTrue("timed out", ok)
    }

    @Test
    fun `a paired remote is told where the device is and its commands arrive`() {
        val port = freePort()
        val host = RemoteHost("Stand 1", "K7Q2PX", port)
        val commands = LinkedBlockingQueue<RemoteLink.Command>()
        host.onCommand = { commands += it }
        assertTrue(host.start())
        try {
            host.library(RemoteLink.Library(songs = listOf(RemoteLink.Item("s1", "The Liberty Bell"))))
            host.show(RemoteLink.State(songId = "s1", title = "The Liberty Bell", page = 1, pages = 4))
            val lines = LinkedBlockingQueue<RemoteLink.Line>()
            val remote = RemoteClient("Phone") { lines += it }
            remote.start(RemoteLink.Target("Stand 1", listOf("127.0.0.1"), port, "k7q2px"))
            // Told at once on joining: the library, and where the device is.
            assertEquals("The Liberty Bell", lines.next<RemoteLink.Line.Songs>()!!.library.songs.single().title)
            val shown = lines.next<RemoteLink.Line.Shows>()!!.state
            assertEquals(1, shown.page)
            assertEquals("Stand 1", shown.device)
            waitFor { host.remotes == 1 }

            // A page turn, and a song picked from the list.
            waitFor { remote.send(RemoteLink.Command(action = "NEXT_PAGE")) }
            assertEquals("NEXT_PAGE", commands.poll(3, TimeUnit.SECONDS)!!.action)
            remote.send(RemoteLink.Command(action = RemoteLink.SONG, id = "s1"))
            assertEquals("s1", commands.poll(3, TimeUnit.SECONDS)!!.id)

            // A change is sent; the same state twice is sent once.
            host.show(RemoteLink.State(songId = "s1", title = "The Liberty Bell", page = 2, pages = 4))
            host.show(RemoteLink.State(songId = "s1", title = "The Liberty Bell", page = 2, pages = 4))
            assertEquals(2, lines.next<RemoteLink.Line.Shows>()!!.state.page)
            assertNull(lines.next<RemoteLink.Line.Shows>(ms = 500))
            remote.stop()
        } finally {
            host.stop()
        }
    }

    @Test
    fun `a remote with the wrong key is turned away, and its commands never arrive`() {
        val port = freePort()
        val host = RemoteHost("Stand 1", "K7Q2PX", port)
        val commands = LinkedBlockingQueue<RemoteLink.Command>()
        host.onCommand = { commands += it }
        assertTrue(host.start())
        try {
            val lines = LinkedBlockingQueue<RemoteLink.Line>()
            val remote = RemoteClient("Stranger") { lines += it }
            remote.start(RemoteLink.Target("Stand 1", listOf("127.0.0.1"), port, "AAAAAA"))
            remote.send(RemoteLink.Command(action = "NEXT_PAGE"))
            assertNotNull(lines.next<RemoteLink.Line.Refused>())
            assertNull(commands.poll(500, TimeUnit.MILLISECONDS))
            assertEquals(0, host.remotes)
        } finally {
            host.stop()
        }
    }

    @Test
    fun `a new code turns away the remotes paired with the old one`() {
        val port = freePort()
        val host = RemoteHost("Stand 1", "K7Q2PX", port)
        assertTrue(host.start())
        try {
            val lines = LinkedBlockingQueue<RemoteLink.Line>()
            val remote = RemoteClient("Phone") { lines += it }
            remote.start(RemoteLink.Target("Stand 1", listOf("127.0.0.1"), port, "K7Q2PX"))
            waitFor { host.remotes == 1 }
            host.rekey("ZZZZZZ")
            assertNotNull(lines.next<RemoteLink.Line.Refused>())
            waitFor { host.remotes == 0 }
        } finally {
            host.stop()
        }
    }

    @Test
    fun `pairing codes round-trip and are never taken for a leader to follow`() {
        val link = RemoteLink.pairLink("Wilson's Tab", listOf("192.168.1.4", "100.70.1.2"), "K7Q2PX")
        val t = RemoteLink.parsePair(link)!!
        assertEquals("Wilson's Tab", t.name)
        assertEquals(listOf("192.168.1.4", "100.70.1.2"), t.hosts)
        assertEquals("K7Q2PX", t.key)
        assertNull(CompanionLink.parseJoin(link))
        assertNull(RemoteLink.parsePair(CompanionLink.joinLink("Stand", listOf("192.168.1.4"))))
        assertNotNull(CompanionLink.parseJoin(CompanionLink.joinLink("Stand", listOf("192.168.1.4"))))
        assertTrue(RemoteLink.sameKey("k7q 2px", "K7Q2PX"))
        assertFalse(RemoteLink.sameKey("K7Q2PY", "K7Q2PX"))
        repeat(50) { assertTrue(RemoteLink.newKey().none { it in "O0I1" }) }
    }

    @Test
    fun `a bookmark's colour and place in the list are kept with the song on every device`() {
        val root = tmp.newFolder("lib")
        var time = 1L
        val lib = Library(LibraryLog(root, "tablet")) { time++ }
        val song = lib.addSong("Take On Me", emptyList())
        val a = Bookmark("Page 2", "p1", 2, at = 10)
        val b = Bookmark("Page 5", "p1", 5, at = 20)
        lib.editSong(song.id) { bookmarks = listOf(a, b) }
        lib.editSong(song.id) { bookmarks = listOf(a.copy(color = 0xFFE53935.toInt(), rank = 1.0), b.copy(rank = 0.0)) }
        val again = Library(LibraryLog(root, "phone")).song(song.id)!!
        val marks = again.bookmarks.sortedBy { it.rank }
        assertTrue(marks[0].samePlace(b))
        assertEquals(0xFFE53935.toInt(), marks[1].color)
        assertTrue(marks[1].samePlace(a.copy(color = 1)))
        assertEquals(10L, marks[1].at)
    }
}
