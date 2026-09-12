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

    // ---- webs of devices, not just pairs -----------------------------------------

    /**
     * A mark crossing a device that is only in the middle.
     *
     * Three devices, and the two ends have never met: the phone knows the tablet, the tablet knows
     * the laptop. Nothing forwards anything on purpose. What carries the mark is that the tablet
     * takes it into the document it is holding and then owes it to everyone it has not sent it to.
     *
     * Done here rather than over sockets because that is where the property actually lives. The
     * same thing written with three real services spent its time waiting on connections and failed
     * on a slow machine about half the time, which is a test that reports the weather.
     */
    @Test
    fun `a mark crosses a device that is only in the middle`() {
        val phone = base.withPage(0, listOf(mark("phone-1")), "phone")
        var tablet = base
        var laptop = base

        val tabletOutbox = PeerOutbox()
        val laptopOutbox = PeerOutbox()

        // Phone to tablet: the tablet had nothing and now holds the phone's mark.
        val fromPhone = PeerSync.answerFor(phone, PeerSync.digestOf(tablet))
        tablet = PeerSync.applied(tablet, fromPhone)
        assertEquals(listOf("phone-1"), tablet.strokesOn(0).map { it.id })

        // Tablet onwards: it owes the laptop everything it has not sent, which is that mark.
        val owed = tabletOutbox.pending(tablet)
        assertNotNull("the middle device should owe the mark onwards", owed)
        laptop = PeerSync.applied(laptop, owed!!)
        tabletOutbox.sent(tablet)

        assertEquals(
            "the phone's mark should have crossed the tablet",
            listOf("phone-1"),
            laptop.strokesOn(0).map { it.id }
        )

        // And it stops there: nobody owes anything once everyone holds the same thing, so a ring
        // of devices passes a mark round once rather than forever.
        assertNull(tabletOutbox.pending(tablet))
        laptopOutbox.sent(laptop)
        assertNull(laptopOutbox.pending(laptop))
    }

}
