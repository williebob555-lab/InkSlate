package com.inkslate.core.peer

import com.inkslate.core.InkDocument
import com.inkslate.core.Stroke
import java.util.Base64

/**
 * Pictures, carried over the link.
 *
 * A picture placed in a document is a file of its own in the folder beside it, and a mark that
 * points at it. The mark reaches the other device in a moment; the file used to wait for file sync,
 * so the other device showed an empty frame where the picture was. This asks the device that has
 * the picture for it as soon as a mark needs one, and hands what comes back to the app to keep.
 *
 * It decides and nothing more: which pictures to ask for, from whom, and whether what arrived is a
 * picture at all. Reading and writing files is the app's, off the editor's thread.
 */
class ImageLink(
    private val docId: String,
    /** Whether this device can already show picture [id]. */
    private val have: (id: String) -> Boolean,
    /** Send pictures [ids] to [peer]: read each and send [dataFor] it, from any thread. */
    private val serve: (peer: String, ids: List<String>) -> Unit,
    /** A picture arrived. Keep it where [have] will find it, and show it. */
    private val keep: (id: String, png: ByteArray) -> Unit,
    private val send: (peer: String, message: PeerMessage) -> Unit,
    private val log: (String) -> Unit = {}
) {
    private class Asked(var at: Long, var times: Int)

    private val asked = HashMap<String, Asked>()
    private var lookedAt: InkDocument? = null
    private var missing: List<String> = emptyList()

    /**
     * Ask [peers] for any picture [doc] shows that this device does not have.
     *
     * Cheap to call on every heartbeat: the document is only searched when it is a different one
     * from last time, and a picture already asked for is not asked for again for a while.
     */
    fun lookFor(peers: Collection<String>, doc: InkDocument, now: Long) {
        if (peers.isEmpty()) return
        if (doc !== lookedAt) {
            lookedAt = doc
            missing = doc.pages.values.asSequence().flatten()
                .filter { it.kind == Stroke.Kind.IMAGE }
                .mapNotNull { it.imageId }
                .distinct()
                .toList()
        }
        if (missing.isEmpty()) return
        val due = missing.filter { id ->
            if (have(id)) return@filter false
            val a = asked[id]
            a == null || (a.times < GIVE_UP_AFTER && now - a.at >= RETRY_MS * a.times)
        }
        missing = missing.filterNot { have(it) }
        if (due.isEmpty()) return
        for (id in due) {
            val a = asked.getOrPut(id) { Asked(now, 0) }
            a.at = now
            a.times++
        }
        log("asking for ${due.size} picture(s)")
        // Every device with it open may have it; whichever answers first is kept, the rest ignored.
        for (peer in peers) send(peer, PeerMessage.ImageWant(docId, due))
    }

    /** A message about pictures. True when it was one, whether or not it changed anything. */
    fun received(peer: String, message: PeerMessage): Boolean = when (message) {
        is PeerMessage.ImageWant -> {
            if (message.docId == docId) {
                val ours = message.ids.filter { have(it) }.take(MAX_PER_REQUEST)
                if (ours.isNotEmpty()) serve(peer, ours)
            }
            true
        }
        is PeerMessage.ImageData -> {
            if (message.docId == docId && !have(message.id)) {
                val png = bytesOf(message)
                if (png == null) {
                    log("ignored picture ${message.id}: not a picture")
                } else {
                    asked.remove(message.id)
                    keep(message.id, png)
                }
            }
            true
        }
        else -> false
    }

    companion object {
        /** The longest picture sent over the link. Bigger ones wait for file sync. */
        const val MAX_BYTES = 12 * 1024 * 1024

        private const val RETRY_MS = 10_000L
        private const val GIVE_UP_AFTER = 4
        private const val MAX_PER_REQUEST = 16

        private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

        /** The message carrying picture [id], or null when it is too big to send this way. */
        fun dataFor(docId: String, id: String, png: ByteArray): PeerMessage.ImageData? {
            if (png.size > MAX_BYTES || !isPng(png)) return null
            return PeerMessage.ImageData(docId, id, Base64.getEncoder().encodeToString(png))
        }

        /** The picture in [message], or null when it is not one. */
        fun bytesOf(message: PeerMessage.ImageData): ByteArray? {
            if (!isSafeId(message.id)) return null
            val bytes = runCatching { Base64.getDecoder().decode(message.png) }.getOrNull() ?: return null
            return bytes.takeIf { it.size <= MAX_BYTES && isPng(it) }
        }

        /**
         * Picture ids become file names, so one from another device has to look like one this app
         * made: a handful of letters and digits, and nothing that could climb out of a folder.
         */
        fun isSafeId(id: String): Boolean =
            id.length in 1..64 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

        private fun isPng(bytes: ByteArray): Boolean =
            bytes.size > PNG.size && PNG.indices.all { bytes[it] == PNG[it] }
    }
}
