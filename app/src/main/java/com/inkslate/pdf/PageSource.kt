package com.inkslate.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Page dimensions in the document's own coordinate space (PDF points, or image pixels). */
data class PageDim(val width: Float, val height: Float)

/**
 * A paginated thing that can be drawn on: a PDF, an image, or a blank notebook.
 *
 * The editor only ever talks to this interface, which is what lets images and PDFs share one
 * annotation pipeline instead of two.
 */
interface PageSource : Closeable {
    val pageCount: Int
    val kind: String                       // "pdf" | "image" | "blank"
    fun pageDim(index: Int): PageDim

    /** Render a page at roughly [targetWidthPx] wide. Returns null if the page cannot be read. */
    fun renderPage(index: Int, targetWidthPx: Int): Bitmap?

    /**
     * Render just [region] of a page, at [targetWidthPx] across that region.
     *
     * Zooming in magnifies the page raster, and past a certain point that is visibly soft. Instead
     * of re-rendering the whole page at a resolution that would not fit in memory, only the part
     * being looked at is rendered sharply.
     */
    fun renderRegion(index: Int, region: RectF, targetWidthPx: Int): Bitmap?
}

/**
 * PDF pages via the platform renderer.
 *
 * [PdfRenderer] allows exactly one open page at a time and is not thread-safe, so every access
 * is serialised on [lock]. Rendering happens off the main thread, so this matters.
 */
class PdfPageSource(private val file: File) : PageSource {

    private val lock = Any()
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var closed = false

    override val kind = "pdf"

    init {
        val d = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = d
        renderer = PdfRenderer(d)
    }

    override val pageCount: Int
        get() = synchronized(lock) { if (closed) 0 else renderer?.pageCount ?: 0 }

    override fun pageDim(index: Int): PageDim = synchronized(lock) {
        val r = renderer ?: return PageDim(612f, 792f)
        if (closed || index !in 0 until r.pageCount) return PageDim(612f, 792f)
        r.openPage(index).use { PageDim(it.width.toFloat(), it.height.toFloat()) }
    }

    /**
     * Render a page, stepping the resolution down if memory is tight.
     *
     * A letter page at 2200px wide is a ~25 MB bitmap. On a device already holding a thumbnail
     * cache and a Compose UI that can fail, and an OOM here would take the whole app down while
     * simply opening a file - so allocation failures retry smaller rather than propagating.
     */
    override fun renderPage(index: Int, targetWidthPx: Int): Bitmap? = synchronized(lock) {
        // These two are bugs in the caller, not rendering failures, so they are thrown rather
        // than returned as null. Reporting "no bitmap" for a closed renderer once cost a long
        // debugging session over a page that silently showed blank.
        val r = renderer
        check(!closed && r != null) { "Renderer for ${file.name} was already closed" }
        require(index in 0 until r.pageCount) {
            "Page $index out of range for ${file.name} (${r.pageCount} pages)"
        }

        var width = targetWidthPx.coerceIn(MIN_RASTER_PX, MAX_RASTER_PX)
        var lastFailure: Throwable? = null
        repeat(4) {
            val attempt = runCatching { renderAt(r, index, width) }
            attempt.getOrNull()?.let { return it }
            lastFailure = attempt.exceptionOrNull()
            // Only memory pressure is worth retrying smaller; anything else will fail identically.
            if (lastFailure !is OutOfMemoryError) throw lastFailure ?: return null
            if (width <= MIN_RASTER_PX) throw lastFailure ?: return null
            com.inkslate.data.EventLog.warn(
                "render",
                "${file.name} page $index: out of memory at ${width}px, retrying smaller"
            )
            width = (width / 2).coerceAtLeast(MIN_RASTER_PX)
        }
        throw lastFailure ?: return null
    }

    /**
     * Sample the raster to see whether anything was actually drawn.
     *
     * Some PDFs - typically ones using features PDFium renders conservatively - come back
     * completely blank in display mode while printing correctly. A blank page and a page that
     * genuinely has nothing on it are indistinguishable to the user, so it is worth the cost of a
     * sparse sample to tell them apart and retry.
     */
    /**
     * Is this raster genuinely empty?
     *
     * Scans whole rows at a fine interval rather than sampling a sparse grid. A grid whose
     * spacing scales with the bitmap will step straight over thin content - music staves, rules,
     * hairlines - and report a perfectly good page as blank. Reading full rows means any
     * horizontal or vertical feature is caught by whichever rows cross it.
     *
     * Real content differs within the first row or two, so the early exit makes this effectively
     * free; only a truly blank page pays for the full scan.
     */
    private fun looksBlank(bmp: Bitmap): Boolean = runCatching {
        val w = bmp.width
        val h = bmp.height
        if (w < 2 || h < 2) return@runCatching true

        val row = IntArray(w)
        bmp.getPixels(row, 0, w, 0, 0, w, 1)
        val reference = row[0]

        val step = maxOf(1, h / 400)
        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (px in row) if (px != reference) return@runCatching false
            y += step
        }
        true
    }.getOrDefault(false)   // if the check itself fails, assume content and keep the render

    private fun renderAt(r: PdfRenderer, index: Int, width: Int): Bitmap =
        r.openPage(index).use { page ->
            val pw = max(1, page.width)
            val ph = max(1, page.height)
            val scale = width.toFloat() / pw
            var w = width
            var h = max(1, (ph * scale).roundToInt())
            // very tall pages must be capped on height too, keeping the aspect ratio intact
            if (h > MAX_RASTER_PX) {
                val shrink = MAX_RASTER_PX.toFloat() / h
                h = MAX_RASTER_PX
                w = max(MIN_RASTER_PX, (w * shrink).roundToInt())
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // PDFs render onto a transparent surface; paint white so pages look like paper
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            if (looksBlank(bmp)) {
                // Try print mode in a separate bitmap. Re-rendering over the original would
                // discard a good result whenever this check is wrong, and a wrong "blank" verdict
                // is far more damaging than a missed one.
                val alternate = runCatching {
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    }
                }.getOrNull()

                if (alternate != null && !looksBlank(alternate)) {
                    com.inkslate.data.EventLog.info(
                        "render",
                        "${file.name} page ${index + 1} needed print-mode rendering"
                    )
                    bmp.recycle()
                    return@use alternate
                }
                alternate?.recycle()
                com.inkslate.data.EventLog.warn(
                    "render",
                    "${file.name} page ${index + 1} appears to be an empty page"
                )
            }
            bmp
        }

    override fun renderRegion(index: Int, region: RectF, targetWidthPx: Int): Bitmap? =
        synchronized(lock) {
            val r = renderer
            if (closed || r == null || index !in 0 until r.pageCount) return null
            if (region.width() <= 0f || region.height() <= 0f) return null

            return runCatching {
                r.openPage(index).use { page ->
                    val w = targetWidthPx.coerceIn(MIN_RASTER_PX, MAX_RASTER_PX)
                    val scale = w / region.width()
                    var outW = w
                    var outH = max(1, (region.height() * scale).roundToInt())
                    if (outH > MAX_RASTER_PX) {
                        val shrink = MAX_RASTER_PX.toFloat() / outH
                        outH = MAX_RASTER_PX
                        outW = max(MIN_RASTER_PX, (outW * shrink).roundToInt())
                    }

                    val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)

                    // Scale the whole page up, then shift so the wanted region lands at the
                    // bitmap origin. PdfRenderer applies this transform before rasterising, so
                    // the result is genuinely re-rendered rather than an upscaled crop.
                    // Maps page coordinates so that `region` fills the bitmap exactly:
                    // region.left -> 0 and region.right -> outW.
                    val sx = outW / region.width()
                    val sy = outH / region.height()
                    val m = android.graphics.Matrix().apply {
                        setScale(sx, sy)
                        postTranslate(-region.left * sx, -region.top * sy)
                    }
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }.getOrNull()
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            renderer = null; pfd = null
        }
    }

    companion object {
        const val MAX_RASTER_PX = 4096
        const val MIN_RASTER_PX = 320
    }
}

/**
 * A single image treated as a one-page document.
 *
 * The page coordinate space is the image's upright pixel size, so annotations land correctly
 * regardless of the EXIF orientation the camera wrote.
 */
class ImagePageSource(private val file: File) : PageSource {

    override val kind = "image"
    override val pageCount = 1

    private val rotation: Int by lazy {
        runCatching {
            when (ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
    }

    private val rawDim: PageDim by lazy {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val w = opts.outWidth.takeIf { it > 0 } ?: 1000
        val h = opts.outHeight.takeIf { it > 0 } ?: 1000
        if (rotation == 90 || rotation == 270) PageDim(h.toFloat(), w.toFloat())
        else PageDim(w.toFloat(), h.toFloat())
    }

    override fun pageDim(index: Int) = rawDim

    /**
     * Decode the image, degrading gracefully rather than returning nothing.
     *
     * A single decode attempt fails for several unrelated reasons - the file is larger than the
     * heap allows, the encoder used a bit depth the platform decoder dislikes, the extension does
     * not match the actual format. Returning null for all of them produced a blank page with no
     * explanation, so each is now retried differently and the reason is recorded.
     */
    override fun renderPage(index: Int, targetWidthPx: Int): Bitmap? {
        val target = targetWidthPx.coerceIn(120, PdfPageSource.MAX_RASTER_PX)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            com.inkslate.data.EventLog.error(
                "render",
                "${file.name}: could not read image bounds " +
                    "(mime=${bounds.outMimeType ?: "unknown"}, ${file.length() / 1024}KB)"
            )
            return null
        }

        // subsample rather than decoding a 50MP photo in full and scaling down
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2

        // Increasingly conservative attempts: full colour, then half-depth colour, then smaller.
        val attempts = listOf(
            Bitmap.Config.ARGB_8888 to sample,
            Bitmap.Config.ARGB_8888 to sample * 2,
            Bitmap.Config.RGB_565 to sample * 2,
            Bitmap.Config.RGB_565 to sample * 4
        )

        for ((config, sampleSize) in attempts) {
            val decoded = runCatching {
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize.coerceAtLeast(1)
                        inPreferredConfig = config
                    }
                )
            }.getOrNull()

            if (decoded != null) {
                if (sampleSize != sample || config != Bitmap.Config.ARGB_8888) {
                    com.inkslate.data.EventLog.warn(
                        "render",
                        "${file.name}: decoded at reduced quality (${config.name}, 1/$sampleSize)"
                    )
                }
                return applyRotation(decoded)
            }
        }

        com.inkslate.data.EventLog.error(
            "render",
            "${file.name}: all decode attempts failed " +
                "(${bounds.outWidth}x${bounds.outHeight}, mime=${bounds.outMimeType ?: "unknown"})"
        )
        return null
    }

    override fun renderRegion(index: Int, region: RectF, targetWidthPx: Int): Bitmap? {
        val full = renderPage(index, PdfPageSource.MAX_RASTER_PX) ?: return null
        return runCatching {
            val sx = full.width / rawDim.width
            val sy = full.height / rawDim.height
            val left = (region.left * sx).toInt().coerceIn(0, full.width - 1)
            val top = (region.top * sy).toInt().coerceIn(0, full.height - 1)
            val right = (region.right * sx).toInt().coerceIn(left + 1, full.width)
            val bottom = (region.bottom * sy).toInt().coerceIn(top + 1, full.height)
            val crop = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
            if (crop !== full) full.recycle()
            crop
        }.getOrNull()
    }

    private fun applyRotation(decoded: Bitmap): Bitmap {
        if (rotation == 0) return decoded
        return runCatching {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
                .also { if (it !== decoded) decoded.recycle() }
        }.getOrDefault(decoded)
    }

    override fun close() = Unit
}

/**
 * Blank pages, for starting a notebook from nothing or padding a worksheet that ran out of room.
 * Optionally ruled or gridded, drawn at render time so nothing is baked into the saved file.
 */
class BlankPageSource(
    override val pageCount: Int = 1,
    private val dim: PageDim = PageDim(612f, 792f),
    private val template: Template = Template.PLAIN
) : PageSource {

    enum class Template(val label: String) {
        PLAIN("Blank"), RULED("Ruled"), GRID("Grid"), DOTS("Dot grid"), GRAPH("Graph")
    }

    override val kind = "blank"
    override fun pageDim(index: Int) = dim

    override fun renderPage(index: Int, targetWidthPx: Int): Bitmap? {
        val w = targetWidthPx.coerceIn(120, PdfPageSource.MAX_RASTER_PX)
        val scale = w / dim.width
        val h = max(1, (dim.height * scale).roundToInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        if (template == Template.PLAIN) return bmp

        val c = Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 40, 80, 140)
            strokeWidth = max(1f, scale * 0.6f)
            style = android.graphics.Paint.Style.STROKE
        }
        val step = (if (template == Template.GRAPH) 14f else 24f) * scale
        when (template) {
            Template.RULED -> {
                var y = step * 2
                while (y < h) { c.drawLine(0f, y, w.toFloat(), y, paint); y += step }
            }
            Template.GRID, Template.GRAPH -> {
                var x = step
                while (x < w) { c.drawLine(x, 0f, x, h.toFloat(), paint); x += step }
                var y = step
                while (y < h) { c.drawLine(0f, y, w.toFloat(), y, paint); y += step }
            }
            Template.DOTS -> {
                paint.style = android.graphics.Paint.Style.FILL
                val r = max(1f, scale * 1.1f)
                var y = step
                while (y < h) {
                    var x = step
                    while (x < w) { c.drawCircle(x, y, r, paint); x += step }
                    y += step
                }
            }
            Template.PLAIN -> Unit
        }
        return bmp
    }

    override fun renderRegion(index: Int, region: RectF, targetWidthPx: Int): Bitmap? {
        val full = renderPage(index, targetWidthPx) ?: return null
        return full
    }

    override fun close() = Unit
}

/** Picks the right [PageSource] for a file, or null if it is not something we can draw on. */
object PageSources {

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif")

    fun isSupported(file: File): Boolean {
        val e = file.extension.lowercase()
        return e == "pdf" || e in IMAGE_EXT
    }

    fun isImage(file: File) = file.extension.lowercase() in IMAGE_EXT
    fun isPdf(file: File) = file.extension.equals("pdf", ignoreCase = true)

    /**
     * Open a document for display.
     *
     * [context] is optional only so thumbnail code can stay simple. Pass it when opening a
     * document for editing: it lets a PDF that already has this app's strokes baked into it be
     * rendered from a stripped copy, so the handwriting is not drawn twice - once by the PDF and
     * once by the editable layer on top.
     */
    fun open(
        file: File,
        context: android.content.Context? = null,
        strokesBakedIn: Boolean = false
    ): PageSource? = runCatching {
        when {
            isPdf(file) -> {
                // Only a document that actually has this app's strokes painted into it needs a
                // stripped copy to render from. The caller has already looked, so this does not
                // go and look again.
                val render = if (context != null && strokesBakedIn) {
                    CleanRenderSource.renderSourceFor(context, file)
                } else file
                PdfPageSource(render)
            }
            isImage(file) -> ImagePageSource(file)
            else -> null
        }
    }.getOrNull()

    /** Cheap fingerprint used as a cache key. Changes on every save; see [geometryFingerprint]. */
    fun fingerprint(file: File): String =
        "${file.length()}-${file.lastModified()}"

    /**
     * What the annotations are actually positioned against: how many pages there are and how big
     * each one is, rounded to the point.
     *
     * Invariant under embedding handwriting into the document, which is the whole point - the
     * byte fingerprint changes on every save and so could never tell a real change apart from
     * our own.
     */
    fun geometryFingerprint(source: PageSource): String {
        val n = source.pageCount
        val sb = StringBuilder(source.kind).append(':').append(n)
        // A thousand-page book does not need every page measured to notice it was replaced.
        val sampled = if (n <= 24) (0 until n) else (0 until n step (n / 24).coerceAtLeast(1))
        for (i in sampled) {
            val d = source.pageDim(i)
            sb.append('|').append(d.width.roundToInt()).append('x').append(d.height.roundToInt())
        }
        return sb.toString()
    }

    /** Scale a page so its longest side is at most [maxPx], for thumbnails. */
    fun thumbWidthFor(dim: PageDim, maxPx: Int): Int {
        val aspect = dim.width / max(1f, dim.height)
        return if (aspect >= 1f) maxPx else max(60, (maxPx * aspect).roundToInt())
    }

    fun clampBitmap(bmp: Bitmap, maxPx: Int): Bitmap {
        val longest = max(bmp.width, bmp.height)
        if (longest <= maxPx) return bmp
        val s = maxPx.toFloat() / longest
        val out = Bitmap.createScaledBitmap(
            bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true
        )
        if (out !== bmp) bmp.recycle()
        return out
    }

    fun min(a: Int, b: Int) = kotlin.math.min(a, b)
}
