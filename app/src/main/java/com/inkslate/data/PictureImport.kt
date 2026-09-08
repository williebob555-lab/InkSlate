package com.inkslate.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Getting a picture onto a page: from the gallery, from a file, or straight off the camera.
 *
 * The editor could already capture a region of what was on screen, which meant the only pictures
 * it could hold were ones it had drawn itself. The thing anyone actually wants to put on a
 * worksheet is a photograph of the working they did on paper, or the diagram from the textbook -
 * neither of which is on the page yet.
 *
 * Everything here produces a plain [Bitmap], which is what [ImageStore] takes, so a photograph and
 * a screen capture end up as exactly the same kind of object on the page and behave identically
 * afterwards.
 */
object PictureImport {

    /**
     * The longest edge a stored picture is allowed to have.
     *
     * A modern phone camera produces a 4000-pixel image; a picture occupying a third of an A4
     * page at print resolution needs about 1400. The rest is file size that syncs between devices
     * for ever and gives nothing back, and it is stored as a PNG, which makes it worse.
     */
    private const val MAX_EDGE = 2000

    /**
     * Decode [uri] at a sensible size, the right way up.
     *
     * Two passes: bounds first to choose a sample size, then the real decode. Loading a
     * twelve-megapixel photograph in full and scaling it afterwards is how an import turns into
     * an out-of-memory crash on a device that is already holding a rendered textbook page.
     */
    fun decode(context: Context, uri: Uri, maxEdge: Int = MAX_EDGE): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) error("Not an image")

        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("Could not read the picture")

        val scaled = fit(raw, maxEdge)
        val rotated = applyOrientation(context, uri, scaled)
        EventLog.info(
            "picture",
            "Imported ${rotated.width}x${rotated.height} (sampled 1/$sample)"
        )
        rotated
    }.onFailure { EventLog.error("picture", "Import failed: ${it.message}") }.getOrNull()

    /**
     * A file for the camera to write into, and the URI to hand it.
     *
     * The camera app writes to a location we own rather than to the shared gallery, because a
     * photograph taken to be drawn on is a working file, not something anyone wants turning up in
     * their camera roll between the holiday pictures.
     */
    fun cameraTarget(context: Context): Pair<File, Uri>? = runCatching {
        val dir = File(context.cacheDir, "camera").also { it.mkdirs() }
        sweep(dir)
        val target = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", target
        )
        target to uri
    }.onFailure { EventLog.error("picture", "Could not prepare the camera: ${it.message}") }
        .getOrNull()

    /** Yesterday's shots have already been placed or abandoned; either way they are not needed. */
    private fun sweep(dir: File) {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) runCatching { f.delete() } }
    }

    private fun fit(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest
        val out = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    /**
     * Put the picture the way up the camera says it was taken.
     *
     * Phone cameras record the sensor's orientation in EXIF rather than rotating the pixels, so a
     * photograph taken in portrait decodes on its side. Dropping a sideways picture onto a page
     * and leaving the user to rotate it is a failure to read data that was right there.
     */
    private fun applyOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return runCatching {
            val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            if (out !== bitmap) bitmap.recycle()
            out
        }.getOrDefault(bitmap)
    }
}
