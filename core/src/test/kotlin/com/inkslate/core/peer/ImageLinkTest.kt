package com.inkslate.core.peer

import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageLinkTest {

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3, 4)

    private fun docShowing(vararg ids: String) = InkDocument.create("a.pdf", "pdf", 1, 1, "").copy(
        pages = mapOf("0" to ids.mapIndexed { i, id ->
            Stroke(
                id = "s$i", kind = Stroke.Kind.IMAGE, color = 0, baseWidth = 1f,
                points = listOf(InkPoint(0f, 0f, 1f), InkPoint(50f, 50f, 1f)), pageIndex = 0,
                imageId = id
            )
        })
    )

    /** Two devices, wired straight to each other. */
    private inner class Pair(docId: String) {
        val tabletStore = HashMap<String, ByteArray>()
        val laptopStore = HashMap<String, ByteArray>()
        val sent = ArrayList<PeerMessage>()
        lateinit var tablet: ImageLink
        lateinit var laptop: ImageLink

        init {
            fun link(me: String, store: HashMap<String, ByteArray>, other: () -> ImageLink) = ImageLink(
                docId = docId,
                have = { it in store },
                serve = { peer, ids ->
                    ids.forEach { id -> ImageLink.dataFor(docId, id, store.getValue(id))?.let { deliver(me, it, other) } }
                },
                keep = { id, bytes -> store[id] = bytes },
                send = { _, m -> deliver(me, m, other) }
            )
            tablet = link("tablet", tabletStore) { laptop }
            laptop = link("laptop", laptopStore) { tablet }
        }

        private fun deliver(from: String, m: PeerMessage, to: () -> ImageLink) {
            // Through the real encoding.
            val back = PeerMessage.decode(PeerMessage.encode(m))!!
            sent += back
            to().received(from, back)
        }
    }

    @Test
    fun `a picture a mark shows is fetched from the device that has it`() {
        val p = Pair("doc")
        val doc = docShowing("abc123")
        p.laptopStore["abc123"] = png
        p.tablet.lookFor(listOf("laptop"), doc, now = 0)
        assertArrayEquals(png, p.tabletStore["abc123"])
    }

    @Test
    fun `a picture already here is not asked for, and one asked for is not asked again at once`() {
        val p = Pair("doc")
        p.tabletStore["have"] = png
        val doc = docShowing("have", "nobody-has-it")
        p.tablet.lookFor(listOf("laptop"), doc, now = 0)
        p.tablet.lookFor(listOf("laptop"), doc, now = 500)
        val wants = p.sent.filterIsInstance<PeerMessage.ImageWant>()
        assertEquals(1, wants.size)
        assertEquals(listOf("nobody-has-it"), wants.single().ids)
        p.tablet.lookFor(listOf("laptop"), doc, now = 20_000)
        assertEquals(2, p.sent.filterIsInstance<PeerMessage.ImageWant>().size)
    }

    @Test
    fun `nothing that is not a picture, or whose name could leave the folder, is kept`() {
        val p = Pair("doc")
        p.tablet.received("laptop", PeerMessage.ImageData("doc", "abc", java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4, 5))))
        p.tablet.received("laptop", PeerMessage.ImageData("doc", "../../evil", java.util.Base64.getEncoder().encodeToString(png)))
        p.tablet.received("laptop", PeerMessage.ImageData("doc", "ok", "not base64 at all!"))
        assertTrue(p.tabletStore.isEmpty())
        assertNull(ImageLink.dataFor("doc", "x", ByteArray(10)))
    }
}
