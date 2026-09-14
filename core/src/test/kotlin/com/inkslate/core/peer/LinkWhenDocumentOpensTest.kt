package com.inkslate.core.peer

import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.PageStructure
import com.inkslate.core.PlannedPage
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
        val base = InkDocument.create("board.pdf", "pdf", 3, 10, "")
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
        /** This device's pictures, by id. */
        val pictures = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val connects = AtomicInteger()
        val drops = AtomicInteger()

        val hub = LinkHub(
            post = { block -> thread.execute { runCatching(block).onFailure { note("$name: hub task threw $it") } } },
            ledger = WriteLedger(),
            keepLedger = {},
            send = { peer, m -> sendLogged(peer, m) }
        )

        fun sendLogged(peer: String, m: PeerMessage) {
            note("$name -> ${m::class.simpleName} ${if (m is PeerMessage.ImageData) "${m.png.length} chars" else PeerMessage.encode(m).take(160)}")
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
                log = { note("$name link: $it") },
                images = ImageLink(
                    docId = doc.docId,
                    have = { pictures.containsKey(it) },
                    // Off the UI thread, as the apps do: a picture is megabytes, and Android
                    // forbids the network there.
                    serve = { peer, ids ->
                        Thread {
                            ids.forEach { id ->
                                ImageLink.dataFor(doc.docId, id, pictures.getValue(id))?.let { sendLogged(peer, it) }
                            }
                        }.start()
                    },
                    keep = { id, png -> pictures[id] = png; note("$name KEEPS picture $id (${png.size} bytes)") },
                    send = { peer, m -> sendLogged(peer, m) },
                    log = { note("$name link: $it") }
                )
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

        /** Draw a mark, the way the editor does: into the document the link is given next. */
        fun draw(id: String, page: Int) = thread.execute {
            val doc = ink ?: return@execute
            note("$name DRAWS $id on page $page of ${doc.layout.ifEmpty { "-" }}")
            val mark = Stroke(
                id = id, kind = Stroke.Kind.FREEHAND, color = 0xFF000000.toInt(), baseWidth = 2f,
                points = listOf(InkPoint(1f, 1f, 2f), InkPoint(9f, 9f, 2f)), pageIndex = page,
                updatedUtc = System.currentTimeMillis()
            )
            ink = doc.withPage(page, doc.strokesOn(page) + mark, tag)
        }

        /** Rearrange the pages as the editors do: once this device may write, writing at once. */
        fun rearrange(vararg sources: Int, onDone: (Boolean) -> Unit) = thread.execute {
            val s = session!!
            if (!s.mayWriteNow(now())) {
                note("$name wants to rearrange, asks to write")
                s.requestSave()
                onDone(false)
                return@execute
            }
            val plan = sources.mapIndexed { i, src -> PlannedPage(source = src, uid = i.toLong()) }
            val next = PageStructure.restructure(ink!!, plan, { 612f to 792f }).first
            note("$name REARRANGES into ${next.layout.take(8)}")
            ink = next
            writtenFile = next
            s.written(FileRevision(99, "rearranged"), next, now())
            hub.wrote(next.docId, s.lastWrite)
            onDone(true)
        }

        /** The rearranged file reaches this device, and the editor reads the document again. */
        fun fileArrives(file: InkDocument) = thread.execute {
            val s = session ?: return@execute
            val arrival = s.diskChanged(FileRevision(99, "rearranged"), { file }, ink!!, now())
            note("$name gets the file: pagesChanged=${arrival.pagesChanged}")
            if (arrival.pagesChanged) {
                // What the editor does: close, and open the document again with everything in it.
                attached?.let { hub.detach(it) }
                s.closed(now())
                session = null
                reopen = arrival.ink
            }
        }

        @Volatile var writtenFile: InkDocument? = null
        @Volatile var reopen: InkDocument? = null

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
    fun `rearranging pages while both have it open keeps every mark, on the page it was drawn on`() {
        val (tablet, laptop) = pairedPair()
        val doc = document("start-0")
        laptop.open(doc)
        tablet.open(doc)
        Thread.sleep(2_000)

        // The laptop takes over writing and moves page 0 to the end.
        var done = false
        val deadline = System.currentTimeMillis() + 15_000
        while (!done && System.currentTimeMillis() < deadline) {
            laptop.rearrange(1, 2, 0) { done = it }
            Thread.sleep(600)
        }
        assertTrue("the laptop should have been handed the writing", done)
        // The tablet has not got the file yet, and draws on its old page 0.
        tablet.draw("tablet-early", 0)
        laptop.draw("laptop-after", 1)
        Thread.sleep(2_000)
        tablet.fileArrives(laptop.writtenFile!!)
        Thread.sleep(1_000)
        tablet.open(tablet.reopen!!)
        Thread.sleep(4_000)

        report("rearranged", tablet, laptop)
        val onTablet = tablet.ink!!
        val onLaptop = laptop.ink!!
        assertEquals("both on the new pages", onLaptop.layout, onTablet.layout)
        fun describe(d: InkDocument) = d.pages.values.flatten().map { it.id to it.pageIndex }.toSet()
        assertEquals("both hold the same marks, in the same places", describe(onLaptop), describe(onTablet))
        // Old page 0 is now page 2; the tablet's mark followed it there.
        val early = PageStructure.remappedId("tablet-early", onLaptop.structureHistory.last(), 0)
        assertTrue("the tablet's mark reached the laptop, on page 2: ${describe(onLaptop)}",
            (early to 2) in describe(onLaptop) || ("tablet-early" to 2) in describe(onLaptop))
        assertTrue("the laptop's mark reached the tablet", describe(onTablet).any { it.first == "laptop-after" })
        // Every message about marks names the arrangement it is for; one that does not is ignored
        // by a device whose pages have moved.
        val lines = timeline.toList()
        val afterReopen = lines.drop(lines.indexOfLast { "Tablet OPENS" in it })
        val unnamed = afterReopen.filter { ("\"t\":\"want\"" in it || "\"t\":\"digest\"" in it) && "\"layout\":\"\"" in it }
        assertTrue("messages for the old arrangement after both reopened: $unnamed", unnamed.isEmpty())
    }

    @Test
    fun `a picture placed on one device reaches the other over the link`() {
        val (tablet, laptop) = pairedPair()
        // A photograph-sized picture: several megabytes, in one message.
        val png = ByteArray(3 * 1024 * 1024).also {
            java.util.Random(7).nextBytes(it)
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()).copyInto(it)
        }
        laptop.pictures["photo01"] = png
        val picture = Stroke(
            id = "laptop-pic", kind = Stroke.Kind.IMAGE, color = 0, baseWidth = 1f,
            points = listOf(InkPoint(0f, 0f, 1f), InkPoint(100f, 100f, 1f)), pageIndex = 0,
            imageId = "photo01", updatedUtc = 1_000
        )
        val doc = document().let { it.withPage(0, listOf(picture), "laptop") }
        laptop.open(doc)
        tablet.open(doc.copy(pages = emptyMap()))
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && !tablet.pictures.containsKey("photo01")) Thread.sleep(100)
        report("picture", tablet, laptop)
        assertTrue("the picture should have reached the tablet", tablet.pictures["photo01"]?.contentEquals(png) == true)
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
