package com.inksheets.core

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun interrupted(root: File, name: String): File {
        val source = File(root, "source.wav")
        val copy = File(root, name)
        WavWriter(source, 48_000).use { writer ->
            writer.write(FloatArray(1234) { .25f }) // less than header refresh interval
            source.copyTo(copy)
        }
        return copy
    }
    @Test fun `interrupted take is repaired paired once and never touches another device's live recording`() {
        val root = tmp.newFolder()
        val lib = Library(LibraryLog(root, "stand"))
        val song = lib.addSong("Rehearsal")
        val file = interrupted(root, "take.wav")
        val before = file.readBytes().drop(44)
        val recovery = RecordingRecovery(root, "stand")
        val take = RecordingRecovery.Take("stand", song.id, file.name, "My interrupted take", 112.0, 3)
        recovery.begin(take)
        assertEquals(0, RecordingRecovery(root, "phone").recover(lib).recovered)
        assertEquals(0, recovery.recover(lib, file.name).recovered)
        val result = recovery.recover(lib)
        assertEquals(1, result.recovered)
        assertEquals(listOf(song.id), result.songs)
        val track = lib.song(song.id)!!.audio.single()
        assertEquals("My interrupted take", track.label)
        assertEquals(112.0, track.clickBpm!!, 0.0)
        assertEquals(3, track.beatsPerBar)
        assertEquals(before, file.readBytes().drop(44))
        assertEquals(2468, java.nio.ByteBuffer.wrap(file.readBytes(), 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(0, recovery.recover(lib).recovered)
        assertEquals(1, lib.song(song.id)!!.audio.size)
    }
    @Test fun `recovery retains an existing track and never resurrects a deleted song`() {
        val root = tmp.newFolder()
        val lib = Library(LibraryLog(root, "stand"))
        val song = lib.addSong("Take") { audio = listOf(AudioTrack("take.wav", "My label", speed = .7)) }
        interrupted(root, "take.wav")
        val recovery = RecordingRecovery(root, "stand")
        val receipt = RecordingRecovery.Take("stand", song.id, "take.wav", "Fallback")
        recovery.begin(receipt)
        assertEquals(1, recovery.recover(lib).recovered)
        assertEquals(.7, lib.song(song.id)!!.audio.single().speed, 0.0)
        recovery.begin(receipt)
        lib.deleteSong(song.id)
        assertEquals(0, recovery.recover(lib).recovered)
        assertTrue(File(root, "take.wav").isFile)
        assertNull(lib.song(song.id))
    }
    @Test fun `unknown formats remain unchanged and receipt cannot escape the library`() {
        val root = tmp.newFolder()
        val file = File(root, "other.wav").apply { writeBytes(ByteArray(100) { 12 }) }
        val before = file.readBytes()
        assertFalse(RecordingRecovery.repair(file))
        assertArrayEquals(before, file.readBytes())
        assertThrows(IllegalArgumentException::class.java) {
            RecordingRecovery(root, "stand").begin(RecordingRecovery.Take("stand", "song", "../outside.wav", "Take"))
        }
    }
}
