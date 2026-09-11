package com.inkslate.core.peer

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
        @SerialName("app") val app: String = "InkSlate"
    ) : PeerMessage

    /** "I have this document open." Live marks only flow for documents both sides are holding. */
    @Serializable
    @SerialName("editing")
    data class Editing(
        @SerialName("docId") val docId: String,
        @SerialName("fileName") val fileName: String
    ) : PeerMessage

    @Serializable
    @SerialName("closed")
    data class Closed(@SerialName("docId") val docId: String) : PeerMessage

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
        @SerialName("deleted") val deleted: Set<String> = emptySet()
    ) : PeerMessage

    /** "Send me these." Sent after comparing a [Digest] against what we hold. */
    @Serializable
    @SerialName("want")
    data class Want(
        @SerialName("docId") val docId: String,
        @SerialName("ids") val ids: List<String> = emptyList()
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
        @SerialName("deleted") val deleted: Map<String, Long> = emptyMap()
    ) : PeerMessage

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
        @SerialName("modifiedUtc") val modifiedUtc: Long = 0
    ) : PeerMessage

    /** "Something in my library changed" - a new document, a rename, a folder added. */
    @Serializable
    @SerialName("library")
    data object LibraryChanged : PeerMessage

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
        const val PROTOCOL = 1

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
