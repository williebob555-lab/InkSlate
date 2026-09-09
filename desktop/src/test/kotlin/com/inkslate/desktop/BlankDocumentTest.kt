package com.inkslate.desktop

import com.inkslate.core.PaperPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The blank-document writer, and the paper it rules.
 *
 * Worth testing because it is the one part of this build written against a different PDFBox from
 * the Android original - version 3, whose colour and content-stream APIs are not the ones the
 * tablet's code calls. A mistake there does not fail to compile; it produces a PDF, and the page
 * is simply wrong.
 */
class BlankDocumentTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a blank document is a readable PDF with the pages asked for`() {
        val dir = temp.newFolder()
        val created = BlankDocumentFactory.create(
            dir,
            BlankDocumentFactory.Spec(name = "Notes", pageCount = 3)
        ).getOrThrow()

        assertTrue(created.isFile)
        assertEquals("Notes.pdf", created.name)

        val source = DesktopSources.open(created)
        assertNotNull("the document this app just wrote should open in it", source)
        source!!.use {
            assertEquals(3, it.pageCount)
            val dim = it.pageDim(0)
            assertEquals(612f, dim.width, 0.5f)
            assertEquals(792f, dim.height, 0.5f)
            // Rendering is what a page being malformed actually shows up as.
            assertNotNull("page 1 should render", it.render(0, 160))
        }
    }

    @Test
    fun `every ruling writes a page that still renders`() {
        val dir = temp.newFolder()
        for (background in BlankDocumentFactory.Background.entries) {
            val f = BlankDocumentFactory.create(
                dir,
                BlankDocumentFactory.Spec(name = background.name, background = background)
            ).getOrThrow()
            DesktopSources.open(f).use { source ->
                assertNotNull("${background.name} should open", source)
                assertNotNull("${background.name} should render", source!!.render(0, 160))
            }
        }
    }

    @Test
    fun `a whiteboard keeps the size it was asked for`() {
        val dir = temp.newFolder()
        val f = BlankDocumentFactory.create(
            dir,
            BlankDocumentFactory.Spec(
                name = "Board",
                pageSize = BlankDocumentFactory.PageSize.WHITEBOARD
            )
        ).getOrThrow()
        DesktopSources.open(f)!!.use {
            assertEquals(2160f, it.pageDim(0).width, 0.5f)
            assertEquals(1215f, it.pageDim(0).height, 0.5f)
        }
    }

    /** A "new" action that silently replaced last week's notes would be indefensible. */
    @Test
    fun `creating the same name twice never clobbers the first`() {
        val dir = temp.newFolder()
        val spec = BlankDocumentFactory.Spec(name = "Notes")
        val first = BlankDocumentFactory.create(dir, spec).getOrThrow()
        val second = BlankDocumentFactory.create(dir, spec).getOrThrow()

        assertTrue(first.isFile)
        assertTrue(second.isFile)
        assertEquals("Notes.pdf", first.name)
        assertEquals("Notes (2).pdf", second.name)
    }

    @Test
    fun `a name that is a path cannot escape the folder it was made in`() {
        val dir = temp.newFolder()
        val f = BlankDocumentFactory.create(
            dir,
            BlankDocumentFactory.Spec(name = """..\..\Windows\notes""")
        ).getOrThrow()
        assertEquals(dir.absolutePath, f.parentFile.absolutePath)
    }

    /**
     * The menu on each side names the same patterns.
     *
     * The two builds declare their own menus - one is Compose for Android, the other Compose for
     * desktop - but a document stores the *name*, so a sheet of Cornell paper made on the tablet
     * has to be Cornell paper here. This is what catches a rename on one side only.
     */
    @Test
    fun `every background maps to a pattern the shared geometry knows`() {
        for (background in BlankDocumentFactory.Background.entries) {
            assertEquals(
                "${background.name} should map to the pattern of the same name",
                background.pattern,
                PaperPattern.patternOf(background.name)
            )
        }
    }
}
