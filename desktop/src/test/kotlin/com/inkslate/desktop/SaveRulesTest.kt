package com.inkslate.desktop

import com.inkslate.core.CopyNaming
import com.inkslate.core.InkDocument
import com.inkslate.core.InkFormat
import com.inkslate.core.InkPoint
import com.inkslate.core.SaveMode
import com.inkslate.core.SaveSettings
import com.inkslate.core.Stroke
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Saving under the file's own rules.
 *
 * Overwriting is the one action in this app that can destroy something it did not create, so the
 * cases below are mostly about that: that a backup is taken first, that the overwrite is refused
 * outright when the backup cannot be made, and that a copy never lands on top of an earlier one.
 */
class SaveRulesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun blankPdf(dir: File = temp.newFolder(), name: String = "Sheet"): File =
        BlankDocumentFactory.create(dir, BlankDocumentFactory.Spec(name = name)).getOrThrow()

    private fun inkWith(n: Int = 3): InkDocument = InkDocument
        .create("Sheet.pdf", "pdf", 1, 0L, "")
        .withPage(
            0,
            (1..n).map { i ->
                Stroke(
                    id = "s$i",
                    kind = Stroke.Kind.FREEHAND,
                    color = 0xFF112233.toInt(),
                    baseWidth = 2f,
                    points = listOf(
                        InkPoint(100f + i, 100f, 2f),
                        InkPoint(200f + i, 160f, 2f)
                    )
                )
            },
            "test"
        )

    private fun annotationCount(pdf: File): Int = Loader.loadPDF(pdf).use { doc ->
        val annots = doc.getPage(0).cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray
        annots?.size() ?: 0
    }

    // ---- the two formats -----------------------------------------------------

    @Test
    fun `annotations mode writes one annotation per mark`() {
        val source = blankPdf()
        val result = DocumentExport.save(
            source, inkWith(3),
            SaveSettings(mode = SaveMode.OVERWRITE, inkFormat = InkFormat.ANNOTATIONS)
        )
        assertTrue(result is SaveResult.Written)
        assertEquals(3, annotationCount(source))
    }

    @Test
    fun `flattening writes no annotations at all`() {
        val source = blankPdf()
        DocumentExport.save(
            source, inkWith(3),
            SaveSettings(mode = SaveMode.OVERWRITE, inkFormat = InkFormat.FLATTENED)
        )
        assertEquals(0, annotationCount(source))
    }

    /**
     * Saving twice must not stack a second copy of every mark on the first.
     *
     * This is what the "remove our annotations first" pass is for, and getting it wrong is
     * invisible until a document has been saved a few times and every stroke is four strokes deep.
     */
    @Test
    fun `saving twice does not double the annotations`() {
        val source = blankPdf()
        val settings = SaveSettings(mode = SaveMode.OVERWRITE, inkFormat = InkFormat.ANNOTATIONS)
        DocumentExport.save(source, inkWith(3), settings)
        DocumentExport.save(source, inkWith(3), settings)
        assertEquals(3, annotationCount(source))
    }

    /** Either way, the handwriting rides along inside the file so it can be edited again. */
    @Test
    fun `the editable copy is embedded whichever format was written`() {
        for (format in InkFormat.entries) {
            val source = blankPdf(name = format.name)
            DocumentExport.save(
                source, inkWith(2), SaveSettings(mode = SaveMode.OVERWRITE, inkFormat = format)
            )
            val read = DesktopEmbedder.read(source)
            assertNotNull("${format.name} should carry its handwriting", read)
            assertEquals(2, read!!.totalStrokes)
        }
    }

    // ---- copies --------------------------------------------------------------

    @Test
    fun `a copy leaves the original untouched`() {
        val dir = temp.newFolder()
        val source = blankPdf(dir)
        val before = source.readBytes().size

        val result = DocumentExport.save(source, inkWith(), SaveSettings(mode = SaveMode.COPY))
        val written = result as SaveResult.Written

        assertTrue(written.wasCopy)
        assertTrue(written.target.absolutePath != source.absolutePath)
        assertEquals("the original should not have been written", before, source.readBytes().size)
        assertEquals(0, annotationCount(source))
    }

    @Test
    fun `a second copy is named beside the first rather than over it`() {
        val dir = temp.newFolder()
        val source = blankPdf(dir)
        val settings = SaveSettings(mode = SaveMode.COPY, copyNaming = CopyNaming.SUFFIX)

        val first = (DocumentExport.save(source, inkWith(), settings) as SaveResult.Written).target
        val second = (DocumentExport.save(source, inkWith(), settings) as SaveResult.Written).target

        assertTrue(first.isFile && second.isFile)
        assertTrue(first.absolutePath != second.absolutePath)
    }

    // ---- backups -------------------------------------------------------------

    @Test
    fun `overwriting takes a backup first`() {
        val source = blankPdf()
        val result = DocumentExport.save(
            source, inkWith(), SaveSettings(mode = SaveMode.OVERWRITE, backupOnOverwrite = true)
        ) as SaveResult.Written

        assertNotNull("a backup should have been kept", result.backup)
        assertTrue(result.backup!!.isFile)
        assertTrue(DocumentExport.backupsFor(source).isNotEmpty())
    }

    @Test
    fun `backups can be turned off`() {
        val source = blankPdf()
        val result = DocumentExport.save(
            source, inkWith(), SaveSettings(mode = SaveMode.OVERWRITE, backupOnOverwrite = false)
        ) as SaveResult.Written
        assertNull(result.backup)
    }

    /**
     * The rule that matters most: a failed backup refuses the overwrite.
     *
     * Losing the only copy of a blank assignment template is not recoverable, and proceeding
     * quietly would be the worst option available.
     */
    @Test
    fun `an overwrite is refused when the backup cannot be made`() {
        val dir = temp.newFolder()
        val source = blankPdf(dir)
        val before = source.readBytes().size
        // A plain file where the backup folder needs to be, so creating it cannot succeed.
        File(dir, DocumentExport.BACKUP_DIR).writeText("in the way")

        val result = DocumentExport.save(
            source, inkWith(), SaveSettings(mode = SaveMode.OVERWRITE, backupOnOverwrite = true)
        )

        assertTrue(result is SaveResult.Failed)
        assertEquals("the original must be untouched", before, source.readBytes().size)
    }

    @Test
    fun `restoring a backup puts the earlier file back`() {
        val source = blankPdf()
        val original = source.readBytes().size

        DocumentExport.save(
            source, inkWith(6), SaveSettings(mode = SaveMode.OVERWRITE, backupOnOverwrite = true)
        )
        assertTrue("the save should have changed the file", source.readBytes().size != original)

        val backup = DocumentExport.backupsFor(source).last()
        DocumentExport.restoreBackup(backup, source).getOrThrow()
        assertEquals(original, source.readBytes().size)
    }

    @Test
    fun `nothing drawn means nothing written`() {
        val source = blankPdf()
        val empty = InkDocument.create("Sheet.pdf", "pdf", 1, 0L, "")
        assertTrue(DocumentExport.save(source, empty, SaveSettings()) is SaveResult.NothingToDo)
    }

    // ---- the rules themselves ------------------------------------------------

    @Test
    fun `a per-file override layers on the global defaults`() {
        val prefs = SavePrefs()
        prefs.clearAllOverrides()
        prefs.setGlobal(SaveSettings(mode = SaveMode.COPY, autosaveSeconds = 30))

        val path = "C:/work/homework.pdf"
        prefs.setOverride(path, com.inkslate.core.FileOverride(mode = SaveMode.OVERWRITE))

        val effective = prefs.effectiveFor(path)
        assertEquals("the override wins", SaveMode.OVERWRITE, effective.mode)
        assertEquals("everything else is still inherited", 30, effective.autosaveSeconds)
        assertEquals(SaveMode.COPY, prefs.effectiveFor("C:/work/other.pdf").mode)

        prefs.clearOverride(path)
        assertFalse(prefs.hasOverride(path))
        assertEquals(SaveMode.COPY, prefs.effectiveFor(path).mode)
    }

    @Test
    fun `an override follows its file when it is renamed`() {
        val prefs = SavePrefs()
        prefs.clearAllOverrides()
        prefs.setOverride("C:/a.pdf", com.inkslate.core.FileOverride(mode = SaveMode.OVERWRITE))
        prefs.moveOverride("C:/a.pdf", "C:/b.pdf")

        assertFalse(prefs.hasOverride("C:/a.pdf"))
        assertEquals(SaveMode.OVERWRITE, prefs.effectiveFor("C:/b.pdf").mode)
        prefs.clearAllOverrides()
    }
}
