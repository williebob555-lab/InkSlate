package com.inksheets.desktop

import com.inksheets.core.omr.Ink
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * A page drawn as a heavy, old plate-print scan: ink thickened or thinned (the scanner's threshold),
 * blurred, bled into the staff lines, with pinholes and broken dots, specks of dirt, paper grain,
 * a slight turn. One profile per page, drawn from the seed - so the training sees thin and thick,
 * clean and broken. Used by MarkExport (-Dinksheets.bench.heavy=<seed>) to teach the mark reader
 * what Copprash-style scans look like (see train/marks.py).
 */
object HeavyScan {
    class Profile(val sigma: Double, val level: Double, val wander: Double, val pinholes: Double, val specks: Double, val turn: Double, val grain: Double) {
        override fun toString() = "sigma %.2f level %.2f wander %.2f pinholes %.4f specks %.5f turn %.2f grain %.0f".format(sigma, level, wander, pinholes, specks, turn, grain)
    }

    fun profile(seed: Long): Profile {
        val r = java.util.Random(seed * 2654435761L + 17)
        // Level: where in the blurred edge the scanner's threshold falls - low thickens the ink, high thins it.
        return Profile(sigma = 0.7 + r.nextDouble() * 1.1, level = 0.12 + r.nextDouble() * 0.56, wander = r.nextDouble() * 0.16,
            pinholes = if (r.nextInt(3) == 0) 0.0 else r.nextDouble() * 0.006, specks = r.nextDouble() * 2.5e-4, turn = (r.nextDouble() - 0.5) * 1.8, grain = 6 + r.nextDouble() * 14)
    }

    /** [ink] (the page, clean) as a grey scan under [p], seeded by [seed]. */
    fun grey(ink: Ink, p: Profile, seed: Long): IntArray {
        val r = java.util.Random(seed)
        val w = ink.width; val h = ink.height
        val a = Math.toRadians(p.turn); val cos = Math.cos(a); val sin = Math.sin(a)
        val cx = w / 2.0; val cy = h / 2.0
        var cov = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (dy in 0..1) for (dx in 0..1) {
                val px = x + 0.25 + dx * 0.5; val py = y + 0.25 + dy * 0.5
                val sx = cos * (px - cx) + sin * (py - cy) + cx; val sy = -sin * (px - cx) + cos * (py - cy) + cy
                if (ink[sx.toInt(), sy.toInt()]) s += 0.25f
            }
            cov[y * w + x] = s
        }
        // The optics' blur (a Gaussian, separable).
        val rad = max(1, Math.ceil(p.sigma * 2.6).toInt())
        val k = FloatArray(2 * rad + 1) { exp(-((it - rad) * (it - rad)) / (2 * p.sigma * p.sigma)).toFloat() }
        val ks = k.sum(); for (i in k.indices) k[i] /= ks
        val tmp = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in -rad..rad) { val xx = x + i; if (xx in 0 until w) s += cov[y * w + xx] * k[i + rad] }
            tmp[y * w + x] = s
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (i in -rad..rad) { val yy = y + i; if (yy in 0 until h) s += tmp[yy * w + x] * k[i + rad] }
            cov[y * w + x] = s
        }
        // The threshold wanders over the page (uneven inking, uneven light): value noise on a coarse grid.
        val cell = 40
        val gw = w / cell + 2; val gh = h / cell + 2
        val grid = FloatArray(gw * gh) { (r.nextDouble() * 2 - 1).toFloat() }
        var v = tmp  // reuse
        for (y in 0 until h) {
            val gy = y / cell; val fy = (y % cell) / cell.toFloat()
            for (x in 0 until w) {
                val gx = x / cell; val fx = (x % cell) / cell.toFloat()
                val n = grid[gy * gw + gx] * (1 - fx) * (1 - fy) + grid[gy * gw + gx + 1] * fx * (1 - fy) + grid[(gy + 1) * gw + gx] * (1 - fx) * fy + grid[(gy + 1) * gw + gx + 1] * fx * fy
                val t = (p.level + p.wander * n).toFloat()
                v[y * w + x] = ((cov[y * w + x] - t) * 7f + 0.5f).coerceIn(0f, 1f)
            }
        }
        cov = FloatArray(0)
        // Pinholes in the ink (a dot breaks into specks) and specks of dirt on the paper.
        if (p.pinholes > 0) {
            for (y in 1 until h - 1) for (x in 1 until w - 1) if (v[y * w + x] > 0.9f && r.nextDouble() < p.pinholes) {
                val big = r.nextInt(3)
                for (dy in 0..(if (big == 2) 1 else 0)) for (dx in 0..(if (big >= 1) 1 else 0)) v[(y + dy) * w + x + dx] = 0f
            }
        }
        val dirt = (w.toLong() * h * p.specks).toInt()
        repeat(dirt) {
            val x = r.nextInt(w - 2); val y = r.nextInt(h - 2); val big = r.nextInt(3)
            for (dy in 0..(if (big == 2) 1 else 0)) for (dx in 0..(if (big >= 1) 1 else 0)) v[(y + dy) * w + x + dx] = 1f
        }
        val grey = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val paper = 225 + (x * 20 / w) - (y * 10 / h)
            grey[y * w + x] = (paper - v[y * w + x] * 215 + r.nextGaussian() * p.grain).toInt().coerceIn(0, 255)
        }
        return grey
    }
}

class HeavyScanPreview {
    /** A preview: a crop of Copprash's No. 8 under several profiles, to compare with the real scan. */
    @Test
    fun `preview heavy profiles`() {
        val dir = System.getProperty("inksheets.heavy.preview") ?: return assumeTrue(false)
        val f = File(System.getenv("USERPROFILE"), "Music/Sheet Music/InkSheets/MobileSheets/Trombone Copprash.pdf")
        val bench = ReadingBenchmark()
        val songs = (System.getProperty("inksheets.heavy.file") ?: "").takeIf { it.isNotEmpty() }?.let { File(it) } ?: bench.corpus().first()
        val (clean, _) = OmrRealPagesTest().renderAt(songs, 0) ?: return
        File(dir).mkdirs()
        val x0 = clean.width / 8; val y0 = clean.height / 6
        for (seed in 1L..6L) {
            val p = HeavyScan.profile(seed)
            val grey = HeavyScan.grey(clean, p, seed)
            val bi = java.awt.image.BufferedImage(700, 260, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 260) for (x in 0 until 700) { val g = grey[(y0 + y) * clean.width + x0 + x]; bi.setRGB(x, y, g * 0x10101) }
            javax.imageio.ImageIO.write(bi, "png", File(dir, "heavy-$seed.png"))
            println("HEAVY $seed $p ${f.name} ${songs.name}")
        }
    }
}
