package com.inkslate.core.peer

import com.inkslate.core.Box
import com.inkslate.core.InkCanvas
import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.PageStructure
import com.inkslate.core.PlannedPage
import com.inkslate.core.Stroke
import com.inkslate.core.StructureChange
import java.util.PriorityQueue
import kotlin.random.Random

/**
 * Two devices, a file sync between them, and a link - all simulated, all on one clock.
 *
 * The point is to use the sync the way a person does on a bad day: draw on both, close one and open
 * the other a second later, press Save in the middle of someone else's write, lose the connection
 * and get it back. [DocumentSync] is the real code; everything around it is a model, and the models
 * are deliberately unkind.
 *
 * ## The file sync model
 *
 * Built on what Syncthing actually does, because that is what turns two writes into a
 * `.sync-conflict-` copy:
 *
 *  - A local write is noticed only when the folder is next scanned, some seconds later.
 *  - What travels is a version vector. An arriving version that dominates the local one replaces
 *    the file; one that the local version dominates is ignored.
 *  - An arriving version that is concurrent with the local one, **or that arrives while there is a
 *    local write not yet scanned**, is a conflict. The newer file keeps the name; the conflict is
 *    counted.
 *  - Deliveries take anywhere from a fraction of a second to many seconds, and can overtake each
 *    other.
 */
class SyncSimulation(seed: Long, private val flaky: Boolean) {

    val rng = Random(seed)
    var now = 0L
        private set

    private var order = 0L
    private class Event(val at: Long, val order: Long, val run: () -> Unit)
    private val queue = PriorityQueue<Event>(compareBy<Event>({ it.at }, { it.order }))

    fun at(time: Long, run: () -> Unit) {
        queue.add(Event(time, order++, run))
    }

    fun runUntil(time: Long) {
        while (queue.isNotEmpty() && queue.peek().at <= time) {
            val e = queue.poll()
            now = e.at
            e.run()
        }
        now = time
    }

    /** The last few hundred things that happened, for a failure message worth reading. */
    val trace = ArrayDeque<String>()

    fun note(line: String) {
        // The wall clock too: it is what marks and tombstones are stamped with.
        trace.addLast("[${now}ms @${System.currentTimeMillis() % 1_000_000}] $line")
        while (trace.size > 3000) trace.removeFirst()
    }

    // ---- the document --------------------------------------------------------------

    val base: InkDocument = InkDocument.create(
        sourceName = "board.pdf", kind = "pdf", pageCount = 3, sizeBytes = 1, fingerprint = ""
    ).copy(canvas = null)

    /** Every mark drawn: the arrangement of pages it was drawn on, and the page. */
    val created = HashMap<String, Pair<String, Int>>()
    /** Every mark erased and not put back: the arrangement it was erased in, and its page. */
    val erased = HashMap<String, Pair<String, Int>>()
    /** Every rearrangement made, by the layout it made. */
    val changes = HashMap<String, StructureChange>()
    /** Writes of an arrangement of pages older than one already written. */
    var staleLayoutWrites = 0
        private set
    private var newestLayout = ""

    /**
     * Where a mark drawn as [id] on [page] of layout [from] is now, in layout [to]: one place for
     * each copy of its page, none if its page was removed. Null if [to] is not reached from [from].
     */
    fun descendants(id: String, from: String, page: Int, to: String): List<Pair<String, Int>>? {
        val route = PageStructure.path(from, to, changes.values.toList()) ?: return null
        var at = listOf(id to page)
        for (change in route) {
            at = at.flatMap { (mark, p) ->
                var occurrence = 0
                change.pages.withIndex().mapNotNull { (i, sp) ->
                    if (sp.source != p) null
                    else (PageStructure.remappedId(mark, change, occurrence++) to i)
                }
            }
        }
        return at
    }

    // ---- file sync -----------------------------------------------------------------

    data class FileState(
        val content: InkDocument,
        val rev: FileRevision,
        val vv: Map<String, Int>,
        val writtenAt: Long
    )

    inner class FileSync(private val names: List<String>) {
        val files = HashMap<String, FileState>()
        private val unscanned = HashSet<String>()
        var conflicts = 0
            private set
        private var revCounter = 0L

        init {
            val start = FileState(base, FileRevision(1, "start"), emptyMap(), 0)
            names.forEach { files[it] = start }
        }

        fun write(device: String, content: InkDocument): FileRevision {
            val rev = FileRevision(1000 + ++revCounter, "w$revCounter")
            if (content.layout != newestLayout) {
                if (PageStructure.path(content.layout, newestLayout, changes.values.toList()) != null) {
                    staleLayoutWrites++
                    note("STALE LAYOUT: $device writes pages arranged ${content.layout.ifEmpty { "as made" }} over $newestLayout")
                } else {
                    newestLayout = content.layout
                }
            }
            val old = files.getValue(device)
            files[device] = FileState(content, rev, old.vv, now)
            note("$device wrote ${rev.tail}")
            if (unscanned.add(device)) at(now + rng.nextLong(500, 6_000)) { scan(device) }
            return rev
        }

        private fun scan(device: String) {
            unscanned.remove(device)
            val f = files.getValue(device)
            files[device] = f.copy(vv = f.vv + (device to (f.vv[device] ?: 0) + 1))
            propagate(device)
        }

        private fun propagate(from: String) {
            val snapshot = files.getValue(from)
            for (other in names) {
                if (other == from) continue
                at(now + rng.nextLong(300, 12_000)) { deliver(other, snapshot) }
            }
        }

        private fun deliver(target: String, incoming: FileState) {
            val mine = files.getValue(target)
            when (compare(incoming.vv, mine.vv)) {
                Order.AFTER -> if (target in unscanned) conflict(target, incoming) else {
                    files[target] = incoming
                    note("sync delivered ${incoming.rev.tail} to $target")
                    devices.getValue(target).fileArrived()
                }
                Order.CONCURRENT -> conflict(target, incoming)
                Order.BEFORE, Order.SAME -> Unit
            }
        }

        private fun conflict(target: String, incoming: FileState) {
            conflicts++
            val mine = files.getValue(target)
            note("CONFLICT on $target: ${mine.rev.tail} vs ${incoming.rev.tail}")
            unscanned.remove(target)
            val winner = if (incoming.writtenAt >= mine.writtenAt) incoming else mine
            val vv = (mine.vv.keys + incoming.vv.keys).associateWith {
                maxOf(mine.vv[it] ?: 0, incoming.vv[it] ?: 0)
            }
            files[target] = winner.copy(vv = vv + (target to (vv[target] ?: 0) + 1))
            if (winner !== mine) devices.getValue(target).fileArrived()
            propagate(target)
        }
    }

    private enum class Order { BEFORE, AFTER, SAME, CONCURRENT }

    private fun compare(a: Map<String, Int>, b: Map<String, Int>): Order {
        var greater = false
        var less = false
        for (k in a.keys + b.keys) {
            val x = a[k] ?: 0
            val y = b[k] ?: 0
            if (x > y) greater = true
            if (x < y) less = true
        }
        return when {
            greater && less -> Order.CONCURRENT
            greater -> Order.AFTER
            less -> Order.BEFORE
            else -> Order.SAME
        }
    }

    // ---- the link ------------------------------------------------------------------

    var linkUp = true
        private set
    private var linkEpoch = 0
    private val lastDelivery = HashMap<Pair<String, String>, Long>()

    fun send(from: String, to: String, message: PeerMessage) {
        if (!linkUp) return
        val epoch = linkEpoch
        // Through the real encoding, so a field the wire drops is a failure here too.
        val text = PeerMessage.encode(message)
        val key = from to to
        val when_ = maxOf(now + rng.nextLong(2, 250), lastDelivery[key] ?: 0L)
        lastDelivery[key] = when_
        at(when_) {
            if (linkUp && linkEpoch == epoch) devices.getValue(to).receive(from, PeerMessage.decode(text)!!)
        }
        // Two sockets to the same device deliver some messages twice.
        if (flaky && rng.nextInt(100) == 0) {
            at(when_ + rng.nextLong(1, 400)) {
                if (linkUp && linkEpoch == epoch) devices.getValue(to).receive(from, PeerMessage.decode(text)!!)
            }
        }
    }

    fun linkDown() {
        if (!linkUp) return
        linkUp = false
        linkEpoch++
        lastDelivery.clear()
        note("LINK DOWN")
        for ((name, d) in devices) d.hub.disconnected(other(name))
    }

    fun linkUpAgain() {
        if (linkUp) return
        linkUp = true
        val epoch = ++linkEpoch
        note("LINK UP")
        at(now + rng.nextLong(50, 800)) {
            if (!linkUp || linkEpoch != epoch) return@at
            for ((name, d) in devices) d.hub.connected(other(name))
        }
    }

    // ---- the devices ---------------------------------------------------------------

    inner class Device(val name: String) {
        var ink: InkDocument? = null
            private set
        var session: DocumentSync? = null
            private set
        private var journal: InkDocument? = null
        private val ledger = WriteLedger()

        /** The same hub the apps use, running everything at once on the simulation's one thread. */
        val hub = LinkHub(
            post = { block -> block() },
            ledger = ledger,
            keepLedger = {},
            send = { peer, m -> send(name, peer, m) }
        )
        private var attached: LinkHub.Document? = null
        private var writeEndsAt = -1L
        private var writeSnapshot: InkDocument? = null
        private var lastEditAt = Long.MIN_VALUE / 2
        private var lastErased: Stroke? = null
        private var lastErasedLayout = ""
        private var strokeCounter = 0
        private var ticking = 0

        val isOpen get() = session != null

        fun open() {
            if (isOpen) return
            val file = sync.files.getValue(name)
            var doc = file.content
            journal?.let { doc = doc.mergeWith(it) }
            ink = doc
            val s = DocumentSync(
                me = name, docId = base.docId, fileName = "board.pdf",
                disk = file.rev, diskInk = file.content,
                lastKnownWrite = ledger[base.docId],
                send = { peer, m -> send(name, peer, m) },
                log = { note("$name: $it") }
            )
            session = s
            note("$name OPENS")
            val document = object : LinkHub.Document {
                override val docId = base.docId
                override fun onConnected(peer: String) = s.connected(peer, ink!!, now)
                override fun onDisconnected(peer: String) = s.disconnected(peer, now)
                override fun onMessage(peer: String, message: PeerMessage) {
                    ink = s.received(peer, message, ink!!, now)
                }
            }
            attached = document
            hub.attach(document)
            val loop = ++ticking
            scheduleTick(loop)
        }

        private fun scheduleTick(loop: Int) {
            at(now + 400) {
                if (ticking != loop || session == null) return@at
                tick()
                scheduleTick(loop)
            }
        }

        private fun tick() {
            val s = session ?: return
            if (writeEndsAt in 0..now) finishWrite()
            if (writeEndsAt >= 0) {
                s.tick(now, ink!!, idle = false)
                return
            }
            if (s.tick(now, ink!!, idle = now - lastEditAt >= 1_200)) {
                writeSnapshot = ink
                writeEndsAt = now + rng.nextLong(50, 2_000)
            }
        }

        private fun finishWrite() {
            val s = session ?: return
            val snapshot = writeSnapshot ?: return
            val rev = sync.write(name, snapshot)
            s.written(rev, snapshot, now)
            hub.wrote(base.docId, s.lastWrite)
            writeEndsAt = -1
            writeSnapshot = null
        }

        fun close() {
            val s = session ?: return
            if (writeEndsAt >= 0) finishWrite()
            val doc = ink!!
            if (s.closing(doc, now)) {
                val rev = sync.write(name, doc)
                s.written(rev, doc, now)
            }
            attached?.let { hub.detach(it) }
            attached = null
            s.closed(now)
            hub.wrote(base.docId, s.lastWrite)
            session = null
            ink = null
            ticking++
            note("$name CLOSES")
        }

        fun receive(from: String, message: PeerMessage) = hub.message(from, message)

        fun fileArrived() {
            val s = session ?: return
            val file = sync.files.getValue(name)
            val arrival = s.diskChanged(file.rev, { file.content }, ink!!, now)
            if (arrival.pagesChanged) note("$name loads the rearranged pages ${arrival.ink.layout}")
            ink = arrival.ink
        }

        private fun edited(next: InkDocument) {
            ink = next
            journal = next
            lastEditAt = now
        }

        fun draw() {
            val doc = ink ?: return
            val page = rng.nextInt(doc.source.pageCount)
            val id = "$name-${++strokeCounter}"
            val x = rng.nextFloat() * 500f
            val s = Stroke(
                id = id, kind = Stroke.Kind.FREEHAND, color = 0xFF000000.toInt(), baseWidth = 2f,
                points = listOf(InkPoint(x, 10f, 2f), InkPoint(x + 20f, 40f, 2f)),
                pageIndex = page, updatedUtc = System.currentTimeMillis()
            )
            created[id] = doc.layout to page
            note("$name draws $id on page $page of ${doc.layout.ifEmpty { "-" }}")
            edited(doc.withPage(page, doc.strokesOn(page) + s, name))
        }

        fun erase() {
            val doc = ink ?: return
            val page = rng.nextInt(doc.source.pageCount)
            val on = doc.strokesOn(page)
            if (on.isEmpty()) return
            // Strictly after anything else in wall-clock time, which is what the merge orders by.
            Thread.sleep(2)
            val victim = on[rng.nextInt(on.size)]
            erased[victim.id] = doc.layout to page
            lastErased = victim
            lastErasedLayout = doc.layout
            note("$name erases ${victim.id}")
            edited(doc.withPage(page, on - victim, name))
        }

        fun undoErase() {
            val doc = ink ?: return
            val back = lastErased ?: return
            lastErased = null
            // Undo does not reach back across a rearrangement: the editor starts afresh with the
            // new pages.
            if (lastErasedLayout != doc.layout) return
            if (doc.strokesOn(back.pageIndex).any { it.id == back.id }) return
            Thread.sleep(2)
            erased.remove(back.id)
            // The same mark erased on the other device under the id it had before a rearrangement
            // is put back too: the undo is the later of the two. So is the same mark erased after a rearrangement this device has not caught up with.
            erased.entries.removeAll { (id, where) ->
                descendants(id, where.first, where.second, doc.layout)?.any { it.first == back.id } == true ||
                    descendants(back.id, doc.layout, back.pageIndex, where.first)?.any { it.first == id } == true
            }
            note("$name undoes erase of ${back.id}")
            edited(doc.withPage(back.pageIndex, doc.strokesOn(back.pageIndex) + back, name))
        }

        fun bookmark() {
            val doc = ink ?: return
            val page = rng.nextInt(doc.source.pageCount)
            note("$name bookmarks page $page")
            edited(doc.withBookmarkAdded(page, "Page ${page + 1}"))
        }

        fun removeBookmark() {
            val doc = ink ?: return
            val mark = doc.bookmarks.randomOrNull(rng) ?: return
            note("$name removes the bookmark on page ${mark.page}")
            edited(doc.withBookmarkRemoved(mark.page))
        }

        fun growCanvas() {
            val doc = ink ?: return
            val c = doc.canvas ?: InkCanvas.startingAt(612f, 792f)
            val grown = c.grownTo(Box(-rng.nextFloat() * 900f, 0f, 600f + rng.nextFloat() * 900f, 700f))
            note("$name grows the canvas")
            edited(doc.copy(canvas = grown))
        }

        fun recolour() {
            val doc = ink ?: return
            val c = doc.canvas ?: InkCanvas.startingAt(612f, 792f)
            Thread.sleep(2)
            note("$name recolours the paper")
            edited(
                doc.copy(
                    canvas = c.copy(
                        paperColor = rng.nextInt(), settingsUtc = System.currentTimeMillis()
                    )
                )
            )
        }

        fun save() {
            val s = session ?: return
            note("$name presses Save")
            s.requestSave()
        }

        /**
         * Move, copy, turn or remove pages - as the editors do it: only once this device may write,
         * asking to first when another device writes, and writing the rearranged file at once.
         */
        fun rearrange() {
            val s = session ?: return
            val doc = ink ?: return
            if (writeEndsAt >= 0) return
            if (!s.mayWriteNow(now)) {
                note("$name wants to rearrange pages, and asks to write first")
                s.requestSave()
                return
            }
            val count = doc.source.pageCount
            var plan = (0 until count).map { PlannedPage(source = it, uid = it.toLong()) }
            repeat(1 + rng.nextInt(2)) {
                val at = rng.nextInt(plan.size)
                plan = when (rng.nextInt(5)) {
                    0 -> plan.toMutableList().also { it.add(rng.nextInt(plan.size), it.removeAt(at)) }
                    1 -> if (plan.size < 6) plan.toMutableList().also { it.add(at + 1, it[at].copy(uid = 100L + at)) } else plan
                    2 -> if (plan.size > 1) plan.toMutableList().also { it.removeAt(at) } else plan
                    3 -> plan.toMutableList().also { it[at] = it[at].copy(quarterTurns = 1 + rng.nextInt(3)) }
                    else -> if (plan.size < 6) plan.toMutableList().also { it.add(at, PlannedPage(-1, 200L + at)) } else plan
                }
            }
            val layout = "$name-L${++strokeCounter}"
            // A rearrangement retires marks as of its own moment, and an erase in the very same
            // millisecond on another device reads as one of those retirements. Real devices do not
            // share a millisecond like a simulation running a session in a blink does.
            Thread.sleep(2)
            val (next, change) = PageStructure.restructure(
                doc, plan, { 612f to 792f }, at = System.currentTimeMillis(), to = layout
            )
            changes[layout] = change
            note("$name REARRANGES ${doc.layout.ifEmpty { "-" }} -> $layout: ${change.pages.map { it.source }}")
            lastErased = null
            edited(next)
            val rev = sync.write(name, next)
            s.written(rev, next, now)
            hub.wrote(base.docId, s.lastWrite)
            Thread.sleep(2)
        }

        /**
         * A write the app makes of its own accord, outside the heartbeat: going to the background,
         * or writing before sharing the file. Allowed only when the link says so.
         */
        fun writeOnTheWayOut() {
            val s = session ?: return
            val doc = ink ?: return
            if (writeEndsAt >= 0 || !s.mayWriteNow(now)) return
            note("$name goes to the background and writes")
            val rev = sync.write(name, doc)
            s.written(rev, doc, now)
            hub.wrote(base.docId, s.lastWrite)
        }
    }

    val devices = linkedMapOf("tablet" to Device("tablet"), "laptop" to Device("laptop"))
    val sync = FileSync(devices.keys.toList())

    fun other(name: String) = devices.keys.first { it != name }

    // ---- a session -----------------------------------------------------------------

    /** Random use, then a long quiet spell with both devices open and linked. */
    fun play(steps: Int) {
        // The link is up from the start; each device's hub knows the other is there.
        for ((name, d) in devices) d.hub.connected(other(name))
        devices.values.forEach { if (rng.nextBoolean()) it.open() }
        if (devices.values.none { it.isOpen }) devices.values.first().open()

        repeat(steps) {
            runUntil(now + rng.nextLong(50, 3_500))
            val d = devices.values.elementAt(rng.nextInt(devices.size))
            when (rng.nextInt(100)) {
                in 0..39 -> if (d.isOpen) d.draw() else d.open()
                in 40..49 -> d.erase()
                in 50..53 -> d.undoErase()
                in 54..59 -> d.close()
                in 60..67 -> d.open()
                in 68..71 -> d.save()
                in 72..73 -> d.bookmark()
                74 -> d.removeBookmark()
                in 75..77 -> d.growCanvas()
                in 78..79 -> d.recolour()
                in 80..85 -> if (flaky) {
                    if (linkUp) linkDown() else linkUpAgain()
                }
                in 86..88 -> d.writeOnTheWayOut()
                // Only the tablet rearranges when the link comes and goes. Two devices rearranging
                // the same document while unable to speak cannot both keep their arrangement, and
                // are settled by keeping the later one - covered on its own, not here.
                in 89..92 -> if (!flaky || d.name == "tablet") d.rearrange()
                else -> Unit
            }
        }

        // Everything settles: linked, both open, nobody drawing, for long enough that every write
        // has been agreed, made and delivered.
        linkUpAgain()
        runUntil(now + 1_000)
        devices.values.forEach { it.open() }
        runUntil(now + 8 * 60_000L)
    }
}
