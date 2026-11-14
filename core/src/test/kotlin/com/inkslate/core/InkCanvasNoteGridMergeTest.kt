package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saving or syncing an older, larger canvas must not undo an intentional Note Grid fit. */
class InkCanvasNoteGridMergeTest {
    private fun canvas(grid: NoteGrid) = InkCanvas.startingAt(
        600f, 800f, background = "NOTE_GRID", ownPaper = true
    ).copy(noteGrid = grid)

    private fun document(canvas: InkCanvas) = InkDocument(
        docId = "same-notebook", source = InkDocument.SourceRef(name = "notes.pdf", kind = "pdf"),
        canvas = canvas
    )

    @Test fun `newer compact grid survives a stale larger canvas in either merge direction`() {
        val largeGrid = NoteGrid(rows = listOf(NoteGridRow(500f, listOf(720f, 360f))), modifiedAt = 10L)
        val compactGrid = largeGrid.copy(rows = listOf(NoteGridRow(220f, listOf(360f, 360f))), modifiedAt = 20L)
        val stale = canvas(largeGrid).copy(right = 1400f, bottom = 1800f)
        val compact = canvas(compactGrid)
        for (merged in listOf(compact.mergeWith(stale), stale.mergeWith(compact))) {
            assertEquals(compactGrid, merged.noteGrid)
            // The underlying canvas retains its safe union; only the editable cell layout shrinks.
            assertEquals(1400f, merged.right, .001f)
            assertEquals(1800f, merged.bottom, .001f)
        }
    }

    @Test fun `grid clock and compact dimensions survive document serialization and repeated sync`() {
        val compactGrid = NoteGrid(rows = listOf(NoteGridRow(220f, listOf(360f, 360f))), modifiedAt = 50L)
        val latest = document(canvas(compactGrid))
        val stale = document(canvas(compactGrid.copy(
            rows = listOf(NoteGridRow(700f, listOf(900f, 600f))), modifiedAt = 25L
        )))
        var reopened = requireNotNull(InkDocument.parse(latest.serialize()))
        repeat(3) {
            reopened = requireNotNull(InkDocument.parse(reopened.mergeWith(stale).serialize()))
            assertEquals(compactGrid, reopened.canvas?.noteGrid)
            assertEquals(compactGrid, stale.mergeWith(reopened).canvas?.noteGrid)
        }
        assertTrue(reopened.serialize().contains("\"modifiedAt\":50"))
    }

    @Test fun `legacy documents without grid clocks remain readable and retain union merging`() {
        val legacy = InkDocument.parse("""{
            "docId":"legacy",
            "source":{"name":"notes.pdf","kind":"pdf"},
            "canvas":{"background":"NOTE_GRID","noteGrid":{
                "rows":[{"height":300,"columns":[480,260]}]
            }}
        }""")
        assertNotNull(legacy)
        val oldCanvas = requireNotNull(legacy?.canvas)
        assertEquals(0L, oldCanvas.noteGrid?.modifiedAt)
        val other = canvas(NoteGrid(rows = listOf(
            NoteGridRow(220f, listOf(360f, 500f, 360f)), NoteGridRow(240f, listOf(400f))
        )))
        val merged = requireNotNull(oldCanvas.mergeWith(other).noteGrid)
        assertEquals(0L, merged.modifiedAt)
        assertEquals(listOf(480f, 500f, 360f), merged.rows[0].columns)
        assertEquals(300f, merged.rows[0].height, .001f)
        assertEquals(2, merged.rows.size)
        assertEquals(merged, other.mergeWith(oldCanvas).noteGrid)
    }

    @Test fun `first clocked compact layout replaces a legacy expanded layout`() {
        val legacy = canvas(NoteGrid(rows = listOf(NoteGridRow(900f, listOf(1200f)))))
        val compact = canvas(NoteGrid(rows = listOf(NoteGridRow(220f, listOf(360f))), modifiedAt = 1L))
        assertEquals(compact.noteGrid, compact.mergeWith(legacy).noteGrid)
        assertEquals(compact.noteGrid, legacy.mergeWith(compact).noteGrid)
    }

    @Test fun `simultaneous structural choices converge deterministically through canvas merge`() {
        val alternatives = listOf(
            NoteGrid(rows = listOf(NoteGridRow(220f, listOf(360f))), modifiedAt = 40L),
            NoteGrid(rows = listOf(NoteGridRow(300f, listOf(400f, 350f))), modifiedAt = 40L),
            NoteGrid(rows = listOf(NoteGridRow(250f, listOf(450f))), contentGap = 32f, modifiedAt = 40L)
        ).map(::canvas)
        for (a in alternatives) for (b in alternatives) {
            val merged = a.mergeWith(b)
            assertEquals(merged, b.mergeWith(a))
            assertTrue(merged.noteGrid == a.noteGrid || merged.noteGrid == b.noteGrid)
            assertEquals(merged, merged.mergeWith(a).mergeWith(b))
        }
    }

    @Test fun `grid layout clock is independent of paper appearance settings`() {
        val oldGrid = NoteGrid(rows = listOf(NoteGridRow(700f, listOf(900f))), modifiedAt = 5L)
        val smaller = oldGrid.copy(rows = listOf(NoteGridRow(220f, listOf(360f))), modifiedAt = 10L)
        val oldLayoutNewLook = canvas(oldGrid).copy(paperColor = 0xFFEEEEEE.toInt(), settingsUtc = 100L)
        val newLayoutOldLook = canvas(smaller).copy(settingsUtc = 1L)
        val merged = oldLayoutNewLook.mergeWith(newLayoutOldLook)
        assertEquals(smaller, merged.noteGrid)
        assertEquals(oldLayoutNewLook.paperColor, merged.paperColor)
        assertEquals(100L, merged.settingsUtc)
    }
}
