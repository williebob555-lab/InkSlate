package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CompanionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `followers are told where the leader is, including one that joins late`() {
        val port = 47_900 + (Math.random() * 90).toInt()
        val leader = CompanionLeader("Stand 1", port)
        assertTrue(leader.start())
        try {
            val first = LinkedBlockingQueue<CompanionLink.Showing>()
            val a = CompanionFollower { first += it }
            assertTrue(a.connect("127.0.0.1", port))
            waitFor { leader.followerCount == 1 }
            leader.show(CompanionLink.Showing(songId = "s1", title = "The Liberty Bell", page = 2))
            val got = first.poll(3, TimeUnit.SECONDS)!!
            assertEquals(2, got.page)
            assertEquals("Stand 1", got.leader)

            // Joining partway through: told at once.
            val late = LinkedBlockingQueue<CompanionLink.Showing>()
            val b = CompanionFollower { late += it }
            assertTrue(b.connect("127.0.0.1", port))
            assertEquals("The Liberty Bell", late.poll(3, TimeUnit.SECONDS)!!.title)
            a.disconnect(); b.disconnect()
        } finally {
            leader.stop()
        }
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
        val theirs = lib.addSong("Liberty Bell, The".let { "The Liberty Bell" })
        val found = CompanionLink.songFor(lib, CompanionLink.Showing(songId = "someone-elses-id", title = "the liberty bell"))
        assertEquals(theirs.id, found?.id)
        assertEquals("Stand 2", CompanionLink.readAnnouncement(CompanionLink.announcement("Stand 2", 47820), "10.0.0.5")?.name)
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 3000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(20)
    }
}
