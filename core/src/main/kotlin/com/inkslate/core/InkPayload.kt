package com.inkslate.core

import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The bytes that get carried inside a document.
 *
 * A document's handwriting used to live in a `.inkdoc` file sitting next to it, which cluttered
 * the file explorer, had to be dragged along by hand when a document was copied, and had to be
 * kept in step whenever the parent was renamed. Putting the same bytes *inside* the parent file
 * removes all three problems at once: there is nothing to hide, nothing to follow, and nothing to
 * forget.
 *
 * The container is deliberately plain rather than encrypted. Encryption would make the payload
 * unreadable to a future version of this app, to the desktop build, and to any recovery attempt
 * made with a hex editor at two in the morning - which is the exact situation the format exists
 * to survive. What it does carry is a length and a checksum, so a truncated or mangled payload is
 * detected and rejected rather than silently parsed into a half-document.
 */
object InkPayload {

    /** Chosen to be recognisable in a hex dump, which is where this gets read from in a crisis. */
    private val MAGIC = "INKSLATE".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1

    /** Header: magic(8) version(1) reserved(3) rawLength(4) crc32(4). */
    private const val HEADER = 20

    /** Serialise, compress, and wrap. */
    fun encode(doc: InkDocument): ByteArray = encodeText(doc.compacted().serialize())

    /**
     * Wrap handwriting that has already been serialised.
     *
     * The autosave writes the same document out as text moments before every save, and building
     * it a second time is a quarter of a second of pure repetition on a document with a term of
     * work in it. The caller is responsible for the text being this document; see
     * [InkJournal.textFor], which only hands back text it built from the very same instance.
     */
    fun encodeText(json: String): ByteArray {
        val raw = json.toByteArray(Charsets.UTF_8)

        val crc = CRC32().apply { update(raw) }.value.toInt()
        // Default level rather than maximum. Stroke JSON is long runs of similar floats, so the
        // two settings land within a couple of percent of each other, and maximum compression
        // spends several times as long getting there - time paid every single save, on the
        // largest documents, which are exactly the ones already waiting longest.
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        val body: ByteArray = try {
            deflater.setInput(raw)
            deflater.finish()
            // Strokes are long runs of similar floats in JSON and compress hard, typically to
            // well under a tenth. The +64 covers the pathological case of incompressible input.
            val out = ByteArray(raw.size + 64)
            var n = 0
            while (!deflater.finished() && n < out.size) {
                n += deflater.deflate(out, n, out.size - n)
            }
            out.copyOf(n)
        } finally {
            deflater.end()
        }

        val result = ByteArray(HEADER + body.size)
        MAGIC.copyInto(result, 0)
        result[8] = VERSION.toByte()
        writeInt(result, 12, raw.size)
        writeInt(result, 16, crc)
        body.copyInto(result, HEADER)
        return result
    }

    /** Unwrap and verify. Returns null for anything that is not an intact payload. */
    fun decode(bytes: ByteArray?): InkDocument? {
        if (bytes == null || bytes.size <= HEADER) return null
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
        if (bytes[8].toInt() != VERSION) return null

        val rawLength = readInt(bytes, 12)
        val expectedCrc = readInt(bytes, 16)
        // A corrupt length must not become a huge allocation, so it is sanity-checked first.
        if (rawLength <= 0 || rawLength > MAX_RAW) return null

        val inflater = Inflater()
        val raw = try {
            inflater.setInput(bytes, HEADER, bytes.size - HEADER)
            val out = ByteArray(rawLength)
            var n = 0
            while (n < rawLength && !inflater.finished()) {
                val step = inflater.inflate(out, n, rawLength - n)
                if (step == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += step
            }
            if (n != rawLength) return null
            out
        } catch (_: Throwable) {
            return null
        } finally {
            inflater.end()
        }

        if (CRC32().apply { update(raw) }.value.toInt() != expectedCrc) return null
        return InkDocument.parse(String(raw, Charsets.UTF_8))
    }

    /** Cheap test used when scanning a file, before committing to a full decode. */
    fun looksLikePayload(bytes: ByteArray, at: Int = 0): Boolean {
        if (at + MAGIC.size > bytes.size) return false
        for (i in MAGIC.indices) if (bytes[at + i] != MAGIC[i]) return false
        return true
    }

    /** 64 MB of JSON is far beyond any real document and well short of an OOM. */
    private const val MAX_RAW = 64 * 1024 * 1024

    private fun writeInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)
}
