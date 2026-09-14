package com.inkslate.core.peer

import com.inkslate.core.InkCanvas
import com.inkslate.core.InkDocument
import com.inkslate.core.Stroke
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one of your devices says to another.
 *
 * The documents already sync as files, and will carry on doing so - this is the fast path for when
 * two devices are awake on the same network at the same time. A mark made on the tablet reaches
 * the laptop in the time it takes to cross the room, instead of waiting for a folder scan.
 *
 * Every message is small and self-contained, and every one of them can be replayed or arrive twice
 * without harm: the merge behind them is the same commutative, tombstone-aware one the file sync
 * uses. That is the whole reason this is a reasonable thing to build rather than a second source of
 * truth waiting to disagree with the first.
 */
@Serializable
sealed interface PeerMessage {

    /** First thing either side sends. A mismatched [protocol] is a polite disconnect, not a crash. */
    @Serializable
    @SerialName("hello")
    data class Hello(
        @SerialName("protocol") val protocol: Int = PROTOCOL,
        @SerialName("deviceTag") val deviceTag: String,
        @SerialName("deviceName") val deviceName: String,
        /**
         * Where to call this device back on.
         *
         * The side that accepted a connection knows the address it came from but not the port the
         * other device is listening on - an outgoing socket's port is whatever the system handed
         * out - so it is said here rather than guessed.
         */
        @SerialName("port") val port: Int = 0,
        @SerialName("app") val app: String = "InkSlate",
        /** The build, as a person reads it - for saying which one is behind when they differ. */
        @SerialName("version") val version: String = ""
    ) : PeerMessage

    /**
     * "I have this document open."
     *
     * Live marks only flow for documents both sides are holding. It also says who this device
     * believes writes the document and the last write it knows of, which is everything the other
     * side needs to agree about writing before either touches the file.
     */
    @Serializable
    @SerialName("editing")
    data class Editing(
        @SerialName("docId") val docId: String,
        @SerialName("fileName") val fileName: String,
        @SerialName("lease") val lease: Lease? = null,
        @SerialName("lastWrite") val lastWrite: WriteRecord? = null,
        /**
         * The arrangement of pages the sender's marks are laid out for - see
         * [com.inkslate.core.PageStructure]. Marks for an arrangement the receiver has not reached
         * cannot be placed on its pages, and are left until it has.
         */
        @SerialName("layout") val layout: String = "",
        /** When the pages were arranged that way, which settles two arrangements made apart. */
        @SerialName("layoutAt") val layoutAt: Long = -1
    ) : PeerMessage

    /** "I have closed this document", and the last write of it I know of. */
    @Serializable
    @SerialName("closed")
    data class Closed(
        @SerialName("docId") val docId: String,
        @SerialName("lastWrite") val lastWrite: WriteRecord? = null
    ) : PeerMessage

    /** "I would like to write this document." Every other device holding it has to agree. */
    @Serializable
    @SerialName("leaseRequest")
    data class LeaseRequest(
        @SerialName("docId") val docId: String,
        @SerialName("lease") val lease: Lease
    ) : PeerMessage

    /**
     * "Go ahead" - sent only once this device has finished any write it had started, and carrying
     * that write, so the new writer knows which bytes to wait for before writing on top of them.
     */
    @Serializable
    @SerialName("leaseGrant")
    data class LeaseGrant(
        @SerialName("docId") val docId: String,
        @SerialName("lease") val lease: Lease,
        @SerialName("lastWrite") val lastWrite: WriteRecord? = null
    ) : PeerMessage

    /** "Not that one - this is who writes." The answer to a request something else outranks. */
    @Serializable
    @SerialName("leaseState")
    data class LeaseState(
        @SerialName("docId") val docId: String,
        @SerialName("lease") val lease: Lease? = null,
        @SerialName("lastWrite") val lastWrite: WriteRecord? = null
    ) : PeerMessage

    /**
     * What one side holds for a document: every mark's id and when it last changed, plus the
     * tombstones. Small enough to send on every connection - a thousand marks is a few tens of
     * kilobytes - and it is what turns "we have both been editing offline" into a short exchange
     * rather than a whole-document transfer.
     */
    @Serializable
    @SerialName("digest")
    data class Digest(
        @SerialName("docId") val docId: String,
        @SerialName("marks") val marks: Map<String, Long> = emptyMap(),
        /**
         * Tombstones with the time each was written.
         *
         * The time is the point of it: a tombstone only outranks a mark that is older than it, so
         * a mark put back by an undo still reaches a device that saw the erase.
         */
        @SerialName("deleted") val deleted: Map<String, Long> = emptyMap(),
        /**
         * A fingerprint of everything in the document that is not a mark - bookmarks, the canvas.
         * Those are small, so they are simply sent whole whenever this does not match.
         */
        @SerialName("meta") val meta: String = "",
        @SerialName("layout") val layout: String = ""
    ) : PeerMessage

    /** "Send me these." Sent after comparing a [Digest] against what we hold. */
    @Serializable
    @SerialName("want")
    data class Want(
        @SerialName("docId") val docId: String,
        @SerialName("ids") val ids: List<String> = emptyList(),
        @SerialName("layout") val layout: String = ""
    ) : PeerMessage

    /**
     * Marks, and deletions, for one document.
     *
     * The same message carries a whole catch-up and a single stroke as it leaves the pen. Nothing
     * distinguishes them, deliberately: one path, exercised constantly.
     */
    @Serializable
    @SerialName("marks")
    data class Marks(
        @SerialName("docId") val docId: String,
        @SerialName("strokes") val strokes: List<Stroke> = emptyList(),
        @SerialName("deleted") val deleted: Map<String, Long> = emptyMap(),
        /** Bookmarks, when the other side's differ. Null means "no news", not "none". */
        @SerialName("bookmarks") val bookmarks: List<InkDocument.Bookmark>? = null,
        /** Bookmarks removed, sent with [bookmarks]. */
        @SerialName("bookmarksRemoved") val bookmarksRemoved: Map<String, Long>? = null,
        /** The canvas, when the other side's differs. Null means "no news". */
        @SerialName("canvas") val canvas: InkCanvas? = null,
        /**
         * The arrangement of pages the sender's marks are laid out for - see
         * [com.inkslate.core.PageStructure]. Marks for an arrangement the receiver has not reached
         * cannot be placed on its pages, and are left until it has.
         */
        @SerialName("layout") val layout: String = ""
    ) : PeerMessage {
        val isEmpty: Boolean
            get() = strokes.isEmpty() && deleted.isEmpty() && bookmarks == null &&
                bookmarksRemoved == null && canvas == null
    }

    /**
     * "I have just written this file."
     *
     * Not the file itself - the bytes still travel the way they always have. This exists so the
     * other device refreshes at once instead of finding out on its next look around.
     */
    @Serializable
    @SerialName("wrote")
    data class Wrote(
        @SerialName("name") val name: String,
        @SerialName("sizeBytes") val sizeBytes: Long = 0,
        @SerialName("modifiedUtc") val modifiedUtc: Long = 0,
        /** Set for a write of a document both devices have open. */
        @SerialName("docId") val docId: String? = null,
        @SerialName("write") val write: WriteRecord? = null,
        /**
         * What the write carried, so a device whose marks it lacks can send them again rather than
         * assume they arrived. This is the acknowledgement the live link otherwise does not have.
         */
        @SerialName("digest") val digest: Digest? = null
    ) : PeerMessage

    /** "Send me these pictures" - ones a mark in this document shows and this device lacks. */
    @Serializable
    @SerialName("imageWant")
    data class ImageWant(
        @SerialName("docId") val docId: String,
        @SerialName("ids") val ids: List<String> = emptyList()
    ) : PeerMessage

    /** One picture, as the PNG file it is stored as. See [ImageLink]. */
    @Serializable
    @SerialName("imageData")
    data class ImageData(
        @SerialName("docId") val docId: String,
        @SerialName("id") val id: String,
        /** The file's bytes, in base64. */
        @SerialName("png") val png: String
    ) : PeerMessage

    /** "Something in my library changed" - a new document, a rename, a folder added. */
    @Serializable
    @SerialName("library")
    data object LibraryChanged : PeerMessage

    /** "Are you there?" - the link test in Settings. Answered at once, by the transport itself. */
    @Serializable
    @SerialName("probe")
    data class Probe(@SerialName("nonce") val nonce: String) : PeerMessage

    @Serializable
    @SerialName("probeReply")
    data class ProbeReply(
        @SerialName("nonce") val nonce: String,
        @SerialName("version") val version: String = ""
    ) : PeerMessage

    /** Keeps a quiet connection from being reaped by anything in between. */
    @Serializable
    @SerialName("ping")
    data object Ping : PeerMessage

    companion object {
        /**
         * Raised only for a change that would make two versions misunderstand each other. Both
         * builds ship from one repository, but they are installed separately and one of them is
         * usually a version behind.
         */
        const val PROTOCOL = 4

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "t"
        }

        fun encode(message: PeerMessage): String = json.encodeToString(message)

        /** Null rather than an exception: a peer that sends nonsense is dropped, not fatal here. */
        fun decode(text: String): PeerMessage? =
            runCatching { json.decodeFromString<PeerMessage>(text) }.getOrNull()
    }
}
