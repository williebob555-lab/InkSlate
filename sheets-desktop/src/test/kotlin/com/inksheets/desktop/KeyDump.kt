package com.inksheets.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The answer key's symbols between two x's on a page: -Dinksheets.omr=key -Dinksheets.omr.file=... -Dinksheets.omr.x=a..b [-Dinksheets.omr.page=1] */
class KeyDump {
    @Test
    fun `the key between two places`() {
        assumeTrue(System.getProperty("inksheets.omr") == "key")
        val f = File(System.getProperty("inksheets.omr.file")!!)
        val p = (System.getProperty("inksheets.omr.page") ?: "1").toInt() - 1
        val (a, b) = System.getProperty("inksheets.omr.x")!!.split("..").map { it.toFloat() }
        val (_, dpi) = OmrRealPagesTest().renderAt(f, p)!!
        for (s in AnswerKey.read(f, p, dpi)!!.filter { it.x in a..b }.sortedWith(compareBy({ (it.y / 200).toInt() }, { it.x })))
            println("KEY ${s.kind} '${s.text}' x=${s.x.toInt()} y=${s.y.toInt()} w=${"%.1f".format(s.width)} size=${"%.1f".format(s.size)}")
        // Every character there, any font: what the key leaves out.
        val k = dpi / 72f
        val y0 = System.getProperty("inksheets.omr.y")?.split("..")?.map { it.toFloat() }
        org.apache.pdfbox.Loader.loadPDF(f).use { doc ->
            object : org.apache.pdfbox.text.PDFTextStripper() {
                override fun writeString(text: String?, positions: MutableList<org.apache.pdfbox.text.TextPosition>?) {
                    for (t in positions.orEmpty()) {
                        val x = t.xDirAdj * k; val y = t.yDirAdj * k
                        if (x in a..b && (y0 == null || y in y0[0]..y0[1])) println("CHAR '${t.unicode}' code ${t.characterCodes?.toList()} font ${t.font?.name} x=${x.toInt()} y=${y.toInt()}")
                    }
                }
            }.apply { startPage = p + 1; endPage = p + 1; sortByPosition = false }.getText(doc)
        }
    }
}
