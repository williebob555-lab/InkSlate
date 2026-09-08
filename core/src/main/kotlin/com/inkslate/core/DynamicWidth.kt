package com.inkslate.core

/**
 * Turning the width slider into a thickness on the glass rather than on the page.
 *
 * Drawing at 8x with a fixed page-space width gives a nib eight times fatter than the one you
 * were writing with a moment ago, which is why zooming in to squeeze an answer into a margin has
 * always been followed by a trip to the slider and another one back. What the hand is judging is
 * the mark it can see, so that is what is held constant.
 *
 * Lives in `:core` rather than in the drawing surface for the usual reason: it is the arithmetic
 * that decides what gets written into a document, the desktop build draws into the same documents,
 * and two implementations of the same formula is one of them being subtly wrong.
 */
object DynamicWidth {

    /** Below this the width is meaningless and the nominal value is the honest answer. */
    private const val MIN_SCALE = 1e-4f

    /**
     * The page-space width to build a stroke at.
     *
     * [nominal] is what the slider says. [referenceScale] is the zoom at which the slider means
     * literal page points - the document filling the viewport's width. [currentScale] is where
     * the view actually is. With [enabled] off this is [nominal] unchanged, which is what makes
     * the switch safe to flick: at the reference zoom the two answers are identical.
     */
    fun resolve(
        nominal: Float,
        referenceScale: Float,
        currentScale: Float,
        enabled: Boolean,
        min: Float = 0.02f,
        max: Float = 600f
    ): Float {
        if (!enabled) return nominal
        if (!nominal.isFinite() || nominal <= 0f) return nominal
        if (!referenceScale.isFinite() || referenceScale <= MIN_SCALE) return nominal
        if (!currentScale.isFinite() || currentScale <= MIN_SCALE) return nominal
        val scaled = nominal * referenceScale / currentScale
        if (!scaled.isFinite()) return nominal
        return scaled.coerceIn(min, max)
    }
}
