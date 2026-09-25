package com.inksheets.core

/**
 * Turning a pile of files into songs.
 *
 * A folder of band music is usually one file per part - "Liberty Bell - Trombone 2.pdf",
 * "Liberty Bell - Euphonium.pdf" - and those are one song with two parts, not two songs. So each
 * file's name has its instrument (and part number) taken off, and the files whose names are then
 * the same become one song. The instrument itself comes from the words printed on the part where
 * there are any, and from the file name where there are not.
 *
 * Names arrive in every shape a website, a scanner or a band director can produce -
 * "01. Liberty_Bell-Tbn1 (2).pdf", "LibertyBell_Trombone2.pdf", "Trombone 2.pdf" in a folder
 * called "Liberty Bell" - and all of those have to come out as the one song "Liberty Bell".
 */
object ImportPlan {

    data class PlannedPart(
        /** Library-relative path. */
        val file: String,
        val instrument: String?,
        val source: InstrumentSource,
        val label: String?,
        /** Other instruments the same part is printed for (a flexible-band part). */
        val also: List<String> = emptyList()
    ) {
        fun toPart() = Part(file = file, instrument = instrument, source = source, label = label, also = also)
    }

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
            val part = readPart(file, textOf, recognise)
            val title = songTitle(file)
            songs.getOrPut(Library.matchKey(title)) { title to ArrayList() }.second += part
        }
        return songs.values.map { (title, parts) -> PlannedSong(title, parts) }
    }

    /** One file's instrument: from the words printed on it, or its name, whichever says more. */
    fun readPart(
        file: String,
        textOf: (String) -> String? = { null },
        recognise: (String) -> String? = { null }
    ): PlannedPart {
        val name = file.substringAfterLast('/')
        val nameText = readableName(name)
        val fromName = InstrumentReader.read(nameText)
        val ownText = textOf(file)?.let { t -> InstrumentReader.read(t)?.let { it to t } }
        // Recognising a scan takes a moment a page, so it is kept for the files that need it:
        // no text of their own, and nothing in the name either.
        val fromText = ownText ?: if (fromName == null) recognise(file)?.let { t -> InstrumentReader.read(t)?.let { it to t } } else null
        val textSource = if (ownText != null) InstrumentSource.TEXT else InstrumentSource.OCR
        // Printed words beat the file name, unless the print only said something as weak as
        // "Bass" and the name says more.
        val chosen = when {
            fromText != null && (fromName == null || fromText.first.strength >= fromName.strength) -> Triple(fromText.first, textSource, fromText.second)
            fromName != null -> Triple(fromName, InstrumentSource.FILE_NAME, nameText)
            else -> null
        } ?: return PlannedPart(file, null, InstrumentSource.UNKNOWN, null)
        // A part printed for several instruments is filed under the first one printed.
        val all = InstrumentReader.readAll(chosen.third).map { it.id }.takeIf { it.size in 2..4 }.orEmpty()
        val main = all.firstOrNull() ?: chosen.first.instrument.id
        return PlannedPart(file, main, chosen.second, chosen.first.label, all.filter { it != main })
    }

    /**
     * The song a file belongs to. Its own name, with the instrument taken off - or, for a file
     * named only for its part ("Trombone 2.pdf"), the folder it is in.
     */
    fun songTitle(path: String, folderIsSong: Boolean = true): String {
        val name = path.substringAfterLast('/')
        val own = titleOf(name)
        if (!onlyPartName(name) || !folderIsSong) return own
        val folder = path.substringBeforeLast('/', "").substringAfterLast('/')
        if (folder.isBlank() || isGenericFolder(folder)) return own
        return cleanFolderName(folder)
    }

    /** Folders that hold a library's music, not one song's parts: never a song's title. */
    fun isGenericFolder(name: String): Boolean =
        Library.matchKey(name) in GENERIC_FOLDERS || name.startsWith(".")

    private val GENERIC_FOLDERS = setOf(
        "mobilesheets", "inbox", "imported", "music", "sheetmusic", "scans", "downloads", "download",
        "inksheets", "library", "pdfs", "pdf", "parts", "sync", "documents", "shared", "sharedwithme"
    )

    /**
     * Whether a folder is one song's parts - every music file in it named only for its part - so
     * its name is the song's title. A folder of many songs that happens to hold a "Euph 2.pdf" is not.
     */
    fun folderIsSong(musicFilesInFolder: List<String>): Boolean =
        musicFilesInFolder.isNotEmpty() && musicFilesInFolder.size <= 40 &&
            musicFilesInFolder.all { onlyPartName(it.substringAfterLast('/')) }

    /** Whether a file name says nothing but which part it is: "Trombone 2", "Tbn. II", "Score". */
    fun onlyPartName(fileName: String): Boolean {
        val base = readableName(fileName)
        val match = InstrumentReader.read(base) ?: return false
        val words = InstrumentReader.normalise(base).filterNot { isPartMarker(it) }
        return words.size <= match.strength + 1 && words.all { w -> w in INSTRUMENT_WORDS }
    }

    /**
     * A song title from a file name: extension, track number, browser copy number, instrument,
     * part number and the separators around them removed. "01 Liberty_Bell - Trombone 2 (1).pdf"
     * -> "Liberty Bell".
     */
    fun titleOf(fileName: String): String {
        val cleaned = cleanTitle(readableName(fileName))
        return stripTrailingInstrument(cleaned)
    }

    /**
     * A file name as words: no extension, no "(1)" a browser adds to a second download, no
     * leading track number, and underscores (or, in a name with no spaces at all, hyphens and
     * joined-up capitals) made into spaces.
     */
    fun readableName(fileName: String): String {
        var base = fileName.substringBeforeLast('.').takeIf { '.' in fileName && fileName.substringAfterLast('.').let { e -> e.length in 2..4 && e.all(Char::isLetterOrDigit) } } ?: fileName
        base = base.replace(Regex("""\s*\(\d{1,2}\)\s*$"""), "")
        base = base.replace('_', ' ')
        if (' ' !in base.trim()) base = base.replace('-', ' ')
        // "LibertyBell", "Trombone1": joined-up words and numbers apart. Not "McDonald" or "iPad".
        base = base.replace(Regex("""(\p{Lu}\p{Ll}{2,})(?=\p{Lu})"""), "$1 ")
            .replace(Regex("""(\p{L}{2,})(\d{1,2})\b"""), "$1 $2")
        // "01 Title", "1. Title", "01 - Title": a track number. Not "76 Trombones" or "9 to 5".
        base = base.replace(Regex("""^\s*(\d{1,3}\s*[.)\]]|\d{1,3}\s*[-–—]|0\d{1,2}(?=[\s_-]))\s*[-–—]?\s*"""), "")
        return base.replace(Regex("""\s+"""), " ").trim().ifEmpty { fileName.substringBeforeLast('.') }
    }

    /**
     * A title with a trailing instrument taken off even without a separator: "1812 Euph 2" ->
     * "1812". Used only to gather parts that would otherwise stay apart - on its own it would turn
     * "All About That Bass" into "All About That".
     */
    fun withoutTrailingInstrument(title: String): String {
        val words = title.trim().split(Regex("""\s+"""))
        var end = words.size
        while (end > 0 && isPartMarker(words[end - 1])) end--
        for (take in 3 downTo 1) {
            if (end - take < 1) continue
            val tail = words.subList(end - take, end).joinToString(" ")
            val match = InstrumentReader.read(tail) ?: continue
            if (InstrumentReader.normalise(tail).filterNot(::isPartMarker).size == match.strength) {
                return words.subList(0, end - take).joinToString(" ")
            }
        }
        return title.trim()
    }

    /**
     * [withoutTrailingInstrument], where it is safe: a lone word that is also an ordinary word -
     * "Bass", "Horn", "Voice" - stays, unless a part number follows it. "Sleigh Ride Trombone" is
     * "Sleigh Ride"; "All About That Bass" is left alone, but "Seven Nation Army Bass 2" is not.
     */
    fun stripTrailingInstrument(title: String): String {
        val words = title.trim().split(Regex("""\s+"""))
        val numbered = words.size > 1 && isPartMarker(words.last())
        val stripped = withoutTrailingInstrument(title)
        if (stripped == title.trim() || stripped.isBlank()) return title.trim()
        val removed = words.drop(stripped.split(Regex("""\s+""")).size).filterNot(::isPartMarker)
        val lone = removed.singleOrNull()?.lowercase()?.trimEnd('.')
        if (!numbered && lone != null && (lone in AMBIGUOUS || lone.removeSuffix("s") in AMBIGUOUS)) return title.trim()
        return stripped.trimEnd(' ', '-', '–', '—', ',', ':').ifBlank { title.trim() }
    }

    /** A folder's name as a song or setlist title: the same tidying as a file's, minus the extension. */
    fun cleanFolderName(name: String): String = readableName("$name.dir").let { cleanTitle(it) }.ifBlank { name }

    /** Instrument words that are also everyday words, which a title can end with. */
    private val AMBIGUOUS = setOf("bass", "horn", "voice", "vocal", "key", "keyboard", "score", "drum", "bell", "chime", "triangle", "snare", "guitar", "piano")

    private val INSTRUMENT_WORDS: Set<String> by lazy {
        Instruments.all.flatMap { i -> i.names.flatMap { it.split(' ') } }.toSet() +
            setOf("in", "f", "bb", "eb", "c", "tc", "bc", "part", "and", "solo", "optional", "opt", "divisi", "div", "flex")
    }

    private fun isPartMarker(word: String): Boolean =
        word.matches(Regex("""\d{1,2}(st|nd|rd|th)?|[IVX]+|[ivx]+|&|and|[-–—/,+]|\d{1,2}[-–—/&+]\d{1,2}""", RegexOption.IGNORE_CASE))

    /** A song title from a name with the instrument (and its separators) taken off. */
    fun cleanTitle(name: String): String {
        val base = name.replace('_', ' ')
        // Cut at the instrument, if the name has one after a separator or at its end. The
        // separators are kept, so "Semper-Fidelis - Trombone" keeps its own hyphen.
        val tokens = Regex("""\s+[-–—]\s+|\s*\(\s*|\s*\)\s*|\s*\[\s*|\s*]\s*""").let { sep ->
            val out = ArrayList<Pair<String, String>>()   // piece to the separator before it
            var at = 0
            var before = ""
            for (m in sep.findAll(base)) {
                out += base.substring(at, m.range.first) to before
                before = m.value
                at = m.range.last + 1
            }
            out += base.substring(at) to before
            out
        }
        val kept = tokens.filter { (piece, _) ->
            val normal = piece.trim()
            normal.isNotEmpty() && InstrumentReader.read(normal)?.let { match ->
                // Drop a piece that is only an instrument (and numbers), keep a real title word.
                val words = InstrumentReader.normalise(normal).filterNot { isPartMarker(it) || it.matches(Regex("""i{1,3}|iv|v""")) }
                words.size <= match.strength + 1 && words.all { it in INSTRUMENT_WORDS }
            } != true
        }
        val chosen = if (kept.isEmpty()) tokens else kept
        val title = chosen.mapIndexed { i, (piece, sep) ->
            if (i == 0) piece.trim() else {
                val s = sep.trim()
                (if (s.isEmpty() || s == "(" || s == ")" || s == "[" || s == "]") " - " else " $s ") + piece.trim()
            }
        }.joinToString("").replace(Regex("""\s+"""), " ").trim()
        return title.ifEmpty { base.trim() }
    }
}
