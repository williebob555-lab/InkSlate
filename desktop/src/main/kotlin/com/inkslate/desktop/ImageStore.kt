package com.inkslate.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Stores images pasted into a document, beside the document itself.
 *
 * Kept as ordinary files in a `<name>.inkassets` folder rather than inlined into the document.
 * Base64 inside the payload would multiply the size of every save and force the whole thing
 * across the network each time a stroke changes; separate files let Syncthing move an image once
 * and never look at it again.
 *
 * The folder name and the file names match the tablet's exactly, because a document and its
 * pictures have to arrive together and be found by whichever machine opens them.
 */
class ImageStore(sourceFile: File) {

    private val dir = File(sourceFile.parentFile, "${sourceFile.name}.inkassets")

    fun dirFor(): File = dir

    /** Write a picture and return its id. */
    fun put(image: ImageBitmap): String? = runCatching {
        dir.mkdirs()
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val target = File(dir, "$id.png")
        val tmp = File(dir, ".$id.tmp")
        FileOutputStream(tmp).use { out ->
            // PNG so a captured diagram stays crisp; these are small regions, not photographs.
            ImageIO.write(image.toAwtImage(), "png", out)
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        id
    }.getOrNull()

    /** Copy a picture from anywhere on disk into the store. */
    fun putFile(file: File): String? = runCatching {
        val decoded: BufferedImage = ImageIO.read(file) ?: return null
        put(decoded.toComposeImageBitmap())
    }.getOrNull()

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
     * Remove pictures nothing points at any more.
     *
     * Only ever called with the complete set of ids the document still references - deleting on
     * a partial view would take away an image that a page nobody has scrolled to still uses.
     */
    fun prune(referenced: Set<String>) {
        val files = dir.listFiles { f -> f.isFile && f.extension == "png" } ?: return
        for (f in files) {
            if (f.nameWithoutExtension !in referenced) f.delete()
        }
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
        @Volatile var linkedDir: File? = File(AppDirs.root, "linked-images")

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
