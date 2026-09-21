package com.inkslate.core.peer

import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How often a document is written back while it is being drawn on.
 *
 * A save is not free, and on a heavily marked document it is not cheap: a three page problem set
 * with two and a half thousand marks took over a second on a laptop. Writing one of those every
 * couple of seconds - which is fine for a document that writes in fifty milliseconds - left the
 * pen stuttering through every stroke, and read as the app freezing as soon as anything was drawn.
 */
class WriteBackoffTest {

    private var clock = 10_000L

    private fun sync() = DocumentSync(
        me = "laptop", docId = "doc", fileName = "work.pdf",
        disk = FileRevision(1, "a"), diskInk = doc(0),
        lastKnownWrite = null,
        send = { _, _ -> },
        log = {}
    )

    private fun doc(marks: Int): InkDocument {
        var d = InkDocument.create("work.pdf", "pdf", 1, 0L, "")
        if (marks > 0) {
            d = d.withPage(
                0,
                (0 until marks).map {
                    Stroke(
                        id = "laptop-$it", kind = Stroke.Kind.FREEHAND, color = 0, baseWidth = 1f,
                        points = listOf(InkPoint(it.toFloat(), 1f, 1f), InkPoint(2f, 2f, 1f))
                    )
                },
                "test"
            )
        }
        return d
    }

    /** Write, and tell the session what it cost. */
    private fun completeWrite(s: DocumentSync, marks: Int, tookMs: Long) {
        clock += tookMs
        s.written(FileRevision(2, "b$marks"), doc(marks), clock, tookMs)
    }

    @Test
    fun `a document that writes quickly is written often`() {
        val s = sync()
        assertEquals(DocumentSync.MIN_WRITE_INTERVAL_MS, s.writeInterval())
        completeWrite(s, 1, tookMs = 40)
        assertEquals(
            "a cheap write should not slow the next one down",
            DocumentSync.MIN_WRITE_INTERVAL_MS, s.writeInterval()
        )
        assertEquals(DocumentSync.IDLE_BEFORE_WRITE_MS, s.idleNeededMs())
    }

    @Test
    fun `a document that takes a second to write is left alone for longer`() {
        val s = sync()
        completeWrite(s, 2500, tookMs = 1_500)
        // Eight times the cost, so writing takes at most an eighth of the time.
        assertEquals(12_000L, s.writeInterval())
        // And it waits for a real pause rather than the gap between two strokes.
        assertEquals(3_000L, s.idleNeededMs())
    }

    @Test
    fun `however slow the document, it is still written and still waits only so long`() {
        val s = sync()
        completeWrite(s, 20_000, tookMs = 30_000)
        assertEquals(DocumentSync.MAX_WRITE_INTERVAL_MS, s.writeInterval())
        assertEquals(DocumentSync.MAX_IDLE_BEFORE_WRITE_MS, s.idleNeededMs())
    }

    @Test
    fun `an expensive document is not written again straight away`() {
        val s = sync()
        completeWrite(s, 2500, tookMs = 1_500)
        // A mark made since that write, so there is something to write.
        val marked = doc(2501)
        // Two seconds later - fine for a cheap document, too soon for this one.
        assertFalse(s.tick(clock + 2_500, marked, idle = true))
        // Thirteen seconds later it is written.
        assertTrue(s.tick(clock + 13_000, marked, idle = true))
    }

    @Test
    fun `asking for a save writes it now, whatever it costs`() {
        val s = sync()
        completeWrite(s, 2500, tookMs = 1_500)
        s.requestSave()
        assertTrue(
            "a save somebody asked for is not something to put off",
            s.tick(clock + 100, doc(2501), idle = false)
        )
    }
}
