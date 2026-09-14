package com.inkslate.core.peer

import kotlinx.serialization.builtins.serializer

/**
 * The latest write of each document this device has heard of, whether or not it was open.
 *
 * What lets a document opened a moment after another device closed it wait for that device's last
 * write instead of writing over the top of the file it has. Small - one record per document - and
 * kept by each app wherever it keeps its settings.
 */
class WriteLedger(initial: Map<String, WriteRecord> = emptyMap()) {

    private val latest = HashMap(initial)

    /** Record [write] for [docId] if it is newer than what is held. Returns true if it was. */
    fun note(docId: String, write: WriteRecord?): Boolean {
        if (write == null) return false
        val held = latest[docId]
        if (!write.isAfter(held)) return false
        latest[docId] = write
        return true
    }

    /** Everything a message says about writes, for a device that does not have the document open. */
    fun heard(message: PeerMessage): Boolean = when (message) {
        is PeerMessage.Wrote -> message.docId?.let { note(it, message.write) } ?: false
        is PeerMessage.Closed -> note(message.docId, message.lastWrite)
        is PeerMessage.Editing -> note(message.docId, message.lastWrite)
        else -> false
    }

    operator fun get(docId: String): WriteRecord? = latest[docId]

    fun snapshot(): Map<String, WriteRecord> = HashMap(latest)

    /** As text, for keeping in settings. Only the most recent documents are kept. */
    fun encode(): String = json.encodeToString(serializer, latest.entries
        .sortedByDescending { it.value.seq }
        .take(KEEP)
        .associate { it.key to it.value })

    companion object {
        /** Enough documents to cover any afternoon's switching between devices. */
        private const val KEEP = 200

        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        private val serializer = kotlinx.serialization.builtins.MapSerializer(
            String.serializer(), WriteRecord.serializer()
        )

        /** Read back what [encode] wrote; an unreadable ledger is an empty one, not an error. */
        fun decode(text: String?): WriteLedger = WriteLedger(
            text?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
                .orEmpty()
        )
    }
}
