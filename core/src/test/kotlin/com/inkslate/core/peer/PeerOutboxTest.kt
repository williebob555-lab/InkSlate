package com.inkslate.core.peer

import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Noticing what has changed, rather than being told.
 *
 * The cases below are the ones that a "send it from where it is made" design gets wrong one at a
 * time: an erase, an undo of that erase, a mark edited in place. None of them is a new stroke being
 * drawn, and all of them have to reach the other device.
 */
class PeerOutboxTest {

    private val base = InkDocument.create(
        sourceName = "homework.pdf", kind = "pdf", pageCount = 1,
        sizeBytes = 1, fingerprint = ""
    )

    private fun mark(id: String, at: Long = 1_000L, colour: Int = 0xFF000000.toInt()) = Stroke(
        id = id,
        kind = Stroke.Kind.FREEHAND,
        color = colour,
        baseWidth = 2f,
        points = listOf(InkPoint(0f, 0f, 2f), InkPoint(9f, 9f, 2f)),
        pageIndex = 0,
        updatedUtc = at
    )

    @Test
    fun `a fresh outbox owes the whole document`() {
        val doc = base.withPage(0, listOf(mark("a-1"), mark("a-2")), "tablet")

        val pending = PeerOutbox().pending(doc)

        assertNotNull(pending)
        assertEquals(setOf("a-1", "a-2"), pending!!.strokes.map { it.id }.toSet())
    }

    @Test
    fun `nothing is owed once it has gone`() {
        val doc = base.withPage(0, listOf(mark("a-1")), "tablet")
        val outbox = PeerOutbox()
        outbox.pending(doc)
        outbox.sent(doc)

        assertNull(outbox.pending(doc))
    }

    @Test
    fun `a new mark is owed and nothing else is`() {
        val outbox = PeerOutbox()
        val first = base.withPage(0, listOf(mark("a-1")), "tablet")
        outbox.sent(first)

        val second = first.withPage(0, listOf(mark("a-1"), mark("a-2", at = 2_000L)), "tablet")
        val pending = outbox.pending(second)

        assertEquals(listOf("a-2"), pending?.strokes?.map { it.id })
    }

    /** The case a hook on "a stroke was drawn" misses entirely. */
    @Test
    fun `an erase is owed`() {
        val outbox = PeerOutbox()
        val both = base.withPage(0, listOf(mark("a-1"), mark("a-2")), "tablet")
        outbox.sent(both)

        val erased = both.withPage(0, listOf(mark("a-1")), "tablet")
        val pending = outbox.pending(erased)

        assertNotNull(pending)
        assertTrue("the deletion should be owed", "a-2" in pending!!.deleted)
    }

    /** And the case a hook on "a stroke was erased" misses. */
    @Test
    fun `undoing an erase is owed too`() {
        val outbox = PeerOutbox()
        val both = base.withPage(0, listOf(mark("a-1"), mark("a-2")), "tablet")
        val erased = both.withPage(0, listOf(mark("a-1")), "tablet")
        outbox.sent(erased)

        // Undo puts it back under the same id, which is what the tombstone had to stop being final.
        val restored = erased.withPage(0, listOf(mark("a-1"), mark("a-2", at = 3_000L)), "tablet")
        val pending = outbox.pending(restored)

        assertEquals(listOf("a-2"), pending?.strokes?.map { it.id })
    }

    @Test
    fun `a mark edited in place is owed again`() {
        val outbox = PeerOutbox()
        val first = base.withPage(0, listOf(mark("a-1")), "tablet")
        outbox.sent(first)

        val recoloured = first.withPage(
            0, listOf(mark("a-1", at = 4_000L, colour = 0xFFFF0000.toInt())), "tablet"
        )
        val pending = outbox.pending(recoloured)

        assertEquals(0xFFFF0000.toInt(), pending?.strokes?.single()?.color)
    }

    @Test
    fun `a different document is owed from scratch`() {
        val outbox = PeerOutbox()
        outbox.sent(base.withPage(0, listOf(mark("a-1")), "tablet"))

        val other = InkDocument.create(
            sourceName = "other.pdf", kind = "pdf", pageCount = 1, sizeBytes = 1, fingerprint = ""
        ).withPage(0, listOf(mark("b-1")), "tablet")

        assertEquals(listOf("b-1"), outbox.pending(other)?.strokes?.map { it.id })
    }
}
