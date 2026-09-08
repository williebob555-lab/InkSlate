package com.inkslate

import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import com.inkslate.data.InkDocument
import com.inkslate.data.InkJournal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Which working copy belongs to which document.
 *
 * The working store is keyed by path, and a path is not an identity: delete a document and make
 * a new one with the same name and it lands in the same place. So a stored copy has to prove it
 * belongs to the document now at that path before it is merged in.
 *
 * Both ways of getting this wrong are silent, which is why it is pinned down here:
 *
 *  - Too lax, and a new document opens wearing a deleted one's handwriting.
 *  - Too strict, and a document opens **blank**. That one is worse than it sounds. A document
 *    this app last saved is deliberately not parsed when it is reopened - the handwriting is
 *    taken from the working copy instead, which is faster and never behind - so refusing the
 *    working copy leaves nothing at all to show.
 *
 * The second is what happened: saving rewrites the document, which changes its size and
 * timestamp, and the note recording which file the working copy was written against was never
 * re-stamped afterwards. Every save therefore invalidated its own evidence, and the next open
 * showed an empty page with the work sitting intact in the history behind a recovery prompt.
 */
class WorkingCopyOwnershipTest {

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

    private fun inkWith(vararg ids: String): InkDocument =
        InkDocument.create("homework.pdf", "pdf", 1, 100L, "fp")
            .withPage(0, ids.map(::stroke), "test")

    private fun journal() = InkJournal(temp.newFolder())

    /** What saving does to the file: the handwriting goes in, so the bytes change. */
    private fun rewriteAsSaveWould(document: File) {
        document.writeText("the document, now carrying an embedded payload")
        document.setLastModified(document.lastModified() + 5_000)
    }

    private fun document(): File =
        temp.newFile("homework.pdf").apply { writeText("the document") }

    @Test
    fun `a working copy is claimed by the document it was written for`() {
        val journal = journal()
        val file = document()
        val ink = inkWith("dev-1", "dev-2")
        journal.save(file, ink)

        assertTrue(journal.belongsTo(file, embeddedDocId = null, working = ink))
    }

    @Test
    fun `a different document that took the same name is refused`() {
        val journal = journal()
        val file = document()
        val ink = inkWith("dev-1", "dev-2")
        journal.save(file, ink)

        // the original is deleted and something else is created with the same name
        file.delete()
        file.writeText("a completely different assignment")
        file.setLastModified(file.lastModified() + 60_000)

        assertFalse(journal.belongsTo(file, embeddedDocId = null, working = ink))
    }

    @Test
    fun `re-stamping ownership after a save keeps the working copy claimed`() {
        val journal = journal()
        val file = document()
        val ink = inkWith("dev-1", "dev-2", "dev-3")
        journal.save(file, ink)

        rewriteAsSaveWould(file)
        // Without this line the document reopens blank: the note still describes the file as it
        // was before its own save, so nothing can vouch for the handwriting.
        assertFalse(journal.belongsTo(file, embeddedDocId = null, working = ink))
        journal.noteOwnership(file, ink)

        assertTrue(journal.belongsTo(file, embeddedDocId = null, working = ink))
    }

    @Test
    fun `our own last output claims its working copy even with a stale note`() {
        val journal = journal()
        val file = document()
        val ink = inkWith("dev-1", "dev-2", "dev-3")
        journal.save(file, ink)

        // A store written by a build that did not re-stamp: the note can never match again, and
        // no future save will fix it retrospectively. Knowing the file is still this app's own
        // last output is what rescues it.
        rewriteAsSaveWould(file)

        assertFalse(journal.belongsTo(file, embeddedDocId = null, working = ink))
        assertTrue(
            journal.belongsTo(file, embeddedDocId = null, working = ink, isOurLastOutput = true)
        )
    }

    @Test
    fun `the document's own id settles it whatever the file has been through`() {
        val journal = journal()
        val file = document()
        val ink = inkWith("dev-1")
        journal.save(file, ink)
        rewriteAsSaveWould(file)

        assertTrue(journal.belongsTo(file, embeddedDocId = ink.docId, working = ink))
        assertFalse(journal.belongsTo(file, embeddedDocId = "some-other-document", working = ink))
    }

    @Test
    fun `an id in the document outranks the file being our own output`() {
        val journal = journal()
        val file = document()
        val ink = inkWith("dev-1")
        journal.save(file, ink)

        // The document says whose handwriting it carries, and it does not say this. Being our
        // own last output cannot override that - it is weaker evidence, not stronger.
        assertFalse(
            journal.belongsTo(
                file,
                embeddedDocId = "some-other-document",
                working = ink,
                isOurLastOutput = true
            )
        )
    }
}
