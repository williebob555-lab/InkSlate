package com.inksheets.desktop

import com.inksheets.core.omr.Recognizer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One staff's traced lines against the page's, along its length: -Dinksheets.omr=trace
 * -Dinksheets.omr.file=... -Dinksheets.omr.bar=staff index (from 0).
 */
class OmrTraceDebug {
    @Test
    fun `a staff's trace against its lines`() {
        assumeTrue(System.getProperty("inksheets.omr") == "trace")
        val file = File(System.getProperty("inksheets.omr.file")!!)
        val si = System.getProperty("inksheets.omr.bar")!!.toInt()
        val ink = OmrRealPagesTest().render(file, 0)!!
        val r = Recognizer()
        val (t, space) = r.metrics(ink)!!
        val s = r.staves(ink, t, space)[si]
        println("staff $si: ${s.left}-${s.right}, space ${s.space}, line $t")
        for (x in s.left until s.right step 25) {
            val offs = (0..4).map { l ->
                val y0 = s.lineY(l, x).roundToInt()
                (-7..7).filter { dy -> (x - 3..x + 3).all { ink[it, y0 + dy] } }.minByOrNull { abs(it) }
            }
            println("x=$x top=${"%.1f".format(s.lineY(0, x))} offs=$offs")
        }
    }
}
