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

    /** One MobileSheets song: in MobileSheets each instrument's part is often a song of its own. */
    data class Candidate(val msId: Int, val title: String, val parts: List<Part>)

    /**
     * What will become one song here: MobileSheets songs put together, under [title]. [into] adds
     * them to a song already in the library instead; [apart] keeps a new song apart from any other
     * of the same name.
     */
    data class Group(val title: String, val members: List<Candidate>, val into: String? = null, val apart: Boolean = false)

    /** An import worked out and not yet made: its groups can be changed before [apply]. */
    class Planned(
        val groups: List<Group>,
        val skipped: Int,
        val missing: List<String>,
        /** MobileSheets songs already here, by their library song - for the setlists. */
        internal val already: Map<Int, String>
    )

    private fun Map<String, Any?>.int(column: String): Int? = when (val v = this[column]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
    private fun Map<String, Any?>.text(column: String): String? =
        this[column]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    /** Import into [library] as planned, groups unchanged. */
    fun run(tables: Tables, library: Library, resolve: (String) -> String?): Result {
        val planned = plan(tables, library, resolve)
        return apply(tables, library, resolve, planned, planned.groups)
    }

    /**
     * Work out an import into [library]. [resolve] turns a path as MobileSheets stored it (usually
     * absolute, on the device it ran on) into a library-relative path, or null when the file
     * cannot be found.
     */
    fun plan(tables: Tables, library: Library, resolve: (String) -> String?): Planned {
        val filesOf = tables.rows("Files").sortedBy { it.int("Id") ?: 0 }.groupBy { it.int("SongId") }
        val known = library.songs.flatMap { s -> s.parts.map { it.file } }.toSet()
        val missing = ArrayList<String>()
        val already = HashMap<Int, String>()
        var skipped = 0
        val found = ArrayList<Candidate>()
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
                // "1-3" of a three-page file is the whole file, as the folder scan sees it too.
                val range = pageRange(f.text("PageOrder"))?.takeUnless { r ->
                    val count = f.int("SourceFilePageCount") ?: 0
                    r.first == 1 && count > 0 && r.last >= count
                }
                val named = fromTitle ?: InstrumentReader.readFileName(rel.substringAfterLast('/'))
                val id = if (range == null) Library.partIdFor(rel) else Library.partIdFor("$rel#${range.first}-${range.last}")
                Part(
                    id = id,
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
                library.songs.firstOrNull { s -> s.parts.any { it.file == parts.first().file } }?.let { already[msId] = it.id }
                skipped++
                continue
            }
            found += Candidate(msId, rawTitle ?: parts.first().file.substringAfterLast('/').substringBeforeLast('.'), parts)
        }

        // MobileSheets keeps each instrument's part as a song of its own - "24K Magic - Electric
        // Bass" beside "24K Magic - Trombone 1". Here they are one song with two parts, which is
        // what lets choosing an instrument open the right one. Titles that agree once a
        // "- Instrument" is taken off go together - with case and punctuation ignored, so "Swag
        // Surfin'" and "Swag Surfin" are one; songs still alone are then tried without a bare
        // trailing instrument ("1812 Euph 2" with "1812 Trombone"). Songs sharing a file are one.
        val byFile = HashMap<String, Int>()
        val union = IntArray(found.size) { it }
        fun rootOf(i: Int): Int { var r = i; while (union[r] != r) r = union[r]; return r }
        fun join(a: Int, b: Int) { union[rootOf(a)] = rootOf(b) }
        found.forEachIndexed { i, c -> c.parts.forEach { p -> byFile[p.file]?.let { join(i, it) } ?: byFile.put(p.file, i) } }
        val byClean = found.indices.groupBy { Library.matchKey(ImportPlan.cleanTitle(found[it].title)) }
        byClean.values.forEach { same -> same.drop(1).forEach { join(it, same.first()) } }
        val loose = found.indices.groupBy { rootOf(it) }.values.filter { it.size == 1 }.map { it.single() }
        loose.groupBy { Library.matchKey(ImportPlan.withoutTrailingInstrument(ImportPlan.cleanTitle(found[it].title))) }
            .values.forEach { same -> same.drop(1).forEach { join(it, same.first()) } }

        // Within a title, MobileSheets songs for the same instrument are separate songs: two
        // editions of a piece, or two pieces that share a name. Only different instruments join.
        val groups = found.indices.groupBy { rootOf(it) }.values.flatMap { members ->
            val cs = members.map { found[it] }
            val title = if (cs.size > 1) {
                ImportPlan.withoutTrailingInstrument(ImportPlan.cleanTitle(cs.first().title)).ifEmpty { cs.first().title }
            } else ImportPlan.cleanTitle(cs.first().title).ifEmpty { cs.first().title }
            val sharesFile = cs.size > 1 && cs.map { c -> c.parts.map { it.file }.toSet() }.let { sets -> sets.any { a -> sets.any { b -> a !== b && a.intersect(b).isNotEmpty() } } }
            val bundles = ArrayList<MutableList<Candidate>>()
            for (c in cs) {
                val mine = c.parts.mapNotNull { it.instrument }.toSet()
                val fits = if (sharesFile) bundles.firstOrNull() else bundles.firstOrNull { b ->
                    b.flatMap { m -> m.parts.mapNotNull { it.instrument } }.none { it in mine }
                }
                if (fits != null) fits += c else bundles += arrayListOf(c)
            }
            bundles.mapIndexed { i, b ->
                val own = if (bundles.size > 1) ImportPlan.cleanTitle(b.first().title).ifEmpty { title } else title
                // A song already in the library with this title takes these as more of its parts.
                val existing = if (i == 0) library.songs.firstOrNull { !it.apart && Library.matchKey(it.title) == Library.matchKey(own) } else null
                Group(own, b, into = existing?.id, apart = i > 0)
            }
        }.sortedBy { Library.sortKey(it.title) }
        return Planned(groups, skipped, missing.distinct(), already)
    }

    /** Make [groups] (the planned ones, as the person left them) into songs, and the setlists. */
    fun apply(tables: Tables, library: Library, resolve: (String) -> String?, planned: Planned, groups: List<Group>): Result {
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
        val audioOf = tables.rows("AudioFiles").sortedBy { it.int("Id") ?: 0 }.groupBy { it.int("SongId") }
        val bookmarksOf = tables.rows("Bookmarks").sortedBy { it.int("PageNum") ?: 0 }.groupBy { it.int("SongId") }
        val rowOf = tables.rows("Songs").associateBy { it.int("Id") }

        val songIds = HashMap(planned.already)
        var added = 0
        for (group in groups) {
            if (group.members.isEmpty()) continue
            val ids = group.members.map { it.msId }
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
            val first = rowOf[ids.first()].orEmpty()
            // Into the chosen song, the song of this title already here, or a new one.
            val target = group.into?.let { library.song(it) }
                ?: if (group.apart) library.addSong(group.title, emptyList()) { apart = true }
                else library.ensureSong(group.title)
            group.members.flatMap { it.parts }.forEach { library.writePart(target.id, it) }
            val known = library.song(target.id) ?: continue
            library.editSong(target.id) {
                // Details fill what is empty and change nothing already set.
                if (known.composers.isEmpty()) composers = union(composersOf)
                if (known.artists.isEmpty()) artists = union(artistsOf)
                if (known.genres.isEmpty()) genres = union(genresOf)
                if (known.key == null) key = ids.firstNotNullOfOrNull { keysOf[it]?.firstOrNull() }
                if (known.timeSignature == null) timeSignature = ids.firstNotNullOfOrNull { signaturesOf[it]?.firstOrNull() }
                if (known.tempo == null) tempo = ids.firstNotNullOfOrNull { temposOf[it] }
                if (known.difficulty == null) difficulty = first.int("Difficulty")?.takeIf { it > 0 }
                val tagsNow = (union(collectionsOf) + union(groupsOf) + group.members.flatMap { m ->
                    rowOf[m.msId]?.text("Keywords")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                }).distinct()
                if (known.tags.isEmpty() && tagsNow.isNotEmpty()) tags = tagsNow
                if (known.notes == null) notes = ids.firstNotNullOfOrNull { notesOf[it] }
                val newTracks = tracks.filter { t -> known.audio.none { it.file == t.file } }
                if (newTracks.isNotEmpty()) audio = known.audio + newTracks
                if (known.bookmarks.isEmpty() && marks.isNotEmpty()) bookmarks = marks
            }
            ids.forEach { songIds[it] = target.id }
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
                    .distinct().map { SetlistEntry(songId = it) }
                val setlist = library.addSetlist(name, folder.id)
                library.editSetlist(setlist.id) { this.entries = entries }
                setlistsAdded++
            }
        }
        return Result(added, setlistsAdded, planned.skipped, planned.missing)
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
