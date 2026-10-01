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
        val key = AnswerKey.read(f, p, dpi)
        org.apache.pdfbox.Loader.loadPDF(f).use { d -> val pg = d.getPage(p); println("KEY page rotation ${pg.rotation}, media ${pg.mediaBox}, crop ${pg.cropBox}") }
        // Whether the key's heads sit on the page as drawn: the share with ink at their middles.
        if (key != null) {
            val (ink, _) = OmrRealPagesTest().renderAt(f, p)!!
            val heads = key.filter { it.kind == AnswerKey.Kind.HEAD_BLACK }
            val v = VectorKey.read(f, p, dpi)
            val stemmed = heads.count { h -> v?.stems?.any { st -> kotlin.math.abs(st.x - (h.x + h.width / 2)) < h.width && h.y >= minOf(st.y0, st.y1) - 4 && h.y <= maxOf(st.y0, st.y1) + 4 } == true }
            println("KEY stems ${v?.stems?.size}, beams ${v?.beams?.size}; heads with a stem beside them $stemmed")
            println("KEY heads ${heads.size}, on ink at their middles: ${heads.count { h -> ink[(h.x + h.width / 2).toInt(), h.y.toInt()] }}; page ${ink.width}x${ink.height}; first at ${heads.take(3).map { "${it.x.toInt()},${it.y.toInt()}" }}")
        }
        if (key == null) {
            // The key turned the page down: what the app's own reading of the PDF makes of it.
            val printed = PdfPrinted.read(f, p)
            println("KEY none; app's reader: " + (printed?.symbols?.groupingBy { it.kind }?.eachCount()?.entries?.sortedByDescending { it.value }?.joinToString { "${it.key} ${it.value}" } ?: "nothing"))
        }
        for (s in key.orEmpty().filter { it.x in a..b }.sortedWith(compareBy({ (it.y / 200).toInt() }, { it.x })))
            println("KEY ${s.kind} '${s.text}' x=${s.x.toInt()} y=${s.y.toInt()} w=${"%.1f".format(s.width)} size=${"%.1f".format(s.size)}")
        // Every character there, any font: what the key leaves out.
        val k = dpi / 72f
        val y0 = System.getProperty("inksheets.omr.y")?.split("..")?.map { it.toFloat() }
        org.apache.pdfbox.Loader.loadPDF(f).use { doc ->
            val seenCodes = HashSet<String>()
            object : org.apache.pdfbox.text.PDFTextStripper() {
                override fun writeString(text: String?, positions: MutableList<org.apache.pdfbox.text.TextPosition>?) {
                    for (t in positions.orEmpty()) {
                        val x = t.xDirAdj * k; val y = t.yDirAdj * k
                        if (x in a..b && (y0 == null || y in y0[0]..y0[1])) println("CHAR '${t.unicode}' code ${t.characterCodes?.toList()} font ${t.font?.name} x=${x.toInt()} y=${y.toInt()}")
                        // -Dinksheets.omr.shapes=1: each music-font character's outline as the outline matching sees it.
                        if (System.getProperty("inksheets.omr.shapes") != null && t.font?.name?.contains("Opus") == true && seenCodes.add(t.font.name + t.characterCodes?.firstOrNull())) {
                            val code = t.characterCodes?.firstOrNull() ?: continue
                            val c = PdfPrinted.contours(t.font, code)
                            val xs = c?.flatMap { k -> (k.indices step 2).map { k[it] } }; val ys = c?.flatMap { k -> (1 until k.size step 2).map { k[it] } }
                            val fam = com.inksheets.core.omr.Printed.familyOf(t.font.name)
                            val scaled = c?.map { k -> FloatArray(k.size) { k[it] * 4f } }
                            val id = if (fam != null && scaled != null) com.inksheets.core.omr.GlyphShapes.identify(fam, scaled) else null
                            val cl = scaled?.let { com.inksheets.core.omr.GlyphShapes.classify(it) }
                            println("SHAPE ${t.font.name} code $code contours ${c?.size} box ${xs?.let { "%.2f..%.2f".format(it.min(), it.max()) }} x ${ys?.let { "%.2f..%.2f".format(it.min(), it.max()) }} family $fam identify ${id?.first}/${id?.second?.kind} classify ${cl?.kind} ${cl?.score}")
                        }
                    }
                }
            }.apply { startPage = p + 1; endPage = p + 1; sortByPosition = false }.getText(doc)
        }
    }
}
