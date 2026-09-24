package com.inksheets.core

/**
 * Turning a pile of files into songs.
 *
 * A folder of band music is usually one file per part - "Liberty Bell - Trombone 2.pdf",
 * "Liberty Bell - Euphonium.pdf" - and those are one song with two parts, not two songs. So each
 * file's name has its instrument (and part number) taken off, and the files whose names are then
 * the same become one song. The instrument itself comes from the words printed on the part where
 * there are any, and from the file name where there are not.
 */
object ImportPlan {

    data class PlannedPart(
        /** Library-relative path. */
        val file: String,
        val instrument: String?,
        val source: InstrumentSource,
        val label: String?
    )

    data class PlannedSong(val title: String, val parts: List<PlannedPart>)

    /**
     * [files] are library-relative paths. [textOf] returns the words at the top of a file's first
     * page (its own text, or text recognised from the scan), or null when there are none.
     */
    fun plan(
        files: List<String>,
        textOf: (String) -> String? = { null },
        existing: List<Song> = emptyList(),
        /** Text recognised from a scan, asked for only when a file has no text of its own. */
        recognise: (String) -> String? = { null }
    ): List<PlannedSong> {
        val known = existing.flatMap { s -> s.parts.map { it.file } }.toSet()
        val songs = LinkedHashMap<String, Pair<String, MutableList<PlannedPart>>>()
        for (file in files) {
            if (file in known) continue
            val name = file.substringAfterLast('/')
            val fromName = InstrumentReader.readFileName(name)
            val ownText = textOf(file)?.let { InstrumentReader.read(it) }
            // Recognising a scan takes a moment a page, so it is kept for the files that need it:
            // no text of their own, and nothing in the name either.
            val fromText = ownText ?: if (fromName == null) recognise(file)?.let { InstrumentReader.read(it) } else null
            val textSource = if (ownText != null) InstrumentSource.TEXT else InstrumentSource.OCR
            // Printed words beat the file name, unless the print only said something as weak as
            // "Bass" and the name says more.
            val chosen = when {
                fromText != null && (fromName == null || fromText.strength >= fromName.strength) -> fromText to textSource
                fromName != null -> fromName to InstrumentSource.FILE_NAME
                else -> null
            }
            val title = titleOf(name)
            val key = Library.sortKey(title)
            val part = PlannedPart(file, chosen?.first?.instrument?.id, chosen?.second ?: InstrumentSource.UNKNOWN, chosen?.first?.label)
            songs.getOrPut(key) { title to ArrayList() }.second += part
        }
        return songs.values.map { (title, parts) -> PlannedSong(title, parts) }
    }

    /**
     * A song title from a file name: extension, instrument, part number and the separators
     * around them removed. "Liberty_Bell - Trombone 2.pdf" -> "Liberty Bell".
     */
    fun titleOf(fileName: String): String {
        val base = fileName.substringBeforeLast('.').replace('_', ' ')
        // Cut at the instrument, if the name has one after a separator or at its end.
        val pieces = base.split(Regex("""\s+[-–—]\s+|\s*\(\s*|\s*\)\s*"""))
        val kept = pieces.filter { piece ->
            val normal = piece.trim()
            normal.isNotEmpty() && InstrumentReader.read(normal)?.let { match ->
                // Drop a piece that is only an instrument (and numbers), keep a real title word.
                val words = InstrumentReader.normalise(normal).filterNot { it.matches(Regex("""\d+(st|nd|rd|th)?|i{1,3}|iv|v""")) }
                words.size <= match.strength + 1
            } != true
        }
        val title = (if (kept.isEmpty()) pieces else kept).joinToString(" - ") { it.trim() }
            .replace(Regex("""\s+"""), " ").trim()
        return title.ifEmpty { base.trim() }
    }
}
