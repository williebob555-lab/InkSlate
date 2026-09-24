package com.inksheets.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * The top of a page as a picture, for text recognition: where a part's title and instrument are
 * printed. Rendered wide enough for small print to be read (1600 px across), and only the top
 * third, so the staves below stay out of it.
 */
internal object TopOfPage {

    private const val WIDTH = 1600
    private const val SHARE = 0.34f

    fun render(file: File, page: Int): Bitmap? = when (file.extension.lowercase()) {
        "pdf" -> pdf(file, page)
        "png", "jpg", "jpeg", "webp" -> picture(file)
        else -> null
    }

    private fun pdf(file: File, page: Int): Bitmap? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (page < 1 || page > renderer.pageCount) return null
                renderer.openPage(page - 1).use { p ->
                    val scale = WIDTH.toFloat() / p.width
                    val height = (p.height * scale * SHARE).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
                    // A scan is often a transparent-backed image; paper is white.
                    bitmap.eraseColor(Color.WHITE)
                    p.render(bitmap, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    bitmap
                }
            }
        }

    private fun picture(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= WIDTH) sample *= 2
        val full = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val top = Bitmap.createBitmap(full, 0, 0, full.width, (full.height * SHARE).toInt().coerceAtLeast(1))
        if (top !== full) full.recycle()
        return top
    }
}
