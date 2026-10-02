package com.inksheets.desktop

import com.inksheets.core.omr.MarkReader
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The mark reader as the app runs it (Kotlin) gives what the network gave when trained (numpy, from
 * the same weights): train/data/marks/parity.tsv, written by marksparity.py. Skipped without it.
 */
class MarkReaderParityTest {
    @Test
    fun `the app's mark reader agrees with the trained network`() {
        val f = File("../train/data/marks/parity.tsv")
        assumeTrue(f.isFile && System.getProperty("inksheets.omr.marknet") != null)
        for (line in f.readLines().filter { it.isNotBlank() }) {
            val r = line.split("\t")
            val feats = (r[2].map { Integer.parseInt(it.toString(), 16) / 15f } + r[3].split(",").map { it.toFloat() }).toFloatArray()
            val (label, p) = MarkReader.classify(feats)!!
            assertEquals(MarkReader.LABELS[r[0].toInt()], label)
            assertEquals(r[1].toFloat(), p, 1e-3f)
        }
    }
}
