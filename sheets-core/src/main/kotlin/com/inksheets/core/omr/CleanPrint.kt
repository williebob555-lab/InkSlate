package com.inksheets.core.omr

import java.io.ByteArrayOutputStream

/**
 * A part printed afresh from its reading: the notes engraved cleanly, a line at a time, as a PDF
 * - for a copy that has been photocopied to pieces, or written over until it cannot be read. The
 * music is drawn as shapes (the music font's outlines, lines and beams), so it is sharp at any
 * size; the words in the PDF's own Helvetica, so nothing needs embedding.
 */
object CleanPrint {
    /** Points to a staff space: a 7 mm staff, the size most parts are printed at. */
    const val SPACE = 5f
    private const val W = 612f       // US Letter
    private const val H = 792f
    private const val MARGIN = 40f
    private const val SYSTEM_GAP = 11f   // spaces from one staff's top line to the next's

    private class Page { val ops = StringBuilder() }

    /** [score] as a PDF: [title] over the first page, [part] under it. */
    fun pdf(score: Score, title: String, part: String): ByteArray {
        val pages = ArrayList<Page>()
        var page = Page().also { pages += it }
        var y = MARGIN + 60f
        text(page, title, W / 2, MARGIN + 18f, 18f, centred = true)
        text(page, part, MARGIN, MARGIN + 44f, 11f)
        val width = (W - 2 * MARGIN) / SPACE
        for (line in lines(score.measures, width)) {
            if (y + 8 * SPACE > H - MARGIN) {
                page = Page().also { pages += it }
                y = MARGIN + 20f
            }
            draw(page, line, width, MARGIN, y + 2 * SPACE)
            text(page, line.first().number.toString(), MARGIN, y + 0.5f * SPACE, 7f)
            y += SYSTEM_GAP * SPACE
        }
        pages.forEachIndexed { i, p -> if (pages.size > 1) text(p, "${i + 1}", W - MARGIN, H - MARGIN / 2, 8f, right = true) }
        return write(pages)
    }

    /** The measures broken into lines no wider than [width] spaces, a multi-bar rest counted as one. */
    fun lines(measures: List<Measure>, width: Float): List<List<Measure>> {
        val out = ArrayList<List<Measure>>()
        var line = ArrayList<Measure>()
        for (m in measures) {
            val trial = line + m
            if (line.isNotEmpty() && layout(trial).width > width) {
                out += line
                line = arrayListOf(m)
            } else line += m
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    /** A line laid out: each line starts with its clef and key, as every printed line does. */
    private fun layout(line: List<Measure>): Engraver.Drawing {
        val first = line.first()
        val shown = line.mapIndexed { i, m ->
            // A multi-bar rest is given the room of two bars' rests; its own bar is drawn over them.
            val rest = if (m.bars > 1) m.copy(events = listOf(Rest(Duration(1), 0f), Rest(Duration(1), 0f))) else m
            // Where the original's lines began is no matter here: a clef and key at this line's
            // start, a time signature only where the time changes (or at the very start).
            val timeChanges = i > 0 && line[i - 1].time != m.time || (i == 0 && m.showsTime)
            rest.copy(showsClef = i == 0, showsKey = i == 0 && first.key.fifths != 0, showsTime = timeChanges)
        }
        return Engraver.line(shown)
    }

    /** [line] drawn [width] spaces wide - its bars spread to the margin, the shapes themselves not - at ([x], [top]). */
    private fun draw(p: Page, line: List<Measure>, width: Float, x: Float, top: Float) {
        val d = layout(line)
        // The last line of a part is not stretched far beyond its natural width.
        val stretch = if (d.width <= 0f) 1f else (width / d.width).coerceAtMost(if (d.width > width * 0.6f) 10f else 1.4f)
        val multi = line.indices.filter { line[it].bars > 1 }.map { d.measures[it] }
        fun sx(v: Float) = x + v * stretch * SPACE
        fun sy(v: Float) = top + v * SPACE
        for (mark in d.marks) when (mark) {
            is Engraver.Stroke -> {
                p.ops.append(f(mark.w * SPACE)).append(" w ").append(f(sx(mark.x1))).append(' ').append(f(H - sy(mark.y1))).append(" m ")
                    .append(f(sx(mark.x2))).append(' ').append(f(H - sy(mark.y2))).append(" l S\n")
            }
            is Engraver.Symbol -> {
                // Not the rests standing in for a multi-bar rest's room.
                if (mark.name == "restWhole" && multi.any { (a, b) -> mark.x in a..b }) continue
                fill(p, MusicGlyphs[mark.name].polygons(SPACE * mark.scale, sx(mark.x), sy(mark.y)))
            }
            is Engraver.Slab -> fill(p, listOf(FloatArray(mark.points.size) { i -> if (i % 2 == 0) sx(mark.points[i]) else sy(mark.points[i]) }))
        }
        // Multi-bar rests: their bar across the middle, and how many over it.
        for ((i, m) in line.withIndex()) {
            if (m.bars <= 1) continue
            val (a, b) = d.measures[i]
            val l = sx(a + 0.8f); val r = sx(b - 0.6f)
            fill(p, listOf(floatArrayOf(l, sy(1.75f), r, sy(1.75f), r, sy(2.25f), l, sy(2.25f))))
            p.ops.append(f(0.16f * SPACE)).append(" w ").append(f(l)).append(' ').append(f(H - sy(1f))).append(" m ").append(f(l)).append(' ').append(f(H - sy(3f))).append(" l S\n")
            p.ops.append(f(r)).append(' ').append(f(H - sy(1f))).append(" m ").append(f(r)).append(' ').append(f(H - sy(3f))).append(" l S\n")
            val digits = m.bars.toString()
            digits.forEachIndexed { k, c -> fill(p, MusicGlyphs["timeSig$c"].polygons(SPACE, (l + r) / 2 + (k - digits.length / 2f) * 1.8f * SPACE, sy(-2f))) }
        }
    }

    private fun fill(p: Page, polys: List<FloatArray>) {
        if (polys.isEmpty()) return
        for (c in polys) {
            if (c.size < 6) continue
            p.ops.append(f(c[0])).append(' ').append(f(H - c[1])).append(" m ")
            var i = 2
            while (i + 1 < c.size) { p.ops.append(f(c[i])).append(' ').append(f(H - c[i + 1])).append(" l "); i += 2 }
            p.ops.append("h\n")
        }
        p.ops.append("f*\n")
    }

    private fun text(p: Page, s: String, x: Float, y: Float, size: Float, centred: Boolean = false, right: Boolean = false) {
        // Helvetica's average width, near enough to centre a title.
        val w = s.length * size * 0.52f
        val at = when { centred -> x - w / 2; right -> x - w; else -> x }
        val safe = s.map { c -> if (c.code in 32..126) c else '?' }.joinToString("").replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        p.ops.append("BT /F1 ").append(f(size)).append(" Tf ").append(f(at)).append(' ').append(f(H - y)).append(" Td (").append(safe).append(") Tj ET\n")
    }

    private fun f(v: Float): String = if (v == v.toLong().toFloat()) v.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, v)

    /** The pages as a PDF file. */
    private fun write(pages: List<Page>): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun obj(body: String) { offsets += out.size(); out.write("${offsets.size} 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1)) }
        out.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        val pageIds = pages.indices.map { 4 + it * 2 }
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        for ((i, p) in pages.withIndex()) {
            val content = "0 g 0 G 1 J\n" + p.ops.toString()
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(W)} ${f(H)}] /Resources << /Font << /F1 3 0 R >> >> /Contents ${pageIds[i] + 1} 0 R >>")
            val bytes = content.toByteArray(Charsets.ISO_8859_1)
            offsets += out.size()
            out.write("${offsets.size} 0 obj\n<< /Length ${bytes.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        val xref = out.size()
        val sb = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", o))
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

}
