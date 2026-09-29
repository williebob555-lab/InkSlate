package com.inksheets.core.omr

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The music symbols' outlines (from Bravura, SIL OFL - see omr/BRAVURA-LICENSE.txt), in staff
 * spaces, y downwards from the glyph's origin. Drawn for redrawn measures, and rasterized at a
 * page's own staff size as templates for reading it.
 */
object MusicGlyphs {
    /** One path command: 'M', 'L', 'Q', 'C' or 'Z' with its points. */
    class Cmd(val op: Char, val p: FloatArray)

    class Glyph(val name: String, val advance: Float, val path: List<Cmd>) {
        /** The outline as polygons (curves flattened), scaled by [scale] and moved to ([x], [y]). */
        fun polygons(scale: Float, x: Float = 0f, y: Float = 0f): List<FloatArray> {
            val out = ArrayList<FloatArray>()
            var cur = ArrayList<Float>()
            var px = 0f; var py = 0f
            fun add(ax: Float, ay: Float) { cur += x + ax * scale; cur += y + ay * scale; px = ax; py = ay }
            for (c in path) when (c.op) {
                'M' -> { if (cur.size >= 6) out += cur.toFloatArray(); cur = ArrayList(); add(c.p[0], c.p[1]) }
                'L' -> add(c.p[0], c.p[1])
                'Q' -> { val sx = px; val sy = py; for (i in 1..6) { val t = i / 6f; val u = 1 - t
                    add(u * u * sx + 2 * u * t * c.p[0] + t * t * c.p[2], u * u * sy + 2 * u * t * c.p[1] + t * t * c.p[3]) } }
                'C' -> { val sx = px; val sy = py; for (i in 1..8) { val t = i / 8f; val u = 1 - t
                    add(u * u * u * sx + 3 * u * u * t * c.p[0] + 3 * u * t * t * c.p[2] + t * t * t * c.p[4],
                        u * u * u * sy + 3 * u * u * t * c.p[1] + 3 * u * t * t * c.p[3] + t * t * t * c.p[5]) } }
                'Z' -> { if (cur.size >= 6) out += cur.toFloatArray(); cur = ArrayList() }
            }
            if (cur.size >= 6) out += cur.toFloatArray()
            return out
        }

        /** Its extent in staff spaces: left, top, right, bottom. */
        val bounds: FloatArray by lazy {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (poly in polygons(1f)) for (i in poly.indices step 2) {
                l = minOf(l, poly[i]); r = maxOf(r, poly[i]); t = minOf(t, poly[i + 1]); b = maxOf(b, poly[i + 1])
            }
            floatArrayOf(l, t, r, b)
        }
    }

    val all: Map<String, Glyph> by lazy {
        val text = MusicGlyphs::class.java.getResourceAsStream("/omr/glyphs.txt")!!.bufferedReader().readText()
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val parts = line.split(' ', limit = 3)
            val cmds = parts.getOrNull(2).orEmpty().split(' ').filter { it.isNotEmpty() }.map { tok ->
                Cmd(tok[0], if (tok.length > 1) tok.substring(1).split(',').map { it.toFloat() }.toFloatArray() else FloatArray(0))
            }
            parts[0] to Glyph(parts[0], parts[1].toFloat(), cmds)
        }
    }

    operator fun get(name: String): Glyph = all[name] ?: error("no glyph $name")

    /** A glyph drawn into a mask [space] pixels to the staff space; its origin at ([ox], [oy]) in it. */
    class Template(val ink: Ink, val ox: Int, val oy: Int) {
        /** How many of its pixels are ink. */
        val count: Int = ink.bits.count { it }
    }

    private val templates = HashMap<Pair<String, Int>, Template>()

    /** [name] at [space] pixels to a staff space (rounded to a tenth), cached. */
    @Synchronized
    fun template(name: String, space: Float): Template {
        val key = name to (space * 10).toInt()
        return templates.getOrPut(key) {
            val g = get(name)
            val b = g.bounds
            val ox = -floor(b[0] * space).toInt() + 1
            val oy = -floor(b[1] * space).toInt() + 1
            val w = ceil((b[2] - b[0]) * space).toInt() + 3
            val h = ceil((b[3] - b[1]) * space).toInt() + 3
            val ink = Ink(w, h)
            Fill.polygons(ink, g.polygons(space, ox.toFloat(), oy.toFloat()))
            Template(ink, ox, oy)
        }
    }
}
