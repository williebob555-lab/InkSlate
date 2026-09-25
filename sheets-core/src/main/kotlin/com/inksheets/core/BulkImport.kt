package com.inksheets.core

import java.io.File
import java.util.zip.ZipFile

/**
 * Bringing in a whole download of band music at once - a class website's "download all", a
 * director's shared folder, a zip of a semester's concerts - with as little sorting left over as
 * possible.
 *
 * Parts are gathered into songs by [ImportPlan] (file names, the words printed on the page, and a
 * scan read where there are no words). Folders become setlists: a folder of songs is a concert,
 * and a folder of parts named only for their instrument ("Trombone 2.pdf") is one song, whose own
 * folder is then the concert. Songs keep the order their track numbers give them.
 *
 * The files are copied into the music folder under the download's own name, keeping its folders,
 * so importing the same download again later finds everything already there and adds only what is
 * new - an updated part replaces nothing, and nothing arrives twice.
 */
object BulkImport {

    val MUSIC = setOf("pdf", "png", "jpg", "jpeg", "webp")
    val SOUND = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "aif", "aiff")

    data class Song(
        val title: String,
        /** Parts, with [ImportPlan.PlannedPart.file] relative to the download's root. */
        val parts: List<ImportPlan.PlannedPart>,
        val audio: List<String>,
        /** A song already in the library to add these to, instead of making one. */
        val into: String? = null,
        /** Kept apart from any other song of the same name (split by the person). */
        val apart: Boolean = false,
        /** What setlists call it; its title unless the person made two of one name. */
        val key: String = title
    )

    data class SetlistPlan(val name: String, val songTitles: List<String>)

    data class Plan(val name: String, val songs: List<Song>, val setlists: List<SetlistPlan>)

    /**
     * Plan an import of [files] (paths relative to the download's root, forward slashes), which is
     * called [rootName]. [textOf] and [recognise] read a file's printed words, as for [ImportPlan].
     */
    fun plan(
        rootName: String,
        files: List<String>,
        textOf: (String) -> String? = { null },
        recognise: (String) -> String? = { null },
        onProgress: (done: Int, of: Int) -> Unit = { _, _ -> }
    ): Plan {
        val usable = files.filter { f ->
            val segments = f.split('/')
            segments.none { it.startsWith(".") || it == "__MACOSX" }
        }
        val music = usable.filter { ext(it) in MUSIC }.sortedWith(compareBy({ trackNumber(it) }, { it.lowercase() }))
        val sound = usable.filter { ext(it) in SOUND }

        data class Placed(val part: ImportPlan.PlannedPart, val title: String, val concert: String, val order: Int)
        val songFolders = music.groupBy { it.substringBeforeLast('/', "") }.mapValues { (_, f) -> ImportPlan.folderIsSong(f) }
        val placed = music.mapIndexed { i, file ->
            onProgress(i, music.size)
            val part = ImportPlan.readPart(file, textOf, recognise)
            Placed(part, ImportPlan.songTitle(file, songFolders[file.substringBeforeLast('/', "")] == true), concertOf(file), trackNumber(file))
        }
        onProgress(music.size, music.size)

        // Songs: one per title, wherever in the download its parts are.
        val byKey = LinkedHashMap<String, MutableList<Placed>>()
        placed.forEach { byKey.getOrPut(Library.titleKey(it.title)) { ArrayList() } += it }
        // A recording goes with the song whose title its name starts with - "Sleigh Ride demo.mp3"
        // - the longest such title, so "Sleigh Ride Jazz" does not go to "Sleigh Ride".
        val audioFor = sound.groupBy { a ->
            val key = Library.titleKey(ImportPlan.songTitle(a))
            byKey.keys.filter { key == it || key.startsWith("$it ") }.maxByOrNull { it.length }
        }
        val songs = byKey.map { (key, group) ->
            Song(bestTitle(group.map { it.title }), group.map { it.part }, audioFor[key].orEmpty())
        }
        val titleOf = songs.associateBy({ Library.titleKey(it.title) }, { it.title })

        // Setlists: one per concert folder, songs in track order and each once.
        val concerts = LinkedHashMap<String, MutableList<Placed>>()
        placed.forEach { concerts.getOrPut(it.concert) { ArrayList() } += it }
        val setlists = concerts.map { (concert, members) ->
            val ordered = members.sortedWith(compareBy({ it.order }, { Library.sortKey(it.title) }))
                .map { titleOf.getValue(Library.titleKey(it.title)) }
                .distinct()
            SetlistPlan(if (concert.isEmpty()) rootName else ImportPlan.cleanFolderName(concert.substringAfterLast('/')), ordered)
        }.filter { it.songTitles.isNotEmpty() }
        return Plan(rootName, songs, setlists)
    }

    /** The folder a file's concert is: its own folder, or its song folder's parent for a part named only by instrument. */
    fun concertOf(file: String): String {
        val dir = file.substringBeforeLast('/', "")
        return if (ImportPlan.onlyPartName(file.substringAfterLast('/'))) dir.substringBeforeLast('/', "") else dir
    }

    /** The number a file or its song folder is filed under ("03 - Sleigh Ride"), for ordering. */
    fun trackNumber(file: String): Int {
        val name = file.substringAfterLast('/')
        val own = if (ImportPlan.onlyPartName(name)) file.substringBeforeLast('/', "").substringAfterLast('/') else name
        // "03 - Sleigh Ride", "3. Sleigh Ride", "03 Sleigh Ride" - but not "76 Trombones".
        val m = Regex("""^\s*(\d{1,3})\s*[-–—.)_]""").find(own) ?: Regex("""^\s*(0\d{1,2})\s""").find(own)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }

    /** Of the ways a song's title was written, the one with the most capitals and spaces kept. */
    private fun bestTitle(titles: List<String>): String =
        titles.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { e -> e.key.count { it == ' ' } }.thenByDescending { e -> e.key.count { it.isUpperCase() } })
            .first().key

    private fun ext(path: String) = path.substringAfterLast('.', "").lowercase()

    // ---- reading a download ----------------------------------------------------------

    /** A download to import: a folder, or a zip. [list] gives paths relative to its root. */
    interface Source {
        val name: String
        fun list(): List<String>
        /** Copy [path] to [to]. */
        fun copy(path: String, to: File)
        /** The file itself, where it already is one (a folder's); null inside a zip. */
        fun fileOf(path: String): File?
    }

    class FolderSource(private val folder: File) : Source {
        override val name: String = folder.name
        override fun list(): List<String> = folder.walkTopDown()
            .onEnter { it == folder || !it.name.startsWith(".") }
            .filter { it.isFile }
            .map { it.relativeTo(folder).invariantSeparatorsPath }
            .toList()
        override fun copy(path: String, to: File) {
            to.parentFile?.mkdirs()
            File(folder, path).copyTo(to, overwrite = true)
        }
        override fun fileOf(path: String): File = File(folder, path)
    }

    class ZipSource(private val zip: File) : Source {
        override val name: String = ImportPlan.cleanFolderName(zip.nameWithoutExtension)
        override fun list(): List<String> = ZipFile(zip).use { z ->
            z.entries().toList().filter { !it.isDirectory }.map { it.name.replace('\\', '/') }
                .filter { safe(it) }
        }
        override fun copy(path: String, to: File) {
            require(safe(path))
            ZipFile(zip).use { z ->
                val entry = z.getEntry(path) ?: return
                to.parentFile?.mkdirs()
                z.getInputStream(entry).use { input -> to.outputStream().use { input.copyTo(it) } }
            }
        }
        override fun fileOf(path: String): File? = null
        /** Never a path that climbs out of where it is put. */
        private fun safe(path: String) = path.split('/').none { it == ".." } && !path.startsWith("/")
    }

    /**
     * Put [plan] into [library]: files copied under `<root>/<plan name>/` (unless [inPlace], for a
     * folder already inside the music folder, given as its library-relative path), songs added or
     * gained parts, and - with [makeSetlists] - the setlists, in a folder of their own when there
     * are several. [skip] holds song titles left out.
     */
    fun apply(
        plan: Plan,
        source: Source,
        library: Library,
        root: File,
        makeSetlists: Boolean,
        skip: Set<String> = emptySet(),
        inPlace: String? = null
    ): Result {
        val base = inPlace ?: uniqueBase(root, plan.name)
        fun placed(path: String): String {
            val rel = if (base.isEmpty()) path else "$base/$path"
            if (inPlace == null) {
                val target = File(root, rel)
                if (!target.isFile || target.length() == 0L) source.copy(path, target)
            }
            return rel
        }
        var added = 0
        var matched = 0
        val ids = HashMap<String, String>()
        for (song in plan.songs) {
            if (song.title in skip) continue
            // Each file's part has the id the folder scan would give it, so the two agree.
            val parts = song.parts.map { planned ->
                val rel = placed(planned.file)
                planned.copy(file = rel).toPart().copy(id = Library.partIdFor(rel))
            }
            val audio = song.audio.map { AudioTrack(file = placed(it)) }
            val existing = song.into?.let { library.song(it) }
                ?: if (song.apart) null else library.songs.firstOrNull { !it.apart && Library.matchKey(it.title) == Library.matchKey(song.title) }
            val target = if (existing != null) {
                matched++
                existing
            } else {
                added++
                if (song.apart) library.addSong(song.title, emptyList()) { apart = true } else library.ensureSong(song.title)
            }
            parts.forEach { library.writePart(target.id, it) }
            val have = library.song(target.id)?.audio.orEmpty()
            val newAudio = audio.filter { a -> have.none { it.file == a.file } }
            if (newAudio.isNotEmpty()) library.editSong(target.id) { this.audio = have + newAudio }
            ids[song.key] = target.id
        }
        var setlists = 0
        if (makeSetlists) {
            val wanted = plan.setlists.map { s -> s.name to s.songTitles.mapNotNull(ids::get).distinct() }.filter { it.second.isNotEmpty() }
            val folder = if (wanted.size > 1) {
                library.foldersIn(null).firstOrNull { it.name == plan.name } ?: library.addFolder(plan.name)
            } else null
            for ((name, songIds) in wanted) {
                // Importing the same download again brings a setlist up to date rather than making a second.
                val setlist = library.setlistsIn(folder?.id).firstOrNull { Library.matchKey(it.name) == Library.matchKey(name) }
                    ?: library.addSetlist(name, folder?.id).also { setlists++ }
                val have = setlist.entries.map { it.songId }.toSet()
                val more = songIds.filter { it !in have }.map { SetlistEntry(songId = it) }
                if (more.isNotEmpty()) library.editSetlist(setlist.id) { entries = setlist.entries + more }
            }
        }
        return Result(added, matched, setlists, ids.values.distinct())
    }

    data class Result(val songsAdded: Int, val songsMatched: Int, val setlistsMade: Int, val songIds: List<String> = emptyList())

    /** Where a download goes: its own name, the same folder again when re-importing it. */
    private fun uniqueBase(root: File, name: String): String {
        val safe = name.map { if (it in "\\/:*?\"<>|") ' ' else it }.joinToString("").trim().ifEmpty { "Imported" }
        return "Imported/$safe"
    }
}
