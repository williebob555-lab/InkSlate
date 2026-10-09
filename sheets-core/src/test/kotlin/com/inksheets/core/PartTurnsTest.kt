package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a recording's page turns fall is kept per part: two parts of one song turn apart. */
class PartTurnsTest {
    @Test
    fun `each part keeps its own turns`() {
        var t = AudioTrack(file = "Song.mp3")
        t = t.withTurn("bass", 0, 30_000).withTurn("bass", 1, 61_000)
        t = t.withTurn("bone", 0, 45_000)
        assertEquals(listOf(30_000L, 61_000L), t.turnsFor("bass", 3))
        assertEquals(listOf(45_000L), t.turnsFor("bone", 2))
        // Not all learned yet: nothing to follow.
        assertNull(t.turnsFor("bone", 3))
        assertNull(t.turnsFor("tuba", 2))
    }

    @Test
    fun `a turn learned out of order waits for the ones before it`() {
        val t = AudioTrack(file = "Song.mp3").withTurn("bass", 1, 61_000)
        assertNull(t.turnsFor("bass", 3))
        assertEquals(listOf(30_000L, 61_000L), t.withTurn("bass", 0, 30_000).turnsFor("bass", 3))
    }

    @Test
    fun `the old one-list turns count only for a part with that many pages, until parts have their own`() {
        val old = AudioTrack(file = "Song.mp3", turnsMs = listOf(20_000L, 40_000L))
        assertEquals(listOf(20_000L, 40_000L), old.turnsFor("bass", 3))
        assertNull(old.turnsFor("bone", 2))
        assertNull(old.withTurn("bone", 0, 45_000).turnsFor("bass", 3))
    }
}
