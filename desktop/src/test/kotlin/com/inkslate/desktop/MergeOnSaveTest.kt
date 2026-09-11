package com.inkslate.desktop

import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What saving does when the file has moved underneath the editor.
 *
 * The ordinary case, not the exceptional one: the documents live in a synced folder, so the tablet
 * writing from the other room lands under an open document here. Saving writes this machine's
 * marks into whatever is on disk at that moment, so the marks that arrived in between have to be
 * folded in first or they are replaced by a payload that never saw them.
 */
class MergeOnSaveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun pdf(name: String = "homework.pdf"): File {
        val f = temp.newFile(name)
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.save(f)
        }
        return f
    }

    private fun mark(id: String) = Stroke(
        id = id,
        kind = Stroke.Kind.FREEHAND,
        color = 0xFF000000.toInt(),
        baseWidth = 3f,
        points = listOf(InkPoint(10f, 10f, 3f), InkPoint(90f, 60f, 3f)),
        pageIndex = 0,
        updatedUtc = 1L
    )

    private fun docWith(file: File, device: String, vararg strokes: Stroke) =
        InkDocument.create(
            sourceName = file.name, kind = "pdf", pageCount = 1,
            sizeBytes = file.length(), fingerprint = ""
        ).withPage(0, strokes.toList(), device)

    @Test
    fun `marks that arrived while the document was open survive the save`() {
        val file = pdf()
        // The tablet's copy lands on disk.
        DesktopEmbedder.write(file, docWith(file, "tablet", mark("tablet-1"))).getOrThrow()

        // This machine has been editing its own copy, which never saw that mark.
        val here = docWith(file, "laptop", mark("laptop-1"))

        val merged = DocumentIO.mergedWithDisk(file, here)

        assertEquals(
            setOf("laptop-1", "tablet-1"),
            merged.strokesOn(0).map { it.id }.toSet()
        )
    }

    /**
     * An erase made here still wins.
     *
     * A deletion is recorded rather than merely absent, so folding in a copy that still carries
     * the mark must not bring it back - which is the failure this whole merge exists to avoid.
     */
    @Test
    fun `folding in an older copy does not resurrect what was erased here`() {
        val file = pdf()
        val both = docWith(file, "laptop", mark("a-1"), mark("a-2"))
        DesktopEmbedder.write(file, both).getOrThrow()

        // Erased here: the page now holds one of the two, and the other is a tombstone.
        val afterErase = both.withPage(0, listOf(mark("a-1")), "laptop")

        val merged = DocumentIO.mergedWithDisk(file, afterErase)

        assertEquals(listOf("a-1"), merged.strokesOn(0).map { it.id })
    }

    @Test
    fun `a document nobody else touched comes back unchanged`() {
        val file = pdf()
        val here = docWith(file, "laptop", mark("laptop-1"))
        DesktopEmbedder.write(file, here).getOrThrow()

        val merged = DocumentIO.mergedWithDisk(file, here)

        assertEquals(1, merged.totalStrokes)
        assertEquals(listOf("laptop-1"), merged.strokesOn(0).map { it.id })
    }

    /** The stamp is what decides whether the parse above is worth doing at all. */
    @Test
    fun `the stamp changes when the file is written and not otherwise`() {
        val file = pdf()
        val before = DocumentIO.stampOf(file)
        assertEquals(before, DocumentIO.stampOf(file))

        // Modification time has one-second resolution on some filesystems, so make the size move.
        DesktopEmbedder.write(file, docWith(file, "tablet", mark("tablet-1"))).getOrThrow()

        assertNotEquals(before, DocumentIO.stampOf(file))
        assertTrue(file.length() > 0)
    }
}
