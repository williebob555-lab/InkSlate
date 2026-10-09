package com.inksheets.desktop.review

import com.inksheets.core.omr.Score
import com.inksheets.core.omr.Scores
import com.inksheets.ui.Transcriber
import java.io.File

/** T5: the real library's PDFs and the readings the app made of them (read only). */
object T5Data {
    val library = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")
    val readings = File(library, ".inksheets/readings")

    fun whole(f: File): String {
        val crc = java.util.zip.CRC32()
        f.inputStream().buffered().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; crc.update(b, 0, n) } }
        return "${java.lang.Long.toHexString(f.length())}-${java.lang.Long.toHexString(crc.value)}"
    }

    fun load(dir: File): Score? {
        val pages = dir.listFiles { f -> Regex("p[0-9]+[.]json").matches(f.name) }.orEmpty().sortedBy { it.name.drop(1).removeSuffix(".json").toInt() }
        val parts = pages.mapNotNull { runCatching { Scores.decode(it.readText()) }.getOrNull() }
        if (parts.isEmpty()) return null
        return Score(parts.flatMap { it.measures }.sortedWith(compareBy({ it.page }, { it.staff }, { it.box.left })), parts.maxOf { it.pages }, parts.first().pageWidths)
    }

    /** The newest reading folder of [pdf] (first-revision key, else whole-file key), or null. */
    fun readingOf(pdf: File): File? {
        val ids = listOf(Transcriber.idOf(pdf), runCatching { whole(pdf) }.getOrDefault("")).distinct()
        for (v in 13 downTo 5) for (id in ids) File(readings, "r$v-$id").takeIf { it.isDirectory && it.listFiles()?.any { f -> f.name.startsWith("p") } == true }?.let { return it }
        return null
    }

    fun pdfs(root: File = library): List<File> = root.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile && it.extension.equals("pdf", true) }.toList()
}
