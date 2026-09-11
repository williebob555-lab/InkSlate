package com.inkslate.core.peer

import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two devices of the same person, awake at the same time, agreeing about a document.
 *
 * The rules being checked are the ones that make a live connection safe to have at all: it may
 * never resurrect something erased, it may never lose a mark made while the two were apart, and
 * running it twice must change nothing the first run did not. A sync that is only *usually*
 * commutative is a sync that loses an afternoon on the day it is not.
 */
class PeerSyncTest {

    private fun doc(vararg strokes: Stroke, device: String = "tablet"): InkDocument =
        InkDocument.create(
            sourceName = "homework.pdf", kind = "pdf", pageCount = 2,
            sizeBytes = 1234, fingerprint = ""
        ).let { base ->
            var d = base
            strokes.groupBy { it.pageIndex }.forEach { (page, onPage) ->
                d = d.withPage(page, onPage, device)
            }
            d
        }

    private fun mark(id: String, page: Int = 0, at: Long = 1000L) = Stroke(
        id = id,
        kind = Stroke.Kind.FREEHAND,
        color = 0xFF101010.toInt(),
        baseWidth = 2f,
        points = listOf(InkPoint(4f, 4f, 2f), InkPoint(40f, 30f, 2f)),
        pageIndex = page,
        updatedUtc = at
    )

    @Test
    fun `each side asks for exactly what it is missing`() {
        val laptop = doc(mark("laptop-1"))
        val tablet = doc(mark("tablet-1"), mark("tablet-2", page = 1))

        assertEquals(
            listOf("tablet-1", "tablet-2"),
            PeerSync.wantedFrom(laptop, PeerSync.digestOf(tablet))
        )
        assertEquals(
            listOf("laptop-1"),
            PeerSync.wantedFrom(tablet, PeerSync.digestOf(laptop))
        )
    }

    @Test
    fun `a document already in step asks for nothing and sends nothing`() {
        val a = doc(mark("a-1"), mark("a-2"))
        val b = a

        assertTrue(PeerSync.wantedFrom(a, PeerSync.digestOf(b)).isEmpty())
        val answer = PeerSync.answerFor(a, PeerSync.digestOf(b))
        assertTrue(answer.strokes.isEmpty())
        assertTrue(answer.deleted.isEmpty())
        assertFalse(PeerSync.changesAnything(b, answer))
    }

    @Test
    fun `marks made on both sides while apart all survive`() {
        val laptop = doc(mark("laptop-1"), device = "laptop")
        val tablet = doc(mark("tablet-1"), mark("tablet-2", page = 1))

        val toLaptop = PeerSync.answerFor(tablet, PeerSync.digestOf(laptop))
        val toTablet = PeerSync.answerFor(laptop, PeerSync.digestOf(tablet))

        val laptopAfter = PeerSync.applied(laptop, toLaptop)
        val tabletAfter = PeerSync.applied(tablet, toTablet)

        val expected = setOf("laptop-1", "tablet-1", "tablet-2")
        assertEquals(expected, laptopAfter.pages.values.flatten().map { it.id }.toSet())
        assertEquals(expected, tabletAfter.pages.values.flatten().map { it.id }.toSet())
    }

    /** The failure this whole design exists to avoid. */
    @Test
    fun `an erase is not undone by the device that had not heard about it`() {
        val both = doc(mark("a-1"), mark("a-2"))
        // The tablet erases one; the laptop still has both.
        val tablet = both.withPage(0, listOf(mark("a-1")), "tablet")
        val laptop = both

        val toLaptop = PeerSync.answerFor(tablet, PeerSync.digestOf(laptop))
        val laptopAfter = PeerSync.applied(laptop, toLaptop)
        assertEquals(listOf("a-1"), laptopAfter.strokesOn(0).map { it.id })

        // ...and the laptop's own reply does not push the erased mark back to the tablet.
        val toTablet = PeerSync.answerFor(laptop, PeerSync.digestOf(tablet))
        val tabletAfter = PeerSync.applied(tablet, toTablet)
        assertEquals(listOf("a-1"), tabletAfter.strokesOn(0).map { it.id })
    }

    @Test
    fun `the newer copy of an edited mark wins, whichever side it came from`() {
        val older = doc(mark("a-1", at = 1_000L))
        val newer = doc(mark("a-1", at = 5_000L).copy(color = 0xFFFF0000.toInt()))

        val applied = PeerSync.applied(older, PeerSync.answerFor(newer, PeerSync.digestOf(older)))

        assertEquals(1, applied.totalStrokes)
        assertEquals(5_000L, applied.strokesOn(0).single().updatedUtc)
        assertEquals(0xFFFF0000.toInt(), applied.strokesOn(0).single().color)
    }

    /** Connections drop and repeat themselves; arriving twice has to be the same as arriving once. */
    @Test
    fun `applying the same batch twice changes nothing the first time did not`() {
        val laptop = doc(mark("laptop-1"), device = "laptop")
        val batch = PeerSync.answerFor(doc(mark("tablet-1")), PeerSync.digestOf(laptop))

        val once = PeerSync.applied(laptop, batch)
        val twice = PeerSync.applied(once, batch)

        assertEquals(
            once.pages.values.flatten().map { it.id },
            twice.pages.values.flatten().map { it.id }
        )
        assertFalse(PeerSync.changesAnything(once, batch))
    }

    @Test
    fun `an explicit request is answered with exactly what was asked for`() {
        val tablet = doc(mark("tablet-1"), mark("tablet-2", page = 1))

        val answer = PeerSync.answerFor(
            tablet, PeerSync.digestOf(doc()), only = listOf("tablet-2")
        )

        assertEquals(listOf("tablet-2"), answer.strokes.map { it.id })
    }

    @Test
    fun `a single mark leaving the pen travels the same path as a whole catch-up`() {
        val laptop = doc(mark("laptop-1"), device = "laptop")
        val live = PeerMessage.Marks("any", listOf(mark("tablet-live", at = 9_000L)))

        assertTrue(PeerSync.changesAnything(laptop, live))
        val after = PeerSync.applied(laptop, live)
        assertEquals(setOf("laptop-1", "tablet-live"), after.pages.values.flatten().map { it.id }.toSet())
    }
}
