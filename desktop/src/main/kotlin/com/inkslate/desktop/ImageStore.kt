package com.inkslate.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.File
import com.inkslate.core.Pictures
import javax.imageio.ImageIO

/**
 * The pictures on a document's pages: captured regions, inserted photos, pasted images.
 *
 * Kept in the app's own storage by [Pictures], and carried inside the document with its
 * handwriting - never written beside it. An older build kept them in a `<name>.inkassets` folder
 * next to the document; those are still read, taken in on first use, and the folder is removed
 * by the first save that is known to carry them all (see [Pictures.retireLegacyFolder]).
 */
class ImageStore(private val sourceFile: File) {

    /** Where an older build kept this document's pictures. Read, never written. */
    fun dirFor(): File = Pictures.legacyFolder(sourceFile)

    /** Write a picture and return its id. */
    fun put(image: ImageBitmap): String? = runCatching {
        val bytes = java.io.ByteArrayOutputStream()
        // PNG so a captured diagram stays crisp; these are small regions, not photographs.
        ImageIO.write(image.toAwtImage(), "png", bytes)
        Pictures.put(bytes.toByteArray())
    }.getOrNull()

    /** Copy a picture from anywhere on disk into the store. */
    fun putFile(file: File): String? = runCatching {
        val decoded: BufferedImage = ImageIO.read(file) ?: return null
        put(decoded.toComposeImageBitmap())
    }.getOrNull()

    /**
     * The picture's file: in the app's store, or else in the folder an older build left beside
     * the document (taken into the store as it is found), or one that arrived over the link.
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

    fun load(id: String): ImageBitmap? {
        synchronized(cache) { cache[cacheKey(id)] }?.let { return it }
        val f = fileFor(id) ?: return null
        val decoded = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
        val bitmap = decoded.toComposeImageBitmap()
        synchronized(cache) { cache[cacheKey(id)] = bitmap }
        return bitmap
    }

    fun exists(id: String) = fileFor(id) != null

    /**
     * The picture as an AWT image, which is what PDFBox embeds.
     *
     * Read from disk rather than converted from the cached [ImageBitmap]: the round trip through
     * Skia and back costs more than reading a small PNG, and an export is not a hot path.
     */
    fun awtImage(id: String): BufferedImage? =
        runCatching { ImageIO.read(fileFor(id)) }.getOrNull()

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
        @Volatile var linkedDir: File? = File(AppDirs.root, "linked-images")

        init {
            if (Pictures.dir == null) Pictures.dir = File(AppDirs.root, "pictures")
        }

        /**
         * Keep a picture that arrived over the link. Into the app's own store, like every other
         * picture - nothing beside the document, so file sync bringing the same document cannot
         * collide with it.
         */
        fun keepLinked(id: String, png: ByteArray): Boolean = Pictures.keep(id, png)

        /**
         * Bounded by count rather than bytes: unlike Android's `LruCache` there is no cheap byte
         * size on a Skia-backed bitmap, and these are captured regions rather than photographs.
         */
        private val cache = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>) = size > 48
        }

        fun clearCache() = synchronized(cache) { cache.clear() }
    }
}
