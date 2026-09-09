package com.inkslate.desktop

import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.PagePlan
import com.inkslate.core.PaperSpec
import com.inkslate.core.PlannedPage
import com.inkslate.core.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Rearranging a real PDF, with the handwriting following its pages.
 *
 * The plan arithmetic is covered in `:core`; what is worth testing here is the half that touches
 * the file - that the page tree comes back in the right order, that a duplicated page does not
 * duplicate a scanned textbook's images, and above all that the pages and the ink land together.
 * A document whose pages moved and whose marks did not still opens and still looks like a
 * document, and is simply wrong.
 */
class DocumentPagesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var minted = 0
    private fun newId(): String = "new-${++minted}"

    /** A document of [pages] pages, with one mark on each, tagged by the page it started on. */
    private fun document(pages: Int): Pair<File, InkDocument> {
        val file = BlankDocumentFactory.create(
            temp.newFolder(),
            BlankDocumentFactory.Spec(name = "Doc", pageCount = pages)
        ).getOrThrow()

        var ink = InkDocument.create("Doc.pdf", "pdf", pages, 0L, "")
        for (page in 0 until pages) {
            ink = ink.withPage(
                page,
                listOf(
                    Stroke(
                        id = "p$page",
                        kind = Stroke.Kind.FREEHAND,
                        color = 0xFF000000.toInt(),
                        baseWidth = 2f,
                        pageIndex = page,
                        // A mark whose position identifies the page it came from.
                        points = listOf(
                            InkPoint(60f + page * 20f, 100f, 2f),
                            InkPoint(120f + page * 20f, 160f, 2f)
                        )
                    )
                ),
                "test"
            )
        }
        return file to ink
    }

    private fun pageCountOf(file: File): Int =
        DesktopSources.open(file)!!.use { it.pageCount }

    @Test
    fun `reordering the pages reorders the file and the handwriting together`() {
        val (file, ink) = document(3)
        val plan = PagePlan.moved(PagePlan.identity(3), from = 0, to = 2)

        val remapped = DocumentPages.rearrange(file, ink, plan, ::newId).getOrThrow()

        assertEquals(3, pageCountOf(file))
        // The mark that was on page 0 is now on the last page, and its x still identifies it.
        assertEquals(1, remapped.strokesOn(2).size)
        assertEquals(60f, remapped.strokesOn(2).single().points[0].x, 0.5f)
    }

    @Test
    fun `removing a page removes it from the file`() {
        val (file, ink) = document(3)
        val plan = PagePlan.removed(PagePlan.identity(3), at = 1)

        val remapped = DocumentPages.rearrange(file, ink, plan, ::newId).getOrThrow()

        assertEquals(2, pageCountOf(file))
        assertEquals(2, remapped.totalStrokes)
    }

    @Test
    fun `duplicating a page adds one to the file`() {
        val (file, ink) = document(2)
        val plan = PagePlan.duplicated(PagePlan.identity(2), at = 0, uid = 99L)

        val remapped = DocumentPages.rearrange(file, ink, plan, ::newId).getOrThrow()

        assertEquals(3, pageCountOf(file))
        assertEquals(3, remapped.totalStrokes)
    }

    @Test
    fun `a blank page can be inserted and still renders`() {
        val (file, ink) = document(2)
        val plan = PagePlan.inserted(
            PagePlan.identity(2), at = 1,
            listOf(
                PlannedPage(
                    source = -1, uid = 90L,
                    blankWidth = 612f, blankHeight = 792f,
                    paper = PaperSpec(background = "GRID")
                )
            )
        )

        DocumentPages.rearrange(file, ink, plan, ::newId).getOrThrow()

        assertEquals(3, pageCountOf(file))
        DesktopSources.open(file)!!.use {
            assertNotNull("the inserted page should render", it.render(1, 160))
        }
    }

    @Test
    fun `turning a page swaps its dimensions in the file`() {
        val (file, ink) = document(1)
        val before = DesktopSources.open(file)!!.use { it.pageDim(0) }

        DocumentPages.rearrange(
            file, ink, PagePlan.turned(PagePlan.identity(1), 0, 1), ::newId
        ).getOrThrow()

        val after = DesktopSources.open(file)!!.use { it.pageDim(0) }
        assertEquals(before.height, after.width, 0.5f)
        assertEquals(before.width, after.height, 0.5f)
    }

    /**
     * Duplicating a page must not duplicate what is on it.
     *
     * Content streams and resources are shared deliberately - a scanned textbook page carries a
     * full-page image, and copying that would double the file for every duplicate.
     */
    @Test
    fun `duplicating does not double the file`() {
        val (file, ink) = document(1)
        val before = file.length()

        DocumentPages.rearrange(
            file, ink, PagePlan.duplicated(PagePlan.identity(1), 0, 55L), ::newId
        ).getOrThrow()

        assertEquals(2, pageCountOf(file))
        assertTrue(
            "a duplicated page should not cost another whole page of content",
            file.length() < before * 2
        )
    }

    @Test
    fun `a document with one page keeps it`() {
        val (file, ink) = document(1)
        DocumentPages.rearrange(
            file, ink, PagePlan.removed(PagePlan.identity(1), 0), ::newId
        ).getOrThrow()
        assertEquals(1, pageCountOf(file))
    }

    @Test
    fun `an image cannot have its pages rearranged`() {
        val notPdf = temp.newFile("photo.png").apply { writeText("not really a png") }
        val result = DocumentPages.rearrange(
            notPdf, InkDocument.create("photo.png", "image", 1, 0L, ""),
            PagePlan.identity(1), ::newId
        )
        assertTrue(result.isFailure)
    }
}
