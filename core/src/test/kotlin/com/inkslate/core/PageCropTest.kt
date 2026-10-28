package com.inkslate.core

import com.inkslate.core.peer.PeerSync
import org.junit.Assert.*
import org.junit.Test

class PageCropTest {
    private fun document() = InkDocument(
        docId = "crop", source = InkDocument.SourceRef("score.pdf", "pdf", pageCount = 3),
        pageSizes = List(3) { InkDocument.PageSize(600f, 800f) },
        pages = mapOf("0" to listOf(Stroke("pen-1", Stroke.Kind.FREEHAND, -1, 2f,
            listOf(InkPoint(120f, 160f, 1f)), updatedUtc = 1L)))
    )
    private val crop = PageCrop(0.1f, 0.2f, 0.9f, 0.8f)

    @Test fun cropAndResetPreserveSourceAndAnnotationGeometry() {
        val original = document()
        val cropped = original.withPageCrop(listOf(0, 2), crop, 10L)
        assertEquals(original.source, cropped.source)
        assertEquals(original.pageSizes, cropped.pageSizes)
        assertEquals(original.pages, cropped.pages)
        assertNull(cropped.pageCrops["1"])
        assertEquals(Box(60f, 160f, 540f, 640f), cropped.pageCrops["0"]!!.box(600f, 800f))
        val reset = cropped.withPageCrop(listOf(0), null, 20L)
        assertEquals(Box(0f, 0f, 600f, 800f), reset.pageCrops["0"]!!.box(600f, 800f))
        assertEquals(original.pages, reset.pages)
        assertEquals(reset.pageCrops, InkDocument.parse(reset.serialize())!!.pageCrops)
        assertEquals(reset.pageCrops, cropped.mergeWith(reset).pageCrops)
        assertEquals(reset.pageCrops, reset.mergeWith(cropped).pageCrops)
    }

    @Test fun duplicateReorderAndTurnCarryTheViewportWithThePage() {
        val doc = document().withPageCrop(listOf(0), crop, 10L)
        val changed = PageStructure.apply(doc, StructureChange("", "turned", listOf(
            StructurePage(2), StructurePage(0, 1), StructurePage(0, 2), StructurePage(-1)
        ), at = 20L))
        assertNull(changed.pageCrops["0"])
        assertEquals(crop.turned(1).copy(updatedUtc = 10L), changed.pageCrops["1"])
        assertEquals(crop.turned(2).copy(updatedUtc = 10L), changed.pageCrops["2"])
        assertNull(changed.pageCrops["3"])
        val afterFour = crop.turned(1).turned(1).turned(1).turned(1)
        assertEquals(crop.left, afterFour.left, 0.00001f)
        assertEquals(crop.bottom, afterFour.bottom, 0.00001f)
    }

    @Test fun cropOnlyChangesTravelThroughLivePeerMetadata() {
        val plain = document()
        val cropped = plain.withPageCrop(listOf(1), crop, 10L)
        assertFalse(PeerSync.holdsEverythingIn(plain, cropped))
        val answer = PeerSync.answerFor(cropped, PeerSync.digestOf(plain))
        assertFalse(answer.isEmpty)
        assertTrue(PeerSync.changesAnything(plain, answer))
        assertEquals(cropped.pageCrops, PeerSync.applied(plain, answer).pageCrops)
        val reset = cropped.withPageCrop(listOf(1), null, 20L)
        assertEquals(reset.pageCrops, PeerSync.applied(cropped, PeerSync.answerFor(reset, PeerSync.digestOf(cropped))).pageCrops)
    }

    @Test fun invalidOrCollapsedBoundsNeverCreateAViewport() {
        assertNull(PageCrop(left = Float.NaN).box(600f, 800f))
        assertNull(PageCrop(left = -0.1f).box(600f, 800f))
        assertNull(PageCrop(left = 0.99f).box(600f, 800f))
    }
}
