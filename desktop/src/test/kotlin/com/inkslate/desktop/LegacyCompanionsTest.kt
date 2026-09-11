package com.inkslate.desktop

import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Folding an old companion file into the document it belongs to.
 *
 * This rewrites a user's document and then deletes a file, which makes the order the only thing
 * that really matters: the companion may only go once the document has been read back carrying the
 * marks. A failure in the middle has to leave the handwriting somewhere.
 */
class LegacyCompanionsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun pdf(name: String): File {
        val f = temp.newFile(name)
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.save(f)
        }
        return f
    }

    private fun mark(id: String, page: Int = 0) = Stroke(
        id = id,
        kind = Stroke.Kind.FREEHAND,
        color = 0xFF000000.toInt(),
        baseWidth = 3f,
        points = listOf(InkPoint(10f, 10f, 3f), InkPoint(90f, 60f, 3f)),
        pageIndex = page,
        updatedUtc = 1L
    )

    private fun companionBeside(file: File, vararg strokes: Stroke) {
        val doc = InkDocument.create(
            sourceName = file.name, kind = "pdf", pageCount = 1,
            sizeBytes = file.length(), fingerprint = ""
        ).withPage(0, strokes.toList(), "tablet")
        DocumentIO.sidecarFor(file).writeText(doc.serialize())
    }

    @Test
    fun `a document with a companion file is found`() {
        val withOne = pdf("homework.pdf")
        pdf("clean.pdf")
        companionBeside(withOne, mark("a-1"))

        val found = LegacyCompanions.find(listOf(temp.root))

        assertEquals(listOf(withOne.absolutePath), found.map { it.absolutePath })
    }

    @Test
    fun `folding one in puts the marks inside the document and removes the companion`() {
        val file = pdf("homework.pdf")
        companionBeside(file, mark("a-1"), mark("a-2"))

        val result = LegacyCompanions.absorb(file)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow())
        assertFalse("the companion should be gone", DocumentIO.sidecarFor(file).exists())
        assertEquals(2, DesktopEmbedder.read(file)?.totalStrokes)
    }

    /** Marks made elsewhere since the companion was written must survive the fold. */
    @Test
    fun `marks already inside the document are kept`() {
        val file = pdf("homework.pdf")
        val inside = InkDocument.create(
            sourceName = file.name, kind = "pdf", pageCount = 1,
            sizeBytes = file.length(), fingerprint = ""
        ).withPage(0, listOf(mark("laptop-1")), "laptop")
        DesktopEmbedder.write(file, inside).getOrThrow()
        companionBeside(file, mark("tablet-1"))

        LegacyCompanions.absorb(file).getOrThrow()

        val ids = DesktopEmbedder.read(file)!!.strokesOn(0).map { it.id }.toSet()
        assertEquals(setOf("laptop-1", "tablet-1"), ids)
    }

    @Test
    fun `a document with no companion file is refused rather than rewritten`() {
        val file = pdf("homework.pdf")
        val before = file.length()

        assertTrue(LegacyCompanions.absorb(file).isFailure)
        assertEquals(before, file.length())
    }
}
