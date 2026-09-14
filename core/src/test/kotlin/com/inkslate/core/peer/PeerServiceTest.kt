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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Two devices, two sockets, one document - on one machine, over the loopback.
 *
 * The decisions are pinned down by the simulation in [DocumentSyncSimulationTest]; this is the other
 * half, the half that cannot be reasoned about from the code alone: that a device which has never met
 * another can pair with it using a code read off a screen, that the real engine then keeps the two
 * in step over real sockets, and that a device which knows no code gets nothing at all.
 */
class PeerServiceTest {

    private companion object {
        val counter = AtomicInteger()
    }

    private val started = mutableListOf<Device>()

    /**
     * A different identity for every test in the class.
     *
     * The services run on real sockets and their retry loops outlive the test that made them by a
     * moment. Reusing names meant a leftover loop from one test could connect to the next test's
     * listener and pair with it - a failure that only appears on a machine fast enough to get there.
     */
    private val run = counter.incrementAndGet()

    @After
    fun tearDown() {
        started.forEach { it.stop() }
        started.clear()
    }

    /** One document, as both devices see it: the same id, because it is the same file. */
    private val base = InkDocument.create(
        sourceName = "homework.pdf", kind = "pdf", pageCount = 1, sizeBytes = 10, fingerprint = ""
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

    /**
     * A device under test: a transport, the shared hub, and the real engine with a document open,
     * all driven the way an app drives them - one thread for the engine, a ticker on it.
     */
    private inner class Device(val tag: String, val name: String, doc: InkDocument) : PeerService.Host {
        private val thread = Executors.newSingleThreadExecutor()
        private val ticker = Executors.newSingleThreadScheduledExecutor()
        val ink = AtomicReference(doc)
        val paired = AtomicReference<PeerService.Peer?>(null)
        val writes = AtomicReference<String?>(null)
        val writeHeard = CountDownLatch(1)
        val connectedTo = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val connections = AtomicInteger()
        lateinit var service: PeerService

        val hub = LinkHub(post = { block -> thread.execute(block) }, ledger = WriteLedger(), keepLedger = {})

        private val session = DocumentSync(
            me = tag, docId = doc.docId, fileName = "homework.pdf",
            disk = null, diskInk = null,
            send = { peer, m -> service.send(peer, m) }
        )

        init {
            hub.attach(object : LinkHub.Document {
                override val docId = doc.docId
                override fun onConnected(peer: String) = session.connected(peer, ink.get(), now())
                override fun onDisconnected(peer: String) = session.disconnected(peer, now())
                override fun onMessage(peer: String, message: PeerMessage) {
                    ink.set(session.received(peer, message, ink.get(), now()))
                }
            })
            ticker.scheduleAtFixedRate({
                thread.execute {
                    // Never idle, so nothing here tries to write a file this test does not have.
                    session.tick(now(), ink.get(), idle = false)
                }
            }, 100, 100, TimeUnit.MILLISECONDS)
        }

        /** A mark made on this device, the way an edit reaches the engine: on its thread. */
        fun draw(stroke: Stroke) = thread.execute {
            ink.set(ink.get().withPage(0, ink.get().strokesOn(0) + stroke, tag))
        }

        fun ids(): Set<String> = ink.get().pages.values.flatten().map { it.id }.toSet()

        private fun now() = System.currentTimeMillis()

        override fun deviceTag() = tag
        override fun deviceName() = name
        override fun onConnected(peer: String) {
            connectedTo.add(peer)
            connections.incrementAndGet()
            hub.connected(peer)
        }
        override fun onDisconnected(peer: String) = hub.disconnected(peer)
        override fun onMessage(peer: String, message: PeerMessage) = hub.message(peer, message)
        override fun onRemoteWrite(peer: String, fileName: String?) {
            writes.set(fileName ?: "(library)")
            writeHeard.countDown()
        }
        override fun onPaired(peer: PeerService.Peer) = paired.set(peer)

        fun stop() {
            ticker.shutdownNow()
            service.stop()
            thread.shutdownNow()
        }
    }

    private fun start(device: Device, port: Int, peers: List<PeerService.Peer> = emptyList()) {
        device.service = PeerService(device)
        started.add(device)
        device.service.start(peers, port)
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun waitFor(what: String, seconds: Long = 20, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(what, condition())
    }

    @Test
    fun `two devices pair with a code and then agree about the document`() {
        val tabletPort = freePort()
        val tablet = Device("tablet-$run", "Tablet", document(mark("tablet-1")))
        val laptop = Device("laptop-$run", "Laptop", document(mark("laptop-1")))
        start(tablet, tabletPort)
        start(laptop, freePort())

        // The code is showing on the tablet; it is typed into the laptop.
        val code = "424242"
        tablet.service.offerPairing(code)
        val paired = laptop.service.pairWith("127.0.0.1", tabletPort, code)

        assertTrue("pairing should succeed: ${paired.exceptionOrNull()}", paired.isSuccess)
        assertEquals("tablet-$run", paired.getOrThrow().tag)
        assertEquals("Tablet", paired.getOrThrow().name)

        val expected = setOf("tablet-1", "laptop-1")
        waitFor("each side should fill the other's gap") {
            tablet.ids() == expected && laptop.ids() == expected
        }
        assertNotNull("the tablet should have stored the laptop too", tablet.paired.get())
    }

    @Test
    fun `a mark made after the connection is up arrives on its own`() {
        val tabletPort = freePort()
        val tablet = Device("tablet-$run", "Tablet", document())
        val laptop = Device("laptop-$run", "Laptop", document())
        start(tablet, tabletPort)
        start(laptop, freePort())

        tablet.service.offerPairing("135790")
        laptop.service.pairWith("127.0.0.1", tabletPort, "135790").getOrThrow()
        waitFor("the two should be connected") { laptop.service.isConnected("tablet-$run") }

        // The pen moves on the tablet.
        tablet.draw(mark("tablet-live", at = 9_000L))

        waitFor("a live mark should arrive without anyone asking") {
            laptop.ids() == setOf("tablet-live")
        }
    }

    /**
     * Both devices reaching for each other in the same moment.
     *
     * Each ends up with two sockets for a while. The engine has to be told about the other device
     * once - not once per socket - and the two still have to end up in step.
     */
    @Test
    fun `two devices dialling each other at once still end up in step`() {
        val tabletPort = freePort()
        val laptopPort = freePort()
        val tablet = Device("tablet-$run", "Tablet", document(mark("tablet-1")))
        val laptop = Device("laptop-$run", "Laptop", document(mark("laptop-1")))
        start(tablet, tabletPort)
        start(laptop, laptopPort)

        tablet.service.offerPairing("505050")
        val peer = laptop.service.pairWith("127.0.0.1", tabletPort, "505050").getOrThrow()
        tablet.service.addPeer(PeerService.Peer("laptop-$run", "Laptop", "127.0.0.1", laptopPort, "505050"))
        laptop.service.addPeer(peer)

        val expected = setOf("tablet-1", "laptop-1")
        waitFor("both should end up with both marks", seconds = 25) {
            tablet.ids() == expected && laptop.ids() == expected
        }
        assertTrue(tablet.connectedTo.contains("laptop-$run"))
        assertTrue(laptop.connectedTo.contains("tablet-$run"))
    }

    @Test
    fun `a device that writes a file says so, without sending the file`() {
        val tabletPort = freePort()
        val tablet = Device("tablet-$run", "Tablet", document())
        val laptop = Device("laptop-$run", "Laptop", document())
        start(tablet, tabletPort)
        start(laptop, freePort())

        tablet.service.offerPairing("246810")
        laptop.service.pairWith("127.0.0.1", tabletPort, "246810").getOrThrow()
        waitFor("the two should be connected") { laptop.service.isConnected("tablet-$run") }

        tablet.service.announceWrote("homework.pdf", 4096, 1_700_000_000_000L)

        assertTrue(laptop.writeHeard.await(20, TimeUnit.SECONDS))
        assertEquals("homework.pdf", laptop.writes.get())
    }

    /** The rule the whole thing rests on: being on the network grants nothing. */
    @Test
    fun `a device with the wrong code is refused and pushes nothing`() {
        val tabletPort = freePort()
        val tablet = Device("tablet-$run", "Tablet", document(mark("tablet-1")))
        val stranger = Device("stranger-$run", "Stranger", document(mark("stranger-1")))
        start(tablet, tabletPort)
        start(stranger, freePort())

        tablet.service.offerPairing("111111")
        val attempt = stranger.service.pairWith("127.0.0.1", tabletPort, "999999")

        assertTrue("a wrong code must not pair", attempt.isFailure)
        Thread.sleep(1_500)
        assertFalse("the tablet should not count the stranger as connected", tablet.connectedTo.isNotEmpty())
        assertEquals(setOf("tablet-1"), tablet.ids())
    }

    @Test
    fun `pairing is refused once the code is no longer being offered`() {
        val tabletPort = freePort()
        val tablet = Device("tablet-$run", "Tablet", document())
        val laptop = Device("laptop-$run", "Laptop", document())
        start(tablet, tabletPort)
        start(laptop, freePort())

        tablet.service.offerPairing("303030", forMs = 1)
        Thread.sleep(30)

        assertTrue(laptop.service.pairWith("127.0.0.1", tabletPort, "303030").isFailure)
    }
}
