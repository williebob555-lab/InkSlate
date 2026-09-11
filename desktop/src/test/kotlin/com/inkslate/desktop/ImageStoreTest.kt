package com.inkslate.desktop

import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.SaveMode
import com.inkslate.core.SaveSettings
import com.inkslate.core.Stroke
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Pictures pasted into a document: where they are kept, and whether they come out again.
 *
 * They live beside the document as ordinary files rather than inside it, so that a sync moves an
 * image once instead of shipping it again with every stroke. That makes two things worth pinning
 * down: that the folder is the one the tablet also looks in, and that an export actually embeds
 * the picture rather than leaving an empty rectangle where it was.
 */
class ImageStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun swatch(w: Int = 40, h: Int = 30, colour: Color = Color.RED): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            paint = colour
            fillRect(0, 0, w, h)
            dispose()
        }
        return img
    }

    private fun pictureFile(dir: File, name: String = "pic.png"): File {
        val f = File(dir, name)
        ImageIO.write(swatch(), "png", f)
        return f
    }

    @Test
    fun `a picture is stored beside its document and comes back`() {
        val dir = temp.newFolder()
        val doc = File(dir, "Notes.pdf").apply { writeText("x") }
        val store = ImageStore(doc)

        val id = store.putFile(pictureFile(dir))
        assertNotNull(id)
        assertTrue(store.exists(id!!))
        assertNotNull(store.load(id))

        // The folder name is the tablet's, so a synced document finds its own pictures.
        assertEquals("Notes.pdf.inkassets", store.dirFor().name)
        assertEquals(dir.absolutePath, store.dirFor().parentFile.absolutePath)
    }

    @Test
    fun `a missing picture is null rather than an error`() {
        val doc = temp.newFile("Notes.pdf")
        assertNull(ImageStore(doc).load("nothing-here"))
        assertFalse(ImageStore(doc).exists("nothing-here"))
    }

    /**
     * Pruning takes the complete set of ids a document still references.
     *
     * Called with a partial view it would delete a picture that a page nobody has scrolled to
     * still uses, so the contract matters more than the code.
     */
    @Test
    fun `pruning keeps what is referenced and drops what is not`() {
        val dir = temp.newFolder()
        val doc = File(dir, "Notes.pdf").apply { writeText("x") }
        val store = ImageStore(doc)

        val kept = store.putFile(pictureFile(dir, "a.png"))!!
        val dropped = store.putFile(pictureFile(dir, "b.png"))!!

        store.prune(setOf(kept))
        assertTrue(store.exists(kept))
        assertFalse(store.exists(dropped))
    }

    // ---- getting back out again ----------------------------------------------

    /**
     * Every picture embedded for the first page, wherever it ended up.
     *
     * Flattening draws into the page's own content, so the XObject lands in the page's resources.
     * An annotation draws into its appearance stream, so it lands in *that* stream's resources
     * instead - the picture is embedded either way, and a check that only looked at the page
     * would report a missing image for a document that has one.
     */
    private fun embeddedImages(pdf: File): List<PDImageXObject> =
        Loader.loadPDF(pdf).use { doc ->
            val page = doc.getPage(0)
            val found = mutableListOf<PDImageXObject>()

            fun collect(resources: org.apache.pdfbox.pdmodel.PDResources?) {
                resources ?: return
                for (name in resources.xObjectNames) {
                    (runCatching { resources.getXObject(name) }.getOrNull() as? PDImageXObject)
                        ?.let { found.add(it) }
                }
            }

            collect(page.resources)
            for (annot in runCatching { page.annotations }.getOrDefault(emptyList())) {
                collect(annot.normalAppearanceStream?.resources)
            }
            found
        }

    @Test
    fun `an exported page carries the picture rather than a gap`() {
        val dir = temp.newFolder()
        val source = BlankDocumentFactory.create(
            dir, BlankDocumentFactory.Spec(name = "Sheet")
        ).getOrThrow()
        val store = ImageStore(source)
        val id = store.putFile(pictureFile(dir))!!

        val ink = InkDocument.create("Sheet.pdf", "pdf", 1, 0L, "").withPage(
            0,
            listOf(
                Stroke(
                    id = "img",
                    kind = Stroke.Kind.IMAGE,
                    color = 0xFF000000.toInt(),
                    baseWidth = 1f,
                    imageId = id,
                    points = listOf(InkPoint(100f, 100f, 1f), InkPoint(300f, 250f, 1f))
                )
            ),
            "test"
        )

        assertTrue(embeddedImages(source).isEmpty())
        DocumentExport.save(
            source, ink, SaveSettings(mode = SaveMode.OVERWRITE, backupOnOverwrite = false)
        )
        assertEquals("the picture should be embedded", 1, embeddedImages(source).size)
    }

    /** Either format carries the picture; they differ only in where it is attached. */
    @Test
    fun `both ink formats embed the picture`() {
        for (format in com.inkslate.core.InkFormat.entries) {
            val dir = temp.newFolder()
            val source = BlankDocumentFactory.create(
                dir, BlankDocumentFactory.Spec(name = format.name)
            ).getOrThrow()
            val id = ImageStore(source).putFile(pictureFile(dir))!!
            val ink = InkDocument.create("${format.name}.pdf", "pdf", 1, 0L, "").withPage(
                0,
                listOf(
                    Stroke(
                        id = "img",
                        kind = Stroke.Kind.IMAGE,
                        color = 0xFF000000.toInt(),
                        baseWidth = 1f,
                        imageId = id,
                        points = listOf(InkPoint(100f, 100f, 1f), InkPoint(300f, 250f, 1f))
                    )
                ),
                "test"
            )
            DocumentExport.save(
                source, ink,
                SaveSettings(
                    mode = SaveMode.OVERWRITE,
                    inkFormat = format,
                    backupOnOverwrite = false
                )
            )
            assertEquals(
                "${format.name} should embed the picture",
                1,
                embeddedImages(source).size
            )
        }
    }

    /**
     * A cropped picture is trimmed before it is embedded, not clipped afterwards.
     *
     * On a photograph cropped down to one diagram, the discarded edges are most of its weight -
     * and carrying them into every exported copy would be paying for them forever.
     */
    @Test
    fun `a cropped picture embeds only the part that is kept`() {
        val dir = temp.newFolder()
        val source = BlankDocumentFactory.create(
            dir, BlankDocumentFactory.Spec(name = "Sheet")
        ).getOrThrow()
        val store = ImageStore(source)
        val big = File(dir, "big.png")
        ImageIO.write(swatch(400, 300), "png", big)
        val id = store.putFile(big)!!

        val cropped = Stroke(
            id = "img",
            kind = Stroke.Kind.IMAGE,
            color = 0xFF000000.toInt(),
            baseWidth = 1f,
            imageId = id,
            points = listOf(InkPoint(50f, 50f, 1f), InkPoint(250f, 200f, 1f)),
            cropLeft = 0.25f, cropTop = 0.25f, cropRight = 0.75f, cropBottom = 0.75f
        )
        assertTrue(cropped.isCropped)

        val ink = InkDocument.create("Sheet.pdf", "pdf", 1, 0L, "")
            .withPage(0, listOf(cropped), "test")
        DocumentExport.save(
            source, ink, SaveSettings(mode = SaveMode.OVERWRITE, backupOnOverwrite = false)
        )

        val embedded = embeddedImages(source).single()
        // Half of each side was cropped away, so the embedded picture is half the size.
        assertEquals(200, embedded.width)
        assertEquals(150, embedded.height)
    }
}
