package com.inkslate.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.inkslate.core.Box
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where the document is under the window, and how far in.
 *
 * The Android build's camera, which lives inside its custom drawing surface. This one is separate
 * because on the desktop it also has to be driven by a scroll wheel and a middle mouse button,
 * and those are not gestures - they arrive as discrete events that have to be turned into motion.
 *
 * [offset] is the document point sitting at the top-left of the viewport, so screen and document
 * coordinates differ by a translate and a scale and nothing else. Keeping it that way is what
 * lets hit testing, drawing and the page arrangement all work in document points without any of
 * them knowing where the view happens to be.
 */
class Viewport {

    var scale by mutableStateOf(1f)
        private set

    var offset by mutableStateOf(Offset.Zero)
        private set

    /** Points per second, decaying, for the throw at the end of a pan. */
    var velocity by mutableStateOf(Offset.Zero)
        private set

    /** The document's extent, so panning can be held near it rather than off into nothing. */
    var content by mutableStateOf(Box(0f, 0f, 612f, 792f))

    private var sizeState by mutableStateOf(Size.Zero)

    /**
     * The window onto the document, in pixels.
     *
     * When it changes width - a split opened or closed, the window resized - what was in the
     * middle stays in the middle, and the top stays where it was. Held by the left edge instead,
     * a page centred in a narrow pane was left hugging the left of a wide one when the split closed.
     */
    var viewSize: Size
        get() = sizeState
        set(value) {
            val old = sizeState
            if (old.width > 0f && value.width > 0f && value.height > 0f && old.width != value.width) {
                offset = Offset(offset.x + (old.width - value.width) / (2f * scale), offset.y)
            }
            sizeState = value
        }

    /** The middle of the window, which is what a keyboard zoom is about. */
    fun centreOfView(): Offset = Offset(viewSize.width / 2f, viewSize.height / 2f)

    fun screenToDoc(p: Offset) = Offset(p.x / scale + offset.x, p.y / scale + offset.y)

    fun docToScreen(x: Float, y: Float) =
        Offset((x - offset.x) * scale, (y - offset.y) * scale)

    // ---- moving --------------------------------------------------------------

    /** Move by a distance in screen pixels, which is what every input device speaks. */
    /** True for a canvas, which needs room past its content to grow into. */
    var growable: Boolean = false

    /**
     * [freely] for two fingers, on the glass or a trackpad: the page can go off any edge. Anything
     * else - the pan tool, the middle button, a throw - goes no further off the page than it
     * already is; see [clamp].
     */
    fun panBy(dxPx: Float, dyPx: Float, freely: Boolean = false) {
        val next = Offset(offset.x - dxPx / scale, offset.y - dyPx / scale)
        offset = if (freely) clamp(next) else clamp(next, heldFrom = offset)
    }

    fun panTo(x: Float, y: Float) {
        offset = clamp(Offset(x, y))
    }

    /**
     * Zoom about a point on screen, so whatever is under the pointer stays under it.
     *
     * Anchoring anywhere else - the centre, say - makes zooming feel like the document is being
     * dragged out from under you, because the thing you were looking at is exactly the thing that
     * moves furthest.
     */
    fun zoomBy(factor: Float, aboutPx: Offset) {
        val next = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (next == scale) return
        val docBefore = screenToDoc(aboutPx)
        scale = next
        val docAfter = screenToDoc(aboutPx)
        offset = clamp(offset + (docBefore - docAfter))
    }

    fun setScale(next: Float, aboutPx: Offset) = zoomBy(next / scale, aboutPx)

    /**
     * Start a throw, scaled by how far the reader asked a flick to carry.
     *
     * Turning momentum off stops the throw here rather than at each call site, so a pan simply
     * ends where the hand left it - which is what someone who turned it off asked for.
     */
    fun throwBy(vx: Float, vy: Float) {
        if (!flingEnabled) {
            velocity = Offset.Zero
            return
        }
        velocity = Offset(vx * flingScale, vy * flingScale)
    }

    /** Set from the drawing settings; see [throwBy]. */
    var flingEnabled = true
    var flingScale = 1.35f

    fun stop() {
        velocity = Offset.Zero
    }

    /**
     * Advance a throw by one frame, returning false once it has run down.
     *
     * Friction per second rather than per frame, so a fast machine and a slow one come to rest
     * after the same distance rather than the same number of frames.
     */
    fun advanceMomentum(seconds: Float): Boolean {
        val v = velocity
        if (abs(v.x) < STOPPED && abs(v.y) < STOPPED) {
            velocity = Offset.Zero
            return false
        }
        panBy(v.x * seconds, v.y * seconds)
        val decay = Math.pow(FRICTION.toDouble(), seconds.toDouble()).toFloat()
        velocity = Offset(v.x * decay, v.y * decay)
        return true
    }

    // ---- fitting -------------------------------------------------------------

    /** Fit the whole of [box] in the window, which is what "fit page" means. */
    fun fit(box: Box, padding: Float = 24f) {
        if (viewSize.width <= 0f || viewSize.height <= 0f) return
        if (box.width <= 0f || box.height <= 0f) return
        val s = min(
            (viewSize.width - padding * 2f) / box.width,
            (viewSize.height - padding * 2f) / box.height
        ).coerceIn(MIN_SCALE, MAX_SCALE)
        scale = s
        offset = Offset(
            box.centerX - viewSize.width / (2f * s),
            box.centerY - viewSize.height / (2f * s)
        )
    }

    /**
     * [fit], keeping a [lane] clear down the right side (a landscape view) or along the bottom (a
     * portrait one) for buttons. When the margin left by fitting is already that wide nothing
     * changes and the page stays centred; otherwise it is fitted into what the lane leaves.
     */
    fun fitClear(box: Box, padding: Float, lane: Float, laneOnLeft: Boolean = false) {
        fit(box, padding)
        if (lane <= 0f || viewSize.width <= 0f || viewSize.height <= 0f) return
        // The lane is always down a side, and always kept whatever the page's shape, so every
        // page of every song sits in the same place and nothing jumps as you turn.
        val w = viewSize.width - lane
        val h = viewSize.height
        val s = min((w - padding * 2f) / box.width, (h - padding * 2f) / box.height).coerceIn(MIN_SCALE, MAX_SCALE)
        scale = s
        val shift = if (laneOnLeft) lane / s else 0f
        offset = Offset(box.centerX - w / (2f * s) - shift, box.centerY - h / (2f * s))
    }

    /** Fit the width of [box], leaving the top where it is - how a page is usually read. */
    fun fitWidth(box: Box, padding: Float = 24f) {
        if (viewSize.width <= 0f || box.width <= 0f) return
        val s = ((viewSize.width - padding * 2f) / box.width).coerceIn(MIN_SCALE, MAX_SCALE)
        scale = s
        offset = clamp(Offset(box.left - padding / s, offset.y))
    }

    /**
     * Put the camera back exactly where it was, for reopening a document where it was left.
     *
     * Deliberately not clamped: the content box is not known until the pages have been measured,
     * and clamping against a default would drag a restored position somewhere it never was.
     */
    fun restore(atScale: Float, atX: Float, atY: Float) {
        if (!atScale.isFinite() || atScale <= 0f) return
        scale = atScale.coerceIn(MIN_SCALE, MAX_SCALE)
        offset = Offset(atX, atY)
        velocity = Offset.Zero
    }

    /** Put a document point at the top-left, used when jumping to a page. */
    fun goTo(x: Float, y: Float, margin: Float = 18f) {
        offset = clamp(Offset(x - margin, y - margin))
        velocity = Offset.Zero
    }

    /**
     * Keep the document within reach.
     *
     * Without [heldFrom] - two fingers, a zoom, a jump - the page can go off any edge, and all that
     * is kept is a strip of it in the window so it cannot be lost. Nothing springs back.
     *
     * With it - the pan tool, the middle button, a throw - the edge of the document is the stop,
     * unless two fingers had already taken it past the edge: then it stays where it is and can
     * only come back, never go further. A page smaller than the window stays where it is put, and
     * a canvas keeps a margin past its content, which is where it grows.
     */
    private fun clamp(candidate: Offset, heldFrom: Offset? = null): Offset {
        if (viewSize.width <= 0f || viewSize.height <= 0f) return candidate
        if (heldFrom == null) {
            val keep = min(viewSize.width, viewSize.height) * KEEP_VISIBLE_FRACTION / scale
            val stripX = min(content.width, keep)
            val stripY = min(content.height, keep)
            val loX = content.left - viewSize.width / scale + stripX
            val loY = content.top - viewSize.height / scale + stripY
            return Offset(
                candidate.x.coerceIn(loX, max(loX, content.right - stripX)),
                candidate.y.coerceIn(loY, max(loY, content.bottom - stripY))
            )
        }
        val slackX = if (growable) viewSize.width / scale * 0.5f else 0f
        val slackY = if (growable) viewSize.height / scale * 0.5f else 0f
        val acrossView = viewSize.width / scale
        val downView = viewSize.height / scale

        val minX: Float
        val maxX: Float
        if (content.width >= acrossView) {
            minX = content.left - slackX
            maxX = max(minX, content.right - acrossView + slackX)
        } else {
            // Smaller than the window: anywhere that keeps all of it on screen.
            minX = content.right - acrossView - slackX
            maxX = max(minX, content.left + slackX)
        }

        val minY: Float
        val maxY: Float
        if (content.height >= downView) {
            minY = content.top - slackY
            maxY = max(minY, content.bottom - downView + slackY)
        } else {
            minY = content.bottom - downView - slackY
            maxY = max(minY, content.top + slackY)
        }

        // Wherever it already was counts as inside, so the stop is the edge or there, whichever is
        // further out.
        return Offset(
            candidate.x.coerceIn(min(minX, heldFrom.x), max(maxX, heldFrom.x)),
            candidate.y.coerceIn(min(minY, heldFrom.y), max(maxY, heldFrom.y))
        )
    }

    companion object {
        /** Share of the window's smaller side the document keeps covering under two fingers. */
        const val KEEP_VISIBLE_FRACTION = 0.22f

        const val MIN_SCALE = 0.08f
        const val MAX_SCALE = 32f

        /** Fraction of the speed kept after one second of coasting. */
        private const val FRICTION = 0.03f

        /** Below this, in points per second, a throw is over. */
        private const val STOPPED = 12f

        /**
         * How much one wheel notch zooms.
         *
         * A wheel reports in notches, not pixels, so this is a ratio rather than a distance -
         * which also makes zooming symmetric: in and straight back out lands exactly where it
         * started rather than slightly off.
         */
        const val ZOOM_PER_NOTCH = 1.18f

        /** How far one wheel notch throws the page when it is panning instead. */
        const val PAN_VELOCITY_PER_NOTCH = 900f

        /**
         * How far one notch moves the page directly, for two fingers on a trackpad.
         *
         * Not a velocity: fingers on a surface are still there to keep moving the page, so the
         * page goes where they put it and stops when they stop - the same as two fingers on the
         * screen. A trackpad reports fractions of a notch, so small movements stay small.
         */
        const val PAN_PER_NOTCH = 64f
    }
}
