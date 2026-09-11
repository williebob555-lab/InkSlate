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

    fun load(id: String): ImageBitmap? {
        synchronized(cache) { cache[cacheKey(id)] }?.let { return it }
        val f = File(dir, "$id.png")
        if (!f.isFile) return null
        val decoded = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
        val bitmap = decoded.toComposeImageBitmap()
        synchronized(cache) { cache[cacheKey(id)] = bitmap }
        return bitmap
    }

    fun exists(id: String) = File(dir, "$id.png").isFile

    /**
     * The picture as an AWT image, which is what PDFBox embeds.
     *
     * Read from disk rather than converted from the cached [ImageBitmap]: the round trip through
     * Skia and back costs more than reading a small PNG, and an export is not a hot path.
     */
    fun awtImage(id: String): BufferedImage? =
        runCatching { ImageIO.read(File(dir, "$id.png")) }.getOrNull()

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
         * Bounded by count rather than bytes: unlike Android's `LruCache` there is no cheap byte
         * size on a Skia-backed bitmap, and these are captured regions rather than photographs.
         */
        private val cache = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>) = size > 48
        }

        fun clearCache() = synchronized(cache) { cache.clear() }
    }
}
