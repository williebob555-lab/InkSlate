package com.inksheets.desktop.review

import org.apache.pdfbox.Loader
import org.junit.Assume.assumeTrue
import org.junit.Test

/** T5: lists real parts that have a reading, with their unsure bars and whether they are scans. */
class T5Pick {
    @Test
    fun list() {
        assumeTrue(T5Data.readings.isDirectory)
        val rows = ArrayList<String>()
        for (pdf in T5Data.pdfs()) {
            val d = T5Data.readingOf(pdf) ?: continue
            val s = T5Data.load(d) ?: continue
            val red = s.measures.count { !it.sure && it.bars == 1 }
            if (s.measures.size < 20 || red < 3) continue
            val scan = runCatching { Loader.loadPDF(pdf).use { doc -> val p = doc.getPage(0); val xo = p.resources.xObjectNames.toList(); val text = org.apache.pdfbox.text.PDFTextStripper().apply { endPage = 1 }.getText(doc).trim().length; "img=${xo.size} text=$text" } }.getOrDefault("?")
            rows += "T5 PICK ${pdf.name} (${pdf.parentFile.name}) ${d.name} bars=${s.measures.size} notSure=$red pages=${s.pages} $scan"
        }
        rows.take(400).forEach { println(it) }
    }
}
