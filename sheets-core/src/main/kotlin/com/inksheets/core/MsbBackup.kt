package com.inksheets.core

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/**
 * Unpacking a MobileSheets backup (`.msb`) - the file MobileSheets' own "Backup library" writes.
 *
 * The layout, big-endian throughout, as worked out by the MSPro-Tools project (backup versions 3
 * to 6; later ones are read the same way and stop cleanly at anything unexpected):
 *
 * - a 4-byte magic, 1234567892 plus (version - 3)
 * - version 4+: preferences - a count, then per item a 2-byte name length, the name, an 8-byte
 *   length and that many bytes
 * - version 5+: user filters - an 8-byte length and the bytes
 * - version 6+: annotation favourites and stamp lists (each an 8-byte length and bytes), then
 *   custom stamps (a count, then name and bytes as for preferences)
 * - the SQLite database: an 8-byte length and the bytes
 * - then per song: an 8-byte header magic, the 8-byte song id, and for each of that song's files
 *   (its Files rows, then its AudioFiles rows, in id order) an 8-byte length and the bytes - a
 *   length of 0 being a file already written for an earlier row
 *
 * The database is written out first because it is what says which file each block is; the files
 * then stream straight to disk, never held in memory, which matters for a backup of gigabytes.
 */
object MsbBackup {

    private const val FILE_MAGIC = 1234567892L
    private const val HEADER_MAGIC = 1287706427353294236L

    data class Result(val database: File, val files: Int, val version: Int, val stoppedEarly: String?)

    /**
     * Unpack [msb]: the database to [databaseOut], each song's files under [filesDir] keeping their
     * folders below what they all have in common. [open] opens the unpacked database's tables (the
     * platform's SQLite). [progress] hears bytes read so far.
     */
    fun extract(
        msb: File,
        databaseOut: File,
        filesDir: File,
        open: (File) -> MobileSheetsImport.Tables?,
        progress: (Long) -> Unit = {}
    ): Result {
        val counted = CountingStream(BufferedInputStream(msb.inputStream(), 1 shl 16))
        DataInputStream(counted).use { input ->
            val magic = input.readInt().toLong() and 0xFFFFFFFFL
            val version = (magic - FILE_MAGIC).toInt() + 3
            require(version in 3..12) { "This is not a MobileSheets backup." }

            fun skipBlock() = skipFully(input, input.readLong())
            fun skipNamedItems() {
                val count = input.readInt()
                repeat(count) {
                    skipFully(input, input.readUnsignedShort().toLong())
                    skipBlock()
                }
            }
            if (version >= 4) skipNamedItems()          // preferences
            if (version >= 5) skipBlock()               // user filters
            if (version >= 6) {
                skipBlock()                             // annotation favourites
                skipBlock()                             // stamp lists
                skipNamedItems()                        // custom stamps
            }

            databaseOut.parentFile?.mkdirs()
            copy(input, input.readLong(), databaseOut)
            val tables = open(databaseOut) ?: error("The backup's library could not be read.")

            fun Map<String, Any?>.num(c: String) = (this[c] as? Number)?.toLong() ?: this[c]?.toString()?.toLongOrNull()
            val filesBySong = tables.rows("Files").sortedBy { it.num("Id") ?: 0 }.groupBy { it.num("SongId") }
            val audioBySong = tables.rows("AudioFiles").sortedBy { it.num("Id") ?: 0 }.groupBy { it.num("SongId") }
            val allPaths = (filesBySong.values.flatten().mapNotNull { it["Path"]?.toString() } +
                audioBySong.values.flatten().mapNotNull { it["File"]?.toString() })
            val common = commonFolder(allPaths)

            var written = 0
            var stopped: String? = null
            while (true) {
                val header = try { input.readLong() } catch (_: EOFException) { break }
                if (header == -1L) break                       // the end marker: eight 0xFF bytes
                if (header != HEADER_MAGIC) { stopped = "unexpected data after $written files"; break }
                val songId = input.readLong()
                val paths = filesBySong[songId].orEmpty().mapNotNull { it["Path"]?.toString() } +
                    audioBySong[songId].orEmpty().mapNotNull { it["File"]?.toString() }
                for (path in paths) {
                    val length = input.readLong()
                    if (length == 0L) continue
                    copy(input, length, File(filesDir, relativeTo(path, common)))
                    written++
                    progress(counted.count)
                }
            }
            return Result(databaseOut, written, version, stopped)
        }
    }

    /** The folder every path sits under - `/storage/emulated/0/MobileSheets` - so it can be dropped. */
    internal fun commonFolder(paths: List<String>): List<String> {
        val split = paths.map { it.replace('\\', '/').split('/').filter { s -> s.isNotEmpty() }.dropLast(1) }
        if (split.isEmpty()) return emptyList()
        var common = split.first()
        for (p in split.drop(1)) {
            var n = 0
            while (n < common.size && n < p.size && common[n] == p[n]) n++
            common = common.take(n)
        }
        return common
    }

    internal fun relativeTo(path: String, common: List<String>): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val rest = if (parts.take(common.size) == common) parts.drop(common.size) else listOf(parts.last())
        // Nothing may climb out of the folder it is unpacked into.
        return rest.filter { it != ".." && it != "." }.joinToString("/")
            .map { if (it in ":*?\"<>|") '_' else it }.joinToString("")
    }

    private fun copy(input: DataInputStream, length: Long, to: File) {
        to.parentFile?.mkdirs()
        to.outputStream().buffered(1 shl 16).use { out ->
            val buffer = ByteArray(1 shl 16)
            var left = length
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) throw EOFException("The backup ends partway through a file.")
                out.write(buffer, 0, n)
                left -= n
            }
        }
    }

    private fun skipFully(input: DataInputStream, length: Long) {
        var left = length
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) { if (input.read() < 0) throw EOFException(); left-- } else left -= n
        }
    }

    private class CountingStream(inner: java.io.InputStream) : java.io.FilterInputStream(inner) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }
}
