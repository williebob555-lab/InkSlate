package com.inkslate.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Stores images pasted into a document, beside the document itself.
 *
 * Kept as ordinary files in a `<name>.inkassets` folder rather than inlined into the sidecar.
 * Base64 inside the JSON would triple the size of every save and force the whole sidecar across
 * the network each time a stroke changes; separate files let Syncthing move an image once and
 * never look at it again.
 */
class ImageStore(private val sourceFile: File) {

    private val dir = File(sourceFile.parentFile, "${sourceFile.name}.inkassets")

    fun dirFor(): File = dir

    /** Write a bitmap and return its id. */
    fun put(bitmap: Bitmap): String? = runCatching {
        dir.mkdirs()
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val target = File(dir, "$id.png")
        val tmp = File(dir, ".$id.tmp")
        FileOutputStream(tmp).use { out ->
            // PNG so a captured diagram stays crisp; these are small regions, not photographs
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        EventLog.info("image", "Stored $id (${target.length() / 1024}KB)")
        id
    }.onFailure { EventLog.error("image", "Could not store image: ${it.message}") }.getOrNull()

    /**
     * The picture's file: in the folder beside the document, or else one that arrived over the
     * link before file sync brought it. See [keepLinked].
     */
    fun fileFor(id: String): File? {
        File(dir, "$id.png").takeIf { it.isFile }?.let { return it }
        return linkedDir?.let { File(it, "$id.png") }?.takeIf { it.isFile }
    }

    /** The picture's bytes, to send to another device. */
    fun bytes(id: String): ByteArray? = fileFor(id)?.let { runCatching { it.readBytes() }.getOrNull() }

    fun load(id: String): Bitmap? {
        cache.get(cacheKey(id))?.let { return it }
        val f = fileFor(id) ?: return null
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: return null
        cache.put(cacheKey(id), bmp)
        return bmp
    }

    fun exists(id: String) = fileFor(id) != null

    /** Remove assets no longer referenced by any stroke, so deletions do not accumulate. */
    fun prune(referenced: Set<String>) {
        val files = dir.listFiles { f -> f.isFile && f.extension == "png" } ?: return
        var removed = 0
        for (f in files) {
            if (f.nameWithoutExtension !in referenced) {
                if (f.delete()) removed++
            }
        }
        if (removed > 0) EventLog.info("image", "Pruned $removed unused image(s)")
    }

    private fun cacheKey(id: String) = "${dir.absolutePath}|$id"

    companion object {
        /**
         * Pictures that arrived over the link, kept in the app's own storage.
         *
         * Not in the folder beside the document: file sync is bringing that same file from the
         * other device, and two devices creating one file at once is exactly how a sync conflict
         * starts. These are found when the folder does not have the picture yet, and are never the
         * copy anything else reads.
         */
        @Volatile var linkedDir: File? = null

        /** Keep a picture that arrived over the link. Returns whether it was kept. */
        fun keepLinked(id: String, png: ByteArray): Boolean = runCatching {
            val dir = linkedDir ?: return false
            dir.mkdirs()
            val target = File(dir, "$id.png")
            val tmp = File(dir, ".$id.tmp")
            tmp.writeBytes(png)
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            true
        }.getOrDefault(false)

        /** Shared across documents; keyed by folder so two files cannot collide. */
        private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

        fun clearCache() = cache.evictAll()
    }
}
