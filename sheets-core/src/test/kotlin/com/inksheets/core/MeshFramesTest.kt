package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshFramesTest {

    @Test
    fun `a position fits one broadcast and comes back as it went`() {
        val s = MeshFrames.State(
            session = MeshFrames.sessionOf("Wilsons-Laptop"), seq = 57,
            songKey = MeshFrames.songKey("24K Magic"), page = 3,
            partKey = MeshFrames.partKey("trumpet", "2", 4), hops = 2
        )
        val bytes = MeshFrames.encode(s)
        assertTrue(bytes.size <= MeshFrames.MAX_BYTES)
        assertEquals(s, MeshFrames.decode(bytes))
        // Two libraries agree on a song by its title however it is written.
        assertEquals(MeshFrames.songKey("24K Magic"), MeshFrames.songKey("24k magic"))
    }

    @Test
    fun `a message goes in pieces, arrives in any order, and is put together once`() {
        val text = "Trumpets: take the second ending, then straight into Fight Song ♪"
        val pieces = MeshFrames.notePieces(7, 123456, text, urgent = true, colour = 3)
        assertTrue(pieces.size > 1)
        pieces.forEach { assertTrue(MeshFrames.encode(it).size <= MeshFrames.MAX_BYTES) }
        val back = pieces.map { MeshFrames.decode(MeshFrames.encode(it)) as MeshFrames.NotePiece }
        val a = MeshFrames.NoteAssembler()
        var got: String? = null
        for (p in back.reversed() + back) a.take(p)?.let { assertNull("once only", got); got = it }
        assertEquals(text, got)
        assertTrue((back.first()).urgent)
        assertEquals(3, back.first().colour)
    }

    @Test
    fun `an older position never undoes a newer one, but a restarted leader is heard`() {
        assertTrue(MeshFrames.isNewer(58, 57))
        assertTrue(!MeshFrames.isNewer(56, 57))
        assertTrue(MeshFrames.isNewer(1, 40_000))
    }
}
