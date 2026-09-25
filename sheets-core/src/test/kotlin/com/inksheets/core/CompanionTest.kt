package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CompanionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun freePort() = java.net.ServerSocket(0).use { it.localPort }

    private fun follower(name: String, into: LinkedBlockingQueue<CompanionLink.Line>) =
        CompanionFollower(name) { into += it }

    private fun LinkedBlockingQueue<CompanionLink.Line>.nextShowing(): CompanionLink.Showing? {
        val until = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < until) {
            val line = poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (line is CompanionLink.Line.Show) return line.showing
        }
        return null
    }

    @Test
    fun `followers are told where the leader is, including one that joins late`() {
        val port = freePort()
        val leader = CompanionLeader("Stand 1", port)
        assertTrue(leader.start())
        try {
            val first = LinkedBlockingQueue<CompanionLink.Line>()
            val a = follower("Tablet A", first)
            assertTrue(a.start(CompanionLink.Leader("Stand 1", "127.0.0.1", port)))
            waitFor { leader.followerCount == 1 }
            leader.show(CompanionLink.Showing(songId = "s1", title = "The Liberty Bell", page = 2))
            val got = first.nextShowing()!!
            assertEquals(2, got.page)
            assertEquals("Stand 1", got.leader)

            // Joining partway through: told at once.
            val late = LinkedBlockingQueue<CompanionLink.Line>()
            val b = follower("Tablet B", late)
            assertTrue(b.start(CompanionLink.Leader("Stand 1", "127.0.0.1", port)))
            assertEquals("The Liberty Bell", late.nextShowing()!!.title)
            a.stop(); b.stop()
        } finally {
            leader.stop()
        }
    }

    @Test
    fun `a whole band follows, and the leader only logs who comes and goes`() {
        val port = freePort()
        val leader = CompanionLeader("Director", port)
        val log = CopyOnWriteArrayList<String>()
        leader.onLog = { log += it }
        assertTrue(leader.start())
        try {
            val queues = List(50) { LinkedBlockingQueue<CompanionLink.Line>() }
            val band = queues.mapIndexed { i, q -> follower("Player ${i + 1}", q) }
            band.forEach { assertTrue(it.start(CompanionLink.Leader("Director", "127.0.0.1", port))) }
            waitFor(8000) { leader.followerCount == 50 }
            assertEquals(50, leader.followerCount)
            leader.show(CompanionLink.Showing(title = "Sleigh Ride", page = 0))
            queues.forEach { assertEquals("Sleigh Ride", it.nextShowing()?.title) }
            waitFor { log.count { "is following" in it } == 50 }
            assertTrue(log.any { it.startsWith("Player 7 is following") })

            band.take(10).forEach { it.stop() }
            waitFor { leader.followerCount == 40 }
            assertEquals(40, leader.followerCount)
            band.drop(10).forEach { it.stop() }
        } finally {
            leader.stop()
        }
    }

    @Test
    fun `a follower whose leader went away joins again by itself`() {
        val port = freePort()
        val lines = LinkedBlockingQueue<CompanionLink.Line>()
        val states = CopyOnWriteArrayList<Boolean>()
        val f = follower("Tablet", lines).apply { onConnected = { states += it } }
        val first = CompanionLeader("Stand", port)
        assertTrue(first.start())
        assertTrue(f.start(CompanionLink.Leader("Stand", listOf("10.255.255.1", "127.0.0.1"), port), retryMs = 200))
        first.stop()
        waitFor { states.lastOrNull() == false }
        // The leader comes back - its app restarted - and the follower is on it again unasked.
        val again = CompanionLeader("Stand", port)
        assertTrue(again.start())
        try {
            waitFor(8000) { again.followerCount == 1 }
            assertEquals(1, again.followerCount)
            again.show(CompanionLink.Showing(title = "Again", page = 1))
            assertEquals("Again", lines.nextShowing()?.title)
        } finally {
            f.stop(); again.stop()
        }
    }

    @Test
    fun `marks reach followers, and a newer set replaces one not yet sent`() {
        val port = freePort()
        val leader = CompanionLeader("Section leader", port)
        assertTrue(leader.start())
        try {
            val lines = LinkedBlockingQueue<CompanionLink.Line>()
            val f = follower("Trombone 2", lines)
            assertTrue(f.start(CompanionLink.Leader("x", "127.0.0.1", port)))
            waitFor { leader.followerCount == 1 }
            leader.show(CompanionLink.Showing(title = "March", instrument = "trombone", partNo = "2", pages = 3, shareInk = true))
            leader.shareInk(CompanionLink.InkShare(title = "March", instrument = "trombone", partNo = "2", pages = 3, ink = "{}"))
            val until = System.currentTimeMillis() + 3000
            var ink: CompanionLink.InkShare? = null
            while (ink == null && System.currentTimeMillis() < until) {
                (lines.poll(100, TimeUnit.MILLISECONDS) as? CompanionLink.Line.Ink)?.let { ink = it.share }
            }
            assertEquals("trombone", ink?.instrument)
            f.stop()
        } finally {
            leader.stop()
        }
    }

    @Test
    fun `join links carry every address and read back, and bare addresses work too`() {
        val link = CompanionLink.joinLink("Mr. Smith's iPad & stand", listOf("192.168.1.4", "100.70.1.2"), 47820)
        val leader = CompanionLink.parseJoin(link)!!
        assertEquals("Mr. Smith's iPad & stand", leader.name)
        assertEquals(listOf("192.168.1.4", "100.70.1.2"), leader.hosts)
        assertEquals(47820, leader.port)
        assertEquals(listOf("10.0.0.9"), CompanionLink.parseJoin("10.0.0.9")?.hosts)
        assertEquals(5000, CompanionLink.parseJoin("stand1:5000")?.port)
        assertNull(CompanionLink.parseJoin("hello there"))
        assertNull(CompanionLink.parseJoin("inksheets://join?name=x"))
    }

    @Test
    fun `a leader from before kinds is still read as a position`() {
        val old = """{"songId":"s","title":"T","page":3,"leader":"L"}"""
        assertEquals(3, (CompanionLink.read(old) as CompanionLink.Line.Show).showing.page)
        assertEquals(CompanionLink.Line.Ping, CompanionLink.read(CompanionLink.PING))
    }

    @Test
    fun `the same part means the same instrument, number and pages`() {
        assertEquals("2", CompanionLink.partNumber("Trombone 2"))
        assertEquals("2", CompanionLink.partNumber("2nd Trombone"))
        assertEquals("2", CompanionLink.partNumber("Tbn. II"))
        assertEquals("1", CompanionLink.partNumber("Liberty Bell - Tbn1.pdf"))
        assertNull(CompanionLink.partNumber("Euphonium"))
        val part = Part(file = "March/Trombone 2.pdf", instrument = "trombone", label = "Trombone 2")
        assertTrue(CompanionLink.samePart("trombone", "2", 3, part, 3))
        assertFalse(CompanionLink.samePart("trombone", "1", 3, part, 3))
        assertFalse(CompanionLink.samePart("trombone", "2", 4, part, 3))
        assertFalse(CompanionLink.samePart("euphonium", "2", 3, part, 3))
    }

    @Test
    fun `what each way of following does with the leader's page`() {
        assertEquals(4, CompanionLink.pageFor(CompanionLink.Follow.SAME_PAGE, 4))
        assertEquals(5, CompanionLink.pageFor(CompanionLink.Follow.NEXT_PAGE, 4))
        assertNull(CompanionLink.pageFor(CompanionLink.Follow.SONG_ONLY, 4))
    }

    @Test
    fun `a bandmate's library finds the song by title`() {
        val lib = Library(LibraryLog(tmp.newFolder("bandmate"), "b"))
        val theirs = lib.addSong("The Liberty Bell")
        val found = CompanionLink.songFor(lib, CompanionLink.Showing(songId = "someone-elses-id", title = "the liberty bell"))
        assertEquals(theirs.id, found?.id)
        assertNotNull(CompanionLink.readAnnouncement(CompanionLink.announcement("Stand 2", 47820), "10.0.0.5"))
        assertEquals("Stand 2", CompanionLink.readAnnouncement(CompanionLink.announcement("Stand 2", 47820), "10.0.0.5")?.name)
    }

    private fun waitFor(ms: Long = 3000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(20)
    }
}
