package com.inksheets.desktop.review

import com.inkslate.core.Perform
import com.inksheets.core.CompanionLink
import com.inksheets.ui.SheetsState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Play together between whole SheetsState "devices" in one JVM, over loopback TCP. */
class T4PlayTogetherTest {
    @get:Rule val tmp = TemporaryFolder()

    private val jumps = CopyOnWriteArrayList<Triple<String, Int, Long>>()

    @After fun reset() { Perform.jumpTo = null }

    private fun onEdt(block: () -> Unit) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) block() else javax.swing.SwingUtilities.invokeAndWait(block)
    }

    /** What the editor does when a song is in front at [page]. */
    private fun view(s: SheetsState, title: String, page: Int, pages: Int = 4) = onEdt {
        val song = s.library!!.songs.first { it.title == title }
        val part = s.partFor(song)!!
        s.current = song
        s.currentPath = s.fileOf(part.file)!!.absolutePath
        s.homeInFront = false
        s.pageShown = page to pages
        s.companion.pageTurned(page)
    }

    private fun lead(s: SheetsState): Int {
        val port = T4.freePort()
        s.companion.leadPort = port
        onEdt { assertTrue(s.companion.lead()) }
        return port
    }

    private fun follow(s: SheetsState, port: Int, name: String = "Leader"): Boolean {
        val ok = java.util.concurrent.CountDownLatch(1)
        var reached = false
        onEdt { s.companion.followLeader(CompanionLink.Leader(name, listOf("127.0.0.1"), port)) { reached = it; ok.countDown() } }
        ok.await(12, java.util.concurrent.TimeUnit.SECONDS)
        return reached
    }

    @Test
    fun `follow modes, and how long a page turn takes to reach the follower`() {
        val (a, _) = T4Lib.make(tmp.newFolder("A"), name = "Leader")
        val (b, pb) = T4Lib.make(tmp.newFolder("B"), name = "Stand 2")
        Perform.jumpTo = { path, page -> jumps += Triple(path, page, System.nanoTime()) }
        val port = lead(a)
        assertTrue(follow(b, port))
        view(a, "Fight Song", 0)
        assertTrue(T4.waitFor(5000) { pb.opened.any { "Fight Song" in it } })
        T4.log("FOLLOW: leader opens a song -> follower opens its own part: ${pb.opened.toList()}")
        // The follower's editor shows the song.
        view(b, "Fight Song", 0)
        for (mode in CompanionLink.Follow.entries) {
            b.companion.follow = mode
            jumps.clear()
            val t0 = System.nanoTime()
            val page = 2 + mode.ordinal % 2
            view(a, "Fight Song", page)
            val got = T4.waitFor(1500) { jumps.isNotEmpty() }
            T4.log("FOLLOW mode ${mode.name}: leader to page ${page + 1} -> follower " + if (got) "jumps to page index ${jumps[0].second} after ${(jumps[0].third - t0) / 1_000_000} ms" else "stays (turns its own pages)")
            view(a, "Fight Song", 0); Thread.sleep(150)
        }
        // Rapid turning: ten turns in a second, does the follower end on the last?
        b.companion.follow = CompanionLink.Follow.SAME_PAGE
        jumps.clear()
        for (p in 0..3) { view(a, "Fight Song", p); Thread.sleep(40) }
        view(a, "Fight Song", 1)
        T4.waitFor(1500) { jumps.lastOrNull()?.second == 1 }
        T4.log("FOLLOW rapid turns: follower's last jump = ${jumps.lastOrNull()?.second} of ${jumps.size} jumps (expected 1)")
        onEdt { b.companion.stopFollowing(); a.companion.stopLeading() }
    }

    @Test
    fun `two notes in the same second - the second is lost - and an urgent note replaced by a plain one`() {
        val (a, _) = T4Lib.make(tmp.newFolder("A"), name = "Leader")
        val (b, _) = T4Lib.make(tmp.newFolder("B"), name = "Stand 2")
        val port = lead(a)
        assertTrue(follow(b, port))
        view(a, "Fight Song", 0); view(b, "Fight Song", 0)
        val seen = CopyOnWriteArrayList<String>()
        fun watch() = onEdt { b.companion.notice?.let { if (seen.lastOrNull() != it.text + "#" + b.companion.noticeCount) seen += it.text + "#" + b.companion.noticeCount } }
        // STOP then Look up in the same second (two presets tapped on a remote).
        onEdt { a.companion.sendNote("STOP", emptyList(), urgent = true); a.companion.sendNote("Look up", emptyList()) }
        Thread.sleep(800); watch()
        T4.log("NOTES same second: follower noticeCount=${b.companion.noticeCount} last='${b.companion.notice?.text}' urgent=${b.companion.notice?.urgent}")
        // Different seconds: plain note arrives while the urgent one is still up.
        Thread.sleep(1200)
        onEdt { a.companion.sendNote("STOP", emptyList(), urgent = true) }
        Thread.sleep(1100)
        onEdt { a.companion.sendNote("Next up: Tom Sawyer", emptyList()) }
        Thread.sleep(500)
        T4.log("NOTES urgent then plain 1.1 s later: notice now '${b.companion.notice?.text}' urgent=${b.companion.notice?.urgent} showing=${b.companion.noticeShowing} -> the STOP cover " + if (b.companion.notice?.urgent == true) "still up" else "is replaced by a small popup before the player tapped it")
        onEdt { b.companion.stopFollowing(); a.companion.stopLeading() }
    }

    @Test
    fun `a note sent while the follower is on Home waits there and shows later over the music`() {
        val (a, _) = T4Lib.make(tmp.newFolder("A"), name = "Leader")
        val (b, _) = T4Lib.make(tmp.newFolder("B"), name = "Stand 2")
        val port = lead(a)
        assertTrue(follow(b, port))
        onEdt { b.homeInFront = true }
        onEdt { a.companion.sendNote("STOP", emptyList(), urgent = true) }
        Thread.sleep(800)
        T4.log("NOTE ON HOME: follower on Home got notice='${b.companion.notice?.text}' showing=${b.companion.noticeShowing}. The popup that times it out (15 s) is only mounted over a song, so it waits.")
        Thread.sleep(16000)
        T4.log("NOTE ON HOME: 16 s later still showing=${b.companion.noticeShowing} (will cover the music of the next song opened)")
        onEdt { b.companion.stopFollowing(); a.companion.stopLeading() }
    }

    @Test
    fun `note for one instrument only reaches that instrument`() {
        val (a, _) = T4Lib.make(tmp.newFolder("A"), name = "Leader")
        val (b, _) = T4Lib.make(tmp.newFolder("B"), name = "Stand 2")
        val port = lead(a)
        assertTrue(follow(b, port))
        view(b, "Fight Song", 0)
        onEdt { a.companion.sendNote("Trombones: letter C", listOf("trombone")) }
        Thread.sleep(600)
        T4.log("NOTE FOR TROMBONES: alto-sax follower shows ${b.companion.notice?.text}")
        onEdt { a.companion.sendNote("Saxes: letter C", listOf("alto-sax")) }
        Thread.sleep(1200)
        T4.log("NOTE FOR ALTO SAX: follower shows ${b.companion.notice?.text}")
        onEdt { b.companion.stopFollowing(); a.companion.stopLeading() }
    }

    @Test
    fun `a follower that restarts resumes following by itself`() {
        val (a, _) = T4Lib.make(tmp.newFolder("A"), name = "Leader")
        val bRoot = tmp.newFolder("B")
        val (b1, pb) = T4Lib.make(bRoot, name = "Stand 2")
        val port = lead(a)
        assertTrue(follow(b1, port))
        view(a, "Tom Sawyer", 0)
        T4.waitFor(3000) { b1.companion.leaderAt != null }
        // The app is closed (its sockets die with the process) and started again on the same prefs.
        onEdt { b1.companion.stopFollowing() }
        // stopFollowing clears the since-stamp as the user pressing Stop would; a kill would not:
        pb.prefs["sheets_companion_following_since"] = System.currentTimeMillis().toString()
        val b2 = SheetsState(pb)
        val resumed = T4.waitFor(8000) { b2.companion.following != null && b2.companion.connected }
        T4.log("RESTART RESUME: restarted follower following='${b2.companion.following}' connected=$resumed leaderAt=${b2.companion.leaderAt?.title}")
        // After 4 hours: no longer resumes.
        pb.prefs["sheets_companion_following_since"] = (System.currentTimeMillis() - 4L * 3600_000).toString()
        onEdt { b2.companion.stopFollowing() }
        pb.prefs["sheets_companion_following_since"] = (System.currentTimeMillis() - 4L * 3600_000).toString()
        val b3 = SheetsState(pb)
        Thread.sleep(500)
        T4.log("RESTART RESUME after 4 h: following='${b3.companion.following}' (should be null: a stale session)")
        onEdt { a.companion.stopLeading() }
    }

    @Test
    fun `twelve whole devices follow one leader - the leader sees a count and a log, never a pop-up`() {
        val (a, pa) = T4Lib.make(tmp.newFolder("A"), name = "Leader")
        val port = lead(a)
        val fs = (1..12).map { i -> T4Lib.make(tmp.newFolder("F$i"), name = "Tablet") }   // all called "Tablet"
        fs.forEach { (s, _) -> assertTrue(follow(s, port)) }
        T4.waitFor(5000) { a.companion.followers >= 1 }
        Thread.sleep(500)
        view(a, "Hey Song", 0)
        T4.waitFor(6000) { fs.all { (_, p) -> p.opened.any { "Hey Song" in it } } }
        val opened = fs.count { (_, p) -> p.opened.any { "Hey Song" in it } }
        T4.log("12 DEVICES (all named 'Tablet'): leader shows '${a.companion.status}' ; ${opened}/12 opened the song; leader log lines: ${pa.logs.count { "following" in it }}")
        T4.log("12 DEVICES leader log: " + pa.logs.filter { "Companion" in it }.take(4))
        onEdt { fs.forEach { (s, _) -> s.companion.stopFollowing() }; a.companion.stopLeading() }
    }

    @Test
    fun `the leader quits mid song - what the follower shows`() {
        val (a, _) = T4Lib.make(tmp.newFolder("A"), name = "Leader")
        val (b, pb) = T4Lib.make(tmp.newFolder("B"), name = "Stand 2")
        val port = lead(a)
        assertTrue(follow(b, port))
        view(a, "Fight Song", 2); view(b, "Fight Song", 2)
        onEdt { a.companion.stopLeading() }
        val t0 = System.currentTimeMillis()
        val flagged = T4.waitFor(10000) { !b.companion.connected }
        T4.log("LEADER QUIT (app level): follower status='${b.companion.status}' after ${System.currentTimeMillis() - t0} ms (connected=$flagged). Follower sits on its page; log: ${pb.logs.takeLast(2)}")
        // Leader returns on the same port and the follower comes back with no tap.
        onEdt { assertTrue(a.companion.lead()) }
        view(a, "Tom Sawyer", 0)
        val back = T4.waitFor(10000) { b.companion.connected && pb.opened.any { "Tom Sawyer" in it } }
        T4.log("LEADER BACK: follower reconnected and moved to the leader's song = $back (${System.currentTimeMillis() - t0} ms from the quit)")
        onEdt { b.companion.stopFollowing(); a.companion.stopLeading() }
    }

    @Test
    fun `go to the leader after wandering off`() {
        val (a, _) = T4Lib.make(tmp.newFolder("A"), name = "Leader")
        val (b, pb) = T4Lib.make(tmp.newFolder("B"), name = "Stand 2")
        Perform.jumpTo = { path, page -> jumps += Triple(path, page, System.nanoTime()) }
        val port = lead(a)
        assertTrue(follow(b, port))
        view(a, "Fight Song", 1); T4.waitFor(3000) { pb.opened.any { "Fight Song" in it } }
        view(b, "Fight Song", 1)
        // The follower wanders: Home, or another song.
        onEdt { b.homeInFront = true }
        view(a, "Fight Song", 2)
        Thread.sleep(400)
        T4.log("WANDER: follower on Home, leader turned: offLeader=${b.companion.offLeader} (page jumps so far ${jumps.size}); the banner is the only prompt")
        onEdt { b.companion.goToLeader() }
        T4.waitFor(2000) { jumps.isNotEmpty() || pb.opened.size >= 2 }
        T4.log("WANDER: goToLeader -> jumps=${jumps.map { it.second }} opened=${pb.opened.toList()}")
        onEdt { b.companion.stopFollowing(); a.companion.stopLeading() }
    }
}
