package com.inksheets.core

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Play together over Bluetooth: tiny broadcasts every device repeats, so where the leader is
 * spreads across a room hop by hop - no Wi-Fi, no network, nothing to connect to. For networks
 * that stop devices reaching each other (school Wi-Fi, eduroam), and for places with none.
 *
 * A broadcast carries 20 bytes at most, so it says only what has to be said: which leader
 * ([session], from the leader's name), which song (a key made from its title, so two libraries
 * with the same song agree), which page, which part the leader reads (a key again), and a count
 * ([seq]) so an older update never undoes a newer one. Messages go in pieces of ten bytes.
 */
object MeshFrames {

    const val MAX_BYTES = 20
    /** How many times a broadcast may be passed on: enough to cross a gym, not to echo forever. */
    const val MAX_HOPS = 6
    private const val VERSION = 1
    private const val STATE = 1
    private const val NOTE = 2
    const val NOTE_PIECE = 10
    /** A message is at most this many pieces - 150 bytes, about a sentence. */
    const val NOTE_PIECES = 15

    sealed interface Frame { val session: Int; val hops: Int }

    data class State(
        override val session: Int,
        val seq: Long,
        val songKey: Long,
        val page: Int,
        val partKey: Int,
        override val hops: Int = 0
    ) : Frame

    data class NotePiece(
        override val session: Int,
        val noteId: Long,
        val index: Int,
        val count: Int,
        val urgent: Boolean,
        /** 0 for the warning colour, else 1 + the index into the song colours. */
        val colour: Int,
        val bytes: ByteArray,
        override val hops: Int = 0
    ) : Frame

    fun sessionOf(leaderName: String): Int = (hash(leaderName.trim().lowercase(), 2) and 0xFFFF).toInt()

    /** Six bytes from a song's title, the same in every library that has the song. */
    fun songKey(title: String): Long = hash(Library.matchKey(title), 6)

    /** Two bytes for a part: instrument, number and length, as [CompanionLink.samePart] compares. */
    fun partKey(instrument: String?, partNo: String?, pages: Int): Int =
        (hash("${instrument.orEmpty()}|${partNo.orEmpty()}|$pages", 2) and 0xFFFF).toInt()

    private fun hash(text: String, bytes: Int): Long {
        val d = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        var v = 0L
        for (i in 0 until bytes) v = (v shl 8) or (d[i].toLong() and 0xFF)
        return v
    }

    fun encode(f: Frame): ByteArray = when (f) {
        is State -> ByteBuffer.allocate(18).apply {
            put(((VERSION shl 4) or STATE).toByte())
            putShort(f.session.toShort())
            putInt(f.seq.toInt())
            for (i in 5 downTo 0) put((f.songKey shr (i * 8)).toByte())
            putShort(f.page.toShort())
            putShort(f.partKey.toShort())
            put(f.hops.toByte())
        }.array()
        is NotePiece -> ByteBuffer.allocate(10 + f.bytes.size).apply {
            put(((VERSION shl 4) or NOTE).toByte())
            putShort(f.session.toShort())
            putInt(f.noteId.toInt())
            put(((f.index shl 4) or (f.count and 0xF)).toByte())
            put(((if (f.urgent) 1 else 0) or (f.colour shl 1)).toByte())
            put(f.hops.toByte())
            put(f.bytes)
        }.array()
    }

    fun decode(b: ByteArray): Frame? = runCatching {
        if (b.isEmpty() || (b[0].toInt() shr 4) and 0xF != VERSION) return null
        val buf = ByteBuffer.wrap(b)
        val head = buf.get().toInt() and 0xF
        val session = buf.short.toInt() and 0xFFFF
        when (head) {
            STATE -> {
                val seq = buf.int.toLong() and 0xFFFFFFFFL
                var key = 0L
                repeat(6) { key = (key shl 8) or (buf.get().toLong() and 0xFF) }
                val page = buf.short.toInt() and 0xFFFF
                val part = buf.short.toInt() and 0xFFFF
                State(session, seq, key, page, part, buf.get().toInt())
            }
            NOTE -> {
                val id = buf.int.toLong() and 0xFFFFFFFFL
                val ic = buf.get().toInt() and 0xFF
                val flags = buf.get().toInt() and 0xFF
                val hops = buf.get().toInt()
                val rest = ByteArray(buf.remaining()).also { buf.get(it) }
                NotePiece(session, id, ic shr 4, ic and 0xF, flags and 1 == 1, flags shr 1, rest, hops)
            }
            else -> null
        }
    }.getOrNull()

    /** A message as pieces, cut on character boundaries and shortened to fit if need be. */
    fun notePieces(session: Int, noteId: Long, text: String, urgent: Boolean, colour: Int): List<NotePiece> {
        var bytes = text.toByteArray(Charsets.UTF_8)
        while (bytes.size > NOTE_PIECE * NOTE_PIECES) bytes = text.substring(0, text.length - (bytes.size - NOTE_PIECE * NOTE_PIECES).coerceAtLeast(1)).let { t -> t.toByteArray(Charsets.UTF_8) }
        val chunks = bytes.toList().chunked(NOTE_PIECE).map { it.toByteArray() }.ifEmpty { listOf(ByteArray(0)) }
        return chunks.mapIndexed { i, c -> NotePiece(session, noteId and 0xFFFFFFFFL, i, chunks.size, urgent, colour, c) }
    }

    /** Pieces of messages as they arrive, in any order, each message put together once. */
    class NoteAssembler {
        private val parts = HashMap<Long, Array<ByteArray?>>()
        private val done = LinkedHashSet<Long>()

        /** The whole message, the moment its last piece arrives; null until then, and after. */
        fun take(p: NotePiece): String? {
            if (p.noteId in done || p.count <= 0 || p.index >= p.count) return null
            val have = parts.getOrPut(p.noteId) { arrayOfNulls(p.count) }
            if (have.size != p.count) return null
            have[p.index] = p.bytes
            if (have.any { it == null }) return null
            parts.remove(p.noteId)
            done += p.noteId
            if (done.size > 64) done.remove(done.first())
            return have.fold(ByteArray(0)) { acc, b -> acc + b!! }.toString(Charsets.UTF_8)
        }
    }

    /**
     * Whether [seq] is news after [last]: newer, or so much older that the leader must have
     * started again (its count restarts from one).
     */
    fun isNewer(seq: Long, last: Long): Boolean = seq > last || (last - seq) > 10_000
}
