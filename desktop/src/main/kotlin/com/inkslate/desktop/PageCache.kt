package com.inkslate.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Pictures of whole pages already drawn, kept to be shown again instead of drawn again.
 *
 * Drawing a scanned page sharp is slow - the library reads and scales the whole scan every time -
 * and music comes back to the same pages over and over: every rehearsal, every time a set is
 * played through. So a page drawn at a width is kept: the last few in memory, to be there at
 * once, and all of them on disk, where reading one back is a fraction of drawing it.
 *
 * A picture belongs to the file as it was: its path, size and last change. Marks saved into the
 * file, or a new copy synced in, make a new key, and the old pictures are simply never asked for
 * again (and go when the store is trimmed).
 */
object PageCache {

    /** The most kept on disk; the oldest go first beyond it. */
    private const val DISK_LIMIT = 1_500L * 1024 * 1024

    /** How many pictures are kept in memory: a set's current song and its neighbours, roughly. */
    private const val MEMORY_PAGES = 10

    private val memory = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MEMORY_PAGES
    }

    private val dir: File by lazy { AppDirs.dir("pages") }
    @Volatile private var written = 0L

    fun key(file: File, page: Int, widthPx: Int): String {
        val raw = "${file.absolutePath}|${file.length()}|${file.lastModified()}|$page|$widthPx"
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /** The kept picture for [key], from memory or disk; null when there is none. */
    fun get(key: String): ImageBitmap? {
        synchronized(memory) { memory[key] }?.let { return it }
        val f = File(dir, "$key.px")
        if (!f.isFile) return null
        val image = runCatching {
            DataInputStream(InflaterInputStream(f.inputStream().buffered(1 shl 16), java.util.zip.Inflater(), 1 shl 16)).use { input ->
                val w = input.readInt()
                val h = input.readInt()
                val pixels = IntArray(w * h)
                val bytes = ByteArray(w * 4)
                val buf = java.nio.ByteBuffer.wrap(bytes).asIntBuffer()
                for (y in 0 until h) {
                    input.readFully(bytes)
                    buf.rewind()
                    buf.get(pixels, y * w, w)
                }
                BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, w, h, pixels, 0, w) }
            }
        }.getOrNull() ?: return null
        runCatching { f.setLastModified(System.currentTimeMillis()) }   // recently used: kept longest
        val bitmap = image.toComposeImageBitmap().frozen()
        synchronized(memory) { memory[key] = bitmap }
        return bitmap
    }

    /** Keep [image] as the picture for [key]; written to disk on this thread. */
    fun put(key: String, image: BufferedImage, bitmap: ImageBitmap) {
        synchronized(memory) { memory[key] = bitmap }
        runCatching {
            val w = image.width
            val h = image.height
            val tmp = File(dir, "$key.tmp")
            DataOutputStream(DeflaterOutputStream(tmp.outputStream().buffered(1 shl 16), Deflater(1), 1 shl 16)).use { out ->
                out.writeInt(w)
                out.writeInt(h)
                val row = IntArray(w)
                val bytes = ByteArray(w * 4)
                val buf = java.nio.ByteBuffer.wrap(bytes).asIntBuffer()
                for (y in 0 until h) {
                    image.getRGB(0, y, w, 1, row, 0, w)
                    buf.rewind()
                    buf.put(row)
                    out.write(bytes)
                }
            }
            val f = File(dir, "$key.px")
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            written += f.length()
            if (written > 64L * 1024 * 1024) { written = 0; trim() }
        }
    }

    /** Forget what is held in memory, keeping the disk: for measuring a read back from disk. */
    internal fun forgetMemory() = synchronized(memory) { memory.clear() }

    /** Oldest-used pictures off the disk until the store is inside its limit. */
    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".px") }.orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= DISK_LIMIT) break
            total -= f.length()
            f.delete()
        }
    }
}
