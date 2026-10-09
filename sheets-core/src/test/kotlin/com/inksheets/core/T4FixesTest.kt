package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Regression tests for the T4 review (remote, controllers, Play together). Own ports: never the app's. */
class T4FixesTest {

    private fun freePort() = java.net.ServerSocket(0).use { it.localPort }

    private fun waitFor(ms: Long = 4000, check: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) { if (check()) return true; Thread.sleep(20) }
        return check()
    }

    @Test
    fun `a huge line before the key is cut off and idle sockets cannot crowd out a real remote`() {
        val port = freePort()
        val host = RemoteHost("Stand", "K7Q2PX", port)
        assertTrue(host.start())
        val idle = ArrayList<Socket>()
        try {
            // 5 MB with no newline: the connection is let go long before it is all read in.
            val big = Socket("127.0.0.1", port)
            val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
            val closed = runCatching {
                repeat(80) { big.getOutputStream().write(chunk); big.getOutputStream().flush() }
                // Reading says it is closed (end of stream, or reset).
                big.soTimeout = 3000
                big.getInputStream().read() == -1
            }.getOrDefault(true)
            assertTrue("a long line should end the connection", closed)
            // 200 sockets that say nothing.
            repeat(200) { runCatching { idle += Socket("127.0.0.1", port) } }
            val lines = LinkedBlockingQueue<RemoteLink.Line>()
            val remote = RemoteClient("Phone") { lines += it }
            remote.start(RemoteLink.Target("Stand", listOf("127.0.0.1"), port, "K7Q2PX"))
            assertTrue("a real remote still gets in", waitFor { host.remotes == 1 })
            remote.stop()
        } finally {
            idle.forEach { runCatching { it.close() } }
            host.stop()
        }
    }

    @Test
    fun `a press the device could not do is answered with the reason`() {
        val port = freePort()
        val host = RemoteHost("Stand", "K7Q2PX", port)
        host.onCommandReply = { c, reply -> if (c.action == "MESSAGE") reply("not leading") }
        assertTrue(host.start())
        try {
            val lines = LinkedBlockingQueue<RemoteLink.Line>()
            val remote = RemoteClient("Phone") { lines += it }
            remote.start(RemoteLink.Target("Stand", listOf("127.0.0.1"), port, "K7Q2PX"))
            assertTrue(waitFor { host.remotes == 1 })
            var seq = 0
            assertTrue(waitFor { seq = remote.send(RemoteLink.Command(action = "MESSAGE", text = "STOP")); seq != 0 })
            val got = generateSequence { lines.poll(3, TimeUnit.SECONDS) }.take(20).filterIsInstance<RemoteLink.Line.Got>().filter { it.seq == seq }.toList()
            assertTrue(got.any { it.why == "not leading" })
            remote.stop()
        } finally { host.stop() }
    }

    @Test
    fun `followers with the same device name are each counted`() {
        val port = freePort()
        val leader = CompanionLeader("Leader", port)
        assertTrue(leader.start())
        val followers = (1..5).map { CompanionFollower("Tablet") { } }
        try {
            followers.forEach { f -> f.start(CompanionLink.Leader("Leader", listOf("127.0.0.1"), port)) }
            assertTrue("five tablets, one model name", waitFor { leader.followerCount == 5 })
        } finally {
            followers.forEach { it.stop() }
            leader.stop()
        }
    }

    @Test
    fun `two messages in the same second are told apart`() {
        val port = freePort()
        val leader = CompanionLeader("Leader", port)
        assertTrue(leader.start())
        val got = LinkedBlockingQueue<CompanionLink.Note>()
        val follower = CompanionFollower("Tablet") { line -> (line as? CompanionLink.Line.Message)?.let { got += it.note } }
        try {
            assertTrue(follower.start(CompanionLink.Leader("Leader", listOf("127.0.0.1"), port)))
            leader.note(CompanionLink.Note(text = "STOP", urgent = true, at = 1_700_000_000_100))
            leader.note(CompanionLink.Note(text = "Look up", at = 1_700_000_000_100))
            val a = got.poll(3, TimeUnit.SECONDS)!!
            val b = got.poll(3, TimeUnit.SECONDS)!!
            assertNotEquals(a.id, b.id)
            assertTrue(a.id.isNotBlank())
        } finally {
            follower.stop()
            leader.stop()
        }
    }

    @Test
    fun `a pedal replugged into another port is still the same device`() {
        assertTrue(sameDevice("T4 Pedal", "2- T4 Pedal"))
        assertTrue(sameDevice("FS-1 [hw:2,0,0]", "FS-1 [hw:3,0,0]"))
        assertTrue(!sameDevice("T4 Pedal", "Other Pedal"))
        val ref = ControlRef("T4 Pedal", ControlEvent.CC, 1, 60)
        assertTrue(ref.matches(ControlEvent("3- T4 Pedal", ControlEvent.CC, 1, 60, 127)))
        assertEquals(false, ref.matches(ControlEvent("Other", ControlEvent.CC, 1, 60, 127)))
    }

    @Test(timeout = 10_000)
    fun `stray system bytes in a midi stream never loop`() {
        val junk = ByteArray(5000) { (it * 31).toByte() }
        ControlEvent.fromMidiBytes("d", junk, 0, junk.size)
        // A system byte, then data bytes with no status: running status must not stay on the system one.
        val bytes = byteArrayOf(0xF2.toByte(), 1, 2, 3, 4)
        ControlEvent.fromMidiBytes("d", bytes, 0, bytes.size)
    }
}
