package com.inksheets.core

/**
 * Bringing a MobileSheets library across.
 *
 * MobileSheets keeps everything in one SQLite database, `mobilesheets.db`, which it writes into
 * its storage folder when "Expose Database File" is on in its settings. Each platform opens that
 * database its own way and hands the tables over as rows ([Tables]); everything else - which
 * table means what, and how it becomes songs, parts and setlists - is here, once.
 *
 * Columns are looked up by name and a missing table reads as empty, because the schema has grown
 * over MobileSheets' versions and an import should take what is there rather than refuse what is
 * not. Running it twice adds nothing the second time: a song whose files are already in the
 * library is skipped.
 */
object MobileSheetsImport {

    /** The rows of a table, each a column-name-to-value map; empty for a table that is not there. */
    fun interface Tables {
        fun rows(table: String): List<Map<String, Any?>>
    }

    data class Result(
        val songs: Int,
        val setlists: Int,
        val skipped: Int,
        /** MobileSheets paths no file could be found for, for the person to go and look for. */
        val missing: List<String>
    )

    /** A setlist folder, so the imported ones sit together and apart from any made here. */
    const val FOLDER_NAME = "From MobileSheets"

    /**
     * Import into [library]. [resolve] turns a path as MobileSheets stored it (usually absolute,
     * on the device it ran on) into a library-relative path, or null when the file cannot be found.
     */
    fun run(tables: Tables, library: Library, resolve: (String) -> String?): Result {
        fun Map<String, Any?>.int(column: String): Int? = when (val v = this[column]) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
        fun Map<String, Any?>.text(column: String): String? =
            this[column]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        /** A name table joined to songs: Composer + ComposerSongs, and so on. */
        fun names(table: String, join: String, idColumn: String, nameColumn: String = "Name"): Map<Int, List<String>> {
            val byId = tables.rows(table).mapNotNull { r -> r.int("Id")?.let { id -> r.text(nameColumn)?.let { id to it } } }.toMap()
            return tables.rows(join)
                .mapNotNull { r -> r.int("SongId")?.let { song -> r.int(idColumn)?.let { byId[it] }?.let { song to it } } }
                .groupBy({ it.first }, { it.second })
        }

        val composersOf = names("Composer", "ComposerSongs", "ComposerId")
        val artistsOf = names("Artists", "ArtistsSongs", "ArtistId")
        val genresOf = names("Genres", "GenresSongs", "GenreId", nameColumn = "Type")
        val keysOf = names("Key", "KeySongs", "KeyId")
        val signaturesOf = names("Signature", "SignatureSongs", "SignatureId")
        val collectionsOf = names("Collections", "CollectionSong", "CollectionId")
        val groupsOf = names("CustomGroup", "CustomGroupSongs", "GroupId")
        val temposOf = tables.rows("Tempos").sortedBy { it.int("TempoIndex") ?: 0 }
            .groupBy { it.int("SongId") }.mapValues { (_, rows) -> rows.firstNotNullOfOrNull { it.int("Tempo") } }
        val notesOf = tables.rows("SongNotes").associate { it.int("SongId") to it.text("Notes") }
        val filesOf = tables.rows("Files").sortedBy { it.int("Id") ?: 0 }.groupBy { it.int("SongId") }
        val audioOf = tables.rows("AudioFiles").sortedBy { it.int("Id") ?: 0 }.groupBy { it.int("SongId") }
        val bookmarksOf = tables.rows("Bookmarks").sortedBy { it.int("PageNum") ?: 0 }.groupBy { it.int("SongId") }

        val known = library.songs.flatMap { s -> s.parts.map { it.file } }.toSet()
        val missing = ArrayList<String>()
        val songIds = HashMap<Int, String>()   // MobileSheets id -> library id, for the setlists
        var added = 0
        var skipped = 0

        // First every MobileSheets song as it stands: its title, its files, its details.
        class Found(val msId: Int, val row: Map<String, Any?>, val title: String, val parts: List<Part>)
        val found = ArrayList<Found>()
        for (row in tables.rows("Songs")) {
            val msId = row.int("Id") ?: continue
            val rawTitle = row.text("Title")
            // The instrument is read from the song's own title first ("24K Magic - Trombone 1"),
            // then from its file's name.
            val fromTitle = rawTitle?.let { InstrumentReader.read(it) }
            val parts = filesOf[msId].orEmpty().mapNotNull { f ->
                val path = f.text("Path") ?: return@mapNotNull null
                val rel = resolve(path)
                if (rel == null) { missing += path; return@mapNotNull null }
                val range = pageRange(f.text("PageOrder"))
                val named = fromTitle ?: InstrumentReader.readFileName(rel.substringAfterLast('/'))
                Part(
                    file = rel,
                    firstPage = range?.first,
                    lastPage = range?.last,
                    instrument = named?.instrument?.id,
                    source = when {
                        named == null -> InstrumentSource.UNKNOWN
                        fromTitle != null -> InstrumentSource.TEXT
                        else -> InstrumentSource.FILE_NAME
                    },
                    label = named?.label
                )
            }
            if (parts.isEmpty()) { skipped++; continue }
            if (parts.all { it.file in known }) {
                // Already brought across; still remembered so its setlists can find it.
                library.songs.firstOrNull { s -> s.parts.any { it.file == parts.first().file } }?.let { songIds[msId] = it.id }
                skipped++
                continue
            }
            found += Found(msId, row, rawTitle ?: parts.first().file.substringAfterLast('/').substringBeforeLast('.'), parts)
        }

        // MobileSheets keeps each instrument's part as a song of its own - "24K Magic - Electric
        // Bass" beside "24K Magic - Trombone 1". Here they are one song with two parts, which is
        // what lets choosing an instrument open the right one. Titles that agree once a
        // "- Instrument" is taken off go together; songs still alone are then tried without a
        // bare trailing instrument ("1812 Euph 2" with "1812 Trombone"), which on its own would
        // wrongly shorten "All About That Bass".
        val groups = ArrayList<Pair<String, List<Found>>>()
        val byClean = found.groupBy { Library.sortKey(ImportPlan.cleanTitle(it.title)) }
        val alone = ArrayList<Found>()
        for ((_, members) in byClean) {
            if (members.size > 1) groups += ImportPlan.cleanTitle(members.first().title) to members
            else alone += members
        }
        for ((_, members) in alone.groupBy { Library.sortKey(ImportPlan.withoutTrailingInstrument(ImportPlan.cleanTitle(it.title))) }) {
            val only = members.first()
            groups += if (members.size > 1) {
                ImportPlan.withoutTrailingInstrument(ImportPlan.cleanTitle(only.title)) to members
            } else {
                ImportPlan.cleanTitle(only.title).ifEmpty { only.title } to members
            }
        }

        for ((title, members) in groups) {
            val ids = members.map { it.msId }
            val tracks = ids.flatMap { msId ->
                audioOf[msId].orEmpty().mapNotNull { a ->
                    val rel = a.text("File")?.let(resolve) ?: return@mapNotNull null
                    val looped = (a.int("ABEnabled") ?: 0) != 0
                    AudioTrack(
                        file = rel,
                        label = a.text("Title"),
                        loopStartMs = if (looped) a.int("APosition")?.takeIf { it >= 0 }?.toLong() else null,
                        loopEndMs = if (looped) a.int("BPosition")?.takeIf { it >= 0 }?.toLong() else null,
                        speed = (a["TempoSpeed"] as? Number)?.toDouble() ?: 1.0,
                        pitch = a.int("PitchShift") ?: 0
                    )
                }
            }.distinctBy { it.file }
            val marks = ids.flatMap { msId ->
                bookmarksOf[msId].orEmpty().mapNotNull { b ->
                    val page = b.int("PageNum") ?: return@mapNotNull null
                    Bookmark(label = b.text("Name") ?: "Page ${page + 1}", page = page + 1)
                }
            }
            fun union(of: Map<Int, List<String>>) = ids.flatMap { of[it].orEmpty() }.distinct()
            val first = members.first().row
            val song = library.addSong(title, members.flatMap { it.parts }) {
                this.composers = union(composersOf)
                this.artists = union(artistsOf)
                this.genres = union(genresOf)
                this.key = ids.firstNotNullOfOrNull { keysOf[it]?.firstOrNull() }
                this.timeSignature = ids.firstNotNullOfOrNull { signaturesOf[it]?.firstOrNull() }
                this.tempo = ids.firstNotNullOfOrNull { temposOf[it] }
                this.difficulty = first.int("Difficulty")?.takeIf { it > 0 }
                this.tags = (union(collectionsOf) + union(groupsOf) + members.flatMap { m ->
                    m.row.text("Keywords")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                }).distinct()
                this.notes = ids.firstNotNullOfOrNull { notesOf[it] }
                if (tracks.isNotEmpty()) this.audio = tracks
                if (marks.isNotEmpty()) this.bookmarks = marks
            }
            ids.forEach { songIds[it] = song.id }
            added++
        }

        // Setlists, in MobileSheets' order, all in one folder.
        val setlistRows = tables.rows("Setlists")
        var setlistsAdded = 0
        if (setlistRows.isNotEmpty()) {
            val folder = library.foldersIn(null).firstOrNull { it.name == FOLDER_NAME } ?: library.addFolder(FOLDER_NAME)
            val existing = library.setlistsIn(folder.id).map { it.name }.toSet()
            val members = tables.rows("SetlistSong").sortedBy { it.int("Id") ?: 0 }.groupBy { it.int("SetlistId") }
            for (s in setlistRows) {
                val name = s.text("Name") ?: continue
                if (name in existing) continue
                val entries = members[s.int("Id")].orEmpty().mapNotNull { m -> m.int("SongId")?.let { songIds[it] } }
                    .map { SetlistEntry(songId = it) }
                val setlist = library.addSetlist(name, folder.id)
                library.editSetlist(setlist.id) { this.entries = entries }
                setlistsAdded++
            }
        }
        return Result(added, setlistsAdded, skipped, missing.distinct())
    }

    /** MobileSheets' page order for a file: "3-5" or "3" picks pages; anything fancier is ignored. */
    internal fun pageRange(order: String?): IntRange? {
        val o = order?.trim().orEmpty()
        Regex("""^(\d+)\s*-\s*(\d+)$""").find(o)?.let { m ->
            return m.groupValues[1].toInt()..m.groupValues[2].toInt()
        }
        o.toIntOrNull()?.let { return it..it }
        return null
    }

    /**
     * Find a MobileSheets path under [searchRoot]. The path was written on another device - say
     * `/storage/emulated/0/MobileSheets/Band/Liberty Bell.pdf` - so it is matched by its tail: the
     * longest run of trailing folders and file name that exists under the root, then the bare
     * file name if only one file anywhere under the root has it.
     */
    fun locate(path: String, searchRoot: java.io.File, byName: Map<String, List<java.io.File>>): java.io.File? {
        val segments = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        for (start in segments.indices) {
            val candidate = java.io.File(searchRoot, segments.drop(start).joinToString("/"))
            if (candidate.isFile) return candidate
        }
        return byName[segments.lastOrNull()?.lowercase()]?.singleOrNull()
    }
}
