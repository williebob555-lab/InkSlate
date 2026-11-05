package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiTest {
    @Test
    fun `writes a playable file, sounding pitch, bars kept in step`() {
        val c4 = Pitch(0, 4)
        val m1 = Measure(1, 0, 0, Box(0, 0, 0, 0), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
            listOf(Note(listOf(8), listOf(c4), Duration(4), 0f), Note(listOf(7), listOf(Pitch(1, 4)), Duration(2, 1), 0f)))
        // Read wrongly: five beats in a four-beat bar - cut to the bar.
        val m2 = m1.copy(number = 2, events = m1.events + Note(listOf(6), listOf(Pitch(2, 4)), Duration(4), 0f))
        val rest = m1.copy(number = 3, events = listOf(Rest(Duration(1), 0f)), bars = 4)
        val bytes = Midi.write(Score(listOf(m1, m2, rest), 1), bpm = 120.0, transpose = 2, program = 56)
        assertEquals("MThd", String(bytes, 0, 4))
        // Read back with Java Sound: notes sounding a tone below written (B-flat trumpet).
        val seq = javax.sound.midi.MidiSystem.getSequence(java.io.ByteArrayInputStream(bytes))
        val track = seq.tracks[0]
        val ons = (0 until track.size()).map { track[it] }.filter { (it.message as? javax.sound.midi.ShortMessage)?.let { m -> m.command == 0x90 && m.data2 > 0 } == true }
        assertEquals(listOf(58, 60, 58, 60), ons.map { (it.message as javax.sound.midi.ShortMessage).data1 })
        assertEquals(listOf(0L, 480L, 1920L, 2400L), ons.map { it.tick })
        // Two bars, then four bars' rest: 6 bars of 4/4.
        assertTrue(seq.tickLength >= 6 * 1920L)
    }
    @Test
    fun `type one has conductor and independent parts with aligned bars`() {
        val m = Measure(1, 0, 0, Box(0, 0, 0, 0), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
            listOf(Note(listOf(8), listOf(Pitch(0, 4)), Duration(4), 0f)))
        val score = Score(listOf(m, m.copy(number = 2, time = TimeSig(3, 4))), 1)
        val parts = listOf(Midi.Part(score, "Trumpet", 2, 56), Midi.Part(score, "Flute", 0, 73), Midi.Part(score, "Drums", percussion = true))
        val bytes = Midi.write(parts, 120.0)
        val input = java.io.ByteArrayInputStream(bytes)
        assertEquals(1, javax.sound.midi.MidiSystem.getMidiFileFormat(input).type)
        val seq = javax.sound.midi.MidiSystem.getSequence(java.io.ByteArrayInputStream(bytes))
        assertEquals(4, seq.tracks.size)
        fun events(track: javax.sound.midi.Track) = (0 until track.size()).map { track[it] }
        val conductor = events(seq.tracks[0])
        val signatures = conductor.filter { (it.message as? javax.sound.midi.MetaMessage)?.type == 0x58 }
        assertEquals(listOf(0L, 1920L), signatures.map { it.tick })
        assertEquals(1, conductor.count { (it.message as? javax.sound.midi.MetaMessage)?.type == 0x51 })
        assertEquals(3360L, seq.tickLength)
        for (i in 1..3) {
            val track = events(seq.tracks[i])
            val name = track.mapNotNull { it.message as? javax.sound.midi.MetaMessage }.first { it.type == 3 }
            assertEquals(parts[i - 1].name, String(name.data, Charsets.UTF_8))
            val ons = track.filter { (it.message as? javax.sound.midi.ShortMessage)?.command == 0x90 }
            assertEquals(listOf(0L, 1920L), ons.map { it.tick })
            assertEquals(listOf(if (i == 1) 58 else 60, if (i == 1) 58 else 60), ons.map { (it.message as javax.sound.midi.ShortMessage).data1 })
            assertEquals(if (i == 3) 9 else i - 1, (ons.first().message as javax.sound.midi.ShortMessage).channel)
            val programs = track.mapNotNull { it.message as? javax.sound.midi.ShortMessage }.filter { it.command == 0xC0 }
            assertEquals(if (i == 3) emptyList<Int>() else listOf(parts[i - 1].program), programs.map { it.data1 })
            assertEquals(3360L, track.last().tick)
            assertEquals(0x2F, (track.last().message as javax.sound.midi.MetaMessage).type)
        }
    }

    @Test
    fun `melodic channels skip drums and reject exhaustion`() {
        val score = Score(emptyList(), 1)
        val legacy = javax.sound.midi.MidiSystem.getSequence(java.io.ByteArrayInputStream(Midi.write(score, 120.0, program = 56)))
        val legacyEvents = (0 until legacy.tracks[0].size()).map { legacy.tracks[0][it].message }
        assertEquals(56, legacyEvents.filterIsInstance<javax.sound.midi.ShortMessage>().first().data1)
        assertEquals(0x2F, (legacyEvents.last() as javax.sound.midi.MetaMessage).type)
        val bytes = Midi.write((0 until 15).map { Midi.Part(score, program = it) }, 120.0)
        val seq = javax.sound.midi.MidiSystem.getSequence(java.io.ByteArrayInputStream(bytes))
        val channels = seq.tracks.drop(1).map { track ->
            (0 until track.size()).map { track[it].message }.filterIsInstance<javax.sound.midi.ShortMessage>().first().channel
        }
        assertEquals((0..15).filter { it != 9 }, channels)
        try {
            Midi.write(List(16) { Midi.Part(score, program = it) }, 120.0)
            throw AssertionError("Expected channel exhaustion to be rejected")
        } catch (_: IllegalArgumentException) { }
        val sections = Midi.write(List(30) { Midi.Part(score, name = "Clarinet ${it + 1}", program = 71) }, 120.0)
        val sectionSequence = javax.sound.midi.MidiSystem.getSequence(java.io.ByteArrayInputStream(sections))
        assertEquals(31, sectionSequence.tracks.size)
        assertEquals(setOf(0), sectionSequence.tracks.drop(1).map { track ->
            (0 until track.size()).map { track[it].message }.filterIsInstance<javax.sound.midi.ShortMessage>().first().channel
        }.toSet())
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            try {
                Midi.write(score, bad)
                throw AssertionError("Expected invalid tempo to be rejected")
            } catch (_: IllegalArgumentException) { }
            try {
                Midi.write(listOf(Midi.Part(score)), bad)
                throw AssertionError("Expected invalid ensemble tempo to be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }
    @Test
    fun `type one repeats and multi bar rests retain alignment`() {
        val bar = Measure(1, 0, 0, Box(0, 0, 0, 0), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
            listOf(Note(listOf(8), listOf(Pitch(0, 4)), Duration(4), 0f)), repeatStart = true)
        val ending = bar.copy(number = 2, repeatStart = false, repeatEnd = true)
        val rest = bar.copy(number = 3, repeatStart = false, events = listOf(Rest(Duration(1), 0f)), bars = 2)
        val score = Score(listOf(bar, ending, rest, bar.copy(number = 4, repeatStart = false)), 1)
        val seq = javax.sound.midi.MidiSystem.getSequence(java.io.ByteArrayInputStream(Midi.write(listOf(Midi.Part(score), Midi.Part(score)), 120.0)))
        for (track in seq.tracks.drop(1)) {
            val ons = (0 until track.size()).map { track[it] }.filter { (it.message as? javax.sound.midi.ShortMessage)?.command == 0x90 }
            assertEquals(listOf(0L, 1920L, 3840L, 5760L, 11520L), ons.map { it.tick })
            assertEquals(13440L, track.ticks())
        }
    }

    @Test
    fun `score document exports selected multi staff parts as named tracks`() {
        fun staff(step: Int, octave: Int = 4) = Score(listOf(Measure(1, 0, 0, Box(0, 0, 0, 0), 10f,
            Clef.TREBLE, Key(0), TimeSig(4, 4), listOf(Note(listOf(0), listOf(Pitch(step, octave)), Duration(4), 0f)))), 1)
        val document = ScoreDocument(listOf(
            ScorePart("piano", listOf(staff(0), staff(0, 3)), "piano", label = "Piano"),
            ScorePart("easy", listOf(staff(1)), "flute", alternative = ScoreAlternative("solo", "Easy")),
            ScorePart("hard", listOf(staff(2)), "clarinet", chair = 2, transposition = 2,
                alternative = ScoreAlternative("solo", "Advanced"))
        ))
        val selection = ScoreSelection(setOf("piano"), mapOf("solo" to "hard"))
        val sequence = javax.sound.midi.MidiSystem.getSequence(java.io.ByteArrayInputStream(Midi.write(document, 96.0, selection)))
        assertEquals(3, sequence.tracks.size)
        fun messages(track: javax.sound.midi.Track) = (0 until track.size()).map { track[it].message }
        val piano = messages(sequence.tracks[1])
        assertEquals("Piano", String(piano.filterIsInstance<javax.sound.midi.MetaMessage>().first { it.type == 3 }.data))
        assertEquals(listOf(48, 60), piano.filterIsInstance<javax.sound.midi.ShortMessage>()
            .filter { it.command == 0x90 }.map { it.data1 }.sorted())
        val clarinet = messages(sequence.tracks[2])
        assertEquals("Clarinet 2", String(clarinet.filterIsInstance<javax.sound.midi.MetaMessage>().first { it.type == 3 }.data))
        assertEquals(listOf(62), clarinet.filterIsInstance<javax.sound.midi.ShortMessage>()
            .filter { it.command == 0x90 }.map { it.data1 })
    }
}
