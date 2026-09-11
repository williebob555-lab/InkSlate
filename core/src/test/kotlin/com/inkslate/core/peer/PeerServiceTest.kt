package com.inkslate.core.peer

import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Two devices, two sockets, one document - on one machine, over the loopback.
 *
 * The pure decisions are pinned down in [PeerSyncTest]; this is the other half, and it is the half
 * that cannot be reasoned about from the code alone: that a device which has never met another can
 * pair with it using a code read off a screen, that a mark made a second later arrives, and that a
 * device which knows no code gets nothing at all however well it behaves otherwise.
 */
class PeerServiceTest {

    private val started = mutableListOf<PeerService>()

    @After
    fun tearDown() {
        started.forEach { it.stop() }
        started.clear()
    }

    /**
     * One document, as both devices see it.
     *
     * Deliberately the same [InkDocument.docId] on each side: two devices hold the same worksheet
     * because they hold the same file, and the id travels inside it. Giving each device its own id
     * would be testing two documents that merely look alike.
     */
    private val base = InkDocument.create(
        sourceName = "homework.pdf", kind = "pdf", pageCount = 1,
        sizeBytes = 10, fingerprint = ""
    )

    private fun document(vararg strokes: Stroke): InkDocument =
        if (strokes.isEmpty()) base else base.withPage(0, strokes.toList(), "test")

    private fun mark(id: String, at: Long = 1_000L) = Stroke(
        id = id,
        kind = Stroke.Kind.FREEHAND,
        color = 0xFF000000.toInt(),
        baseWidth = 2f,
        points = listOf(InkPoint(1f, 1f, 2f), InkPoint(20f, 20f, 2f)),
        pageIndex = 0,
        updatedUtc = at
    )

    /** A device under test: its own tag, its own open document, and what it has been told. */
    private class Device(
        val tag: String,
        val name: String,
        doc: InkDocument
    ) : PeerService.Host {
        val open = AtomicReference(doc)
        val paired = AtomicReference<PeerService.Peer?>(null)
        val marksArrived = CountDownLatch(1)
        val writes = AtomicReference<String?>(null)
        val writeHeard = CountDownLatch(1)
        lateinit var service: PeerService

        override fun deviceTag() = tag
        override fun deviceName() = name
        override fun openDocument() = PeerService.Open("homework.pdf", open.get())

        override fun onMarks(docId: String, marks: PeerMessage.Marks) {
            val before = open.get()
            val after = PeerSync.applied(before, marks)
            open.set(after)
            if (after.totalStrokes > before.totalStrokes) marksArrived.countDown()
        }

        override fun onRemoteWrite(peer: String, fileName: String?) {
            writes.set(fileName ?: "(library)")
            writeHeard.countDown()
        }

        override fun onPaired(peer: PeerService.Peer) {
            paired.set(peer)
        }
    }

    private fun start(device: Device, port: Int, peers: List<PeerService.Peer> = emptyList()) {
        device.service = PeerService(device).also {
            started.add(it)
            it.start(peers, port)
        }
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    @Test
    fun `two devices pair with a code and then agree about the document`() {
        val tabletPort = freePort()
        val tablet = Device("tablet", "Tablet", document(mark("tablet-1")))
        val laptop = Device("laptop", "Laptop", document(mark("laptop-1")))

        start(tablet, tabletPort)
        start(laptop, freePort())

        // The code is showing on the tablet; it is typed into the laptop.
        val code = "424242"
        tablet.service.offerPairing(code)
        val paired = laptop.service.pairWith("127.0.0.1", tabletPort, code)

        assertTrue("pairing should succeed: ${paired.exceptionOrNull()}", paired.isSuccess)
        assertEquals("tablet", paired.getOrThrow().tag)
        assertEquals("Tablet", paired.getOrThrow().name)

        // The connection that follows is the real one, and each side fills the other's gap.

        assertTrue(
            "the tablet's mark should reach the laptop",
            laptop.marksArrived.await(20, TimeUnit.SECONDS)
        )
        assertTrue(
            "the laptop's mark should reach the tablet",
            tablet.marksArrived.await(20, TimeUnit.SECONDS)
        )

        val expected = setOf("tablet-1", "laptop-1")
        assertEquals(expected, laptop.open.get().pages.values.flatten().map { it.id }.toSet())
        assertEquals(expected, tablet.open.get().pages.values.flatten().map { it.id }.toSet())
        assertNotNull("the tablet should have stored the laptop too", tablet.paired.get())
    }

    @Test
    fun `a mark made after the connection is up arrives on its own`() {
        val tabletPort = freePort()
        val tablet = Device("tablet", "Tablet", document())
        val laptop = Device("laptop", "Laptop", document())
        start(tablet, tabletPort)
        start(laptop, freePort())

        tablet.service.offerPairing("135790")
        laptop.service.pairWith("127.0.0.1", tabletPort, "135790").getOrThrow()

        // Wait for the connection rather than for a mark: there are none yet.
        val deadline = System.currentTimeMillis() + 20_000
        while (!laptop.service.isConnected("tablet") && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue("the two should be connected", laptop.service.isConnected("tablet"))

        // The pen moves on the tablet.
        val drawn = mark("tablet-live", at = 9_000L)
        tablet.open.set(tablet.open.get().withPage(0, listOf(drawn), "tablet"))
        tablet.service.sendMarks(tablet.open.get().docId, listOf(drawn))

        assertTrue(
            "a live mark should arrive without anyone asking",
            laptop.marksArrived.await(20, TimeUnit.SECONDS)
        )
        assertEquals(listOf("tablet-live"), laptop.open.get().strokesOn(0).map { it.id })
    }

    @Test
    fun `a device that writes a file says so, without sending the file`() {
        val tabletPort = freePort()
        val tablet = Device("tablet", "Tablet", document())
        val laptop = Device("laptop", "Laptop", document())
        start(tablet, tabletPort)
        start(laptop, freePort())

        tablet.service.offerPairing("246810")
        laptop.service.pairWith("127.0.0.1", tabletPort, "246810").getOrThrow()

        val deadline = System.currentTimeMillis() + 20_000
        while (!laptop.service.isConnected("tablet") && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        tablet.service.announceWrote("homework.pdf", 4096, 1_700_000_000_000L)

        assertTrue(laptop.writeHeard.await(20, TimeUnit.SECONDS))
        assertEquals("homework.pdf", laptop.writes.get())
    }

    /** The rule the whole thing rests on: being on the network grants nothing. */
    @Test
    fun `a device with the wrong code is refused and pushes nothing`() {
        val tabletPort = freePort()
        val tablet = Device("tablet", "Tablet", document(mark("tablet-1")))
        val stranger = Device("stranger", "Stranger", document(mark("stranger-1")))
        start(tablet, tabletPort)
        start(stranger, freePort())

        tablet.service.offerPairing("111111")
        val attempt = stranger.service.pairWith("127.0.0.1", tabletPort, "999999")

        assertTrue("a wrong code must not pair", attempt.isFailure)
        assertFalse(
            "nothing should have reached the tablet",
            tablet.marksArrived.await(2, TimeUnit.SECONDS)
        )
        assertEquals(listOf("tablet-1"), tablet.open.get().strokesOn(0).map { it.id })
    }

    @Test
    fun `pairing is refused once the code is no longer being offered`() {
        val tabletPort = freePort()
        val tablet = Device("tablet", "Tablet", document())
        val laptop = Device("laptop", "Laptop", document())
        start(tablet, tabletPort)
        start(laptop, freePort())

        tablet.service.offerPairing("303030", forMs = 1)
        Thread.sleep(30)

        assertTrue(laptop.service.pairWith("127.0.0.1", tabletPort, "303030").isFailure)
    }
}
