package com.inkslate.core.peer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Which bytes a document file holds, in a form two devices can compare.
 *
 * File sync moves bytes exactly, so the same write has the same revision wherever it lands. That is
 * the whole point: a device can tell whether the other device's write has reached its own disk
 * without reading the document, and without trusting a modification time - which a phone's storage
 * does not always keep.
 *
 * The size and a hash of the last stretch of the file. Every write this app makes changes the end
 * of the file - an appended update ends in a new trailer, and a full rewrite writes one - so the
 * tail is where two different writes differ. Hashing the whole of a two-hundred-megabyte textbook
 * on every change would be the wrong trade for a question this cheap to answer.
 */
@Serializable
data class FileRevision(
    @SerialName("size") val size: Long,
    @SerialName("tail") val tail: String
) {
    companion object {
        private const val TAIL_BYTES = 64 * 1024

        /** The revision of [file] as it is now, or null when it cannot be read. */
        fun of(file: File): FileRevision? = runCatching {
            if (!file.isFile) return null
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                val n = minOf(size, TAIL_BYTES.toLong()).toInt()
                val buffer = ByteArray(n)
                raf.seek(size - n)
                raf.readFully(buffer)
                val digest = MessageDigest.getInstance("SHA-256").digest(buffer)
                FileRevision(size, digest.take(12).joinToString("") { "%02x".format(it) })
            }
        }.getOrNull()
    }
}

/**
 * One write of a document, as the device that made it announces it.
 *
 * [seq] orders writes across devices: each is one past the highest the writer knew of. Two devices
 * that wrote while apart can reach the same number; [by] then settles which is "latest", the same
 * way on both.
 */
@Serializable
data class WriteRecord(
    @SerialName("seq") val seq: Long,
    @SerialName("by") val by: String,
    @SerialName("file") val file: FileRevision
) {
    fun isAfter(other: WriteRecord?): Boolean =
        other == null || seq > other.seq || (seq == other.seq && by > other.by)
}

/**
 * Which device writes a document while more than one has it open.
 *
 * Higher [term] wins; on equal terms the higher [holder] tag does, so two devices asking at the same
 * moment settle it the same way without a third party.
 */
@Serializable
data class Lease(
    @SerialName("term") val term: Long,
    @SerialName("holder") val holder: String
) {
    fun outranks(other: Lease?): Boolean =
        other == null || term > other.term || (term == other.term && holder > other.holder)
}
