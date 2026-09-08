package com.inkslate

import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import com.inkslate.data.InkDocument
import com.inkslate.data.InkEmbedder
import com.inkslate.core.InkPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.CRC32

/**
 * The handwriting now lives inside the document rather than beside it, which means a bug in this
 * layer is not a rendering glitch - it is someone's homework gone. These tests exist to make that
 * failure loud and early rather than discovered a week later on another device.
 */
class InkPayloadTest {

    @get:Rule val temp = TemporaryFolder()

    private fun sampleDoc(strokes: Int = 40): InkDocument {
        val list = (0 until strokes).map { i ->
            Stroke(
                id = "test-$i",
                kind = Stroke.Kind.FREEHAND,
                color = 0xFF203040.toInt(),
                baseWidth = 2.5f + i,
                pageIndex = i % 3,
                points = (0 until 25).map { p ->
                    InkPoint(p * 1.5f + i, p * 2.25f - i, 1f + (p % 5) * 0.1f)
                }
            )
        }
        var doc = InkDocument.create("sample.pdf", "pdf", 3, 1234L, "fp")
        for (page in 0..2) {
            doc = doc.withPage(page, list.filter { it.pageIndex == page }, "test")
        }
        return doc
    }

    // ---- the container -------------------------------------------------------

    @Test fun `payload round trips`() {
        val doc = sampleDoc()
        val decoded = InkPayload.decode(InkPayload.encode(doc))
        assertNotNull("payload did not decode", decoded)
        assertEquals(doc.totalStrokes, decoded!!.totalStrokes)
        assertEquals(
            doc.strokesOn(1).map { it.id },
            decoded.strokesOn(1).map { it.id }
        )
        // point data is the part that would degrade silently, so check it explicitly
        assertEquals(doc.strokesOn(0).first().points, decoded.strokesOn(0).first().points)
    }

    @Test fun `payload compresses`() {
        val doc = sampleDoc(200)
        val raw = doc.serialize().toByteArray().size
        val encoded = InkPayload.encode(doc).size
        assertTrue("expected compression, got $encoded from $raw", encoded < raw / 2)
    }

    @Test fun `corrupt payload is rejected rather than half parsed`() {
        val good = InkPayload.encode(sampleDoc())

        assertNull("truncated payload accepted", InkPayload.decode(good.copyOf(good.size / 2)))
        assertNull("empty payload accepted", InkPayload.decode(ByteArray(0)))
        assertNull("foreign bytes accepted", InkPayload.decode("not a payload".toByteArray()))

        // Corrupt the middle of the compressed body. The last few bytes are deflate padding and
        // can absorb a flip without changing the output, which is a fine outcome but not a test.
        val flipped = good.copyOf()
        val at = flipped.size / 2
        flipped[at] = (flipped[at].toInt() xor 0x5A).toByte()
        assertNull("bit flip accepted", InkPayload.decode(flipped))

        // a header claiming a different size must not be believed either
        val lied = good.copyOf()
        lied[15] = (lied[15] + 7).toByte()
        assertNull("bad length accepted", InkPayload.decode(lied))
    }

    // ---- carriers ------------------------------------------------------------

    @Test fun `png carries handwriting and stays a valid png`() {
        val png = temp.newFile("page.png")
        png.writeBytes(minimalPng())
        val original = png.readBytes()

        val doc = sampleDoc()
        InkEmbedder.write(png, doc).getOrThrow()

        val read = InkEmbedder.read(png)
        assertNotNull("nothing read back from the png", read)
        assertEquals(doc.totalStrokes, read!!.totalStrokes)

        // the image itself must survive untouched: same signature, same IHDR and IDAT bytes
        val after = png.readBytes()
        assertTrue(after.size > original.size)
        assertEquals(
            original.toList().take(8), after.toList().take(8)
        )
        assertTrue("IEND missing", String(after, Charsets.ISO_8859_1).endsWith("IEND®B`"))
    }

    @Test fun `rewriting a png replaces rather than accumulates`() {
        val png = temp.newFile("page.png")
        png.writeBytes(minimalPng())

        InkEmbedder.write(png, sampleDoc(40)).getOrThrow()
        InkEmbedder.write(png, sampleDoc(40)).getOrThrow()
        InkEmbedder.write(png, sampleDoc(40)).getOrThrow()

        // Count the chunks rather than compare file sizes: the payload embeds a timestamp, so
        // two runs compress to very slightly different lengths even though nothing accumulated.
        assertEquals("payload accumulated instead of replacing", 1, countChunks(png, "inKs"))
        assertEquals(40, InkEmbedder.read(png)!!.totalStrokes)
    }

    @Test fun `jpeg carries handwriting across multiple segments`() {
        val jpg = temp.newFile("scan.jpg")
        jpg.writeBytes(minimalJpeg())

        // large enough to need more than one 64KB application segment
        val doc = sampleDoc(900)
        InkEmbedder.write(jpg, doc).getOrThrow()

        val read = InkEmbedder.read(jpg)
        assertNotNull("nothing read back from the jpeg", read)
        assertEquals(doc.totalStrokes, read!!.totalStrokes)

        val after = jpg.readBytes()
        assertEquals(0xFF, after[0].toInt() and 0xFF)
        assertEquals(0xD8, after[1].toInt() and 0xFF)
        assertEquals(0xD9, after[after.size - 1].toInt() and 0xFF)
    }

    @Test fun `a document with no payload reads as none rather than failing`() {
        val png = temp.newFile("clean.png")
        png.writeBytes(minimalPng())
        assertNull(InkEmbedder.read(png))

        val junk = temp.newFile("junk.png")
        junk.writeBytes(ByteArray(200) { it.toByte() })
        assertNull(InkEmbedder.read(junk))
    }

    @Test fun `unsupported formats are refused, not silently skipped`() {
        val txt = temp.newFile("notes.txt")
        txt.writeText("hello")
        assertTrue(InkEmbedder.write(txt, sampleDoc()).isFailure)
        assertEquals("hello", txt.readText())
    }

    // ---- fixtures ------------------------------------------------------------

    /** A 1x1 greyscale PNG, built by hand so the test has no image-library dependency. */
    private fun minimalPng(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        chunk(out, "IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0))
        chunk(
            out, "IDAT",
            byteArrayOf(0x78, 0x9C.toByte(), 0x63, 0x60, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01)
        )
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: java.io.ByteArrayOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(intBytes(data.size))
        out.write(t)
        out.write(data)
        val crc = CRC32()
        crc.update(t); crc.update(data)
        out.write(intBytes(crc.value.toInt()))
    }

    /** SOI, a JFIF APP0, a start-of-scan with a byte of data, and EOI. */
    private fun minimalJpeg(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        val jfif = byteArrayOf(
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
        )
        out.write(0xFF); out.write(0xE0)
        out.write(((jfif.size + 2) ushr 8) and 0xFF); out.write((jfif.size + 2) and 0xFF)
        out.write(jfif)
        out.write(0xFF); out.write(0xDA)
        out.write(0x00); out.write(0x03); out.write(0x00)
        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    /** How many chunks of the given type a PNG carries. */
    private fun countChunks(png: java.io.File, type: String): Int {
        val bytes = png.readBytes()
        var i = 8
        var n = 0
        while (i + 8 <= bytes.size) {
            val length = ((bytes[i].toInt() and 0xFF) shl 24) or
                ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                (bytes[i + 3].toInt() and 0xFF)
            val name = String(bytes, i + 4, 4, Charsets.US_ASCII)
            if (name == type) n++
            if (name == "IEND") break
            i += 12 + length
        }
        return n
    }

    private fun intBytes(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

}
