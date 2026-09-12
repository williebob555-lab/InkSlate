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

    var viewSize by mutableStateOf(Size.Zero)

    /** The middle of the window, which is what a keyboard zoom is about. */
    fun centreOfView(): Offset = Offset(viewSize.width / 2f, viewSize.height / 2f)

    fun screenToDoc(p: Offset) = Offset(p.x / scale + offset.x, p.y / scale + offset.y)

    fun docToScreen(x: Float, y: Float) =
        Offset((x - offset.x) * scale, (y - offset.y) * scale)

    // ---- moving --------------------------------------------------------------

    /** Move by a distance in screen pixels, which is what every input device speaks. */
    fun panBy(dxPx: Float, dyPx: Float) {
        offset = clamp(Offset(offset.x - dxPx / scale, offset.y - dyPx / scale))
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
     * Not a hard clamp to the content box: half a screen of slack in every direction is what
     * makes it possible to draw comfortably in a margin, and to write past the edge of a canvas
     * that is about to grow to meet it. Without any limit at all a stray throw sends the document
     * somewhere it takes a minute to find again.
     */
    private fun clamp(candidate: Offset): Offset {
        if (viewSize.width <= 0f || viewSize.height <= 0f) return candidate
        val slackX = viewSize.width / scale * 0.5f
        val slackY = viewSize.height / scale * 0.5f
        val minX = content.left - slackX
        val maxX = max(minX, content.right - viewSize.width / scale + slackX)
        val minY = content.top - slackY
        val maxY = max(minY, content.bottom - viewSize.height / scale + slackY)
        return Offset(candidate.x.coerceIn(minX, maxX), candidate.y.coerceIn(minY, maxY))
    }

    companion object {
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
