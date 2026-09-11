package com.inkslate.desktop

import com.inkslate.core.InkDocument
import com.inkslate.core.Stroke
import com.inkslate.core.peer.PeerDiscovery
import com.inkslate.core.peer.PeerMessage
import com.inkslate.core.peer.PeerService
import java.net.InetAddress

/**
 * This machine's end of the direct link to your other devices.
 *
 * One of these for the whole program: the connection outlives any one screen, because the point of
 * it is that the tablet's marks are already here by the time you open the document. The editor
 * hands it the document it has open and takes back whatever arrives; everything else - sockets,
 * pairing, retries - is the shared service underneath.
 *
 * Nothing here decides what a mark is or what a merge means. That all lives in `:core`, which is
 * what keeps the laptop and the tablet from developing opinions of their own.
 */
object DesktopPeers {

    /** What the editor currently has open, and what to do with what arrives for it. */
    @Volatile private var open: PeerService.Open? = null
    @Volatile private var marksListener: ((String, PeerMessage.Marks) -> Unit)? = null
    @Volatile private var remoteWriteListener: ((String, String?) -> Unit)? = null
    @Volatile private var peersListener: (() -> Unit)? = null

    /** Devices announcing themselves on this network that are not paired yet. */
    @Volatile var discovered: List<PeerDiscovery.Announcement> = emptyList()
        private set

    private val host = object : PeerService.Host {
        override fun deviceTag() = DocumentIO.deviceTag()
        override fun deviceName() = name()
        override fun openDocument() = open

        override fun onMarks(docId: String, marks: PeerMessage.Marks) {
            marksListener?.invoke(docId, marks)
        }

        override fun onRemoteWrite(peer: String, fileName: String?) {
            EventLog.info("peer", "$peer wrote ${fileName ?: "something in its library"}")
            remoteWriteListener?.invoke(peer, fileName)
        }

        override fun onPaired(peer: PeerService.Peer) {
            savePeers(peers().filterNot { it.tag == peer.tag } + peer)
        }

        override fun onPeersChanged() {
            peersListener?.invoke()
        }

        override fun log(level: String, message: String) {
            when (level) {
                "warn" -> EventLog.warn("peer", message)
                "error" -> EventLog.error("peer", message)
                else -> EventLog.info("peer", message)
            }
        }
    }

    private val service = PeerService(host)

    private val discovery = PeerDiscovery(
        selfTag = DocumentIO.deviceTag(),
        selfName = { name() },
        servicePort = { port() },
        onSeen = { seen ->
            // A device we already know, turning up somewhere new, is worth following; one we do
            // not is only worth offering.
            service.noteSeenAt(seen.tag, seen.address, seen.port)
            if (peers().none { it.tag == seen.tag }) {
                val without = discovered.filterNot { it.tag == seen.tag }
                discovered = (without + seen).takeLast(12)
                peersListener?.invoke()
            }
        },
        log = { level, message -> host.log(level, message) }
    )

    // ---- settings --------------------------------------------------------------

    fun enabled(): Boolean = DesktopPrefs.get(K_ENABLED)?.toBoolean() ?: false

    fun setEnabled(on: Boolean) {
        DesktopPrefs.put(K_ENABLED, on.toString())
        if (on) start() else stop()
    }

    fun discoveryEnabled(): Boolean = DesktopPrefs.get(K_DISCOVERY)?.toBoolean() ?: true

    fun setDiscoveryEnabled(on: Boolean) {
        DesktopPrefs.put(K_DISCOVERY, on.toString())
        if (enabled()) start()
    }

    /** What the other device calls this one. The machine's own name, unless you say otherwise. */
    fun name(): String = DesktopPrefs.get(K_NAME)?.takeIf { it.isNotBlank() }
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        ?: "This PC"

    fun setName(value: String) = DesktopPrefs.put(K_NAME, value.trim())

    fun port(): Int = DesktopPrefs.get(K_PORT)?.toIntOrNull() ?: PeerService.DEFAULT_PORT

    fun setPort(value: Int) {
        DesktopPrefs.put(K_PORT, value.toString())
        if (enabled()) start()
    }

    fun peers(): List<PeerService.Peer> = DesktopPrefs.getList(K_PEERS).mapNotNull { line ->
        val parts = line.split(SEP)
        if (parts.size < 5) return@mapNotNull null
        PeerService.Peer(
            tag = parts[0],
            name = parts[1],
            host = parts[2],
            port = parts[3].toIntOrNull() ?: PeerService.DEFAULT_PORT,
            code = parts[4]
        )
    }

    private fun savePeers(list: List<PeerService.Peer>) {
        DesktopPrefs.putList(
            K_PEERS,
            list.map { listOf(it.tag, it.name, it.host, it.port.toString(), it.code).joinToString(SEP) }
        )
    }

    fun forget(tag: String) {
        savePeers(peers().filterNot { it.tag == tag })
        service.forgetPeer(tag)
    }

    fun statuses(): List<PeerService.Status> = service.statuses()

    // ---- running ---------------------------------------------------------------

    fun start() {
        if (!enabled()) return
        service.start(peers(), port())
        if (discoveryEnabled()) discovery.start() else discovery.stop()
    }

    fun stop() {
        service.stop()
        discovery.stop()
    }

    /** Offer a code for a few minutes so another device can introduce itself to this one. */
    fun offerPairing(code: String) = service.offerPairing(code)

    fun stopOfferingPairing() = service.stopOfferingPairing()

    /** Introduce this machine to one at [address]. Blocking: call it off the interface thread. */
    fun pairWith(address: String, port: Int, code: String): Result<PeerService.Peer> {
        if (!enabled()) setEnabled(true)
        return service.pairWith(address, port, code).onSuccess {
            discovered = discovered.filterNot { d -> d.tag == it.tag }
        }
    }

    // ---- what the editor and the library tell it -------------------------------

    fun documentOpened(fileName: String, doc: InkDocument) {
        open = PeerService.Open(fileName, doc)
        service.announceOpen()
    }

    /** Kept current as the page is drawn on, so a peer asking to catch up gets today's answer. */
    fun documentChanged(fileName: String, doc: InkDocument) {
        open = PeerService.Open(fileName, doc)
    }

    fun documentClosed() {
        open?.let { service.announceClosed(it.docId) }
        open = null
    }

    fun sendMarks(docId: String, added: List<Stroke>, removed: Map<String, Long> = emptyMap()) =
        service.sendMarks(docId, added, removed)

    fun announceWrote(file: java.io.File) =
        service.announceWrote(file.name, file.length(), file.lastModified())

    fun announceLibraryChanged() = service.announceLibraryChanged()

    // ---- listeners -------------------------------------------------------------

    fun onMarks(listener: ((String, PeerMessage.Marks) -> Unit)?) {
        marksListener = listener
    }

    fun onRemoteWrite(listener: ((String, String?) -> Unit)?) {
        remoteWriteListener = listener
    }

    fun onPeersChanged(listener: (() -> Unit)?) {
        peersListener = listener
    }

    /** Not a character anyone types into a device name. */
    private const val SEP = "\u001F"

    private const val K_ENABLED = "peer_enabled"
    private const val K_DISCOVERY = "peer_discovery"
    private const val K_NAME = "peer_name"
    private const val K_PORT = "peer_port"
    private const val K_PEERS = "peer_devices"
}
