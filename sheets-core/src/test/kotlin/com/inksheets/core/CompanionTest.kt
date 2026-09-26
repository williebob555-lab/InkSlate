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

    /**
     * A network like eduroam: every connection between two devices is let through, then cut
     * after a moment. Stood in for by a relay that drops each link after 1.5 s.
     */
    @Test
    fun `on a network that cuts links, a follower swapping to fresh ones stays in step`() {
        val port = freePort()
        val leader = CompanionLeader("Director", port)
        val log = CopyOnWriteArrayList<String>()
        leader.onLog = { log += it }
        assertTrue(leader.start())
        val relayPort = freePort()
        val relay = java.net.ServerSocket(relayPort)
        Thread {
            while (!relay.isClosed) {
                val a = runCatching { relay.accept() }.getOrNull() ?: break
                val b = java.net.Socket("127.0.0.1", port)
                fun pipe(from: java.net.Socket, to: java.net.Socket) = Thread {
                    runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
                    runCatching { to.close() }; runCatching { from.close() }
                }.apply { isDaemon = true; start() }
                pipe(a, b); pipe(b, a)
                // The network's cut: silently, after a moment.
                Thread { Thread.sleep(1500); runCatching { a.close() }; runCatching { b.close() } }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
        try {
            val q = LinkedBlockingQueue<CompanionLink.Line>()
            val f = follower("Tablet", q)
            f.refreshEveryMs = 1000
            assertTrue(f.start(CompanionLink.Leader("Director", "127.0.0.1", relayPort)))
            waitFor { leader.followerCount == 1 }
            val pages = CopyOnWriteArrayList<Int>()
            val notes = CopyOnWriteArrayList<String>()
            Thread {
                while (true) when (val l = q.take()) {
                    is CompanionLink.Line.Show -> pages += l.showing.page
                    is CompanionLink.Line.Message -> notes += l.note.text
                    else -> Unit
                }
            }.apply { isDaemon = true; start() }
            for (page in 1..8) {
                leader.show(CompanionLink.Showing(title = "Fight Song", page = page))
                if (page == 4) leader.note(CompanionLink.Note(text = "Look up"))
                Thread.sleep(700)
            }
            waitFor(4000) { pages.lastOrNull() == 8 }
            assertEquals(8, pages.last())
            assertTrue("Look up" in notes)
            assertEquals(1, leader.followerCount)
            // Five or so swaps, and not one of them in the leader's log.
            assertEquals(1, log.count { "is following" in it })
            assertTrue(log.none { "left" in it || "silent" in it })
            f.stop()
        } finally {
            relay.close()
            leader.stop()
        }
    }

    @Test
    fun `a message reaches every follower, and says who it is for`() {
        val port = freePort()
        val leader = CompanionLeader("Director", port)
        assertTrue(leader.start())
        try {
            val q = LinkedBlockingQueue<CompanionLink.Line>()
            val f = follower("Trumpet 2", q)
            assertTrue(f.start(CompanionLink.Leader("Director", "127.0.0.1", port)))
            waitFor { leader.followerCount == 1 }
            leader.note(CompanionLink.Note(text = "Trumpets: second ending", instruments = listOf("trumpet")))
            var note: CompanionLink.Note? = null
            val until = System.currentTimeMillis() + 5000
            while (note == null && System.currentTimeMillis() < until) {
                (q.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) as? CompanionLink.Line.Message)?.let { note = it.note }
            }
            assertEquals("Trumpets: second ending", note!!.text)
            assertEquals("Director", note!!.from)
            assertTrue(CompanionLink.noteIsFor(note!!, setOf("trumpet")))
            assertTrue(!CompanionLink.noteIsFor(note!!, setOf("tuba")))
            assertTrue(CompanionLink.noteIsFor(note!!.copy(instruments = emptyList()), setOf("tuba")))
            f.stop()
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
    fun `a follower whose link died without a goodbye is let go`() {
        val port = freePort()
        val leader = CompanionLeader("Stand", port)
        val log = CopyOnWriteArrayList<String>()
        leader.onLog = { log += it }
        assertTrue(leader.start())
        try {
            // A tablet that says hello and answers one heartbeat, then its Wi-Fi goes quiet.
            val ghost = java.net.Socket("127.0.0.1", port)
            val out = java.io.OutputStreamWriter(ghost.getOutputStream())
            out.write(CompanionLink.encode(CompanionLink.Hello(name = "Ghost")) + "\n"); out.flush()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(ghost.getInputStream()))
            while (true) {
                val got = CompanionLink.read(reader.readLine()) as? CompanionLink.Line.Ping ?: continue
                out.write(CompanionLink.pong(got.seq, got.at) + "\n"); out.flush()
                break
            }
            waitFor(4000) { leader.followerCount == 1 }
            waitFor(CompanionFollower.SILENT_FOR_MS + 5000) { leader.followerCount == 0 }
            assertEquals(0, leader.followerCount)
            // The count drops a moment before the line is logged.
            waitFor(CompanionLeader.BACK_WITHIN_MS + 3000) { log.any { "Ghost went silent" in it } }
            assertTrue(log.toString(), log.any { "Ghost went silent" in it })
            ghost.close()
        } finally {
            leader.stop()
        }
    }

    @Test
    fun `a leader gone silent is noticed within a few heartbeats and joined again`() {
        val server = java.net.ServerSocket(0)
        val accepted = LinkedBlockingQueue<java.net.Socket>()
        Thread {
            while (!server.isClosed) runCatching { accepted += server.accept() }
        }.apply { isDaemon = true; start() }
        val log = CopyOnWriteArrayList<String>()
        val f = CompanionFollower("Tablet") { }.apply { onLog = { log += it } }
        try {
            assertTrue(f.start(CompanionLink.Leader("Quiet", "127.0.0.1", server.localPort), retryMs = 200))
            val first = accepted.poll(3, TimeUnit.SECONDS)!!
            // One heartbeat, then nothing: the follower starts timing it.
            first.getOutputStream().apply { write((CompanionLink.ping(1, System.currentTimeMillis()) + "\n").toByteArray()); flush() }
            val second = accepted.poll(CompanionFollower.SILENT_FOR_MS + 5000, TimeUnit.MILLISECONDS)
            assertNotNull("never reconnected", second)
            assertTrue(log.toString(), log.any { "nothing arrived for" in it })
        } finally {
            f.stop(); server.close()
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
        assertEquals(CompanionLink.Line.Ping(7, 99), CompanionLink.read(CompanionLink.ping(7, 99)))
        assertEquals(CompanionLink.Line.Ping(), CompanionLink.read("""{"kind":"ping"}"""))
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
