package com.inksheets.core.omr

/**
 * The exact outline of a shape of ink: every edge between its pixels and paper, joined into closed
 * loops - its outside and any holes (an "o", a box round a number) - with straight runs merged.
 * Filled even-odd, the loops give the shape back pixel for pixel, at any size.
 */
object Outline {
    /** The pixels 8-connected to ([x], [y]) on [ink] (at most [limit]), as x, y pairs; empty on paper. */
    fun component(ink: Ink, x: Int, y: Int, seen: HashSet<Long>, limit: Int = 200_000): IntArray {
        if (!ink[x, y]) return IntArray(0)
        val out = ArrayList<Int>()
        val stack = ArrayDeque<Long>()
        fun key(a: Int, b: Int) = a.toLong() shl 32 or (b.toLong() and 0xffffffffL)
        stack += key(x, y)
        while (stack.isNotEmpty() && out.size < limit * 2) {
            val k = stack.removeLast()
            if (!seen.add(k)) continue
            val px = (k shr 32).toInt(); val py = k.toInt()
            if (!ink[px, py]) continue
            out += px; out += py
            for (dy in -1..1) for (dx in -1..1) if ((dx != 0 || dy != 0) && ink[px + dx, py + dy] && key(px + dx, py + dy) !in seen) stack += key(px + dx, py + dy)
        }
        return out.toIntArray()
    }

    /**
     * The loops round [pixels] (x, y pairs), each a polygon of pixel corners (x, y, x, y...),
     * clockwise round ink. Where two of its pixels touch only at a corner the loops are kept apart
     * there, so every loop is simple.
     */
    fun loops(pixels: IntArray): List<FloatArray> {
        if (pixels.isEmpty()) return emptyList()
        val set = HashSet<Long>(pixels.size)
        fun key(a: Int, b: Int) = a.toLong() shl 32 or (b.toLong() and 0xffffffffL)
        for (i in pixels.indices step 2) set += key(pixels[i], pixels[i + 1])
        fun inside(a: Int, b: Int) = key(a, b) in set
        // Directed edges, ink on their right: from corner to corner (corner (x, y) is the pixel's top left).
        val next = HashMap<Long, MutableList<Long>>()
        fun edge(ax: Int, ay: Int, bx: Int, by: Int) { next.getOrPut(key(ax, ay)) { ArrayList(2) } += key(bx, by) }
        for (i in pixels.indices step 2) {
            val x = pixels[i]; val y = pixels[i + 1]
            if (!inside(x, y - 1)) edge(x, y, x + 1, y)              // top, left to right
            if (!inside(x + 1, y)) edge(x + 1, y, x + 1, y + 1)      // right, down
            if (!inside(x, y + 1)) edge(x + 1, y + 1, x, y + 1)      // bottom, right to left
            if (!inside(x - 1, y)) edge(x, y + 1, x, y)              // left, up
        }
        val out = ArrayList<FloatArray>()
        while (next.isNotEmpty()) {
            val startKey = next.keys.first()
            val pts = ArrayList<Int>()
            var at = startKey
            var dirX = 0; var dirY = 0
            var guard = 0
            while (guard++ < 4 * pixels.size + 8) {
                val outs = next[at] ?: break
                val ax = (at shr 32).toInt(); val ay = at.toInt()
                // At a corner two pixels share diagonally, turn right: each loop stays simple.
                val pick = if (outs.size == 1) outs[0] else outs.minByOrNull { b ->
                    val bx = (b shr 32).toInt() - ax; val by = b.toInt() - ay
                    val cross = dirX * by - dirY * bx
                    -cross
                }!!
                outs.remove(pick)
                if (outs.isEmpty()) next.remove(at)
                val bx = (pick shr 32).toInt(); val by = pick.toInt()
                val ndx = bx - ax; val ndy = by - ay
                // A corner only where the way turns: straight runs come out as one edge.
                if (ndx != dirX || ndy != dirY) { pts += ax; pts += ay }
                dirX = ndx; dirY = ndy
                at = pick
                if (at == startKey) break
            }
            if (pts.size >= 6) out += FloatArray(pts.size) { pts[it].toFloat() }
        }
        return out
    }
}
