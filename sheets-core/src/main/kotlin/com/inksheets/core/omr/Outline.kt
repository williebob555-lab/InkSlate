package com.inksheets.core.omr

/**
 * The exact outline of a shape of ink: every edge between its pixels and paper, joined into closed
 * loops - its outside and any holes (an "o", a box round a number) - with straight runs merged.
 * Filled even-odd, the loops give the shape back pixel for pixel, at any size.
 *
 * Everything works on small grids the size of the region or the shape (never the whole page), as
 * a phone reads a page in a few seconds and has little memory to spare.
 */
object Outline {
    /** A part of the page: columns [x0] until [x0] + [w], rows [y0] until [y0] + [h]. */
    class Region(val x0: Int, val y0: Int, val w: Int, val h: Int) {
        val seen = BooleanArray(w * h)
        fun inside(x: Int, y: Int) = x >= x0 && y >= y0 && x < x0 + w && y < y0 + h
        fun index(x: Int, y: Int) = (y - y0) * w + (x - x0)
    }

    /**
     * The pixels of [ink] 8-connected to ([x], [y]) within [region] (at most [limit]), as x, y pairs,
     * marking them seen there; empty on paper or where already seen.
     */
    fun component(ink: Ink, x: Int, y: Int, region: Region, limit: Int = 200_000): IntArray {
        if (!region.inside(x, y) || !ink[x, y] || region.seen[region.index(x, y)]) return IntArray(0)
        var out = IntArray(256); var n = 0
        var stack = IntArray(256); var sp = 0
        fun push(a: Int, b: Int) { if (sp + 2 > stack.size) stack = stack.copyOf(stack.size * 2); stack[sp++] = a; stack[sp++] = b }
        region.seen[region.index(x, y)] = true
        push(x, y)
        while (sp > 0 && n < limit * 2) {
            val py = stack[--sp]; val px = stack[--sp]
            if (n + 2 > out.size) out = out.copyOf(out.size * 2)
            out[n++] = px; out[n++] = py
            for (dy in -1..1) for (dx in -1..1) {
                val qx = px + dx; val qy = py + dy
                if ((dx == 0 && dy == 0) || !region.inside(qx, qy) || !ink[qx, qy]) continue
                val i = region.index(qx, qy)
                if (region.seen[i]) continue
                region.seen[i] = true
                push(qx, qy)
            }
        }
        return out.copyOf(n)
    }

    /** [pixels] (x, y pairs) split into their 8-connected pieces. */
    fun pieces(pixels: IntArray): List<IntArray> {
        if (pixels.isEmpty()) return emptyList()
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        for (i in pixels.indices step 2) { l = minOf(l, pixels[i]); r = maxOf(r, pixels[i]); t = minOf(t, pixels[i + 1]); b = maxOf(b, pixels[i + 1]) }
        val region = Region(l, t, r - l + 1, b - t + 1)
        val ink = Ink(region.w, region.h)
        for (i in pixels.indices step 2) ink[pixels[i] - l, pixels[i + 1] - t] = true
        val local = Region(0, 0, region.w, region.h)
        val out = ArrayList<IntArray>()
        for (i in pixels.indices step 2) {
            val piece = component(ink, pixels[i] - l, pixels[i + 1] - t, local)
            if (piece.isEmpty()) continue
            for (j in piece.indices step 2) { piece[j] += l; piece[j + 1] += t }
            out += piece
        }
        return out
    }

    /**
     * The loops round [pixels] (x, y pairs), each a polygon of pixel corners (x, y, x, y...),
     * clockwise round ink. Where two of its pixels touch only at a corner the loops are kept apart
     * there, so every loop is simple.
     */
    fun loops(pixels: IntArray): List<FloatArray> {
        if (pixels.isEmpty()) return emptyList()
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        for (i in pixels.indices step 2) { l = minOf(l, pixels[i]); r = maxOf(r, pixels[i]); t = minOf(t, pixels[i + 1]); b = maxOf(b, pixels[i + 1]) }
        val w = r - l + 1; val h = b - t + 1
        val grid = BooleanArray(w * h)
        for (i in pixels.indices step 2) grid[(pixels[i + 1] - t) * w + (pixels[i] - l)] = true
        fun inside(x: Int, y: Int) = x >= l && y >= t && x <= r && y <= b && grid[(y - t) * w + (x - l)]
        // Corners are numbered on a grid one wider and taller than the pixels'.
        val cw = w + 1
        fun corner(x: Int, y: Int) = (y - t) * cw + (x - l)
        // Directed edges, ink on their right, from corner to corner: at most two leave any corner.
        val first = IntArray(cw * (h + 1)) { -1 }; val second = IntArray(cw * (h + 1)) { -1 }
        var edges = 0
        fun edge(ax: Int, ay: Int, bx: Int, by: Int) {
            val a = corner(ax, ay); val c = corner(bx, by)
            if (first[a] < 0) first[a] = c else second[a] = c
            edges++
        }
        for (i in pixels.indices step 2) {
            val x = pixels[i]; val y = pixels[i + 1]
            if (!inside(x, y - 1)) edge(x, y, x + 1, y)              // top, left to right
            if (!inside(x + 1, y)) edge(x + 1, y, x + 1, y + 1)      // right, down
            if (!inside(x, y + 1)) edge(x + 1, y + 1, x, y + 1)      // bottom, right to left
            if (!inside(x - 1, y)) edge(x, y + 1, x, y)              // left, up
        }
        val out = ArrayList<FloatArray>()
        var cursor = 0
        while (edges > 0) {
            while (first[cursor] < 0 && second[cursor] < 0) cursor++
            val start = cursor
            val pts = ArrayList<Int>()
            var at = start
            var dirX = 0; var dirY = 0
            var guard = 0
            while (guard++ < 4 * pixels.size + 8) {
                val ax = at % cw + l; val ay = at / cw + t
                val f = first[at]; val g = second[at]
                if (f < 0 && g < 0) break
                // At a corner two pixels share diagonally, turn right: each loop stays simple.
                val pick = if (g < 0) f else if (f < 0) g else {
                    fun cross(c: Int) = dirX * (c / cw + t - ay) - dirY * (c % cw + l - ax)
                    if (cross(f) >= cross(g)) f else g
                }
                if (pick == f) { first[at] = second[at]; second[at] = -1 } else second[at] = -1
                edges--
                val bx = pick % cw + l; val by = pick / cw + t
                val ndx = bx - ax; val ndy = by - ay
                // A corner only where the way turns: straight runs come out as one edge.
                if (ndx != dirX || ndy != dirY) { pts += ax; pts += ay }
                dirX = ndx; dirY = ndy
                at = pick
                if (at == start) break
            }
            if (pts.size >= 6) out += FloatArray(pts.size) { pts[it].toFloat() }
        }
        return out
    }
}
