package com.inkslate.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pictures on a page travel inside the document, not in a folder beside it.
 *
 * A capture used to leave `<name>.inkassets/<id>.png` next to the worksheet - a folder and a file
 * per capture, in the person's own coursework. These pin down that the pictures now ride in the
 * payload, come back out wherever it is opened, and that the old folder is only removed once the
 * document is known to carry everything it showed.
 */
class PicturesTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pictures").toFile()
        Pictures.dir = File(root, "store")
    }

    @After
    fun tearDown() {
        Pictures.dir = null
        root.deleteRecursively()
    }

    private fun docShowing(vararg ids: String) = InkDocument(
        docId = "d",
        source = InkDocument.SourceRef(name = "hw.pdf", kind = "pdf", pageCount = 1),
        pages = mapOf(
            "0" to ids.mapIndexed { i, id ->
                Stroke(
                    id = "s$i", kind = Stroke.Kind.IMAGE, color = 0, baseWidth = 0f,
                    points = listOf(InkPoint(0f, 0f, 1f), InkPoint(10f, 10f, 1f)),
                    imageId = id
                )
            }
        )
    )

    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4, 5)

    @Test
    fun `a capture is kept in the app, not beside the document`() {
        val id = Pictures.put(png)
        assertNotNull(id)
        assertEquals(File(root, "store"), Pictures.file(id!!)!!.parentFile)
        assertArrayEquals(png, Pictures.bytes(id))
    }

    @Test
    fun `the payload carries the pictures and opening it brings them back`() {
        val id = Pictures.put(png)!!
        val doc = docShowing(id)
        val payload = InkPayload.encode(doc)
        assertEquals(setOf(id), InkPayload.pictureIds(payload))

        // Another device: an empty store.
        Pictures.dir = File(root, "elsewhere")
        assertNull(Pictures.file(id))
        assertEquals(doc, InkPayload.decode(payload))
        assertArrayEquals(png, Pictures.bytes(id))
    }

    @Test
    fun `a document with no pictures encodes exactly as before`() {
        val plain = docShowing().copy(pages = emptyMap())
        val payload = InkPayload.encode(plain)
        assertTrue(InkPayload.pictureIds(payload).isEmpty())
        assertTrue(payload.contentEquals(InkPayload.encodeText(plain.compacted().serialize())))
    }

    @Test
    fun `an older reader still finds the handwriting before the pictures`() {
        val id = Pictures.put(png)!!
        val payload = InkPayload.encode(docShowing(id))
        // What an older build hands to the decoder is the same bytes; it inflates the handwriting
        // and never looks further. Cutting the pictures off must leave a payload that decodes.
        val withoutPictures = InkPayload.encodeText(docShowing(id).compacted().serialize())
        assertTrue(payload.size > withoutPictures.size)
        assertEquals(InkPayload.decode(withoutPictures), InkPayload.decode(payload))
    }

    @Test
    fun `the old folder goes only once the document carries what it shows`() {
        val document = File(root, "hw.pdf").apply { writeText("pdf") }
        val folder = Pictures.legacyFolder(document).apply { mkdirs() }
        File(folder, "aaaa1111.png").writeBytes(png)
        File(folder, "bbbb2222.png").writeBytes(png)   // nothing shows this one any more

        Pictures.adoptLegacy(document)
        assertNotNull(Pictures.file("aaaa1111"))

        val doc = docShowing("aaaa1111")
        // A save that did not carry the picture leaves the folder alone.
        Pictures.retireLegacyFolder(document, doc, InkPayload.encodeText(doc.serialize()))
        assertTrue(folder.isDirectory)

        Pictures.retireLegacyFolder(document, doc, InkPayload.encode(doc))
        assertFalse(folder.exists())
    }

    @Test
    fun `a folder with anything else in it is never removed`() {
        val document = File(root, "hw.pdf").apply { writeText("pdf") }
        val folder = Pictures.legacyFolder(document).apply { mkdirs() }
        File(folder, "notes.txt").writeText("mine")
        Pictures.retireLegacyFolder(document, docShowing(), InkPayload.encode(docShowing()))
        assertTrue(File(folder, "notes.txt").isFile)
    }

    @Test
    fun `ids cannot name a file outside the store`() {
        assertFalse(Pictures.keep("../escape", png))
        assertNull(Pictures.file("../escape"))
    }
}
