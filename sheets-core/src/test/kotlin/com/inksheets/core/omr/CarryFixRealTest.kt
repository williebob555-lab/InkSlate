package com.inksheets.core.omr

import com.inksheets.core.InstrumentReader
import org.junit.Test
import java.io.File

/**
 * How often bars count as "the same" between the parts of real songs in the library (read only):
 * every bar of every part, as if it had been put right, against the same bar of each other part.
 * Run with the environment variable INKSHEETS_CARRYREAL=1; prints per song and the pairs of instruments matched.
 */
class CarryFixRealTest {
    private val library = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    private fun idOf(pdf: File): String {
        val bytes = pdf.readBytes()
        val mark = "%%EOF".toByteArray()
        var end = bytes.size
        var i = 0
        outer@ while (i <= bytes.size - mark.size) {
            for (j in mark.indices) if (bytes[i + j] != mark[j]) { i++; continue@outer }
            end = i + mark.size
            while (end < bytes.size && (bytes[end] == '\r'.code.toByte() || bytes[end] == '\n'.code.toByte())) end++
            break
        }
        val crc = java.util.zip.CRC32(); crc.update(bytes, 0, end)
        return "${java.lang.Long.toHexString(end.toLong())}-${java.lang.Long.toHexString(crc.value)}"
    }

    private fun readingOf(pdf: File): Score? {
        val base = File(library, ".inksheets/readings")
        val id = idOf(pdf)
        val page = Regex("""p\d+\.json""")
        val dir = (13 downTo 1).map { File(base, "r$it-$id") }.firstOrNull { d -> d.listFiles { f -> page.matches(f.name) }?.isNotEmpty() == true } ?: return null
        val pages = dir.listFiles { f -> page.matches(f.name) }.orEmpty().sortedBy { it.name.drop(1).removeSuffix(".json").toInt() }
        val parts = pages.mapNotNull { Scores.decode(it.readText()) }
        if (parts.isEmpty()) return null
        return Score(parts.flatMap { it.measures }.sortedWith(compareBy({ it.page }, { it.staff }, { it.box.left })), parts.maxOf { it.pages }, parts.first().pageWidths)
    }

    @Test
    fun `how often bars are the same between real parts`() {
        if (System.getenv("INKSHEETS_CARRYREAL") == null) return
        val music = File(library, "Imported/PEP BAND/Music")
        if (!music.isDirectory) return
        val pairs = HashMap<String, Int>()
        var songs = 0; var tried = 0; var matched = 0
        for (dir in music.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }) {
            val parts = dir.listFiles { f -> f.extension.equals("pdf", true) }.orEmpty().mapNotNull { f ->
                val inst = InstrumentReader.readFileName(f.name)?.instrument ?: return@mapNotNull null
                val score = readingOf(f) ?: return@mapNotNull null
                Triple(inst.id, inst.transpose, score)
            }
            if (parts.size < 2) continue
            songs++
            var t = 0; var m = 0
            for (a in parts) for (b in parts) {
                if (a === b) continue
                for (bar in a.third.measures) {
                    // As if this bar had been put right: its notes in another order (the same notes, so the carry is about
                    // whether B's bar was read the same as A's, not about the fix).
                    val changed = bar.events.reversed()
                    if (changed == bar.events) continue
                    t++
                    val c = CarryFix.carry(a.third, mapOf(bar.number to changed), a.second, bar.number, b.third, b.second) ?: continue
                    if (c.confirmed) continue
                    m++
                    pairs.merge("${a.first} -> ${b.first}", 1, Int::plus)
                }
            }
            tried += t; matched += m
            println("CARRY ${dir.name}: ${parts.size} parts (${parts.joinToString { it.first }}), $m of $t bar pairs would carry")
        }
        println("CARRY total: $songs songs, $matched of $tried bar pairs (${"%.1f".format(100.0 * matched / tried.coerceAtLeast(1))}%)")
        pairs.entries.sortedByDescending { it.value }.take(30).forEach { println("CARRY pair ${it.key}: ${it.value}") }
    }
}
