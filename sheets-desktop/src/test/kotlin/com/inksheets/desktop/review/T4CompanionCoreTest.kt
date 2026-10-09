package com.inksheets.desktop.review

import com.inksheets.core.CompanionFollower
import com.inksheets.core.CompanionLeader
import com.inksheets.core.CompanionLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Play together's wire (leader + followers) on loopback: a band-sized crowd, a leader that quits, slow links. */
class T4CompanionCoreTest {

    private class Fol(val name: String, port: Int) {
        val shows = CopyOnWriteArrayList<Pair<Int, Long>>()   // page, arrival nanoTime
        val notes = CopyOnWriteArrayList<String>()
        val up = AtomicInteger(); val down = AtomicInteger()
        val lastUp = AtomicLong(); val lastDown = AtomicLong()
        val logs = CopyOnWriteArrayList<String>()
        val f = CompanionFollower(name) { line ->
            when (line) {
                is CompanionLink.Line.Show -> shows += line.showing.page to System.nanoTime()
                is CompanionLink.Line.Message -> notes += line.note.text
                else -> Unit
            }
        }.also { f ->
            f.onConnected = { on -> if (on) { up.incrementAndGet(); lastUp.set(System.currentTimeMillis()) } else { down.incrementAndGet(); lastDown.set(System.currentTimeMillis()) } }
            f.onLog = { logs += it }
        }
        val leader = CompanionLink.Leader("T4 leader", listOf("127.0.0.1"), port)
        fun join() = Thread { f.start(leader) }.apply { isDaemon = true; start() }
    }

    @Test
    fun `fifty followers join at once - the count, the log and the speed of a page turn`() {
        val port = T4.freePort()
        val l = CompanionLeader("T4 leader", port)
        val counts = CopyOnWriteArrayList<Int>()
        val logs = CopyOnWriteArrayList<String>()
        l.onFollowers = { counts += it }
        l.onLog = { logs += it }
        assertTrue(l.start())
        val fs = (1..50).map { Fol("Stand ${it}", port) }
        try {
            l.show(CompanionLink.Showing(songId = "s1", title = "Fight Song", page = 0, pages = 4))
            val t0 = System.currentTimeMillis()
            fs.forEach { it.join() }
            val all = T4.waitFor(15000) { l.followerCount == 50 && fs.all { it.shows.isNotEmpty() } }
            T4.log("50 FOLLOWERS: all joined and told where the leader is in ${System.currentTimeMillis() - t0} ms (ok=$all, count=${l.followerCount}); onFollowers called ${counts.size} times; log lines ${logs.size}")
            // A page turn to all of them.
            val t1 = System.nanoTime()
            l.show(CompanionLink.Showing(songId = "s1", title = "Fight Song", page = 1, pages = 4))
            T4.waitFor(5000) { fs.all { f -> f.shows.any { it.first == 1 } } }
            val lat = fs.mapNotNull { f -> f.shows.firstOrNull { it.first == 1 }?.let { (it.second - t1) / 1_000_000 } }
            T4.log("50 FOLLOWERS page turn fan-out: " + T4.summary("turn", lat))
            // A message to all.
            val t2 = System.nanoTime()
            l.note(CompanionLink.Note(text = "STOP", urgent = true))
            T4.waitFor(5000) { fs.all { it.notes.contains("STOP") } }
            T4.log("50 FOLLOWERS note reached: ${fs.count { it.notes.contains("STOP") }}/50 in ${(System.nanoTime() - t2) / 1_000_000} ms")
            T4.log("50 FOLLOWERS leader log sample: " + logs.take(3))
            assertEquals(50, l.followerCount)
        } finally { fs.forEach { it.f.stop() }; l.stop() }
    }

    @Test
    fun `followers with the same device name are counted as one`() {
        val port = T4.freePort()
        val l = CompanionLeader("T4 leader", port)
        val logs = CopyOnWriteArrayList<String>()
        l.onLog = { logs += it }
        assertTrue(l.start())
        // Fifteen identical phones (a band buying the same model), all called the model name.
        val fs = (1..15).map { Fol("Pixel 7", port) } + (1..5).map { Fol("SM-X516B", port) }
        try {
            fs.forEach { it.join() }
            Thread.sleep(2500)
            T4.log("SAME NAMES: 20 followers (15 'Pixel 7', 5 'SM-X516B') -> leader counts ${l.followerCount}; log lines naming joins: ${logs.count { "following" in it }}")
            // One of the identical ones leaves: is it noticed?
            fs.first().f.stop()
            Thread.sleep(6000)
            T4.log("SAME NAMES: one 'Pixel 7' left -> count ${l.followerCount}; 'left' log lines: ${logs.count { "left" in it || "dropped" in it }}")
        } finally { fs.forEach { it.f.stop() }; l.stop() }
    }

    @Test
    fun `the leader quits mid song - followers notice, and find the leader when it is back`() {
        val port = T4.freePort()
        var l = CompanionLeader("T4 leader", port).also { assertTrue(it.start()) }
        val fs = (1..10).map { Fol("Stand $it", port) }
        try {
            l.show(CompanionLink.Showing(songId = "s1", title = "Fight Song", page = 2, pages = 4))
            fs.forEach { it.join() }
            assertTrue(T4.waitFor(8000) { fs.all { it.up.get() == 1 && it.shows.isNotEmpty() } })
            val quit = System.currentTimeMillis()
            l.stop()
            val noticed = T4.waitFor(8000) { fs.all { it.down.get() >= 1 } }
            T4.log("LEADER QUIT: all 10 followers flagged 'lost' in ${fs.maxOf { it.lastDown.get() } - quit} ms (ok=$noticed)")
            Thread.sleep(3000)
            val back = System.currentTimeMillis()
            l = CompanionLeader("T4 leader", port).also { assertTrue(it.start()) }
            l.show(CompanionLink.Showing(songId = "s2", title = "Tom Sawyer", page = 0, pages = 2))
            val ok = T4.waitFor(15000) { fs.all { it.up.get() >= 2 } }
            T4.log("LEADER BACK: all followers reconnected ${if (ok) "within ${fs.maxOf { it.lastUp.get() } - back} ms" else "NOT within 15 s (${fs.count { it.up.get() >= 2 }}/10)"}; new song delivered to ${fs.count { f -> f.shows.any { it.first == 0 } }}/10")
            T4.log("LEADER QUIT follower log sample: " + fs[0].logs.take(4))
        } finally { fs.forEach { it.f.stop() }; l.stop() }
    }

    @Test
    fun `one follower that stops reading does not hold up the others, and is let go`() {
        val port = T4.freePort()
        val l = CompanionLeader("T4 leader", port)
        val logs = CopyOnWriteArrayList<String>()
        l.onLog = { logs += it }
        assertTrue(l.start())
        val good = (1..10).map { Fol("Good $it", port) }
        // A follower that says hello and then never reads another byte (a tablet in a bag).
        val stuck = Socket("127.0.0.1", port).apply { receiveBufferSize = 1024 }
        stuck.getOutputStream().write((CompanionLink.encode(CompanionLink.Hello(name = "Bagged tablet")) + "\n").toByteArray())
        try {
            good.forEach { it.join() }
            T4.waitFor(5000) { l.followerCount == 11 }
            // A big ink share, then page turns: the stuck writer blocks, the others must not.
            val big = "x".repeat(4_000_000)
            l.shareInk(CompanionLink.InkShare(songId = "s1", title = "Fight Song", instrument = "trombone", partNo = "1", pages = 4, ink = big))
            val t = System.nanoTime()
            repeat(20) { i -> l.show(CompanionLink.Showing(songId = "s1", title = "Fight Song", page = i % 4, pages = 4)); Thread.sleep(20) }
            T4.waitFor(5000) { good.all { f -> f.shows.size >= 10 } }
            val lastTurn = good.map { (it.shows.lastOrNull()?.second ?: t) - t }.max() / 1_000_000
            T4.log("STUCK FOLLOWER: others got ${good.minOf { it.shows.size }}+ turns, last arrived ${lastTurn} ms after start; leader log: ${logs.takeLast(3)}")
            Thread.sleep(8000)
            T4.log("STUCK FOLLOWER after 8 s: leader count ${l.followerCount} (the bagged tablet is let go only when it stops answering heartbeats); log: ${logs.takeLast(3)}")
        } finally { good.forEach { it.f.stop() }; runCatching { stuck.close() }; l.stop() }
    }

    @Test
    fun `a follower joined by a bare address or an old client without a hello still counts`() {
        val port = T4.freePort()
        val l = CompanionLeader("T4 leader", port)
        assertTrue(l.start())
        val socks = (1..3).map { Socket("127.0.0.1", port) }     // never say hello
        try {
            Thread.sleep(500)
            T4.log("NO HELLO: 3 sockets that never say hello are counted as ${l.followerCount} follower(s) (named by IP address)")
        } finally { socks.forEach { it.close() }; l.stop() }
    }

    @Test
    fun `messages are replayed to a follower that joins within 30 seconds`() {
        val port = T4.freePort()
        val l = CompanionLeader("T4 leader", port)
        assertTrue(l.start())
        try {
            l.note(CompanionLink.Note(text = "From the top"))
            Thread.sleep(50)
            l.note(CompanionLink.Note(text = "STOP", urgent = true))
            val late = Fol("late", port)
            late.join()
            T4.waitFor(3000) { late.notes.size >= 2 }
            T4.log("REPLAY: a follower joining after two notes was given: ${late.notes}")
            late.f.stop()
        } finally { l.stop() }
    }

    @Test
    fun `join links - odd ones`() {
        val cases = listOf(
            "inksheets://join?name=Stand%201&hosts=192.168.1.4,100.70.1.2&port=47820",
            "inksheets://join?name=Stand&hosts=&port=47820",
            "inksheets://join?hosts=10.0.0.5&port=abc",
            "inksheets://join?name=%E9%9F%B3%E6%A5%BD&hosts=10.0.0.5",
            "INKSHEETS://JOIN?hosts=10.0.0.5",
            "10.0.0.5:47820", "10.0.0.5", "  10.0.0.5  ", "stand1.local", "fe80::1", "http://10.0.0.5", "inksheets://remote?name=x&hosts=1.2.3.4&key=ABC123",
            "10.0.0.5:99999", "10.0.0.5:0", "10.0.0.5:-1", "inksheets://join?hosts=10.0.0.5&port=70000"
        )
        for (c in cases) {
            val l = CompanionLink.parseJoin(c)
            T4.log("PARSEJOIN '$c' -> ${l?.let { "${it.name} ${it.hosts} :${it.port}" }}")
        }
    }
}
