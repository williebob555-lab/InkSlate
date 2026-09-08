package com.inkslate.core

import java.util.zip.CRC32

/**
 * Where handwriting hides inside an image file.
 *
 * PNG and JPEG both have standard places to put bytes that every viewer ignores: a private
 * ancillary chunk, and an application segment. Using them means a screenshot marked up on the
 * tablet is still editable after being copied to the laptop, with no companion file to remember.
 *
 * Deliberately byte-array in, byte-array out. File handling differs between the platforms - one
 * stages through a temporary file with Android's storage quirks, the other does not - but the
 * format must not, or a document written on one would be unreadable on the other. That is the
 * whole reason this lives in the shared module rather than being written twice.
 */
object ImageInkCarrier {

    /**
     * PNG chunk type, chosen bit by bit.
     *
     * Lowercase first letter: ancillary, so a decoder may ignore it. Lowercase second: private,
     * not a registered type. Uppercase third: required by the spec. Lowercase fourth: safe to
     * copy, so an editor that rewrites the file may carry it across rather than corrupting it.
     */
    private const val PNG_CHUNK = "inKs"

    private const val JPEG_MARKER = 0xE8   // APP8
    private val JPEG_ID = "InkSlate ".toByteArray(Charsets.US_ASCII)

    /** A JPEG segment cannot exceed 64KB, so a busy page's handwriting spans several. */
    private const val JPEG_CHUNK = 65533 - 2 - 12

    // ---- PNG -----------------------------------------------------------------

    fun readPng(bytes: ByteArray): ByteArray? {
        if (bytes.size < 8) return null
        var i = 8                                        // past the signature
        val parts = ArrayList<ByteArray>()
        while (i + 8 <= bytes.size) {
            val length = readInt(bytes, i)
            if (length < 0 || i + 12 + length > bytes.size) break
            val name = String(bytes, i + 4, 4, Charsets.US_ASCII)
            if (name == PNG_CHUNK) {
                parts.add(bytes.copyOfRange(i + 8, i + 8 + length))
            } else if (name == "IEND") {
                break
            }
            i += 12 + length
        }
        return if (parts.isEmpty()) null else join(parts)
    }

    /** Returns a new PNG carrying [payload], replacing any copy already in there. */
    fun writePng(bytes: ByteArray, payload: ByteArray): ByteArray {
        require(bytes.size > 8) { "Not a PNG" }
        val out = java.io.ByteArrayOutputStream(bytes.size + payload.size + 64)
        out.write(bytes, 0, 8)

        var i = 8
        var wrote = false
        while (i + 8 <= bytes.size) {
            val length = readInt(bytes, i)
            if (length < 0 || i + 12 + length > bytes.size) break
            val name = String(bytes, i + 4, 4, Charsets.US_ASCII)
            val total = 12 + length
            if (name == PNG_CHUNK) { i += total; continue }      // drop the previous copy
            if (name == "IEND") {
                writePngChunk(out, payload)
                wrote = true
            }
            out.write(bytes, i, total)
            i += total
        }
        if (!wrote) writePngChunk(out, payload)
        return out.toByteArray()
    }

    private fun writePngChunk(out: java.io.ByteArrayOutputStream, payload: ByteArray) {
        val type = PNG_CHUNK.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(type)
        crc.update(payload)
        out.write(intBytes(payload.size))
        out.write(type)
        out.write(payload)
        out.write(intBytes(crc.value.toInt()))
    }

    // ---- JPEG ----------------------------------------------------------------

    fun readJpeg(bytes: ByteArray): ByteArray? {
        if (!isJpeg(bytes)) return null
        var i = 2
        val parts = ArrayList<ByteArray>()
        while (i + 4 <= bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0xFF) break
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) break      // end of image, or start of scan
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (length < 2 || i + 2 + length > bytes.size) break
            if (marker == JPEG_MARKER && regionMatches(bytes, i + 4, JPEG_ID)) {
                parts.add(bytes.copyOfRange(i + 4 + JPEG_ID.size, i + 2 + length))
            }
            i += 2 + length
        }
        return if (parts.isEmpty()) null else join(parts)
    }

    fun writeJpeg(bytes: ByteArray, payload: ByteArray): ByteArray {
        require(isJpeg(bytes)) { "Not a JPEG" }
        val out = java.io.ByteArrayOutputStream(bytes.size + payload.size + 256)
        out.write(bytes, 0, 2)

        var i = 2
        var inserted = false
        while (i + 4 <= bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0xFF) break
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) break
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (length < 2 || i + 2 + length > bytes.size) break

            val ours = marker == JPEG_MARKER && regionMatches(bytes, i + 4, JPEG_ID)
            if (!ours) {
                // Ours goes after any JFIF header, which has to stay first, and before everything
                // else - so it survives tools that only preserve leading segments.
                if (!inserted && marker != 0xE0) {
                    writeJpegSegments(out, payload)
                    inserted = true
                }
                out.write(bytes, i, 2 + length)
            }
            i += 2 + length
        }
        if (!inserted) writeJpegSegments(out, payload)
        if (i < bytes.size) out.write(bytes, i, bytes.size - i)
        return out.toByteArray()
    }

    private fun writeJpegSegments(out: java.io.ByteArrayOutputStream, payload: ByteArray) {
        var at = 0
        while (at < payload.size) {
            val n = minOf(JPEG_CHUNK, payload.size - at)
            val length = 2 + JPEG_ID.size + n
            out.write(0xFF)
            out.write(JPEG_MARKER)
            out.write((length ushr 8) and 0xFF)
            out.write(length and 0xFF)
            out.write(JPEG_ID)
            out.write(payload, at, n)
            at += n
        }
    }

    private fun isJpeg(b: ByteArray) =
        b.size > 4 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8

    // ---- shared --------------------------------------------------------------

    private fun join(parts: List<ByteArray>): ByteArray {
        if (parts.size == 1) return parts[0]
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        return out
    }

    private fun regionMatches(b: ByteArray, at: Int, other: ByteArray): Boolean {
        if (at + other.size > b.size) return false
        for (i in other.indices) if (b[at + i] != other[i]) return false
        return true
    }

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)

    private fun intBytes(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )
}
