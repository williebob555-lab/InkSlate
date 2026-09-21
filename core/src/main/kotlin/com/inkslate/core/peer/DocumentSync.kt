package com.inkslate.core.peer

import com.inkslate.core.InkDocument
import com.inkslate.core.PageStructure

/**
 * One open document, kept in step with every other device that has it open.
 *
 * ## Why this exists
 *
 * The decisions that make two devices agree about a document - what to send, what to take in,
 * when to write the file and when to leave it alone - used to live inside each app's editor
 * screen, written twice, tuned with timers, and testable only by hand. The same conflict bug had
 * to be found and fixed once per app. This is the one place those decisions are made now; both
 * editors hand it what happened and do what it says.
 *
 * It is pure: no sockets, no files, no clock of its own. Time is passed in, messages go out
 * through [send], and the caller does the writing. That is what lets a simulation drive two of
 * these through thousands of sessions - lossy links, slow file sync, documents opened and closed
 * at the worst moments - and check the outcome, which no amount of reading this can replace.
 * Calls are expected on one thread.
 *
 * ## The rules
 *
 * - **Marks flow both ways, all the time.** Each side streams what the other lacks, and a write's
 *   announcement carries what it held, so marks that went astray are sent again.
 * - **One device writes a document while more than one has it open**, and which one is agreed
 *   over the link ([Lease]) rather than guessed from timing. The others' marks reach the writer
 *   live and are written by it. Two devices writing one file at once is exactly what file sync
 *   cannot reconcile, and this is what removes it rather than making it rarer.
 * - **A device writes on top of the latest write, never beside it.** Before writing, its own disk
 *   has to hold the most recent write any device has announced. Writing earlier would be a second,
 *   rival version of the file.
 * - **Alone, a device writes as it always has**, and says so when it next meets another, so that
 *   the two settle who has the newer file before either writes again.
 * - **Pages are rearranged by the device that writes.** Marks keep flowing from a device that has
 *   not caught up - they are brought forward to the new arrangement on arrival - but that device
 *   writes nothing until the rearranged file has reached it, because what it would write is the old
 *   arrangement of pages. See [PageStructure].
 */
class DocumentSync(
    private val me: String,
    val docId: String,
    private val fileName: String,
    disk: FileRevision?,
    diskInk: InkDocument?,
    /**
     * The latest write of this document this device has heard of, kept while it was closed.
     *
     * Close a document on the tablet and open it on the laptop a second later, and the tablet's
     * last write is still on its way through file sync. Without remembering that it was announced,
     * the laptop would write on top of the file it has - a rival to the one arriving. See
     * [WriteLedger].
     */
    lastKnownWrite: WriteRecord? = null,
    private val send: (peer: String, message: PeerMessage) -> Unit,
    private val log: (String) -> Unit = {},
    /** Pictures the marks show, asked for from the devices that have them. Null: marks only. */
    private val images: ImageLink? = null
) {

    /** What the link is doing for this document, for the indicator beside the save state. */
    enum class Status {
        /** No other device has this document open. */
        ALONE,
        /** Linked, and this device writes the document. */
        WRITING_HERE,
        /** Linked, and another device writes it; marks made here reach it live. */
        WRITTEN_ELSEWHERE,
        /** Settling which device writes. */
        AGREEING,
        /** This device writes, once the other device's last write has reached this disk. */
        WAITING_FOR_FILE
    }

    /** What a change on disk came to. */
    data class Arrival(
        /** The document to show: the one held before, or that merged with what arrived. */
        val ink: InkDocument,
        /** True when the file brought something the document did not already have. */
        val merged: Boolean,
        /** True when the file on disk now holds everything in [ink]: nothing left to write. */
        val holdsEverything: Boolean,
        /**
         * True when the pages were rearranged elsewhere and [ink] is now laid out for the file's
         * new arrangement. The document's pages have to be loaded again from the file before it is
         * shown - handwriting for page 3 of the new arrangement is not for page 3 of the old.
         */
        val pagesChanged: Boolean = false
    )

    private class Peer {
        var connected = false
        /**
         * They have said whether they have this document open - [PeerMessage.Editing] for it, or
         * [PeerMessage.Closed]. Until then this side cannot know it is alone, and does not act as if
         * it were: a device that has only just opened the document was writing in the moment before
         * the other device's answer arrived, beside that device's write.
         */
        var heard = false
        var connectedAt = 0L
        /** They have this document open. */
        var open = false
        /** Their digest has been answered, so streaming the difference to them is meaningful. */
        var caughtUp = false
        val outbox = PeerOutbox()
        /** Marks their last write lacked; sent again if the next write still lacks them. */
        var suspectMissing: Set<String> = emptySet()
        /** The arrangement of pages their marks are laid out for, once they have said. */
        var layout: String? = null
        var layoutAt = -1L
    }

    private val peers = HashMap<String, Peer>()

    private var disk: FileRevision? = disk
    private var diskInk: InkDocument? = diskInk

    /** The most recent write any device has announced, or that this device made. */
    private var latest: WriteRecord? = lastKnownWrite
    /** Revisions of writes that are older than, or tied with, [latest]. */
    private val known = ArrayDeque<FileRevision>()

    private var lease: Lease? = null
    /** Open peers that have agreed this device writes, since they last opened the document. */
    private val confirmedBy = HashSet<String>()
    private var request: Lease? = null
    private var requestFirstAt = 0L
    private var requestSentAt = 0L
    private val grants = HashSet<String>()
    /** Grants owed once the write in progress is finished. */
    private val pendingGrants = ArrayList<Pair<String, Lease>>()

    private var writing = false
    private var lastWriteAt = Long.MIN_VALUE / 2

    /**
     * How long the last write of this document actually took.
     *
     * A save is not free, and on a heavily marked document it is not cheap either: a three page
     * problem set with two and a half thousand marks took over a second to write. Writing one of
     * those every couple of seconds, as a small document can afford to, leaves the pen stuttering
     * through every stroke - which reads as the app freezing as soon as anything is drawn.
     */
    private var lastWriteCostMs = 0L
    private var saveRequested = false
    /** When this device started waiting for its disk to catch up, or 0. */
    private var waitSince = 0L
    private var gaveUpWaitingFor: FileRevision? = null
    /** When the writer dropped off the link without closing, or 0. */
    private var partitionedAt = 0L

    /** The document as last handed in, for its arrangement of pages. */
    private var seen: InkDocument? = diskInk

    // The last dirty answer, by identity: the same question is asked every tick.
    private var dirtyFor: Pair<InkDocument?, InkDocument>? = null
    private var dirtyAnswer = true

    // ---- the link ----------------------------------------------------------------

    /** A device came onto the link. Tell it what is open here and what this side holds. */
    fun connected(peer: String, current: InkDocument, now: Long) {
        see(current)
        val p = peers.getOrPut(peer) { Peer() }
        p.connected = true
        p.open = false
        p.heard = false
        p.connectedAt = now
        p.caughtUp = false
        p.layout = null
        p.outbox.reset()
        send(peer, editing())
        send(peer, PeerSync.digestOf(current))
    }

    fun disconnected(peer: String, now: Long) {
        val p = peers[peer] ?: return
        val wasOpen = p.open
        p.connected = false
        p.open = false
        p.caughtUp = false
        confirmedBy.remove(peer)
        grants.remove(peer)
        // The writer vanished mid-session. It may still be writing, on the far side of whatever
        // broke; give the link a moment to come back before this side starts writing too.
        if (wasOpen && lease?.holder == peer) partitionedAt = now
        checkGrants()
    }

    /** Something arrived. Returns the document to show, which may now hold more. */
    fun received(peer: String, message: PeerMessage, current: InkDocument, now: Long): InkDocument {
        see(current)
        if (images?.received(peer, message) == true) return current
        val p = peers.getOrPut(peer) {
            Peer().also {
                it.connected = true
                it.connectedAt = now
            }
        }
        when (message) {
            is PeerMessage.Editing -> {
                p.heard = true
                if (message.docId != docId) {
                    if (p.open) closedBy(peer, p)
                    return current
                }
                val wasOpen = p.open
                val rearranged = wasOpen && p.layout != null && p.layout != message.layout
                p.connected = true
                p.open = true
                p.layout = message.layout
                p.layoutAt = message.layoutAt
                partitionedAt = 0L
                absorb(message.lastWrite)
                message.lease?.let { if (it.outranks(lease)) lease = it }
                // Whatever this device held, it held without this one. It is agreed again.
                confirmedBy.remove(peer)
                if (!wasOpen) {
                    log("$fileName: $peer has it open too")
                    send(peer, editing())
                    send(peer, PeerSync.digestOf(current))
                } else if (rearranged) {
                    // Their pages moved - rearranged there, or caught up with a rearrangement
                    // here. What each side sent the other was for the old arrangement, so the two
                    // compare again from the start.
                    log("$fileName: pages on $peer are now arranged as ${message.layout.take(8)}")
                    p.caughtUp = false
                    p.outbox.reset()
                    send(peer, PeerSync.digestOf(current))
                }
            }

            is PeerMessage.Closed -> if (message.docId == docId) {
                p.heard = true
                absorb(message.lastWrite)
                closedBy(peer, p)
            }

            is PeerMessage.Digest -> if (message.docId == docId) {
                // A digest describes marks by id, and ids are only comparable within one
                // arrangement of pages. Whichever side is behind sends another once it catches up.
                if (message.layout != current.layout) return current
                val answer = PeerSync.answerFor(current, message)
                if (!answer.isEmpty) send(peer, answer)
                val wanted = PeerSync.wantedFrom(current, message)
                if (wanted.isNotEmpty()) send(peer, PeerMessage.Want(docId, wanted, current.layout))
                p.outbox.sent(current)
                p.caughtUp = true
            }

            is PeerMessage.Want -> if (message.docId == docId && message.layout == current.layout) {
                send(peer, PeerSync.answerFor(current, PeerMessage.Digest(docId), message.ids))
            }

            is PeerMessage.Marks -> if (message.docId == docId) {
                if (message.layout == current.layout) {
                    p.outbox.heard(message)
                } else if (!PeerSync.canPlace(current, message.layout)) {
                    // Laid out for pages this side does not have yet. The digests exchanged once
                    // it does bring these across again.
                    return current
                }
                return PeerSync.applied(current, message)
            }

            is PeerMessage.Wrote -> if (message.docId == docId) {
                absorb(message.write)
                message.digest?.takeIf { it.layout == current.layout }
                    ?.let { d -> resendWhatWasMissed(peer, p, d, current) }
            }

            is PeerMessage.LeaseRequest -> if (message.docId == docId) {
                requested(peer, message.lease)
            }

            is PeerMessage.LeaseGrant -> if (message.docId == docId) {
                absorb(message.lastWrite)
                if (message.lease == request) {
                    grants.add(peer)
                    checkGrants()
                }
            }

            is PeerMessage.LeaseState -> if (message.docId == docId) {
                absorb(message.lastWrite)
                message.lease?.let { theirs ->
                    if (theirs.outranks(lease)) lease = theirs
                    if (request != null && theirs.outranks(request)) {
                        request = null
                        grants.clear()
                    }
                }
            }

            else -> Unit
        }
        return current
    }

    // ---- the disk ----------------------------------------------------------------

    /**
     * The document's file changed on disk - a write arriving from file sync, most often.
     *
     * [read] is only called when the change is not one this side already knows the contents of.
     */
    fun diskChanged(
        file: FileRevision?,
        read: () -> InkDocument?,
        current: InkDocument,
        now: Long
    ): Arrival {
        if (file == null) return Arrival(current, merged = false, holdsEverything = false)
        if (file == disk) {
            return Arrival(current, false, diskInk?.let { holds(it, current) } ?: false)
        }
        disk = file
        val announced = latest?.file == file || file in known
        val l = latest
        if (!announced && (l == null || l.by == me || l.by == EXTERNAL)) {
            // Nobody on the link wrote this: a device that is not connected, or another program.
            // Only believed when the latest write known here is this device's own - then nothing a
            // linked device wrote can still be on its way. When the latest is another device's, an
            // unfamiliar file is far more likely to be one of that device's earlier writes arriving
            // late, and taking it for the newest would mean writing over the one still to come.
            log("$fileName changed on disk without being announced")
            l?.let { remember(it.file) }
            latest = WriteRecord(l?.seq ?: 0L, EXTERNAL, file)
        }
        if (latest?.file == file) waitSince = 0L

        val arrived = runCatching(read).getOrNull()
            ?: return Arrival(current, merged = false, holdsEverything = false)
        diskInk = arrived
        val same = arrived.layout == current.layout
        val merged = if (same && PeerSync.holdsEverythingIn(current, arrived)) current
        else current.mergeWith(arrived)
        val pagesChanged = merged.layout != current.layout
        if (pagesChanged) log("$fileName: the pages were rearranged elsewhere; loading the new arrangement")
        return Arrival(merged, merged !== current, holds(arrived, merged), pagesChanged)
    }

    // ---- writing -----------------------------------------------------------------

    /**
     * Stream what is owed, move the agreement along, and say whether to write the document now.
     *
     * Call regularly - a few times a second. [idle] is whether the pen has paused long enough for a
     * write not to land in the middle of a stroke. After `true`, the caller must report back with
     * [written] or [writeFailed]; until then nothing else is written.
     */
    fun tick(now: Long, current: InkDocument, idle: Boolean): Boolean {
        see(current)
        stream(current)
        images?.lookFor(openPeers(), current, now)
        flushGrants()
        moveRequestAlong(now)
        if (behind()) return false

        val open = openPeers()
        val dirty = isDirty(current)

        if (open.isEmpty()) {
            if (!dirty || writing) return false
            if (undecided(now)) return false
            if (partitionedAt != 0L && now - partitionedAt < PARTITION_GRACE_MS) return false
            if (!caughtUp(now)) return false
            if (!saveRequested && (!idle || now - lastWriteAt < writeInterval())) return false
            // Writing without anyone to agree with. Recorded as a lease of its own, so that meeting
            // another device later starts from "this one wrote last" rather than from nothing.
            if (lease?.holder != me) {
                lease = Lease(maxTerm() + 1, me)
                confirmedBy.clear()
            }
            writing = true
            return true
        }

        if (!holding(open)) {
            val holder = lease?.holder
            // A writer whose pages are arranged the old way cannot write until it has the new
            // file, and the new file is the one this device has to write.
            val writerIsHere = holder != null && holder != me && holder in open &&
                peers[holder]?.let { behindMe(it, current) } != true
            // Another device that has this open writes it, and gets this side's marks live. Only
            // a Save asked for here, or a writer that has gone, is a reason to ask for the lease.
            val wantLease = (dirty && !writerIsHere) || saveRequested
            if (wantLease && request == null) ask(now, open)
            return false
        }

        if (!dirty || writing) return false
        if (!caughtUp(now)) return false
        if (!saveRequested && (!idle || now - lastWriteAt < MIN_WRITE_INTERVAL_MS)) return false
        writing = true
        return true
    }

    /**
     * How long to leave between writes, from what the last one cost.
     *
     * Eight times the cost, so writing never takes more than an eighth of the time - a document
     * that writes in fifty milliseconds still saves every couple of seconds, and one that takes a
     * second and a half is left alone for twelve. Capped, because a document nobody can save
     * quickly still has to be saved.
     */
    fun writeInterval(): Long =
        (lastWriteCostMs * 8).coerceIn(MIN_WRITE_INTERVAL_MS, MAX_WRITE_INTERVAL_MS)

    /**
     * How long the pen has to have been still before a write, from what the last one cost.
     *
     * A cheap write can go in any gap between strokes. An expensive one waits for a real pause,
     * because interrupting a line of working is what the stutter actually is.
     */
    fun idleNeededMs(): Long =
        (lastWriteCostMs * 2).coerceIn(IDLE_BEFORE_WRITE_MS, MAX_IDLE_BEFORE_WRITE_MS)

    /** The write [tick] asked for is done. [file] is the file as it now is, [ink] what went in it. */
    fun written(file: FileRevision, ink: InkDocument, now: Long, tookMs: Long = 0L) {
        writing = false
        lastWriteAt = now
        if (tookMs > 0L) lastWriteCostMs = tookMs
        saveRequested = false
        val record = WriteRecord((latest?.seq ?: 0L) + 1, me, file)
        absorb(record)
        disk = file
        diskInk = ink
        waitSince = 0L
        val digest = PeerSync.digestOf(ink)
        // To every device on the link, not only those with the document open: one that opens it a
        // moment from now needs to know this write is on its way. The digest is only for those
        // that have it open.
        val open = openPeers()
        for ((tag, p) in peers) {
            if (!p.connected) continue
            send(
                tag,
                PeerMessage.Wrote(
                    name = fileName, docId = docId, write = record,
                    digest = if (tag in open) digest else null
                )
            )
        }
        flushGrants()
    }

    /** The write [tick] asked for did not happen - it failed, or there turned out to be nothing. */
    fun writeFailed(now: Long) {
        writing = false
        lastWriteAt = now
        flushGrants()
    }

    /**
     * Whether this device could write the document right now without making a rival file.
     *
     * For the writes the app makes of its own accord outside [tick] - on the way to the background,
     * before sharing the file. A device that does not write the document, or whose disk has not yet
     * caught up with the latest write, leaves the file alone; its marks are in its working copy.
     */
    fun mayWriteNow(now: Long): Boolean {
        if (writing) return false
        if (undecided(now)) return false
        if (behind()) return false
        val open = openPeers()
        if (open.isNotEmpty() && !holding(open)) return false
        val l = latest
        return l == null || disk == l.file || gaveUpWaitingFor == l.file
    }

    /**
     * The file on disk is already known to hold [ink] - the app found nothing to write. Saves asking
     * again every tick about a document that is, in fact, written.
     */
    fun diskAlreadyHolds(ink: InkDocument) {
        diskInk = ink
    }

    /** Somebody pressed Save here. Written by this device, after agreeing it with the others. */
    fun requestSave() {
        saveRequested = true
    }

    /**
     * The document is being closed. Sends whatever is still owed, and says whether this device
     * should write it before it goes - which it should only when it is the one that writes.
     */
    fun closing(current: InkDocument, now: Long): Boolean {
        see(current)
        stream(current)
        val open = openPeers()
        if (behind()) return false
        if (!isDirty(current)) return false
        val mayWrite = (open.isEmpty() && !undecided(now)) || holding(open)
        if (!mayWrite) return false
        // Closing cannot wait for the other device's write to arrive. The working copy has
        // everything, and the next open writes it; writing now would be a rival file.
        val l = latest
        return l == null || disk == l.file || gaveUpWaitingFor == l.file
    }

    /** The document is closed. Tell the others, with the last write known here. */
    fun closed(now: Long) {
        val news = PeerMessage.Closed(docId, latest)
        for ((tag, p) in peers) if (p.connected) send(tag, news)
    }

    fun status(now: Long): Status {
        val open = openPeers()
        return when {
            open.isEmpty() -> Status.ALONE
            behind() -> Status.WAITING_FOR_FILE
            request != null -> Status.AGREEING
            holding(open) -> {
                val l = latest
                if (l == null || disk == l.file || gaveUpWaitingFor == l.file) Status.WRITING_HERE
                else Status.WAITING_FOR_FILE
            }
            lease?.holder?.let { it in open } == true -> Status.WRITTEN_ELSEWHERE
            else -> Status.AGREEING
        }
    }

    /** Devices that currently have this document open on the link. */
    fun openPeers(): Set<String> =
        peers.filter { (_, p) -> p.connected && p.open }.keys

    val lastWrite: WriteRecord? get() = latest

    // ---- inside ------------------------------------------------------------------

    private fun editing(): PeerMessage.Editing {
        val doc = seen
        val layout = doc?.layout ?: ""
        val at = doc?.let { PageStructure.layoutAt(it.layout, it.structureHistory) } ?: -1L
        return PeerMessage.Editing(docId, fileName, lease, latest, layout, at)
    }

    /**
     * Notice the document's arrangement of pages changing here - rearranged on this device, or
     * loaded again after a rearrangement elsewhere - and tell the devices on the link.
     */
    private fun see(current: InkDocument) {
        val before = seen
        seen = current
        if (before == null || before.layout == current.layout) return
        dirtyFor = null
        for ((tag, p) in peers) {
            if (!p.connected) continue
            p.caughtUp = false
            p.outbox.reset()
            send(tag, editing())
            if (p.open) send(tag, PeerSync.digestOf(current))
        }
    }

    /**
     * Another device that has this document open has its pages arranged in a way this one has not
     * reached. Whatever this device wrote now would be the old arrangement, over the new one.
     *
     * Two arrangements made while apart, neither from the other, are settled the way the merge
     * settles them: the later one is kept, and only the device with the other one is behind.
     */
    private fun behind(): Boolean {
        val doc = seen ?: return false
        return peers.values.any { p -> p.connected && p.open && behindMe(p, doc, reverse = true) }
    }

    /**
     * Whether [p]'s pages are arranged the old way compared with [doc] - or, with [reverse], whether
     * this side's are compared with theirs.
     */
    private fun behindMe(p: Peer, doc: InkDocument, reverse: Boolean = false): Boolean {
        val theirs = p.layout ?: return false
        val mine = doc.layout
        if (theirs == mine) return false
        val history = doc.structureHistory
        val mineAt = PageStructure.layoutAt(mine, history)
        return if (!reverse) {
            PageStructure.path(theirs, mine, history) != null ||
                PageStructure.outranks(mine, mineAt, theirs, p.layoutAt)
        } else {
            PageStructure.path(theirs, mine, history) == null &&
                !PageStructure.outranks(mine, mineAt, theirs, p.layoutAt)
        }
    }

    private fun maxTerm(): Long = maxOf(lease?.term ?: 0L, request?.term ?: 0L)

    private fun holding(open: Set<String>): Boolean =
        lease?.holder == me && open.all { it in confirmedBy }

    /**
     * A device on the link that has not yet said whether it has this document open.
     *
     * Not waited on for ever: a device that never answers - an older build, a connection that
     * came up and went straight down - is taken to have it closed after a few seconds.
     */
    private fun undecided(now: Long): Boolean =
        peers.values.any { it.connected && !it.heard && now - it.connectedAt < HANDSHAKE_MS }

    private fun closedBy(peer: String, p: Peer) {
        p.open = false
        p.caughtUp = false
        confirmedBy.remove(peer)
        grants.remove(peer)
        if (lease?.holder == peer) partitionedAt = 0L
        checkGrants()
    }

    private fun stream(current: InkDocument) {
        for ((tag, p) in peers) {
            if (!p.connected || !p.open || !p.caughtUp || p.layout != current.layout) continue
            val owed = p.outbox.pending(current) ?: continue
            send(tag, owed)
            p.outbox.sent(current)
        }
    }

    /**
     * Marks the writer's announcement shows it did not have.
     *
     * Only those missing from two writes in a row: a mark sent a moment before a write started is
     * simply in flight, and re-sending everything drawn since the last write would double the
     * traffic of anyone drawing while the other device writes.
     */
    private fun resendWhatWasMissed(
        peer: String,
        p: Peer,
        written: PeerMessage.Digest,
        current: InkDocument
    ) {
        if (!p.open || !p.caughtUp) return
        val missing = PeerSync.answerFor(current, written)
        val ids = missing.strokes.map { it.id }.toSet() + missing.deleted.keys
        val again = ids intersect p.suspectMissing
        p.suspectMissing = ids
        if (again.isEmpty()) return
        send(
            peer,
            PeerMessage.Marks(
                docId,
                missing.strokes.filter { it.id in again },
                missing.deleted.filterKeys { it in again }
            )
        )
    }

    private fun requested(peer: String, wanted: Lease) {
        when {
            wanted == lease -> {
                // Asked again - a duplicate, or a grant that went astray. Same answer.
                if (writing) pendingGrants.add(peer to wanted)
                else send(peer, PeerMessage.LeaseGrant(docId, wanted, latest))
            }
            wanted.outranks(lease) && wanted.outranks(request) -> {
                if (request != null) {
                    request = null
                    grants.clear()
                }
                lease = wanted
                confirmedBy.clear()
                // A Save pressed here is answered by the device taking over: everything drawn here
                // reaches it live, and its write carries it. Asking for the lease back would only
                // start two devices taking it from each other.
                saveRequested = false
                // Not until the write in hand is on disk: the grant carries that write, and the
                // new writer waits for exactly those bytes before writing on top of them.
                if (writing) pendingGrants.add(peer to wanted)
                else send(peer, PeerMessage.LeaseGrant(docId, wanted, latest))
            }
            else -> {
                val stronger = if (request != null && request!!.outranks(lease)) request else lease
                send(peer, PeerMessage.LeaseState(docId, stronger, latest))
            }
        }
    }

    private fun ask(now: Long, open: Set<String>) {
        val wanted = Lease(maxTerm() + 1, me)
        request = wanted
        requestFirstAt = now
        requestSentAt = now
        grants.clear()
        for (tag in open) send(tag, PeerMessage.LeaseRequest(docId, wanted))
        log("$fileName: asking to write (term ${wanted.term})")
    }

    private fun moveRequestAlong(now: Long) {
        val wanted = request ?: return
        val open = openPeers()
        val silent = open.filter { it !in grants }
        if (silent.isEmpty()) {
            checkGrants()
            return
        }
        if (now - requestFirstAt >= REQUEST_GIVE_UP_MS) {
            // A device that has the document open and says nothing for this long is not one that
            // can be agreed with. It is treated as gone; if it is alive, it says so again.
            log("$fileName: no answer from ${silent.joinToString()}; carrying on without")
            for (tag in silent) peers[tag]?.let { closedBy(tag, it) }
            return
        }
        if (now - requestSentAt >= REQUEST_RETRY_MS) {
            requestSentAt = now
            for (tag in silent) send(tag, PeerMessage.LeaseRequest(docId, wanted))
        }
    }

    private fun checkGrants() {
        val wanted = request ?: return
        if (!openPeers().all { it in grants }) return
        lease = wanted
        confirmedBy.clear()
        confirmedBy.addAll(grants)
        request = null
        grants.clear()
        log("$fileName: this device writes (term ${wanted.term})")
    }

    private fun flushGrants() {
        if (writing || pendingGrants.isEmpty()) return
        for ((tag, l) in pendingGrants) send(tag, PeerMessage.LeaseGrant(docId, l, latest))
        pendingGrants.clear()
    }

    private fun absorb(write: WriteRecord?) {
        if (write == null) return
        val current = latest
        when {
            current == null || write.isAfter(current) -> {
                current?.let { remember(it.file) }
                latest = write
                if (write.file != disk) waitSince = 0L
            }
            else -> remember(write.file)
        }
    }

    private fun remember(file: FileRevision) {
        if (file in known) return
        known.addLast(file)
        while (known.size > KNOWN_LIMIT) known.removeFirst()
    }

    /**
     * Whether this disk holds the latest write, so writing now goes on top of it.
     *
     * A write that has not arrived for a long while is given up on: file sync may be paused, or
     * not carrying this folder at all, and a document nobody may ever write again is worse than
     * the conflict copy that writing risks - which is merged back in, not lost.
     */
    private fun caughtUp(now: Long): Boolean {
        val l = latest ?: return true
        if (disk == l.file || gaveUpWaitingFor == l.file) {
            waitSince = 0L
            return true
        }
        if (waitSince == 0L) waitSince = now
        if (now - waitSince >= DISK_WAIT_MS) {
            log("$fileName: the latest write has not arrived after ${DISK_WAIT_MS / 1000}s; writing anyway")
            gaveUpWaitingFor = l.file
            return true
        }
        return false
    }

    private fun isDirty(current: InkDocument): Boolean {
        if (saveRequested) return true
        val onDisk = diskInk ?: return true
        val asked = dirtyFor
        if (asked != null && asked.first === onDisk && asked.second === current) return dirtyAnswer
        dirtyAnswer = !holds(onDisk, current)
        dirtyFor = onDisk to current
        return dirtyAnswer
    }

    private fun holds(onDisk: InkDocument, current: InkDocument): Boolean =
        PeerSync.holdsEverythingIn(onDisk, current)

    companion object {
        /** How long to wait for another device's write to arrive before writing regardless. */
        const val DISK_WAIT_MS = 90_000L

        /** How long a writer that dropped off the link is given to come back. */
        const val PARTITION_GRACE_MS = 20_000L

        /** How long a device that has just come onto the link is given to say what it has open. */
        const val HANDSHAKE_MS = 5_000L

        const val REQUEST_RETRY_MS = 5_000L
        const val REQUEST_GIVE_UP_MS = 30_000L

        /** The least time between two writes nobody asked for. */
        const val MIN_WRITE_INTERVAL_MS = 2_000L

        /** However slow a document is to write, it is still written this often while it is dirty. */
        const val MAX_WRITE_INTERVAL_MS = 20_000L

        /** The pause after the last mark before a write is worth starting. */
        const val IDLE_BEFORE_WRITE_MS = 1_200L

        /** However slow the write, a pause this long is taken as the moment for it. */
        const val MAX_IDLE_BEFORE_WRITE_MS = 4_000L

        private const val KNOWN_LIMIT = 64

        /** Stands in for the writer of a change nobody announced. */
        const val EXTERNAL = "~external"
    }
}
