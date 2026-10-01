package com.inksheets.desktop

import org.apache.pdfbox.pdmodel.PDPage

/**
 * Where a point of a PDF page's own space (points, y up, as drawing commands give them) is on the
 * page as shown: points from its top left, the page turned as its rotation says (a Mac's print to
 * PDF stores a page sideways and turns it 270 degrees to show it). The text stripper's positions
 * are already as shown; drawn lines - stems, beams, dots - need this.
 */
class PageSpace(page: PDPage) {
    private val box = page.cropBox
    private val w = box.width
    private val h = box.height
    private val rotation = ((page.rotation % 360) + 360) % 360

    fun x(x: Float, y: Float): Float {
        val u = x - box.lowerLeftX; val v = y - box.lowerLeftY
        return when (rotation) { 90 -> v; 180 -> w - u; 270 -> h - v; else -> u }
    }

    fun y(x: Float, y: Float): Float {
        val u = x - box.lowerLeftX; val v = y - box.lowerLeftY
        return when (rotation) { 90 -> u; 180 -> v; 270 -> w - u; else -> h - v }
    }

    /** The page's width as shown, in points. */
    val width: Float get() = if (rotation == 90 || rotation == 270) h else w
    /** The page's height as shown, in points. */
    val height: Float get() = if (rotation == 90 || rotation == 270) w else h
}
