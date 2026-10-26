package com.inksheets.core

import org.junit.Assert.*
import org.junit.Test

class CompanionPartFollowTest {
    @Test fun `shared song does not mean shared page numbering`() {
        val part = Part(id = "euph", file = "euph.pdf", instrument = "euphonium")
        val leader = CompanionLink.Showing(songId = "same", instrument = "score", partId = "score", page = 5)
        assertEquals(CompanionLink.Follow.SONG_ONLY, CompanionLink.automaticFollow(leader, "same", part))
        assertNull(CompanionLink.pageFor(CompanionLink.automaticFollow(leader, "same", part), leader.page))
    }

    @Test fun `exact shared part follows including unnamed scans`() {
        val part = Part(id = "same-part", file = "scan.pdf")
        val leader = CompanionLink.Showing(songId = "same", partId = part.id, page = 2)
        assertEquals(CompanionLink.Follow.SAME_PAGE, CompanionLink.automaticFollow(leader, "same", part))
    }

    @Test fun `same instrument but different shared editions do not follow pages`() {
        val part = Part(id = "edition-b", file = "euph.pdf", instrument = "euphonium")
        val leader = CompanionLink.Showing(songId = "same", partId = "edition-a", instrument = "euphonium")
        assertEquals(CompanionLink.Follow.SONG_ONLY, CompanionLink.automaticFollow(leader, "same", part))
    }

    @Test fun `legacy leader uses instrument and chair instead of song identity`() {
        val part = Part(file = "tbn.pdf", instrument = "trombone", chair = 2)
        assertEquals(CompanionLink.Follow.SONG_ONLY, CompanionLink.automaticFollow(
            CompanionLink.Showing(songId = "same", instrument = "euphonium"), "same", part))
        assertEquals(CompanionLink.Follow.SONG_ONLY, CompanionLink.automaticFollow(
            CompanionLink.Showing(instrument = "trombone", partNo = "1"), "other", part))
        assertEquals(CompanionLink.Follow.SAME_PAGE, CompanionLink.automaticFollow(
            CompanionLink.Showing(instrument = "trombone", partNo = "2"), "other", part))
    }

    @Test fun `wire round trip preserves optional part identity and accepts older leaders`() {
        val showing = CompanionLink.Showing(songId = "s", partId = "p", page = 1)
        assertEquals(showing, CompanionLink.decode(CompanionLink.encode(showing)))
        assertNull(CompanionLink.decode("""{"songId":"s","page":1}""")!!.partId)
    }
}
