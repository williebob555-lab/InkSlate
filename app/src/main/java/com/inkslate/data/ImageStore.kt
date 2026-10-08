package com.inkslate.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import com.inkslate.core.Pictures

/**
 * The pictures on a document's pages: captured regions, inserted photos, pasted images.
 *
 * Kept in the app's own storage by [Pictures], and carried inside the document with its
 * handwriting - never written beside it. An older build kept them in a `<name>.inkassets` folder
 * next to the document, a folder and a file per capture in the middle of someone's coursework;
 * those are still read, taken in on first use, and the folder is removed by the first save that
 * is known to carry them all (see [Pictures.retireLegacyFolder]).
 */
class ImageStore(private val sourceFile: File) {

    /** Where an older build kept this document's pictures. Read, never written. */
    fun dirFor(): File = Pictures.legacyFolder(sourceFile)

    /** Write a bitmap and return its id. */
    fun put(bitmap: Bitmap): String? = runCatching {
        val bytes = java.io.ByteArrayOutputStream()
        // PNG so a captured diagram stays crisp; these are small regions, not photographs
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        val id = Pictures.put(bytes.toByteArray())
        if (id != null) EventLog.info("image", "Stored $id (${bytes.size() / 1024}KB)")
        id
    }.onFailure { EventLog.error("image", "Could not store image: ${it.message}") }.getOrNull()

    /**
     * The picture's file: in the app's store, or else in the folder an older build left beside
     * the document (taken into the store as it is found), or one that arrived over the link
     * before this build kept those in the store too.
     */
    fun fileFor(id: String): File? {
        Pictures.file(id)?.let { return it }
        File(dirFor(), "$id.png").takeIf { it.isFile }?.let { old ->
            runCatching { Pictures.keep(id, old.readBytes()) }
            return Pictures.file(id) ?: old
        }
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

    /**
     * Nothing to remove any more. The store is shared by every document - a picture copied from
     * one into another is the same file - so no one document can say a picture is unused, and
     * the pictures are small. Kept so callers need not change.
     */
    @Suppress("UNUSED_PARAMETER")
    fun prune(referenced: Set<String>) = Unit

    private fun cacheKey(id: String) = id

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

        /**
         * Keep a picture that arrived over the link. Into the app's own store, like every other
         * picture - nothing beside the document, so file sync bringing the same document cannot
         * collide with it.
         */
        fun keepLinked(id: String, png: ByteArray): Boolean = Pictures.keep(id, png)

        /** Shared across documents; keyed by folder so two files cannot collide. */
        private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

        fun clearCache() = cache.evictAll()
    }
}
