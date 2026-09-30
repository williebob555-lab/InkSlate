package com.inksheets.desktop

import com.inksheets.core.omr.Digits
import com.inksheets.core.omr.Recognizer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The shapes over a staff's start - where its first bar's number is printed - and what each reads
 * as: -Dinksheets.omr=digits -Dinksheets.omr.file=... -Dinksheets.omr.bar=staff (from 0).
 */
class DigitsDebug {
    @Test
    fun `what the bar number reads as`() {
        assumeTrue(System.getProperty("inksheets.omr") == "digits")
        val file = File(System.getProperty("inksheets.omr.file")!!)
        val si = System.getProperty("inksheets.omr.bar")!!.toInt()
        val ink = OmrRealPagesTest().render(file, (System.getProperty("inksheets.omr.page") ?: "1").toInt() - 1)!!
        val reading = Recognizer().read(ink)
        val st = reading.staves[si]
        val sp = st.space
        val clean = ink
        val x0 = st.left - (sp * 3).toInt(); val x1 = st.left + (sp * 4).toInt()
        val y0 = st.y(-8, st.left).toInt(); val y1 = st.y(-1, st.left).toInt()
        println("band x $x0..$x1, y $y0..$y1, space $sp")
        for ((i, s2) in reading.staves.withIndex()) println("staff $i number: " + Digits.number(ink, s2.left - (sp * 3).toInt(), s2.left + (sp * 4).toInt(), s2.y(-8, s2.left).toInt(), s2.y(-1, s2.left).toInt(),
            (sp * 0.6f).toInt(), (sp * 2.5f).toInt(), bottomFrom = s2.y(-6, s2.left).toInt()))
        val seen = HashSet<Pair<Int, Int>>()
        for (y in y0..y1) for (x in x0 - (sp * 2).toInt()..x1 + (sp * 2).toInt()) {
            if (!clean[x, y] || (x to y) in seen) continue
            val stack = ArrayDeque<Pair<Int, Int>>(); stack += x to y
            var l = x; var r = x; var t = y; var b = y
            while (stack.isNotEmpty() && seen.size < 200_000) {
                val (px, py) = stack.removeLast()
                if (!clean[px, py] || !seen.add(px to py)) continue
                l = minOf(l, px); r = maxOf(r, px); t = minOf(t, py); b = maxOf(b, py)
                for (dy in -1..1) for (dx in -1..1) stack += (px + dx) to (py + dy)
            }
            val m = Digits.mask(clean, l, t, r, b) ?: continue
            println("shape $l..$r x $t..$b (${r - l + 1}x${b - t + 1}): reads ${Digits.read(m)}")
            for (row in 0 until Digits.H) println("   " + (0 until Digits.W).joinToString("") { if (m[row * Digits.W + it]) "#" else "." })
        }
        println("number: " + Digits.number(clean, x0, x1, y0, y1, (sp * 0.6f).toInt(), (sp * 2.5f).toInt(), bottomFrom = st.y(-5, st.left).toInt()))
    }
}
