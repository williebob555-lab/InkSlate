package com.inksheets.core.omr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A reading of a bar laid over the bar as printed, to see how well they line up: the reading drawn
 * where the bar is ([Engraver.aligned]), and the two compared pixel by pixel with a little give
 * (an engraver's heads, stems and beams are not the music font's). What the drawing puts where
 * the page has no ink counts against it - a head a step off, a filled head where it is hollow, a
 * beam too many - and so does the page's ink it leaves unexplained: a note it left out. Ink no
 * reading draws (a slur, a word) costs every reading alike, so readings of one bar compare fairly.
 */
object Overlay {
    /** The drawing of [events] in bar [m], in [m]'s page pixels: a mask over the bar's box, 5 spaces above and below. */
    private class Drawn(val x0: Int, val y0: Int, val ink: Ink)

    private fun draw(m: Measure, events: List<Event>): Drawn {
        val sp = m.space
        val x0 = m.box.left; val y0 = (m.box.top - sp * 5).roundToInt()
        val w = m.box.width + 1; val h = (m.box.bottom - m.box.top + sp * 10).roundToInt() + 1
        val out = Ink(max(1, w), max(1, h))
        val drawing = Engraver.aligned(m.copy(events = events, showsClef = false, showsKey = false, showsTime = false, directions = emptyList()))
        val ox = 0f; val oy = m.box.top - y0.toFloat()
        val polys = ArrayList<FloatArray>()
        for (mark in drawing.marks) when (mark) {
            is Engraver.Symbol -> polys += MusicGlyphs[mark.name].polygons(sp * mark.scale, ox + mark.x * sp, oy + mark.y * sp)
            is Engraver.Slab -> polys += FloatArray(mark.points.size) { i -> if (i % 2 == 0) ox + mark.points[i] * sp else oy + mark.points[i] * sp }
            is Engraver.Stroke -> {
                // A stroke as the thin box it is.
                val ax = ox + mark.x1 * sp; val ay = oy + mark.y1 * sp; val bx = ox + mark.x2 * sp; val by = oy + mark.y2 * sp
                val dx = bx - ax; val dy = by - ay
                val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
                val half = max(0.6f, mark.w * sp / 2)
                val nx = -dy / len * half; val ny = dx / len * half
                polys += floatArrayOf(ax + nx, ay + ny, bx + nx, by + ny, bx - nx, by - ny, ax - nx, ay - ny)
            }
        }
        Fill.polygons(out, polys)
        return Drawn(x0, y0, out)
    }

    /**
     * How well [events] read as bar [m] line up with [page] (its ink, staff lines and all): higher is
     * better. Each pixel drawn on the page's ink (within [give] spaces) counts for it, each drawn
     * where there is none counts twice against it, and each pixel of the page's ink near nothing
     * drawn counts against it once - all in square spaces, so bars of any size compare.
     */
    fun score(page: Ink, m: Measure, events: List<Event>, give: Float = 0.18f): Float {
        val d = draw(m, events)
        val sp = m.space
        val r = max(1, (sp * give).roundToInt())
        fun pageNear(x: Int, y: Int): Boolean {
            for (dy in -r..r) for (dx in -r..r) if (page[x + dx, y + dy]) return true
            return false
        }
        // The print's ink is explained only by drawing right on it (a pixel's give): a hollow head
        // drawn over a filled one leaves its middle unexplained.
        fun drawnNear(x: Int, y: Int): Boolean {
            for (dy in -1..1) for (dx in -1..1) if (d.ink[x - d.x0 + dx, y - d.y0 + dy]) return true
            return false
        }
        var on = 0; var off = 0; var missed = 0
        val x1 = min(page.width - 1, d.x0 + d.ink.width - 1); val y1 = min(page.height - 1, d.y0 + d.ink.height - 1)
        for (y in max(0, d.y0)..y1) for (x in max(0, d.x0)..x1) {
            val drawn = d.ink[x - d.x0, y - d.y0]
            if (drawn) { if (pageNear(x, y)) on++ else off++ }
            else if (page[x, y] && !drawnNear(x, y)) missed++
        }
        return (on - 2f * off - missed) / (sp * sp)
    }

    /**
     * [choices] of bar [m] (best first, as [BarChoices.of] gives them), the first kept first - the
     * reader's own best - and the rest the best lined up with [page] first, each weighed with its
     * [BarChoices.Choice.cost] by [weight]. (Held-out scans: laid over the print, the right reading
     * of a bar read wrong is among the three offered more often; put first, the overlay pushed
     * down readings that were right.)
     */
    fun rank(page: Ink, m: Measure, choices: List<BarChoices.Choice>, weight: Float = 1f): List<BarChoices.Choice> {
        if (choices.size < 3) return choices
        return listOf(choices[0]) + rankAll(page, m, choices.drop(1), weight)
    }

    /** [choices] the best lined up with [page] first, each weighed with its cost by [weight]. */
    fun rankAll(page: Ink, m: Measure, choices: List<BarChoices.Choice>, weight: Float = 1f): List<BarChoices.Choice> {
        if (choices.size < 2) return choices
        val scores = choices.map { score(page, m, it.events) }
        val best = scores.max()
        return choices.indices.sortedBy { i -> (best - scores[i]) * weight + choices[i].cost + if (choices[i].addsUp(m.time.quarters)) 0f else 10f }.map { choices[it] }
    }
}
