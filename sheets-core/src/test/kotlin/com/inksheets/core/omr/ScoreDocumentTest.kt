package com.inksheets.core.omr

import com.inksheets.core.Part
import com.inksheets.core.Song
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ScoreDocumentTest {
    private val score = Score(emptyList(), 1)
    private fun part(id: String, group: String? = null) = ScorePart(
        id, listOf(score), alternative = group?.let { ScoreAlternative(it, id, "easy") }
    )

    @Test fun `single score adapter preserves the score and selects it`() {
        val document = ScoreDocument.fromScore(score)
        assertEquals(listOf(score), document.selectedParts().single().staves)
    }

    @Test fun `song adapter retains read parts in library order and normalizes seats`() {
        val song = Song("song", "Song", parts = listOf(
            Part(id = "trumpet-id", file = "a", instrument = "trumpet:2", label = "Trumpet II"),
            Part(id = "unread", file = "b", instrument = "flute"),
            Part(id = "piccolo-id", file = "c", instrument = "piccolo", chair = 1),
            Part(id = "unknown-id", file = "d", instrument = "unknown:4", chair = 3)
        ))
        val document = ScoreDocument.fromSong(song, mapOf(
            "unknown-id" to listOf(score), "piccolo-id" to listOf(score),
            "trumpet-id" to listOf(score, score), "other-song" to listOf(score)
        ))
        assertEquals(listOf("trumpet-id", "piccolo-id", "unknown-id"), document.parts.map { it.id })
        assertEquals(ScorePart("trumpet-id", listOf(score, score), "trumpet", 2, "Trumpet II", 2,
            provenance = listOf(ScoreProvenance("file", "a"))), document.parts[0])
        assertEquals(-12, document.parts[1].transposition)
        assertEquals(3, document.parts[2].chair)
        assertEquals(0, document.parts[2].transposition)
        assertTrue(ScoreDocument.fromSong(song, emptyMap()).parts.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            ScoreDocument.fromSong(song, mapOf("trumpet-id" to emptyList()))
        }
    }

    @Test fun `defaults include normal parts and one alternative per group`() {
        val document = ScoreDocument(listOf(part("piano"), part("easy", "melody"),
            part("hard", "melody"), part("low", "bass"), part("high", "bass")))
        assertEquals(listOf("piano", "easy", "low"), document.selectedParts().map { it.id })
        val selection = ScoreSelection(emptySet(), mapOf("melody" to "hard", "bass" to "high"))
        assertEquals(listOf("hard", "high"), document.selectedParts(selection).map { it.id })
        assertThrows(IllegalArgumentException::class.java) {
            document.selectedParts(ScoreSelection(emptySet(), mapOf("melody" to "hard")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            document.selectedParts(ScoreSelection(emptySet(), mapOf("melody" to "low", "bass" to "high")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            document.selectedParts(ScoreSelection(setOf("easy"), mapOf("melody" to "hard", "bass" to "high")))
        }
    }

    @Test fun `serializes metadata and multi staff parts`() {
        val document = ScoreDocument(listOf(ScorePart("keys", listOf(score, score), "piano", 2,
            "Keyboard", -2, ScoreAlternative("keys-group", "simplified", "beginner"))))
        assertEquals(document, Json.decodeFromString<ScoreDocument>(Json.encodeToString(document)))
        assertEquals(document.defaultSelection(), Json.decodeFromString<ScoreSelection>(Json.encodeToString(document.defaultSelection())))
    }

    @Test fun `rejects missing staves and duplicate part ids`() {
        assertThrows(IllegalArgumentException::class.java) { ScorePart("empty", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ScoreDocument(listOf(part("same"), part("same"))) }
    }

    @Test fun `written measures receive stable canonical bars and multi bar alignments`() {
        val first = Measure(8, 0, 0, Box(0, 0, 10, 10), 5f, Clef.TREBLE, Key(0), TimeSig(4, 4),
            listOf(Rest(Duration(1), 0f)), bars = 2)
        val second = first.copy(number = 10, bars = 1)
        val document = ScoreDocument.fromScore(Score(listOf(first, second), 1), "flute")
        assertEquals(listOf("written-8", "written-9", "written-10"), document.bars.map { it.id })
        assertEquals(listOf("written-8", "written-9"), document.alignmentFor("flute", 0, 0)?.canonicalBarIds)
        assertEquals(.8f, document.alignmentFor("flute", 0, 1)?.confidence)
    }

    @Test fun `alignment and provenance reject broken references and ranges`() {
        assertThrows(IllegalArgumentException::class.java) { ScoreProvenance("video", "x", startMs = 20, endMs = 10) }
        assertThrows(IllegalArgumentException::class.java) {
            ScoreDocument(listOf(part("p")), listOf(CanonicalBar("b", 1)),
                listOf(MeasureAlignment("missing", 0, 0, listOf("b"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeasureAlignment("p", 0, 0, listOf("b"), confidence = 1.1f)
        }
    }
}
