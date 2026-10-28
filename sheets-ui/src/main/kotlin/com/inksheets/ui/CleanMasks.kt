package com.inksheets.ui

import com.inkslate.core.BrushType
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke.Kind
import com.inkslate.core.PageMark
import com.inkslate.core.Perform
import com.inkslate.core.Stroke
import com.inkslate.core.StrokeOutline
import com.inkslate.core.Tool
import com.inkslate.core.ToolConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Base64
import java.util.WeakHashMap

/** Clean brush masks live in reading coordinates, independent of PDF render size or zoom.
 * Only completed strokes are saved. A moving pen replaces its preview rather than accumulating
 * overlapping copies; the final sample is one undo step, including an eraser stroke.
 */
object CleanMasks {
    private const val KIND = "sheets_clean_masks"
    private class FileMasks {
        var saved: String? = null
        var loaded = false
        var checkedAt = 0L
        val pages = linkedMapOf<Int, List<PageMark.MaskStroke>>()
        val undo = arrayListOf<Pair<Int, List<PageMark.MaskStroke>>>()
        var preview: Pair<Int, PageMark.MaskStroke>? = null
        var previewAt=0L
    }
    private val states = WeakHashMap<SheetsState, MutableMap<String, FileMasks>>()

    private fun masks(state: SheetsState, path: String): FileMasks {
        val data = states.getOrPut(state) { hashMapOf() }.getOrPut(path) { FileMasks() }
        val now = System.nanoTime()
        // One mask can be requested for every bar in a frame. Do not read the synced file for
        // every bar; still pick up edits from another device while this score remains open.
        if (data.loaded && (data.preview != null || now - data.checkedAt < 250_000_000L)) return data
        data.checkedAt = now
        val saved = Transcriber.keptEdit(state, File(path), KIND)
        if (!data.loaded || data.saved != saved) {
            data.pages.clear()
            data.pages.putAll(decode(saved))
            data.saved = saved
            data.loaded = true
            data.undo.clear() // A synced edit must not be undone with this device's stale history.
        }
        return data
    }

    /** Returned polygons must be multiplied by the reading-to-page scale by the renderer. */
    fun getMask(state: SheetsState, path: String, page: Int, baseFull: Boolean = false): PageMark.Mask? {
        val data = masks(state, path)
        val strokes = data.pages[page].orEmpty() + listOfNotNull(data.preview?.takeIf { it.first == page }?.second)
        return if (strokes.isEmpty() && !baseFull) null else PageMark.Mask(strokes, baseFull)
    }

    fun hasMask(state: SheetsState, path: String, page: Int): Boolean =
        getMask(state, path, page) != null

    fun canUndo(state: SheetsState, path: String): Boolean = masks(state, path).undo.isNotEmpty()

    /** [pts] are cumulative x/y pairs in PDF page points; [pageScale] is page points / reading unit.
     * Use the ToolConfig captured at pointer-down so changing a preset cannot alter a live stroke.
     * An eraser restores original print under its round outline, even on a fully cleaned bar.
     * Returns false for a throttled preview; callers owning their overlay version can notify once
     * after advancing it by passing notify=false. Completed strokes always bypass throttling.
     */
    fun gesture(state: SheetsState, path: String, page: Int, pts: FloatArray, done: Boolean,
                config: ToolConfig, pageScale: Float, notify: Boolean = true): Boolean {
        val data = masks(state, path)
        if (page < 0 || !pageScale.isFinite() || pageScale <= 0f || pts.size < 2 ||
            pts.size % 2 != 0 || pts.any { !it.isFinite() }) {
            data.preview = null
            if (notify) Perform.marksChanged()
            return true
        }
        val erase = config.tool == Tool.ERASER
        if(!done) {
            val now=System.nanoTime()
            if(data.preview!=null && now-data.previewAt<16_000_000L)return false
            data.previewAt=now
        }
        val width = (if (erase) config.eraserRadius * 2f else config.strokeWidth) / pageScale
        if (!width.isFinite() || width <= 0f) {
            data.preview = null
            if (notify) Perform.marksChanged()
            return true
        }
        val stroke = Stroke("clean", Kind.FREEHAND, 0, width,
            (pts.indices step 2).map { InkPoint(pts[it] / pageScale, pts[it + 1] / pageScale, width) },
            brush = if (erase) BrushType.BALLPOINT else config.brush)
        val mask = PageMark.MaskStroke(StrokeOutline.contours(stroke), erase)
        if (done) {
            val before = data.pages[page].orEmpty()
            data.undo += page to before
            data.pages[page] = before + mask
            data.preview = null
            save(state, path, data)
        } else data.preview = page to mask
        if (notify) Perform.marksChanged()
        return true
    }

    /** Cancel a pointer stream without committing its last preview (e.g. document switched). */
    fun cancel(state: SheetsState, path: String) {
        masks(state, path).preview = null
        Perform.marksChanged()
    }

    fun undo(state: SheetsState, path: String): Boolean {
        val data = masks(state, path)
        data.preview = null
        val previous = data.undo.removeLastOrNull() ?: return false
        if (previous.second.isEmpty()) data.pages.remove(previous.first) else data.pages[previous.first] = previous.second
        save(state, path, data)
        Perform.marksChanged()
        return true
    }

    private fun save(state: SheetsState, path: String, data: FileMasks) {
        val value = encode(data.pages)
        Transcriber.keepEdit(state, File(path), KIND, value)
        data.saved = value
    }

    // Versioned, bounded binary payload inside the same stable per-file edit storage as Clean/Fix.
    // Coordinates are retained exactly; text rounding would visibly shift repeated brush edits.
    private fun encode(pages: Map<Int, List<PageMark.MaskStroke>>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeInt(pages.size)
            pages.forEach { (page, strokes) ->
                out.writeInt(page); out.writeInt(strokes.size)
                strokes.forEach { stroke ->
                    out.writeBoolean(stroke.erase); out.writeInt(stroke.contours.size)
                    stroke.contours.forEach { polygon ->
                        out.writeInt(polygon.size); polygon.forEach(out::writeFloat)
                    }
                }
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private fun decode(value: String?): Map<Int, List<PageMark.MaskStroke>> = runCatching {
        if (value.isNullOrBlank()) return@runCatching emptyMap()
        val bytes = Base64.getDecoder().decode(value)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            fun count(max: Int): Int = input.readInt().also { require(it in 0..max) }
            require(input.readInt() == 1)
            val pages = linkedMapOf<Int, List<PageMark.MaskStroke>>()
            repeat(count(bytes.size / 8)) {
                val page = input.readInt().also { require(it >= 0) }
                pages[page] = List(count(bytes.size / 5)) {
                    val erase = input.readBoolean()
                    val contours = List(count(bytes.size / 4)) {
                        val n = count(bytes.size / 4).also { require(it >= 6 && it % 2 == 0) }
                        FloatArray(n) { input.readFloat().also { f -> require(f.isFinite()) } }
                    }
                    PageMark.MaskStroke(contours, erase)
                }
            }
            require(input.available() == 0)
            pages
        }
    }.getOrDefault(emptyMap())
}
