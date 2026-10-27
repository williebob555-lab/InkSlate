package com.inksheets.core

import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A durable receipt for this device's unfinished takes. Other devices never repair a live take. */
class RecordingRecovery(private val root: File, private val device: String) {
    @Serializable data class Take(val device: String, val song: String, val file: String, val label: String,
        val clickBpm: Double? = null, val beatsPerBar: Int? = null)
    data class Result(val recovered: Int, val problems: List<String>, val songs: List<String> = emptyList())
    private val json = Json { ignoreUnknownKeys = true }
    private val dir get() = File(root, ".inksheets/unfinished-recordings")
    private fun receipt(take: Take) = File(dir, Library.partIdFor("${take.device}/${take.file}") + ".json")
    fun begin(take: Take) {
        require(take.device == device)
        require(safeFile(take.file) != null)
        dir.mkdirs()
        val target = receipt(take)
        val temp = File(dir, target.name + ".tmp")
        temp.outputStream().use { it.write(json.encodeToString(take).toByteArray()); it.fd.sync() }
        java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    fun finish(take: Take) { receipt(take).delete() }
    fun track(take: Take) = AudioTrack(file = take.file, label = take.label, clickBpm = take.clickBpm,
        beatsPerBar = take.beatsPerBar, firstBeatMs = if (take.clickBpm != null) 0 else null)
    /** Called on library opening; completed receipts are removed only after the association is durable. */
    fun recover(library: Library, activeFile: String? = null): Result {
        var recovered = 0
        val problems = mutableListOf<String>()
        val songs = mutableListOf<String>()
        for (receipt in dir.listFiles().orEmpty().filter { it.extension == "json" }) {
            val take = try { json.decodeFromString<Take>(receipt.readText()) } catch (error: Exception) {
                problems += "Unreadable recording recovery receipt: ${receipt.name}: ${error.message}"; continue
            }
            if (take.device != device || take.file == activeFile) continue
            val file = safeFile(take.file)
            if (file == null || !file.isFile) { problems += "Unfinished recording is missing: ${take.file}"; continue }
            if (!repair(file)) { problems += "Unfinished recording could not be recovered: ${take.file}"; continue }
            val song = library.song(take.song)
            if (song == null) { problems += "Recovered audio remains in ${take.file}; its song was removed"; continue }
            if (song.audio.none { it.file == take.file }) library.editSong(song.id) { audio = song.audio + track(take) }
            receipt.delete()
            recovered++
            songs += song.id
        }
        return Result(recovered, problems, songs.distinct())
    }
    private fun safeFile(relative: String): File? {
        val base = root.canonicalFile
        val file = File(base, relative).canonicalFile
        return file.takeIf { it.toPath().startsWith(base.toPath()) && it != base }
    }
    companion object {
        /** Only our fixed PCM WAV format is repaired. Unknown files are never rewritten. */
        fun repair(file: File): Boolean = runCatching {
            RandomAccessFile(file, "rw").use { out ->
                if (out.length() < 46) return false
                val header = ByteArray(44); out.readFully(header)
                fun text(at: Int, count: Int) = String(header, at, count, Charsets.US_ASCII)
                fun u16(at: Int) = (header[at].toInt() and 255) or ((header[at + 1].toInt() and 255) shl 8)
                if (text(0, 4) != "RIFF" || text(8, 8) != "WAVEfmt " || text(36, 4) != "data" ||
                    u16(20) != 1 || u16(22) != 1 || u16(32) != 2 || u16(34) != 16) return false
                val data = ((out.length() - 44) / 2 * 2).coerceAtMost(0xffff_ffffL - 36)
                fun writeSize(at: Long, value: Long) { out.seek(at); repeat(4) { out.write((value shr (it * 8)).toInt() and 255) } }
                writeSize(4, data + 36); writeSize(40, data)
                out.setLength(44 + data)
                out.fd.sync()
                true
            }
        }.getOrDefault(false)
    }
}
