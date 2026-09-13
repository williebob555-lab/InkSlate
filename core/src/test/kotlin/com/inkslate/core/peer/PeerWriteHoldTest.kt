package com.inkslate.core.peer

import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a device leaves a document for the other device to write.
 *
 * Getting this wrong one way makes sync-conflict copies; getting it wrong the other way leaves
 * handwriting unwritten. Both halves are checked.
 */
class PeerWriteHoldTest {

    private val base = InkDocument.create(
        sourceName = "board.pdf", kind = "pdf", pageCount = 1, sizeBytes = 1, fingerprint = ""
    )

    private fun mark(id: String, at: Long) = Stroke(
        id = id, kind = Stroke.Kind.FREEHAND, color = 0xFF000000.toInt(), baseWidth = 2f,
        points = listOf(InkPoint(1f, 1f, 2f), InkPoint(9f, 9f, 2f)), pageIndex = 0, updatedUtc = at
    )

    private fun batch(vararg s: Stroke) = PeerMessage.Marks(base.docId, s.toList())

    @Test
    fun `marks that only arrived over the link are left to the device that drew them`() {
        val hold = PeerWriteHold()
        val written = base.withPage(0, listOf(mark("mine", 1_000)), "laptop")
        val theirs = batch(mark("tablet-1", 5_000))
        hold.arrived(written, theirs, now = 10_000)

        val onScreen = PeerSync.applied(written, theirs)
        assertTrue(hold.leftToPeer(onScreen, now = 11_000))
    }

    @Test
    fun `a mark drawn here as well is written here`() {
        val hold = PeerWriteHold()
        val written = base.withPage(0, listOf(mark("mine", 1_000)), "laptop")
        val theirs = batch(mark("tablet-1", 5_000))
        hold.arrived(written, theirs, now = 10_000)

        val onScreen = PeerSync.applied(written, theirs)
            .withPage(0, listOf(mark("mine", 1_000), mark("tablet-1", 5_000), mark("mine-2", 12_000)), "laptop")
        assertFalse(hold.leftToPeer(onScreen, now = 12_500))
    }

    @Test
    fun `nothing is held when nothing arrived, or when what is on disk is unknown`() {
        val hold = PeerWriteHold()
        assertFalse(hold.leftToPeer(base, now = 1))
        hold.arrived(written = null, marks = batch(mark("tablet-1", 5)), now = 10)
        assertFalse(hold.leftToPeer(base, now = 11))
    }

    @Test
    fun `a peer that never writes is not waited on for ever`() {
        val hold = PeerWriteHold()
        val theirs = batch(mark("tablet-1", 5_000))
        hold.arrived(base, theirs, now = 10_000)
        val onScreen = PeerSync.applied(base, theirs)
        assertTrue(hold.leftToPeer(onScreen, now = 10_000 + PeerWriteHold.GRACE_MS - 1))
        assertFalse(hold.leftToPeer(onScreen, now = 10_000 + PeerWriteHold.GRACE_MS + 1))
    }

    @Test
    fun `an announced write is waited for, but only for a while`() {
        val hold = PeerWriteHold()
        assertFalse(hold.awaitingPeerWrite(now = 5))
        hold.peerWroteAt = 1_000
        assertTrue(hold.awaitingPeerWrite(now = 2_000))
        assertFalse(hold.awaitingPeerWrite(now = 1_000 + PeerWriteHold.AWAIT_PEER_WRITE_MS + 1))
    }

    @Test
    fun `writing everything settles the hold`() {
        val hold = PeerWriteHold()
        val theirs = batch(mark("tablet-1", 5_000))
        hold.arrived(base, theirs, now = 10_000)
        val onScreen = PeerSync.applied(base, theirs)
        hold.afterWrite(written = onScreen, current = onScreen)
        assertFalse(hold.leftToPeer(onScreen, now = 10_500))
    }
}
