package com.inkslate.core

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

/** The size of one page as it is actually shown, after any crop. */
data class PageExtent(val width: Float, val height: Float)

/**
 * Where each page sits on the canvas.
 *
 * Pure geometry, taking sizes in and giving origins back, so that both builds arrange a document
 * the same way and neither owns the answer. Stroke coordinates stay local to their page - that is
 * what keeps saving, exporting and syncing unchanged when the arrangement changes. The origin is
 * the only thing that moves.
 */
object PageArranger {

    /** Space between pages, in page points. */
    const val GAP = 26f

    /** Pages parked out of the way in [PageLayout.SINGLE] sit here. */
    const val FAR_AWAY = 1_000_000f

    /**
     * Origins for [extents], one per page, in document space.
     *
     * [canvas] is the extent of a document that grows to fit its content, when it is one. A canvas
     * is placed at its own origin whatever the layout says, and that has to hold in *every*
     * arrangement rather than only the single-page one: a canvas has one page, so every layout
     * degenerates to placing that page - but the column and grid arrangements place their first
     * page at zero, and a canvas whose origin has gone negative would then be offset by exactly
     * how far it had grown. Every coordinate on it would shift by that amount, which on screen is
     * the whole document sliding sideways the instant you write past the top or left edge.
     * Handling it here is what makes page coordinates and document coordinates the same thing for
     * a canvas, which is the invariant that lets it grow in any direction without anything
     * appearing to move.
     */
    fun arrange(
        extents: List<PageExtent>,
        layout: PageLayout,
        currentPage: Int,
        canvas: Box? = null
    ): List<Pair<Float, Float>> {
        if (extents.isEmpty()) return emptyList()

        if (canvas != null) {
            return extents.indices.map { i ->
                if (i == 0) canvas.left to canvas.top else FAR_AWAY to FAR_AWAY
            }
        }

        return when (layout) {
            PageLayout.SINGLE ->
                // Only the current page occupies the canvas; the rest are parked off to one side
                // so nothing else can be hit-tested or drawn.
                extents.indices.map { i ->
                    if (i == currentPage) 0f to 0f else FAR_AWAY to FAR_AWAY
                }

            PageLayout.VERTICAL -> {
                val widest = extents.maxOf { it.width }
                var y = 0f
                extents.map { e ->
                    // centre narrower pages in the column
                    val origin = (widest - e.width) / 2f to y
                    y += e.height + GAP
                    origin
                }
            }

            PageLayout.HORIZONTAL -> {
                val tallest = extents.maxOf { it.height }
                var x = 0f
                extents.map { e ->
                    val origin = x to (tallest - e.height) / 2f
                    x += e.width + GAP
                    origin
                }
            }

            PageLayout.SPREAD -> {
                // Facing pages, the way a physical book falls open: page 1 alone on the right,
                // then pairs. Reading a scanned textbook laid out for print is much easier this
                // way round than as a single column.
                val cellW = extents.maxOf { it.width }
                val cellH = extents.maxOf { it.height }
                extents.mapIndexed { i, e ->
                    val row = (i + 1) / 2
                    val col = (i + 1) % 2
                    (col * (cellW + GAP) + (cellW - e.width) / 2f) to
                        (row * (cellH + GAP) + (cellH - e.height) / 2f)
                }
            }

            PageLayout.GRID -> {
                val cols = max(1, ceil(sqrt(extents.size.toDouble())).toInt())
                val cellW = extents.maxOf { it.width }
                val cellH = extents.maxOf { it.height }
                extents.mapIndexed { i, e ->
                    val r = i / cols
                    val c = i % cols
                    (c * (cellW + GAP) + (cellW - e.width) / 2f) to
                        (r * (cellH + GAP) + (cellH - e.height) / 2f)
                }
            }
        }
    }

    /**
     * Overall extent of the arranged pages.
     *
     * A canvas sits at its own origin, which may be negative, so its extent is its rectangle
     * rather than a box starting at zero. Getting this wrong sends the scroll limits and the
     * culling test looking in the wrong place once it has grown up or left.
     */
    fun bounds(
        extents: List<PageExtent>,
        origins: List<Pair<Float, Float>>,
        layout: PageLayout,
        currentPage: Int,
        canvas: Box? = null
    ): Box {
        if (extents.isEmpty()) return Box(0f, 0f, 612f, 792f)
        if (canvas != null) return canvas
        if (layout == PageLayout.SINGLE) {
            val e = extents.getOrNull(currentPage) ?: extents.first()
            return Box(0f, 0f, e.width, e.height)
        }
        var box: Box? = null
        for (i in extents.indices) {
            val (x, y) = origins.getOrNull(i) ?: continue
            if (x >= FAR_AWAY) continue
            val r = Box(x, y, x + extents[i].width, y + extents[i].height)
            box = box?.union(r) ?: r
        }
        return box ?: Box(0f, 0f, extents[0].width, extents[0].height)
    }
}
