package com.inksheets.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncedListsTest {
    @get:Rule val tmp = TemporaryFolder()
    private inner class Device(val id: String) {
        val root = tmp.newFolder(id)
        val log = LibraryLog(root, id)
        var library = Library(log)
        fun send(to: Device) {
            val own = File(log.dir, "$id.jsonl")
            if (own.exists()) own.copyTo(File(to.log.dir, own.name), overwrite = true)
            to.library.refresh()
        }
        fun reopen() { library = Library(LibraryLog(root, id)) }
    }
    private fun meet(a: Device, b: Device) { a.send(b); b.send(a) }
    private fun entries(lib: Library, set: String) = lib.setlist(set)!!.entries
    private fun tracks(lib: Library, song: String) = lib.song(song)!!.audio

    @Test fun `two offline setlist additions both survive in the same deterministic order`() {
        val a = Device("a"); val b = Device("b")
        val first = a.library.addSong("First"); val second = a.library.addSong("Second")
        val set = a.library.addSetlist("Concert")
        a.send(b)
        a.library.addToSetlist(set.id, first.id)
        b.library.addToSetlist(set.id, second.id)
        meet(a, b)
        assertEquals(setOf(first.id, second.id), entries(a.library, set.id).map { it.songId }.toSet())
        assertEquals(entries(a.library, set.id), entries(b.library, set.id))
        a.reopen(); b.reopen()
        assertEquals(entries(a.library, set.id), entries(b.library, set.id))
    }

    @Test fun `setlist remove wins over a concurrent entry note edit without dropping an unrelated add`() {
        val a = Device("a"); val b = Device("b")
        val song = a.library.addSong("First"); val other = a.library.addSong("Other")
        val set = a.library.addSetlist("Concert"); a.library.addToSetlist(set.id, song.id)
        a.send(b)
        val entry = entries(a.library, set.id).single()
        a.library.removeFromSetlist(set.id, entry.id)
        b.library.editSetlist(set.id) { entries = listOf(entry.copy(note = "Play softly"), SetlistEntry(songId = other.id)) }
        meet(a, b)
        assertEquals(listOf(other.id), entries(a.library, set.id).map { it.songId })
        assertEquals(entries(a.library, set.id), entries(b.library, set.id))
    }

    @Test fun `simultaneous setlist entry metadata edits merge independently`() {
        val a = Device("a"); val b = Device("b")
        val song = a.library.addSong("First"); val set = a.library.addSetlist("Concert")
        a.library.addToSetlist(set.id, song.id); a.send(b)
        val entry = entries(a.library, set.id).single()
        a.library.editSetlist(set.id) { entries = listOf(entry.copy(note = "Mute at C")) }
        b.library.editSetlist(set.id) { entries = listOf(entry.copy(color = 12345)) }
        meet(a, b)
        assertEquals("Mute at C", entries(a.library, set.id).single().note)
        assertEquals(12345, entries(a.library, set.id).single().color)
        assertEquals(entries(a.library, set.id), entries(b.library, set.id))
    }

    @Test fun `recording additions and metadata from separate devices do not replace each other`() {
        val a = Device("a"); val b = Device("b")
        val track = AudioTrack("rehearsal.wav", label = "Original")
        val song = a.library.addSong("Nocturne") { audio = listOf(track) }
        a.send(b)
        a.library.editSong(song.id) { audio = listOf(track.copy(speed = .75), AudioTrack("take-a.wav")) }
        b.library.editSong(song.id) { audio = listOf(track.copy(volume = .5), AudioTrack("take-b.wav")) }
        meet(a, b)
        val got = tracks(a.library, song.id)
        assertEquals(setOf("rehearsal.wav", "take-a.wav", "take-b.wav"), got.map { it.file }.toSet())
        assertEquals(.75, got.single { it.file == track.file }.speed, 0.0)
        assertEquals(.5, got.single { it.file == track.file }.volume, 0.0)
        assertEquals(got, tracks(b.library, song.id))
    }

    @Test fun `teaching two parts of one recording merges each parts page turns`() {
        val a = Device("a"); val b = Device("b")
        val track = AudioTrack("rehearsal.wav", partTurns = mapOf("euph" to listOf(1000L), "bass" to listOf(1500L)))
        val song = a.library.addSong("Nocturne") { audio = listOf(track) }; a.send(b)
        a.library.editSong(song.id) { audio = listOf(track.copy(partTurns = track.partTurns + ("euph" to listOf(2000L)))) }
        b.library.editSong(song.id) { audio = listOf(track.copy(partTurns = track.partTurns + ("bass" to listOf(3000L)))) }
        meet(a, b)
        assertEquals(mapOf("euph" to listOf(2000L), "bass" to listOf(3000L)), tracks(a.library, song.id).single().partTurns)
        assertEquals(tracks(a.library, song.id), tracks(b.library, song.id))
    }

    @Test fun `recording removal survives later concurrent speed edits until explicitly added again`() {
        val a = Device("a"); val b = Device("b")
        val track = AudioTrack("rehearsal.wav")
        val song = a.library.addSong("Nocturne") { audio = listOf(track) }; a.send(b)
        a.library.editSong(song.id) { audio = emptyList() }
        b.library.editSong(song.id) { audio = listOf(track.copy(speed = .5)) }
        meet(a, b)
        assertTrue(tracks(a.library, song.id).isEmpty())
        assertTrue(tracks(b.library, song.id).isEmpty())
        b.library.editSong(song.id) { audio = listOf(track.copy(label = "Restored")) }
        meet(a, b)
        assertEquals("Restored", tracks(a.library, song.id).single().label)
    }

    @Test fun `concurrent reorder add and remove converge without losing surviving set entries`() {
        val a = Device("a"); val b = Device("b")
        val songs = listOf("One", "Two", "Three", "Four").map { a.library.addSong(it) }
        val set = a.library.addSetlist("Concert")
        songs.take(3).forEach { a.library.addToSetlist(set.id, it.id) }; a.send(b)
        val before = entries(a.library, set.id)
        a.library.editSetlist(set.id) { entries = listOf(before[2], before[0], before[1]) }
        b.library.editSetlist(set.id) { entries = listOf(before[0], before[2], SetlistEntry(songId = songs[3].id)) }
        meet(a, b)
        assertEquals(setOf(songs[0].id, songs[2].id, songs[3].id), entries(a.library, set.id).map { it.songId }.toSet())
        assertEquals(entries(a.library, set.id), entries(b.library, set.id))
        assertEquals(3, entries(a.library, set.id).size)
    }

    @Test fun `compaction retains independent deltas tombstones nested data and unknown fields`() {
        val a = Device("a"); val b = Device("b")
        val track = AudioTrack("old.wav")
        val song = a.library.addSong("Nocturne") { audio = listOf(track) }
        // Unknown scalar data must survive compaction as before; future clients can add fields.
        a.log.append(listOf(Op(Stamp(1, 0, "a"), "song", song.id, "futureField", JsonPrimitive("keep me"))))
        a.library.refresh(); a.send(b)
        a.library.editSong(song.id) { audio = listOf(AudioTrack("take-a.wav", partTurns = mapOf("euph" to listOf(1000L)))) }
        b.library.editSong(song.id) { audio = listOf(track.copy(pitch = 2), AudioTrack("take-b.wav")) }
        meet(a, b)
        val wanted = tracks(a.library, song.id)
        assertEquals(setOf("take-a.wav", "take-b.wav"), wanted.map { it.file }.toSet())
        a.library.compactIfLarge(0); b.library.compactIfLarge(0)
        a.reopen(); b.reopen(); meet(a, b)
        assertEquals(wanted, tracks(a.library, song.id)); assertEquals(wanted, tracks(b.library, song.id))
        assertTrue(File(a.log.dir, "a.jsonl").readText().contains("futureField"))
    }

    @Test fun `legacy list snapshots load and new changes remain readable as full lists`() {
        val dir = tmp.newFolder("legacy")
        val log = LibraryLog(dir, "old")
        val audio = JsonArray(listOf(buildJsonObject { put("file", "original.wav"); put("label", "Legacy") }))
        log.append(listOf(Op(Stamp(1, 0, "old"), "song", "song", "title", JsonPrimitive("Legacy song")),
            Op(Stamp(2, 0, "old"), "song", "song", "audio", audio)))
        val library = Library(log)
        assertEquals("Legacy", library.song("song")!!.audio.single().label)
        library.editSong("song") { this.audio = listOf(AudioTrack("original.wav", label = "Renamed"), AudioTrack("new.wav")) }
        val latest = log.readNew().last { it.field == "audio" }
        assertNotNull(latest.base)
        assertEquals(2, (latest.value as JsonArray).size)
        library.compactIfLarge(0)
        assertEquals(listOf("original.wav", "new.wav"), Library(LibraryLog(dir, "reopened")).song("song")!!.audio.map { it.file })
    }

    @Test fun `delta replay is independent of log arrival order and duplicated delivery`() {
        val base = JsonArray(listOf(buildJsonObject { put("file", "original.wav"); put("speed", 1.0) }))
        val plus = JsonArray(base + buildJsonObject { put("file", "new.wav") })
        val speed = JsonArray(listOf(buildJsonObject { put("file", "original.wav"); put("speed", .5) }))
        val ops = listOf(Op(Stamp(1, 0, "old"), "song", "s", "audio", base),
            Op(Stamp(2, 0, "a"), "song", "s", "audio", plus, base),
            Op(Stamp(2, 0, "b"), "song", "s", "audio", speed, base))
        fun replay(sequence: List<Op>): JsonElement? {
            val state = LibraryState(); sequence.forEach(state::apply)
            return state.fields("song", "s")!!["audio"]!!.value
        }
        val wanted = replay(ops)
        assertEquals(wanted, replay(ops.reversed()))
        assertEquals(wanted, replay(listOf(ops[1], ops[0], ops[2], ops[1], ops[2])))
    }

    @Test fun `concurrent same-file addition without observing its removal cannot revive it`() {
        val a = Device("a"); val b = Device("b"); val c = Device("c")
        val song = a.library.addSong("Nocturne"); a.send(b); a.send(c)
        val track = AudioTrack("same-file.wav")
        a.library.editSong(song.id) { audio = listOf(track) }; a.send(b)
        b.library.editSong(song.id) { audio = emptyList() }
        // C never saw the first addition/removal and its clock is far ahead. Timestamp alone
        // must not let this unrelated addition override B's deliberate removal.
        c.library = Library(c.log) { System.currentTimeMillis() + 1_000_000L }
        c.library.editSong(song.id) { audio = listOf(track.copy(label = "Independent add")) }
        b.send(a); c.send(a); a.send(b); c.send(b); b.send(c); a.send(c)
        for (device in listOf(a, b, c)) assertTrue(tracks(device.library, song.id).isEmpty())
        c.library.editSong(song.id) { audio = listOf(track.copy(label = "Intentionally restored")) }
        meet(a, c)
        assertEquals("Intentionally restored", tracks(a.library, song.id).single().label)
    }

    @Test fun `readding a removed recording uses the requested order without an anchor cycle`() {
        val device = Device("a")
        val first = AudioTrack("first.wav"); val second = AudioTrack("second.wav")
        val song = device.library.addSong("Nocturne") { audio = listOf(first, second) }
        device.library.editSong(song.id) { audio = listOf(second) }
        device.library.editSong(song.id) { audio = listOf(second, first) }
        assertEquals(listOf("second.wav", "first.wav"), tracks(device.library, song.id).map { it.file })
        device.library.compactIfLarge(0); device.reopen()
        assertEquals(listOf("second.wav", "first.wav"), tracks(device.library, song.id).map { it.file })
    }

    @Test fun `duplicate recording paths remain one item and unknown future metadata survives editing and compaction`() {
        val device = Device("a")
        val song = device.library.addSong("Nocturne")
        val raw = JsonArray(listOf(buildJsonObject {
            put("file", "track.wav"); put("label", "Original")
            putJsonObject("futureMetadata") { put("take", "Keep this") }
        }))
        device.log.append(listOf(Op(Stamp(System.currentTimeMillis() + 1000L, 0, "future"), "song", song.id, "audio", raw)))
        device.library.refresh()
        device.library.editSong(song.id) { audio = listOf(AudioTrack("track.wav", label = "Renamed"), AudioTrack("track.wav", label = "Duplicate")) }
        assertEquals(1, tracks(device.library, song.id).size)
        assertEquals("Renamed", tracks(device.library, song.id).single().label)
        device.library.compactIfLarge(0); device.reopen()
        assertEquals("Renamed", tracks(device.library, song.id).single().label)
        // Read the projected raw state to verify data a newer client understands is retained.
        val state = LibraryState(); LibraryLog(device.root, "inspection").readNew().forEach(state::apply)
        val projected = state.fields("song", song.id)!!["audio"]!!.value as JsonArray
        assertEquals("Keep this", projected.single().jsonObject["futureMetadata"]!!.jsonObject["take"]!!.jsonPrimitive.content)
    }

    @Test fun `restoring after one deletion does not override an unseen independent deletion`() {
        val a = Device("a"); val b = Device("b")
        val song = a.library.addSong("Nocturne") { audio = listOf(AudioTrack("take.wav")) }
        a.send(b)
        a.library.editSong(song.id) { audio = emptyList() }
        b.library.editSong(song.id) { audio = emptyList() }
        // Each restore only observes its own deletion. Neither observes the other device's intent.
        a.library.editSong(song.id) { audio = listOf(AudioTrack("take.wav", label = "A")) }
        b.library.editSong(song.id) { audio = listOf(AudioTrack("take.wav", label = "B")) }
        meet(a, b)
        assertTrue(tracks(a.library, song.id).isEmpty())
        assertTrue(tracks(b.library, song.id).isEmpty())
        a.library.editSong(song.id) { audio = listOf(AudioTrack("take.wav", label = "Observed both")) }
        meet(a, b)
        for (device in listOf(a, b)) {
            assertEquals("Observed both", tracks(device.library, song.id).single().label)
            device.library.compactIfLarge(0); device.reopen()
            assertEquals("Observed both", tracks(device.library, song.id).single().label)
        }
    }

    @Test fun `restoring a recording resets settings from its deleted generation`() {
        val a = Device("a"); val b = Device("b")
        val song = a.library.addSong("Nocturne") {
            audio = listOf(AudioTrack("take.wav", label = "Old", volume = 0.5, pitch = 3))
        }
        a.send(b)
        a.library.editSong(song.id) { audio = emptyList() }
        a.library.editSong(song.id) { audio = listOf(AudioTrack("take.wav", label = "Fresh")) }
        meet(a, b)
        for (device in listOf(a, b)) {
            val restored = tracks(device.library, song.id).single()
            assertEquals("Fresh", restored.label)
            assertEquals(1.0, restored.volume, 0.0)
            assertEquals(0, restored.pitch)
            device.library.compactIfLarge(0); device.reopen()
            assertEquals(restored, tracks(device.library, song.id).single())
        }
    }

    @Test fun `compaction preserves future operation fields and unreadable complete lines`() {
        val device = Device("a")
        val song = device.library.addSong("Nocturne")
        val wire = Json.encodeToString(Op.serializer(), Op(Stamp(System.currentTimeMillis() + 5000L, 0, "a"), "song", song.id, "futureScalar", JsonPrimitive("keep")))
            .dropLast(1) + ",\"futureOperation\":{\"meaning\":true}}"
        val own = File(device.log.dir, "a.jsonl")
        own.appendText(wire + "\n" + "{future-format-not-readable-by-this-version}\n")
        device.library.refresh(); device.library.compactIfLarge(0)
        assertTrue(own.readText().contains("\"futureOperation\":{\"meaning\":true}"))
        assertTrue(own.readText().contains("{future-format-not-readable-by-this-version}"))
    }
}
