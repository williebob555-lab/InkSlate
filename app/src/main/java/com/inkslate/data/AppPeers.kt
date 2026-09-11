package com.inkslate.data

import android.content.Context
import com.inkslate.core.InkDocument
import com.inkslate.core.Stroke
import com.inkslate.core.peer.PeerDiscovery
import com.inkslate.core.peer.PeerMessage
import com.inkslate.core.peer.PeerService
import java.io.File

/**
 * This tablet's end of the direct link to your other devices.
 *
 * The same service the laptop runs - sockets, pairing and merging all live in `:core` - with the
 * platform's own answers for who this device is and where its settings are kept. Two builds, one
 * protocol, and no chance of them disagreeing about what a mark is.
 *
 * Runs only while the app is in the foreground. A background service that holds a socket open all
 * day is a battery cost the user did not ask for, and the file sync already covers the case where
 * this device is not the one being drawn on.
 */
object AppPeers {

    private lateinit var appContext: Context

    @Volatile private var open: PeerService.Open? = null
    @Volatile private var marksListener: ((String, PeerMessage.Marks) -> Unit)? = null
    @Volatile private var remoteWriteListener: ((String, String?) -> Unit)? = null
    @Volatile private var peersListener: (() -> Unit)? = null

    /** Devices announcing themselves on this network that are not paired yet. */
    @Volatile var discovered: List<PeerDiscovery.Announcement> = emptyList()
        private set

    private val host = object : PeerService.Host {
        override fun deviceTag() = DeviceId.get(appContext)
        override fun deviceName() = DeviceId.label(appContext)
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

    private val service by lazy { PeerService(host) }

    private val discovery by lazy {
        PeerDiscovery(
            selfTag = DeviceId.get(appContext),
            selfName = { DeviceId.label(appContext) },
            servicePort = { port() },
            onSeen = { seen ->
                service.noteSeenAt(seen.tag, seen.address, seen.port)
                if (peers().none { it.tag == seen.tag }) {
                    discovered = (discovered.filterNot { it.tag == seen.tag } + seen).takeLast(12)
                    peersListener?.invoke()
                }
            },
            log = { level, message -> host.log(level, message) }
        )
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ---- settings --------------------------------------------------------------

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs().getBoolean(K_ENABLED, false)

    fun setEnabled(on: Boolean) {
        prefs().edit().putBoolean(K_ENABLED, on).apply()
        if (on) start() else stop()
    }

    fun discoveryEnabled(): Boolean = prefs().getBoolean(K_DISCOVERY, true)

    fun setDiscoveryEnabled(on: Boolean) {
        prefs().edit().putBoolean(K_DISCOVERY, on).apply()
        if (enabled()) start()
    }

    fun port(): Int = prefs().getInt(K_PORT, PeerService.DEFAULT_PORT)

    fun peers(): List<PeerService.Peer> =
        prefs().getString(K_PEERS, "").orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
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
        prefs().edit().putString(
            K_PEERS,
            list.joinToString("\n") {
                listOf(it.tag, it.name, it.host, it.port.toString(), it.code).joinToString(SEP)
            }
        ).apply()
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

    fun offerPairing(code: String) = service.offerPairing(code)

    fun stopOfferingPairing() = service.stopOfferingPairing()

    /** Blocking: it opens a socket and derives a key, so never on the main thread. */
    fun pairWith(address: String, port: Int, code: String): Result<PeerService.Peer> {
        if (!enabled()) setEnabled(true)
        return service.pairWith(address, port, code).onSuccess { paired ->
            discovered = discovered.filterNot { it.tag == paired.tag }
        }
    }

    // ---- what the editor and the library tell it -------------------------------

    fun documentOpened(fileName: String, doc: InkDocument) {
        open = PeerService.Open(fileName, doc)
        service.announceOpen()
    }

    fun documentChanged(fileName: String, doc: InkDocument) {
        open = PeerService.Open(fileName, doc)
    }

    fun documentClosed() {
        open?.let { service.announceClosed(it.docId) }
        open = null
    }

    fun sendMarks(docId: String, added: List<Stroke>, removed: Map<String, Long> = emptyMap()) =
        service.sendMarks(docId, added, removed)

    fun announceWrote(file: File) =
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

    private const val PREFS = "peers"
    private const val K_ENABLED = "enabled"
    private const val K_DISCOVERY = "discovery"
    private const val K_PORT = "port"
    private const val K_PEERS = "devices"

    /** Not a character anyone types into a device name. */
    private const val SEP = "\u001F"
}
