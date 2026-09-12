package com.inkslate.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A page that grows to fit what is drawn on it.
 *
 * ## The idea
 *
 * A whiteboard has no edges, but a PDF page does, and the document has to stay a PDF - it is what
 * syncs, what opens on the laptop, and what gets handed in. So the page is not fixed: it is
 * whatever rectangle currently contains the work, and it is enlarged as the work spreads. The
 * user never asks for more room; they just write, and the room appears.
 *
 * ## Why coordinates never move
 *
 * The obvious way to grow leftwards is to shift everything right and keep the origin at zero.
 * That would rewrite every stroke on the page each time the canvas grew past its left edge, and
 * on a synced document that is the same trap page rearrangement had: a device that has not seen
 * the shift merges its marks back in against the old origin and scatters them.
 *
 * So the canvas keeps its own origin instead, and it is allowed to be negative. Ink written to
 * the left of where the document started simply has a negative x, forever. Nothing is ever
 * rewritten, two devices that both grew the canvas merge by taking the union of their rectangles,
 * and that union is the same whichever order it happens in.
 *
 * [paperLeft] and friends record the part of the canvas the PDF page itself covers, which lags
 * behind while drawing and catches up on save. Outside it, the paper is drawn by the app; inside,
 * the page's own raster shows. That is what makes growth free until the document is written.
 */
@Serializable
data class InkCanvas(
    @SerialName("left") val left: Float = 0f,
    @SerialName("top") val top: Float = 0f,
    @SerialName("right") val right: Float = 1224f,
    @SerialName("bottom") val bottom: Float = 1584f,
    /** The region the document's own page covers, in canvas coordinates. */
    @SerialName("paperLeft") val paperLeft: Float = 0f,
    @SerialName("paperTop") val paperTop: Float = 0f,
    @SerialName("paperRight") val paperRight: Float = 1224f,
    @SerialName("paperBottom") val paperBottom: Float = 1584f,
    /** Name of a [com.inkslate.core] -agnostic background; the app maps it to a pattern. */
    @SerialName("background") val background: String = "PLAIN",
    @SerialName("paperColor") val paperColor: Int = WHITE,
    @SerialName("lineColor") val lineColor: Int = DEFAULT_RULING,
    @SerialName("spacing") val spacing: Float = 24f,
    /**
     * Whether the paper inside the page was ruled by this program rather than by a document.
     *
     * A whiteboard started from nothing has its ruling written into the page, so that the file
     * still looks ruled in any other reader - and the same ruling painted across the rest of the
     * canvas, which is not part of the page at all. Two drawings of the same lines, meeting at the
     * page's edge: one a picture of ruling, the other the ruling itself, and at close zoom you can
     * see exactly where the page used to end.
     *
     * Where this is set, the whole canvas is ruled directly and the picture of the page is not
     * drawn over the middle of it. The ruling is then one drawing, sharp at any magnification,
     * with no edge anywhere - and the page in the file is untouched, so it still opens ruled
     * everywhere else.
     *
     * False for a canvas made out of a document somebody brought with them, whose page has content
     * that has to be shown.
     */
    @SerialName("ownPaper") val ownPaper: Boolean = false
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val paperWidth: Float get() = paperRight - paperLeft
    val paperHeight: Float get() = paperBottom - paperTop

    val box: Box get() = Box(left, top, right, bottom)
    val paperBox: Box get() = Box(paperLeft, paperTop, paperRight, paperBottom)

    /**
     * Grow to contain [content], with [margin] of clear space around it.
     *
     * Enlargement is quantised to [chunk] so that writing along an edge does not re-lay-out the
     * canvas on every sample; it steps out once and then has room to spare. It only ever grows -
     * a canvas that shrank back when you erased something would move the page under your hand.
     */
    fun grownTo(
        content: Box,
        margin: Float = 96f,
        chunk: Float = 256f,
        /**
         * The most the canvas may span in either direction.
         *
         * "Infinite" describes how it should feel to use, not a promise about the arithmetic. A
         * page a few hundred feet across is not a document any more - it is a way to lose an
         * afternoon's work inside a scroll bar, and a PDF nothing else will open.
         */
        maxSpan: Float = Float.MAX_VALUE
    ): InkCanvas {
        val wantLeft = content.left - margin
        val wantTop = content.top - margin
        val wantRight = content.right + margin
        val wantBottom = content.bottom + margin
        if (wantLeft >= left && wantTop >= top && wantRight <= right && wantBottom <= bottom) {
            return this
        }
        // Step out in whole chunks measured from the current edge, so the seams stay predictable.
        fun outLow(current: Float, wanted: Float): Float =
            if (wanted >= current) current
            else current - ceil((current - wanted) / chunk) * chunk

        fun outHigh(current: Float, wanted: Float): Float =
            if (wanted <= current) current
            else current + ceil((wanted - current) / chunk) * chunk

        var l = outLow(left, wantLeft)
        var t = outLow(top, wantTop)
        var r = outHigh(right, wantRight)
        var b = outHigh(bottom, wantBottom)

        /**
         * Trim growth back to the limit, taking it from whichever side actually moved.
         *
         * Capping the far edge to `near + maxSpan` would be simpler and wrong: a canvas that has
         * grown a long way to the left would have its right-hand side - where all the work is -
         * cut off. Only new room is ever given back, and never more than was just added.
         */
        fun limit(lowNow: Float, highNow: Float, lowWas: Float, highWas: Float): Pair<Float, Float> {
            val over = (highNow - lowNow) - maxSpan
            if (over <= 0f) return lowNow to highNow
            val grewLow = lowWas - lowNow
            val grewHigh = highNow - highWas
            val added = grewLow + grewHigh
            if (added <= 0f) return lowWas to highWas      // already over; simply refuse to grow
            val giveBack = minOf(over, added)
            return (lowNow + giveBack * (grewLow / added)) to
                (highNow - giveBack * (grewHigh / added))
        }

        val (nl, nr) = limit(l, r, left, right)
        val (nt, nb) = limit(t, b, top, bottom)
        l = nl; r = nr; t = nt; b = nb

        return copy(left = l, top = t, right = r, bottom = b)
    }

    /** The canvas as it will be once the document's page has been written to match it. */
    fun withPaperMatched(): InkCanvas = copy(
        paperLeft = left, paperTop = top, paperRight = right, paperBottom = bottom
    )

    /** True when the page on disk no longer covers the canvas, so a save has work to do. */
    val paperIsBehind: Boolean
        get() = paperLeft > left || paperTop > top || paperRight < right || paperBottom < bottom

    /**
     * Fold another device's idea of the canvas into this one.
     *
     * The union, which is the only answer that cannot lose work and the only one that does not
     * depend on which device ran it. Paper takes the union too: claiming a larger covered region
     * than really exists only means some paper is drawn twice, whereas claiming a smaller one
     * would leave a hole.
     */
    fun mergeWith(other: InkCanvas): InkCanvas = copy(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom),
        paperLeft = min(paperLeft, other.paperLeft),
        paperTop = min(paperTop, other.paperTop),
        paperRight = max(paperRight, other.paperRight),
        paperBottom = max(paperBottom, other.paperBottom)
    )

    companion object {
        /** Plain white paper. ARGB, matching the platform's own packing. */
        const val WHITE: Int = -0x1
        /** The default ruling colour, `#8FA8C8`. */
        const val DEFAULT_RULING: Int = -0x705738

        /**
         * A canvas the size of one page, ready to grow.
         *
         * Starts at the page's own bounds so a document that becomes a canvas looks exactly as it
         * did a moment earlier, and only changes as something is drawn past its edge.
         */
        fun startingAt(
            width: Float,
            height: Float,
            background: String = "PLAIN",
            paperColor: Int = WHITE,
            lineColor: Int = DEFAULT_RULING,
            spacing: Float = 24f,
            /** True only when this program ruled the page itself; see [InkCanvas.ownPaper]. */
            ownPaper: Boolean = false
        ) = InkCanvas(
            left = 0f, top = 0f, right = width, bottom = height,
            paperLeft = 0f, paperTop = 0f, paperRight = width, paperBottom = height,
            background = background, paperColor = paperColor,
            lineColor = lineColor, spacing = spacing, ownPaper = ownPaper
        )

        /** Snap a value down to a multiple of [step]; used when laying patterns out. */
        fun snapDown(value: Float, step: Float): Float =
            if (step <= 0f) value else floor(value / step) * step
    }
}
