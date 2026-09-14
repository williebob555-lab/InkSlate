package com.inkslate.core.peer

import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two devices linked with nothing open, and then a document is opened - the way the apps do it.
 *
 * Reported: the link held until a document was opened, then connected and dropped every couple
 * of seconds. The other link tests open their documents before connecting; this one follows the
 * real order, and writes a timeline of every message and connection event to
 * `core/build/link-timeline-<case>.txt`, so a failure explains itself.
 */
class LinkWhenDocumentOpensTest {

    private companion object {
        val counter = AtomicInteger(100)
    }

    private val run = counter.incrementAndGet()
    private val devices = mutableListOf<Device>()
    private val timeline = CopyOnWriteArrayList<String>()
    private val started = System.currentTimeMillis()

    private fun note(line: String) {
        timeline.add("[+${System.currentTimeMillis() - started}ms] $line")
    }

    @After
    fun tearDown() {
        devices.forEach { it.stop() }
    }

    private fun document(vararg ids: String): InkDocument {
        val base = InkDocument.create("board.pdf", "pdf", 1, 10, "")
        val strokes = ids.map {
            Stroke(
                id = it, kind = Stroke.Kind.FREEHAND, color = 0xFF000000.toInt(), baseWidth = 2f,
                points = listOf(InkPoint(1f, 1f, 2f), InkPoint(9f, 9f, 2f)), pageIndex = 0,
                updatedUtc = 1_000
            )
        }
        return if (strokes.isEmpty()) base else base.withPage(0, strokes, "test")
    }

    /** A device as the apps build one: transport, hub, and a document opened later, or not. */
    private inner class Device(val tag: String, val name: String) : PeerService.Host {
        /** The device's UI thread - where the apps' hub runs everything, and where Android forbids network writes. */
        private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "$name-ui") }
        private val ticker = Executors.newSingleThreadScheduledExecutor()
        lateinit var service: PeerService
        @Volatile var ink: InkDocument? = null
        @Volatile var session: DocumentSync? = null
        private var attached: LinkHub.Document? = null
        val connects = AtomicInteger()
        val drops = AtomicInteger()

        val hub = LinkHub(
            post = { block -> thread.execute { runCatching(block).onFailure { note("$name: hub task threw $it") } } },
            ledger = WriteLedger(),
            keepLedger = {},
            send = { peer, m -> sendLogged(peer, m) }
        )

        fun sendLogged(peer: String, m: PeerMessage) {
            note("$name -> ${m::class.simpleName} ${PeerMessage.encode(m).take(160)}")
            service.send(peer, m)
        }

        /** What an editor does when a document finishes loading. */
        fun open(doc: InkDocument) = thread.execute {
            note("$name OPENS ${doc.docId.take(8)}")
            ink = doc
            val s = DocumentSync(
                me = tag, docId = doc.docId, fileName = "board.pdf",
                disk = FileRevision(10, "start"), diskInk = doc,
                send = { peer, m -> sendLogged(peer, m) },
                log = { note("$name link: $it") }
            )
            session = s
            val d = object : LinkHub.Document {
                override val docId = doc.docId
                override fun onConnected(peer: String) = s.connected(peer, ink!!, now())
                override fun onDisconnected(peer: String) = s.disconnected(peer, now())
                override fun onMessage(peer: String, message: PeerMessage) {
                    ink = s.received(peer, message, ink!!, now())
                }
            }
            attached = d
            hub.attach(d)
        }

        fun close() = thread.execute {
            val s = session ?: return@execute
            note("$name CLOSES")
            attached?.let { hub.detach(it) }
            s.closed(now())
            session = null
            ink = null
        }

        init {
            ticker.scheduleAtFixedRate({
                thread.execute {
                    val s = session ?: return@execute
                    // Writes are what the app would make here; this test has no file to write,
                    // so every write the link asks for is reported as not having happened.
                    if (s.tick(now(), ink!!, idle = true)) s.writeFailed(now())
                }
            }, 400, 400, TimeUnit.MILLISECONDS)
        }

        private fun now() = System.currentTimeMillis()

        override fun deviceTag() = tag
        override fun deviceName() = name
        override fun appVersion() = "test"
        override fun onConnected(peer: String) {
            connects.incrementAndGet()
            note("$name: CONNECTED to $peer")
            hub.connected(peer)
        }
        override fun onDisconnected(peer: String) {
            drops.incrementAndGet()
            note("$name: DISCONNECTED from $peer")
            hub.disconnected(peer)
        }
        override fun onMessage(peer: String, message: PeerMessage) {
            note("$name <- ${message::class.simpleName}")
            hub.message(peer, message)
        }
        override fun onRemoteWrite(peer: String, fileName: String?) = Unit
        override fun log(level: String, message: String) = note("$name [$level] $message")

        fun stop() {
            ticker.shutdownNow()
            service.stop()
            thread.shutdownNow()
        }
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    /**
     * Android's rule, applied here: a socket write on the UI thread throws, as
     * NetworkOnMainThreadException does on the tablet. Without it this test runs on a desktop JVM
     * where the rule does not exist, and passes while the tablet drops every connection.
     */
    private val androidRules: (java.io.OutputStream) -> java.io.OutputStream = { raw ->
        object : java.io.FilterOutputStream(raw) {
            private fun check() {
                val thread = Thread.currentThread().name
                if (thread.endsWith("-ui")) {
                    throw IllegalStateException("network write on the UI thread ($thread)")
                }
            }
            override fun write(b: Int) { check(); out.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { check(); out.write(b, off, len) }
            override fun flush() { check(); out.flush() }
        }
    }

    private fun pairedPair(): Pair<Device, Device> {
        val tablet = Device("tablet-$run", "Tablet").also { devices += it }
        val laptop = Device("laptop-$run", "Laptop").also { devices += it }
        val tabletPort = freePort()
        tablet.service = PeerService(tablet, androidRules).also { it.start(emptyList(), tabletPort) }
        laptop.service = PeerService(laptop, androidRules).also { it.start(emptyList(), freePort()) }
        tablet.service.offerPairing("112233")
        laptop.service.pairWith("127.0.0.1", tabletPort, "112233").getOrThrow()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline &&
            !(laptop.service.isConnected(tablet.tag) && tablet.service.isConnected(laptop.tag))
        ) Thread.sleep(50)
        assertTrue("the two should connect", laptop.service.isConnected(tablet.tag))
        Thread.sleep(300)
        // Pairing's own socket closes as soon as it has done its job, which is counted as a drop.
        // Only what happens from here on is the subject of this test.
        tablet.drops.set(0)
        laptop.drops.set(0)
        note("---- linked, nothing open")
        return tablet to laptop
    }

    private fun report(case: String, vararg devicesToCheck: Device) {
        val file = File("build/link-timeline-$case.txt")
        file.parentFile.mkdirs()
        file.writeText(timeline.joinToString("\n"))
        println("Timeline written to ${file.absolutePath}")
        for (d in devicesToCheck) {
            assertEquals(
                "${d.name} lost the link after a document was opened - see ${file.absolutePath}\n" +
                    timeline.takeLast(40).joinToString("\n"),
                0, d.drops.get()
            )
        }
    }

    @Test
    fun `opening a document on one device keeps the link up`() {
        val (tablet, laptop) = pairedPair()
        Thread.sleep(1_500)
        laptop.open(document("laptop-1"))
        Thread.sleep(6_000)
        report("one-side", tablet, laptop)
    }

    @Test
    fun `opening the same document on both keeps the link up and brings the marks across`() {
        val (tablet, laptop) = pairedPair()
        val doc = document("laptop-1")
        laptop.open(doc)
        Thread.sleep(1_500)
        // The same document, as a device that has not seen the laptop's mark yet.
        tablet.open(doc.copy(pages = emptyMap()))
        Thread.sleep(6_000)
        report("both-sides", tablet, laptop)
        assertEquals(
            "the laptop's mark should have reached the tablet over the link",
            setOf("laptop-1"), tablet.ink?.pages?.values?.flatten()?.map { it.id }?.toSet()
        )
    }

    @Test
    fun `opening different documents keeps the link up`() {
        val (tablet, laptop) = pairedPair()
        laptop.open(document("laptop-1"))
        Thread.sleep(1_000)
        tablet.open(document("tablet-1"))
        Thread.sleep(6_000)
        report("different", tablet, laptop)
    }
}
