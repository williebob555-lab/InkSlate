package com.inksheets.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One marking brought across from MobileSheets, in a form independent of both apps: which page
 * of which file, and where on it as fractions of the page, so it lands in the same place however
 * big the page is here.
 */
@Serializable
data class ImportedMark(
    /** Stable, so bringing the same markings across twice gives the same marks, not two copies. */
    val id: String,
    /** 0-based page of the file. */
    val page: Int,
    val kind: Kind,
    /** ARGB. */
    val color: Int,
    val opacity: Float = 1f,
    /** Line width as a fraction of the page's width. */
    val width: Float,
    /** x, y, x, y... as fractions of the page's width and height. For text: its box's corners. */
    val points: List<Float>,
    val text: String? = null,
    /** Text size as a fraction of the page's width. */
    val textSize: Float = 0f
) {
    @Serializable
    enum class Kind { PEN, LINE, TEXT }
}

/**
 * MobileSheets' own markings - pen strokes, straight lines and text boxes - read out of its
 * database, where it keeps them apart from the PDFs.
 *
 * The layout is not documented; this is what its database holds (checked against a real library):
 * `AnnotationsBase` gives each marking's song, page, type and the size of the page it was made on;
 * `DrawAnnotations` and `TextboxAnnotations` its look; `AnnotationPoints.Points` its geometry as
 * little-endian doubles in that page's units. A pen stroke's doubles start with a three-number
 * header (0, the count that follows, the width) and a pair of float-max values marks the pen
 * lifting; a line is two points; a text box is its rectangle and then its baseline point.
 */
object MobileSheetsMarks {

    /** Every marking, by the library-relative file it belongs on. */
    fun read(tables: MobileSheetsImport.Tables, resolve: (String) -> String?): Map<String, List<ImportedMark>> {
        fun Map<String, Any?>.int(c: String): Int? = when (val v = this[c]) { is Number -> v.toInt(); is String -> v.toIntOrNull(); else -> null }
        fun Map<String, Any?>.num(c: String): Double? = when (val v = this[c]) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
        fun Map<String, Any?>.text(c: String): String? = this[c]?.toString()

        val draws = tables.rows("DrawAnnotations").associateBy { it.int("BaseId") }
        val texts = tables.rows("TextboxAnnotations").associateBy { it.int("BaseId") }
        val points = tables.rows("AnnotationPoints").associateBy { it.int("AnnotationId") }
        val hidden = tables.rows("Layers").filter { (it.int("Visible") ?: 1) == 0 }
            .map { Triple(it.int("SongId"), it.int("Page"), it.int("LayerIndex")) }.toSet()
        val filesOf = tables.rows("Files").sortedBy { it.int("Id") ?: 0 }.groupBy { it.int("SongId") }

        // Song page -> (file, page of that file), through each file's page order ("1-4", "102-105", "2,5").
        val pageMaps = HashMap<Int, List<Pair<String, Int>>>()
        fun pagesOf(songId: Int): List<Pair<String, Int>> = pageMaps.getOrPut(songId) {
            filesOf[songId].orEmpty().flatMap { f ->
                val rel = f.text("Path")?.let(resolve) ?: return@flatMap emptyList()
                val order = f.text("PageOrder").orEmpty()
                val count = f.int("SourceFilePageCount") ?: 0
                pageOrder(order, count).map { rel to it - 1 }
            }
        }

        val out = LinkedHashMap<String, MutableList<ImportedMark>>()
        for (base in tables.rows("AnnotationsBase")) {
            val id = base.int("Id") ?: continue
            val song = base.int("SongId") ?: continue
            if (Triple(song, base.int("Page"), base.int("Layer") ?: 0) in hidden) continue
            val (file, page) = pagesOf(song).getOrNull(base.int("Page") ?: continue) ?: continue
            val w = base.num("SourcePageWidth")?.takeIf { it > 0 } ?: continue
            val h = base.num("SourcePageHeight")?.takeIf { it > 0 } ?: continue
            val d = (points[id]?.get("Points") as? ByteArray)?.let(::doubles) ?: continue
            val opacity = ((base.num("Opacity") ?: 100.0) / 100.0).toFloat().coerceIn(0.05f, 1f)
            fun frac(xs: List<Double>) = xs.mapIndexed { i, v -> (if (i % 2 == 0) v / w else v / h).toFloat() }

            val text = texts[id]
            if (text != null) {
                if (d.size < 4) continue
                out.getOrPut(file) { ArrayList() } += ImportedMark(
                    id = "ms-$id", page = page, kind = ImportedMark.Kind.TEXT,
                    color = text.int("TextColor") ?: 0xFF000000.toInt(), opacity = opacity,
                    width = 0f, points = frac(d.take(4)),
                    text = text.text("Text").orEmpty(),
                    textSize = ((text.num("FontSize") ?: 14.0) / w).toFloat()
                )
                continue
            }
            val draw = draws[id] ?: continue
            val color = draw.int("LineColor") ?: 0xFF000000.toInt()
            val width = ((draw.num("LineWidth") ?: 1.0) / w).toFloat()
            when (draw.int("DrawMode") ?: 0) {
                2 -> if (d.size >= 4) out.getOrPut(file) { ArrayList() } += ImportedMark(
                    "ms-$id", page, ImportedMark.Kind.LINE, color, opacity, width, frac(d.take(4))
                )
                else -> strokes(d).forEachIndexed { n, run ->
                    out.getOrPut(file) { ArrayList() } += ImportedMark(
                        "ms-$id-$n", page, ImportedMark.Kind.PEN, color, opacity, width, frac(run)
                    )
                }
            }
        }
        return out
    }

    /** The pen-down runs of a freehand marking: its header skipped, split where the pen lifted. */
    fun strokes(d: DoubleArray): List<List<Double>> {
        val start = if (d.size >= 3 && d[0] == 0.0 && d[1].toInt() == d.size - 3) 3 else 0
        val runs = ArrayList<List<Double>>()
        var run = ArrayList<Double>()
        var i = start
        while (i + 1 < d.size) {
            val x = d[i]
            val y = d[i + 1]
            if (x > 1e30 || y > 1e30 || x.isNaN() || y.isNaN()) {
                if (run.size >= 2) runs += run
                run = ArrayList()
            } else {
                run += x; run += y
            }
            i += 2
        }
        if (run.size >= 2) runs += run
        return runs
    }

    fun doubles(bytes: ByteArray): DoubleArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(bytes.size / 8) { buffer.getDouble(it * 8) }
    }

    /** "1-4" -> 1,2,3,4; "2,5-6" -> 2,5,6; empty -> every page. 1-based. */
    fun pageOrder(order: String, count: Int): List<Int> {
        if (order.isBlank()) return (1..count).toList()
        return order.split(',').flatMap { piece ->
            val p = piece.trim()
            val a = p.substringBefore('-').trim().toIntOrNull() ?: return@flatMap emptyList()
            val b = p.substringAfter('-', p).trim().toIntOrNull() ?: a
            if (b >= a) (a..b).toList() else (a downTo b).toList()
        }
    }

    // ---- kept in the library ----------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), ListSerializer(ImportedMark.serializer()))

    /** Where the markings are kept: in the library, so every device that has it can place them. */
    fun fileIn(root: File) = File(root, ".inksheets/imported-marks.json")

    fun load(root: File): Map<String, List<ImportedMark>> =
        runCatching { json.decodeFromString(serializer, fileIn(root).readText()) }.getOrDefault(emptyMap())

    /** Add [marks] to what is kept; a marking already kept is replaced by its newer reading. */
    fun save(root: File, marks: Map<String, List<ImportedMark>>) {
        if (marks.isEmpty()) return
        val all = load(root).toMutableMap()
        for ((file, list) in marks) {
            val ids = list.map { it.id }.toSet()
            all[file] = all[file].orEmpty().filter { it.id !in ids } + list
        }
        val target = fileIn(root)
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(json.encodeToString(serializer, all))
        if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
    }
}
