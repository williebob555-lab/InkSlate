package com.inkslate

import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import com.inkslate.data.InkDocument
import com.inkslate.data.InkJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The check that stops the app saving a document it has just loaded badly.
 *
 * Autosave is what makes annotating pleasant and it is also what makes a load bug permanent: a
 * document that opens emptier than it should be gets that state written back within seconds, and
 * closing it finishes the job. The editor freezes its automatic writes when either of two signals
 * fires, and this pins down the cheaper of them - the store's own record of how much handwriting
 * a document had, compared against what actually arrived.
 *
 * The rule it has to get right in both directions:
 *  - Fire when handwriting has gone missing, or the freeze never happens and work is lost.
 *  - Stay quiet when handwriting was deliberately erased, or every ordinary rub-out and sync
 *    would freeze saving and the warning would be trained away.
 */
class TruncationGuardTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun stroke(id: String) = Stroke(
        id = id,
        kind = Stroke.Kind.FREEHAND,
        color = 0xFF000000.toInt(),
        baseWidth = 2f,
        pageIndex = 0,
        points = listOf(InkPoint(1f, 1f, 1f), InkPoint(2f, 2f, 1f))
    )

    private fun inkWith(count: Int): InkDocument =
        InkDocument.create("homework.pdf", "pdf", 1, 100L, "fp")
            .withPage(0, (1..count).map { stroke("dev-$it") }, "test")

    /**
     * The guard's own arithmetic, matching `DocumentRepo.looksTruncated`.
     *
     * Tested against the real journal rather than a stub, because the number it reads is written
     * by [InkJournal.save] and the two have to agree about where it lives - which is exactly the
     * kind of thing that silently stops working.
     */
    private fun looksTruncated(journal: InkJournal, file: File, live: InkDocument): Boolean {
        val recorded = journal.recordedStrokeCount(file)
        if (recorded < 3) return false
        return live.totalStrokes + live.deleted.size < recorded - 2
    }

    private fun document(): File =
        temp.newFile("homework.pdf").apply { writeText("the document") }

    @Test
    fun `a document that loads with nothing after twenty marks is caught`() {
        val journal = InkJournal(temp.newFolder())
        val file = document()
        journal.save(file, inkWith(20))

        assertEquals(20, journal.recordedStrokeCount(file))
        assertTrue(looksTruncated(journal, file, inkWith(0)))
    }

    @Test
    fun `a document that loads intact is not flagged`() {
        val journal = InkJournal(temp.newFolder())
        val file = document()
        val ink = inkWith(20)
        journal.save(file, ink)

        assertFalse(looksTruncated(journal, file, ink))
    }

    /**
     * Rubbing something out is not a loss, and must not be reported as one.
     *
     * Deleting leaves a tombstone - the permanent, syncable record that a stroke was removed - so
     * the marks plus the tombstones still account for everything the store counted.
     */
    @Test
    fun `strokes erased on purpose are explained by their tombstones`() {
        val journal = InkJournal(temp.newFolder())
        val file = document()
        val full = inkWith(20)
        journal.save(file, full)

        val trimmed = full.withPage(0, full.strokesOn(0).take(4), "test")
        assertEquals(4, trimmed.totalStrokes)
        assertEquals(16, trimmed.deleted.size)
        assertFalse(
            "16 tombstones account for the 16 missing marks",
            looksTruncated(journal, file, trimmed)
        )
    }

    /** A document the store knows nothing about cannot be judged, so it is left alone. */
    @Test
    fun `a document with no record is never flagged`() {
        val journal = InkJournal(temp.newFolder())
        assertFalse(looksTruncated(journal, document(), inkWith(0)))
    }

    /**
     * A margin of two, so the guard is about loss rather than about arithmetic.
     *
     * Counts can legitimately be a stroke or two apart across a merge, and freezing saving over
     * that would train the warning away long before it mattered.
     */
    @Test
    fun `a difference of one or two marks is tolerated`() {
        val journal = InkJournal(temp.newFolder())
        val file = document()
        journal.save(file, inkWith(20))

        assertFalse(looksTruncated(journal, file, inkWith(19)))
        assertFalse(looksTruncated(journal, file, inkWith(18)))
        assertTrue(looksTruncated(journal, file, inkWith(17)))
    }

    /** Below three recorded marks there is nothing to be confident about either way. */
    @Test
    fun `a nearly empty document is never flagged`() {
        val journal = InkJournal(temp.newFolder())
        val file = document()
        journal.save(file, inkWith(2))

        assertFalse(looksTruncated(journal, file, inkWith(0)))
    }
}
