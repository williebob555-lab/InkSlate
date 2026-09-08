package com.inkslate.ink

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** How pages are arranged on the canvas. */
enum class PageLayout(val label: String) {
    SINGLE("One page"),
    VERTICAL("Vertical scroll"),
    HORIZONTAL("Horizontal scroll"),
    GRID("Grid"),
    SPREAD("Two-page spread");

    val isContinuous: Boolean get() = this != SINGLE
}

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

/** Computes page origins for a layout. Pure geometry, so it can be reasoned about on its own. */
object PageArranger {

    /** Space between pages, in page points. */
    const val GAP = 26f

    fun arrange(slots: List<PageSlot>, layout: PageLayout, currentPage: Int) {
        if (slots.isEmpty()) return
        // A canvas is placed at its own origin, whatever the layout says.
        //
        // This has to hold in *every* arrangement, not just the single-page one. A canvas has one
        // page, so every layout degenerates to placing that page - but the column and grid
        // arrangements place their first page at zero, and a canvas whose origin has gone
        // negative is then offset by exactly how far it has grown. Every coordinate on it shifts
        // by that amount, which on screen is the whole document sliding sideways the instant you
        // write past the top or left edge. Handling it once, here, is what makes page coordinates
        // and document coordinates the same thing for a canvas - which is the invariant that lets
        // it grow in any direction without anything appearing to move.
        val canvasSlot = slots.firstOrNull { it.canvasRect != null }
        if (canvasSlot != null) {
            val c = canvasSlot.canvasRect!!
            slots.forEach { it.originX = FAR_AWAY; it.originY = FAR_AWAY }
            canvasSlot.originX = c.left
            canvasSlot.originY = c.top
            return
        }

        when (layout) {
            PageLayout.SINGLE -> {
                // Only the current page occupies the canvas; the rest are parked off to one side
                // so nothing else can be hit-tested or drawn.
                slots.forEach { it.originX = FAR_AWAY; it.originY = FAR_AWAY }
                slots.getOrNull(currentPage)?.let { it.originX = 0f; it.originY = 0f }
            }

            PageLayout.VERTICAL -> {
                val widest = slots.maxOf { it.visibleWidth }
                var y = 0f
                for (s in slots) {
                    s.originX = (widest - s.visibleWidth) / 2f   // centre narrower pages in the column
                    s.originY = y
                    y += s.visibleHeight + GAP
                }
            }

            PageLayout.HORIZONTAL -> {
                val tallest = slots.maxOf { it.visibleHeight }
                var x = 0f
                for (s in slots) {
                    s.originX = x
                    s.originY = (tallest - s.visibleHeight) / 2f
                    x += s.visibleWidth + GAP
                }
            }

            PageLayout.SPREAD -> {
                // Facing pages, the way a physical book falls open: page 1 alone on the right,
                // then pairs. Reading a scanned textbook laid out for print is much easier this
                // way round than as a single column.
                val cellW = slots.maxOf { it.visibleWidth }
                val cellH = slots.maxOf { it.visibleHeight }
                for ((i, s) in slots.withIndex()) {
                    val row = (i + 1) / 2
                    val col = (i + 1) % 2
                    s.originX = col * (cellW + GAP) + (cellW - s.visibleWidth) / 2f
                    s.originY = row * (cellH + GAP) + (cellH - s.visibleHeight) / 2f
                }
            }

            PageLayout.GRID -> {
                val cols = max(1, ceil(sqrt(slots.size.toDouble())).toInt())
                val cellW = slots.maxOf { it.visibleWidth }
                val cellH = slots.maxOf { it.visibleHeight }
                for ((i, s) in slots.withIndex()) {
                    val r = i / cols
                    val c = i % cols
                    s.originX = c * (cellW + GAP) + (cellW - s.visibleWidth) / 2f
                    s.originY = r * (cellH + GAP) + (cellH - s.visibleHeight) / 2f
                }
            }
        }
    }

    /** Overall extent of the arranged pages, in document space. */
    fun bounds(slots: List<PageSlot>, layout: PageLayout, currentPage: Int): RectF {
        if (slots.isEmpty()) return RectF(0f, 0f, 612f, 792f)
        // A canvas sits at its own origin, which may be negative, so its extent is its rectangle
        // rather than a box starting at zero. Getting this wrong sends the scroll limits and the
        // culling test looking in the wrong place once it has grown up or left.
        slots.firstOrNull { it.canvasRect != null }?.let { return RectF(it.rect) }
        if (layout == PageLayout.SINGLE) {
            val s = slots.getOrNull(currentPage) ?: slots.first()
            return RectF(0f, 0f, s.visibleWidth, s.visibleHeight)
        }
        val r = RectF(slots.first().rect)
        slots.drop(1).forEach { r.union(it.rect) }
        return r
    }

    /** Pages parked out of the way in SINGLE mode sit here. */
    const val FAR_AWAY = 1_000_000f
}
