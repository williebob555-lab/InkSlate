package com.inkslate.pdf

import android.content.Context
import android.graphics.Bitmap
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Pages already drawn, kept on disk to be shown again rather than drawn again.
 *
 * Drawing a scanned page is the slow part of opening a song: the renderer decodes and scales the
 * whole scan each time, and music comes back to the same pages at every rehearsal. A page drawn at
 * a width is written down once - in the background, never holding up the screen - and the next
 * time that page is wanted at that width it is read back instead, which is a fraction of the work.
 *
 * Keyed on the file as it was (path, size, last change), so marks saved into it or a newer copy
 * synced in simply make new keys. Lives in the app's cache folder, which the system may clear.
 */
object PageCache {

    private const val DISK_LIMIT = 800L * 1024 * 1024

    @Volatile private var dir: File? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "page-cache").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var queued = 0
    @Volatile private var written = 0L

    fun init(context: Context) {
        dir = File(context.cacheDir, "pages").apply { mkdirs() }
    }

    fun key(file: File, page: Int, width: Int): String {
        val raw = "${file.absolutePath}|${file.length()}|${file.lastModified()}|$page|$width"
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /** The page kept for [key], as a new bitmap of its own; null when there is none. */
    fun get(key: String): Bitmap? {
        val f = File(dir ?: return null, "$key.px")
        if (!f.isFile) return null
        return runCatching {
            DataInputStream(InflaterInputStream(f.inputStream().buffered(1 shl 16), java.util.zip.Inflater(), 1 shl 16)).use { input ->
                val w = input.readInt()
                val h = input.readInt()
                val bytes = ByteArray(w * h * 4)
                input.readFully(bytes)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
            }
        }.onSuccess { runCatching { f.setLastModified(System.currentTimeMillis()) } }
            .onFailure { f.delete() }
            .getOrNull()
    }

    /** Write [bitmap] down as [key]'s page, in the background. The pixels are copied first. */
    fun put(key: String, bitmap: Bitmap) {
        val d = dir ?: return
        // A backlog means pages are arriving faster than they can be written: skip, not queue
        // tens of megabytes. The page is simply drawn again next time.
        if (queued >= 2 || bitmap.config != Bitmap.Config.ARGB_8888) return
        val w = bitmap.width
        val h = bitmap.height
        val bytes = runCatching { ByteBuffer.allocate(w * h * 4).also { bitmap.copyPixelsToBuffer(it) }.array() }.getOrNull() ?: return
        queued++
        writer.execute {
            try {
                val tmp = File(d, "$key.tmp")
                DataOutputStream(DeflaterOutputStream(tmp.outputStream().buffered(1 shl 16), Deflater(1), 1 shl 16)).use { out ->
                    out.writeInt(w)
                    out.writeInt(h)
                    out.write(bytes)
                }
                val f = File(d, "$key.px")
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                written += f.length()
                if (written > 48L * 1024 * 1024) { written = 0; trim(d) }
            } catch (_: Throwable) {
            } finally {
                queued--
            }
        }
    }

    private fun trim(d: File) {
        val files = d.listFiles { f -> f.name.endsWith(".px") }.orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= DISK_LIMIT) break
            total -= f.length()
            f.delete()
        }
    }
}
