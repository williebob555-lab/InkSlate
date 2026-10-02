package com.inksheets.desktop

import com.inksheets.core.omr.WordReader
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Training data for the word reader: tempo words (and many others musicians print) drawn in the
 * italic serif fonts parts are set in, each roughened as a scan is - thickened or thinned, blurred,
 * grainy, a little turned, its box found a little loosely - cut as WordReader.features cuts a word.
 * -Dinksheets.wordsynth=<dir>
 */
class WordSynth {
    private val words = mapOf(
        "rit" to listOf("rit.", "rit", "ritard.", "ritardando", "rall.", "rallentando", "allarg.", "allargando", "poco rit.", "molto rit.", "poco rall.",
            "molto rall.", "slowing", "slower", "rit. e dim.", "Rit.", "Rall.", "poco a poco rit.", "ritenuto", "riten."),
        "atempo" to listOf("a tempo", "A tempo", "Tempo I", "tempo I", "in tempo", "Tempo primo", "a tempo", "In tempo", "Tempo 1"),
        "accel" to listOf("accel.", "accelerando", "poco accel.", "stringendo", "string.", "faster", "Accel.", "poco a poco accel."),
        "other" to listOf("cresc.", "dim.", "decresc.", "legato", "dolce", "espress.", "simile", "solo", "Solo", "tutti", "sub.", "marcato", "cantabile",
            "poco", "molto", "sempre", "gliss.", "div.", "unis.", "mute", "open", "arr.", "con sord.", "senza sord.", "Bsn.", "Hn.", "Tpt.", "subito",
            "ad lib.", "hold", "swing", "lay back", "pizz.", "arco", "staccato", "rubato", "legato cantabile", "p legato", "Fine", "D.S. al Coda",
            "To Coda", "Coda", "play", "2nd time only", "1st time", "tacet", "opt.", "fill", "drums", "with feeling", "lightly", "broadly", "freely",
            "Maestoso", "Allegro", "Andante", "Moderato", "Largo", "Presto", "Vivace", "dolce legato", "misterioso", "giocoso", "quasi", "poco a poco",
            "morendo", "sfz", "fp", "mp", "mf", "f", "ff", "p", "pp", "cresc. poco a poco", "dim. e rit.", "arr. Potter", "Craig Potter", "Holtz",
            "Bari", "Tenor", "Alto", "Clar.", "Fl.", "Tbn.", "Tuba", "Perc.", "Drum", "(Drum)", "Snare", "Bass", "cue", "sord.", "straight mute")
    )
    // (Some ending in rit ("e rit.") are slowing words still; "dim. e rit." counted with the others, being both.)

    private val fonts = listOf("Times New Roman", "Georgia", "Book Antiqua", "Palatino Linotype", "Cambria", "Constantia", "Garamond", "Century Schoolbook",
        "Bookman Old Style", "Serif").filter { name -> java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.any { it.equals(name, true) } || name == "Serif" }

    @Test
    fun `tempo words drawn as a scan shows them`() {
        val dir = System.getProperty("inksheets.wordsynth") ?: return assumeTrue(false)
        val r = java.util.Random(17)
        val out = File(dir).apply { mkdirs() }.resolve("words.tsv").bufferedWriter()
        var n = 0
        for ((label, list) in words) for (word in list) for (font in fonts) for (style in listOf(Font.ITALIC, Font.BOLD or Font.ITALIC)) for (v in 0 until (if (label == "other") 2 else 5)) {
            val size = 26 + r.nextInt(30)
            val f = Font(font, style, size)
            val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().apply { this.font = f }
            val fm = probe.fontMetrics
            val iw = fm.stringWidth(word) + size * 2; val ih = fm.height + size
            val img = BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color.WHITE; g.fillRect(0, 0, iw, ih)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.rotate(Math.toRadians((r.nextDouble() - 0.5) * 4), iw / 2.0, ih / 2.0)
            g.color = Color.BLACK; g.font = f
            g.drawString(word, size, size / 2 + fm.ascent)
            g.dispose()
            // As a scan: ink spread or worn, paper grain, a light grey ground.
            val px = IntArray(iw * ih); img.getRGB(0, 0, iw, ih, px, 0, iw)
            val dark = FloatArray(iw * ih) { 1f - (px[it] and 0xFF) / 255f }
            val spread = r.nextInt(3) - 1
            val grey = IntArray(iw * ih) { i ->
                val x = i % iw; val y = i / iw
                var d = dark[i]
                if (spread != 0) for (dy in -1..1) for (dx in -1..1) { val j = (y + dy).coerceIn(0, ih - 1) * iw + (x + dx).coerceIn(0, iw - 1); d = if (spread > 0) max(d, dark[j] * 0.8f) else min(d, dark[j] + 0.3f) }
                val paper = 225 + r.nextInt(20)
                (paper - d * 205 + r.nextGaussian() * 12).toInt().coerceIn(0, 255)
            }
            // Its box, found a little loosely.
            var l = iw; var t = ih; var rr = -1; var b = -1
            for (y in 0 until ih) for (x in 0 until iw) if (dark[y * iw + x] > 0.4f) { l = min(l, x); rr = max(rr, x); t = min(t, y); b = max(b, y) }
            if (rr < 0) continue
            val jw = (rr - l) * 0.04f; val jh = (b - t) * 0.08f
            val box = intArrayOf((l + (r.nextFloat() - 0.5f) * 2 * jw).toInt(), (t + (r.nextFloat() - 0.5f) * 2 * jh).toInt(),
                (rr + (r.nextFloat() - 0.5f) * 2 * jw).toInt(), (b + (r.nextFloat() - 0.5f) * 2 * jh).toInt())
            val feats = WordReader.features(grey, iw, ih, box)
            out.write("$label\t$word\tsynth\t" + (0 until WordReader.W * WordReader.H).joinToString("") { Integer.toHexString((feats[it] * 15f).toInt().coerceIn(0, 15)) } +
                "\t" + "%.3f".format(java.util.Locale.ROOT, feats.last()))
            out.newLine(); n++
        }
        out.close()
        println("WORDSYNTH: $n words in ${fonts.size} fonts")
    }
}
