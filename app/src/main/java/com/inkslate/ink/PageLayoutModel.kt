package com.inkslate.ink

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * How pages are arranged on the canvas.
 *
 * The enum and the arithmetic behind it live in `:core`, shared with the Windows build - a
 * document read in two columns on one machine and one column on the other would be the same
 * document laid out by two different sets of rules, and only one of them can be right.
 */
typealias PageLayout = com.inkslate.core.PageLayout

/**
 * One page placed in document space.
 *
 * Stroke coordinates stay local to their page - that is what keeps saving, exporting and syncing
 * unchanged when the arrangement changes. [originX]/[originY] is the only thing that moves.
 */
class PageSlot(val index: Int, val width: Float, val height: Float) {
    var bitmap: Bitmap? = null
    var originX: Float = 0f
    var originY: Float = 0f

    /**
     * The page's content box, in full-page coordinates, or null if not measured.
     *
     * Cropping changes only how much of the page is shown; stroke coordinates stay relative to
     * the whole page so that toggling the crop cannot move existing ink, and so export is
     * unaffected either way.
     */
    var cropRect: RectF? = null
    var cropEnabled: Boolean = false

    /**
     * The extent of a canvas that grows to fit its content, in page coordinates.
     *
     * Mechanically this is a crop turned inside out - a rectangle that says which part of page
     * space the slot occupies - except that it is allowed to be *larger* than the page, and to
     * start at a negative coordinate. Reusing the crop's arithmetic is what lets an unbounded
     * canvas fall out of the existing layout, hit testing and clipping rather than needing a
     * parallel set of all three.
     */
    var canvasRect: RectF? = null

    /** The part of [canvasRect] the document's own page covers, and can supply a raster for. */
    var paperRect: RectF? = null

    private val crop: RectF? get() = canvasRect ?: (if (cropEnabled) cropRect else null)

    /** Offset from the full page to the visible area. */
    val cropLeft: Float get() = crop?.left ?: 0f
    val cropTop: Float get() = crop?.top ?: 0f

    val visibleWidth: Float get() = crop?.width() ?: width
    val visibleHeight: Float get() = crop?.height() ?: height

    val rect: RectF
        get() = RectF(originX, originY, originX + visibleWidth, originY + visibleHeight)

    fun contains(docX: Float, docY: Float): Boolean =
        docX >= originX && docX <= originX + visibleWidth &&
            docY >= originY && docY <= originY + visibleHeight

    /** Squared distance from a document point to this page, 0 when inside. */
    fun distanceSq(docX: Float, docY: Float): Float {
        val dx = when {
            docX < originX -> originX - docX
            docX > originX + visibleWidth -> docX - (originX + visibleWidth)
            else -> 0f
        }
        val dy = when {
            docY < originY -> originY - docY
            docY > originY + visibleHeight -> docY - (originY + visibleHeight)
            else -> 0f
        }
        return dx * dx + dy * dy
    }
}

/**
 * Places the slots, using the shared arithmetic in `core/PageArranger`.
 *
 * This end owns only what a slot *is* on Android - it carries a rendered bitmap, which is the one
 * part that cannot be shared. Where each page goes is decided in `:core`.
 */
object PageArranger {

    /** Space between pages, in page points. */
    const val GAP = com.inkslate.core.PageArranger.GAP

    /** Pages parked out of the way in SINGLE mode sit here. */
    const val FAR_AWAY = com.inkslate.core.PageArranger.FAR_AWAY

    private fun extentsOf(slots: List<PageSlot>) =
        slots.map { com.inkslate.core.PageExtent(it.visibleWidth, it.visibleHeight) }

    private fun canvasOf(slots: List<PageSlot>): com.inkslate.core.Box? =
        slots.firstOrNull { it.canvasRect != null }?.canvasRect?.let {
            com.inkslate.core.Box(it.left, it.top, it.right, it.bottom)
        }

    fun arrange(slots: List<PageSlot>, layout: PageLayout, currentPage: Int) {
        if (slots.isEmpty()) return
        val origins = com.inkslate.core.PageArranger.arrange(
            extentsOf(slots), layout, currentPage, canvasOf(slots)
        )
        for ((i, s) in slots.withIndex()) {
            val (x, y) = origins.getOrNull(i) ?: continue
            s.originX = x
            s.originY = y
        }
    }

    /** Overall extent of the arranged pages, in document space. */
    fun bounds(slots: List<PageSlot>, layout: PageLayout, currentPage: Int): RectF {
        if (slots.isEmpty()) return RectF(0f, 0f, 612f, 792f)
        val extents = extentsOf(slots)
        val box = com.inkslate.core.PageArranger.bounds(
            extents,
            slots.map { it.originX to it.originY },
            layout,
            currentPage,
            canvasOf(slots)
        )
        return RectF(box.left, box.top, box.right, box.bottom)
    }
}
