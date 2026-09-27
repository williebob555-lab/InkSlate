package com.inksheets.core

import java.io.File

/**
 * Putting every part in the song it belongs to, and nothing anywhere else: the automatic sort.
 *
 * Run by every folder scan, after new files are taken in. One run leaves no stray: no song made of
 * one part of another song, no song called "Piccolo", no second copy of a part showing, no empty
 * song left in a setlist. Every rule decides from the library and the folder alone, so two devices
 * sorting on their own come to the same songs:
 *
 * 1. **A folder of one piece's parts is one song** ([ImportPlan.folderSong]). Whatever each file is
 *    called - "SweetC - Trumpet 1", "Sweet Caroline - Electric Bass", "neckalto" - they go together.
 * 2. **Songs sharing a file are one song.** "Pour Some Sugar - Trombone 1.pdf" in the MobileSheets
 *    folder and in the song's own folder is the same part, so the songs holding them are one piece.
 * 3. **A second copy of a part is kept but not shown**: the same file name, or the same file, twice
 *    in one song. The copy kept is one that opens, then one with markings, then the newest.
 * 4. **A song emptied by all this goes**, and its places in setlists pass to the song its parts
 *    went to - songs an older version made of single parts ("Piccolo", "Score") included.
 *
 * Never moves a part a person placed, nor touches a song a person split off on purpose.
 */
class LibrarySort(
    private val root: File,
    private val library: Library,
    /** A file's page count, 0 when it will not open; null when it cannot be told here. */
    private val pages: (File) -> Int? = { null }
) {

    /** What the sort did, in words, one line each. */
    val done = ArrayList<String>()

    /** How long each step took the last time, for the log. */
    val timings = LinkedHashMap<String, Long>()

    fun run(disk: List<LibraryScan.Found>) {
        fun step(name: String, block: () -> Unit) { val t = System.nanoTime(); block(); timings[name] = (System.nanoTime() - t) / 1_000_000 }
        step("names") { reread() }
        step("folders") { byFolder(disk) }
        step("shared files") { bySharedFile() }
        step("copies") { duplicates() }
        step("emptied") { emptied() }
        step("titles") { titles() }
    }

    /** Size and the start's hash, remembered while the file stays the same size and age. */
    private fun fingerprint(f: File): String? {
        if (!f.isFile || f.length() == 0L) return null
        val key = "${f.path}|${f.length()}|${f.lastModified()}"
        return prints.getOrPut(key) { "${f.length()}:${LibraryScan.hashOf(f)}" }
    }

    private fun pageCount(f: File): Int? {
        val key = "${f.path}|${f.length()}|${f.lastModified()}"
        pageCounts[key]?.let { return it }
        return pages(f)?.also { pageCounts[key] = it }
    }

    // ---- 0. what a part's name plainly says -----------------------------------------------

    /**
     * A part whose file name says plainly which part it is ("24K Magic - Score.pdf") takes that
     * instrument, over whatever an older version read off its first page. Never a part a person
     * named; a part nothing had named yet takes whatever its name says.
     */
    private fun reread() {
        for (s in library.songs) for (p in s.all) {
            if (p.source == InstrumentSource.PERSON) continue
            val readable = ImportPlan.readableName(p.file.substringAfterLast('/'))
            val plain = ImportPlan.namesPartPlainly(readable)
            if (!plain && p.instrument != null) continue
            var n = ImportPlan.namePart(p.file)
            if (n.instrument == null) continue
            // A lone "Tenor" beside "Double Tenor" and "Double Second" is the pans' tenor, not a saxophone.
            if (n.instrument == "tenor-sax" && InstrumentReader.normalise(n.label.orEmpty()).filterNot { it.all(Char::isDigit) } == listOf("tenor") &&
                s.all.any { it.instrument == "steel-pan" || ImportPlan.namePart(it.file).instrument == "steel-pan" }) n = n.copy(instrument = "steel-pan")
            val chair = n.chair ?: p.chair.takeIf { n.instrument == p.instrument }
            if (n.instrument == p.instrument && chair == p.chair && n.also == p.also) continue
            library.writePart(s.id, p.copy(instrument = n.instrument, source = InstrumentSource.FILE_NAME, label = n.label, also = n.also, chair = chair))
            done += "${s.title}: ${p.file.substringAfterLast('/')} is ${Instruments.partName(p.copy(instrument = n.instrument, chair = chair))}"
        }
    }

    // ---- 1. folders ------------------------------------------------------------------

    /** Per-song folders and their titles, for the music files on disk. */
    fun folderSongs(disk: List<LibraryScan.Found>): Map<String, String> =
        disk.filter { it.music }.groupBy { it.path.substringBeforeLast('/', "") }
            .mapNotNull { (folder, files) -> ImportPlan.folderSong(folder, files.map { it.path })?.let { folder to it } }
            .toMap()

    private fun byFolder(disk: List<LibraryScan.Found>) {
        for ((folder, title) in folderSongs(disk)) {
            val songs = library.songs
            val holding = songs.filter { s -> s.all.any { it.inFolder(folder) } }
            val movable = holding.filter { !it.apart }
            if (movable.isEmpty()) continue
            val key = Library.matchKey(title)
            val target = movable.firstOrNull { Library.matchKey(it.title) == key }
                ?: songs.firstOrNull { !it.apart && Library.matchKey(it.title) == key }
                ?: movable.maxWith(compareBy<Song> { s -> s.all.count { it.inFolder(folder) } }.thenByDescending { it.id })
            for (s in movable) {
                if (s.id == target.id) continue
                val theirs = s.all.filter { it.inFolder(folder) && !it.placed }
                if (theirs.isEmpty()) continue
                // All of it is this folder's (or copies in a general folder): the whole song goes in,
                // setlists and recordings with it. Otherwise only this folder's parts move.
                if (s.all.all { it.inFolder(folder) || ImportPlan.isGenericFolder(it.folderName()) }) {
                    library.mergeSongs(s.id, target.id)
                    done += "${s.title} put into ${target.title} (one folder)"
                } else {
                    theirs.forEach { library.writePart(target.id, it) }
                    done += "${theirs.size} part(s) of ${s.title} moved to ${target.title} (one folder)"
                }
            }
            // A song called after one of its parts, or with a part's name still on it, takes the folder's title.
            val now = library.song(target.id) ?: continue
            if (Library.matchKey(now.title) != key && strayTitle(now.title, title)) {
                library.editSong(now.id) { this.title = title }
                done += "${now.title} renamed $title"
            }
        }
    }

    /** "Piccolo", "Barbie Girl - DL", "Take On Me - Full Score copy": a title that is a part's, not a piece's. */
    private fun strayTitle(title: String, wanted: String): Boolean =
        ImportPlan.onlyPartName(title) || Library.titleKey(title).startsWith(Library.titleKey(wanted) + " ")

    // ---- 2. a file two songs share ------------------------------------------------------

    private fun bySharedFile() {
        // Content fingerprints, worked out once: the same file under any name is the same part.
        fun print(p: Part): String? = if (p.firstPage != null && p.firstPage != 1) null else fingerprint(File(root, p.file))
        var rounds = 0
        while (rounds++ < 50) {
            var merged = false
            val songs = library.songs.filter { !it.apart }
            // Decided from this round's picture; songs changed by a merge wait for the next round.
            val byId = songs.associateBy { it.id }
            val byName = HashMap<String, MutableSet<String>>()
            val byPrint = HashMap<String, MutableSet<String>>()
            for (s in songs) for (p in s.all) {
                sameName(p.file)?.let { byName.getOrPut(it) { LinkedHashSet() } += s.id }
                print(p)?.let { byPrint.getOrPut(it) { LinkedHashSet() } += s.id }
            }
            val gone = HashSet<String>()
            fun keeper(group: List<Song>) = group.maxWith(
                // The song kept: one whose title is not a copy's ("Song - 1"), then one that is a
                // folder's, then the one with most parts, then the smallest id - the same choice
                // on every device.
                compareBy<Song> { if (COPY_TITLE.containsMatchIn(it.title)) 0 else 1 }
                    .thenBy { s -> s.all.count { !ImportPlan.isGenericFolder(it.folderName()) } }
                    .thenBy { it.parts.size }.thenByDescending { it.id }
            )
            for ((name, ids) in byName) {
                val group = ids.filter { it !in gone }.mapNotNull { byId[it] }
                if (group.size < 2) continue
                val keep = keeper(group)
                for (s in group) {
                    if (s.id == keep.id) continue
                    // Same file name is not proof on its own ("Etude 1.pdf" in two books): the titles
                    // must agree too, or the files be the same file.
                    val alike = ImportPlan.likeness(s.title, keep.title) >= 0.6 ||
                        Library.titleKey(s.title).startsWith(Library.titleKey(keep.title)) ||
                        Library.titleKey(keep.title).startsWith(Library.titleKey(s.title)) ||
                        sameFile(s, keep, name)
                    if (!alike) continue
                    library.mergeSongs(s.id, keep.id)
                    gone += s.id
                    gone += keep.id
                    merged = true
                    done += "${s.title} put into ${keep.title} (a file both have: $name)"
                }
            }
            // A song that is nothing but copies of another song's files is that song.
            for ((_, ids) in byPrint) {
                val group = ids.filter { it !in gone }.mapNotNull { byId[it] }
                if (group.size < 2) continue
                for (s in group) {
                    val others = group.filter { it.id != s.id }
                    val home = others.firstOrNull { o -> s.all.all { p -> print(p) != null && o.all.any { print(it) == print(p) } } } ?: continue
                    if (keeper(listOf(s, home)).id == s.id && home.all.all { p -> print(p) != null && s.all.any { print(it) == print(p) } }) continue
                    library.mergeSongs(s.id, home.id)
                    gone += s.id
                    gone += home.id
                    merged = true
                    done += "${s.title} put into ${home.title} (the same file)"
                    break
                }
            }
            if (!merged) break
        }
    }

    private fun sameFile(a: Song, b: Song, name: String): Boolean {
        val x = a.all.firstOrNull { sameName(it.file) == name }?.let { File(root, it.file) } ?: return false
        val y = b.all.firstOrNull { sameName(it.file) == name }?.let { File(root, it.file) } ?: return false
        return fingerprint(x) != null && fingerprint(x) == fingerprint(y)
    }

    // ---- 3. copies of one part -----------------------------------------------------------

    private fun duplicates() {
        val marked = MobileSheetsMarks.load(root).filterValues { it.isNotEmpty() }.keys.map { it.lowercase() }.toSet()
        for (s in library.songs) {
            val all = s.all
            if (all.size < 2) continue
            val groups = ArrayList<List<Part>>()
            all.groupBy { p -> sameName(p.file, evenPartNames = true) + p.pageKey() }.values.filter { it.size > 1 }.forEach { groups += it }
            // Byte-for-byte the same file under two names.
            all.filter { it.firstPage == null || it.firstPage == 1 }
                .mapNotNull { p -> fingerprint(File(root, p.file))?.let { p to it } }
                .groupBy({ it.second }, { it.first }).values.filter { it.size > 1 }
                .forEach { same -> if (groups.none { g -> g.containsAll(same) }) groups += same }
            for (group in groups) {
                val files = group.map { File(root, it.file) }
                // Only decided where every copy is here to be looked at.
                if (files.any { !it.isFile }) continue
                val count = files.associateWith { f -> if (f.extension.equals("pdf", true)) pageCount(f) else 1 }
                val best = group.sortedWith(
                    compareBy<Part> { if (count[File(root, it.file)] == 0) 1 else 0 }
                        .thenBy { if (it.file.lowercase() in marked) 0 else 1 }
                        .thenBy { if (it.source == InstrumentSource.PERSON) 0 else 1 }
                        .thenByDescending { File(root, it.file).lastModified() / 2000 }
                        .thenBy { it.file }
                ).first()
                for (p in group) {
                    val dup = p.id != best.id
                    if (p.dup != dup) library.writePart(s.id, p.copy(dup = dup))
                }
                if (group.any { it.id != best.id && !it.dup }) done += "${s.title}: ${group.size - 1} copy(ies) of ${best.file.substringAfterLast('/')} hidden"
            }
        }
    }

    // ---- 4. songs left empty ---------------------------------------------------------------

    private fun emptied() {
        for (s in library.songs) {
            if (s.all.isNotEmpty() || s.audio.isNotEmpty()) continue
            // Where the parts it once had are now.
            val went = library.partsEverIn(s.id).mapNotNull { id -> library.partHome(id).takeIf { it.second }?.first }
                .filter { it != s.id }
            val key = Library.matchKey(ImportPlan.titleOf(s.title).ifBlank { s.title })
            val into = went.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.let { library.song(it) }
                // Or the song of the same name it was a second copy of.
                ?: library.songs.firstOrNull { o -> o.id != s.id && !o.apart && o.all.isNotEmpty() && Library.matchKey(ImportPlan.titleOf(o.title).ifBlank { o.title }) == key }
            if (into != null) {
                library.mergeSongs(s.id, into.id)
                done += "${s.title} was empty: its setlist places now go to ${into.title}"
            } else if (library.partsEverIn(s.id).isNotEmpty()) {
                // Its parts were all removed: nothing to open, so nothing to list.
                library.deleteSong(s.id)
                done += "${s.title} was empty and removed"
            }
        }
    }

    // ---- 5. titles with a part's name still on --------------------------------------------

    /** "Candy Man TROMBONE 1", "Score & Parts - Raiders": the song's title without the part's name. */
    private fun titles() {
        for (s in library.songs) {
            val tidy = tidyTitle(s) ?: continue
            // Not onto another song's title: that is a merge, which the name rules decide.
            if (library.songs.any { it.id != s.id && Library.matchKey(it.title) == Library.matchKey(tidy) }) continue
            library.editSong(s.id) { title = tidy }
            done += "${s.title} renamed $tidy"
        }
    }

    private fun tidyTitle(s: Song): String? {
        if (s.apart) return null
        // A second download's "(2)", carried into a title as " - 2", goes with the rest.
        val tidy = ImportPlan.titleOf(s.title).trim().replace(Regex("""\s+[-–]\s+\d{1,2}$"""), "")
        if (tidy.isBlank() || Library.matchKey(tidy) == Library.matchKey(s.title)) return null
        val words = Library.titleKey(tidy).split(' ')
        // "Studies for Trombone", "Escape for Euph": the instrument is part of the title.
        if (words.last() in setOf("for", "of", "with", "and", "on", "to") || words.last().length < 2) return null
        return tidy
    }

    // ---- what is left over ------------------------------------------------------------------

    /**
     * Everything that still looks out of place, one line each: what a person would call a stray.
     * Empty after a sort is the aim.
     */
    fun strays(disk: List<LibraryScan.Found>): List<String> {
        val out = ArrayList<String>()
        val songs = library.songs
        for (s in songs) {
            if (s.all.isEmpty() && s.audio.isEmpty()) out += "empty song: ${s.title}"
            // Only where it could have gone somewhere: a song whose one file is called "Euph 1" in a
            // general folder is simply called that.
            if (!s.apart && ImportPlan.onlyPartName(s.title) && s.all.any { !ImportPlan.isGenericFolder(it.folderName()) }) out += "song named for a part: ${s.title}"
            s.parts.groupBy { sameName(it.file, evenPartNames = true) + it.pageKey() }.values.filter { it.size > 1 }
                .forEach { out += "copies showing in ${s.title}: ${it.joinToString { p -> p.file }}" }
        }
        for (s in songs) tidyTitle(s)?.let { out += "title carries a part name: ${s.title} -> $it" }
        for ((folder, title) in folderSongs(disk)) {
            val holding = songs.filter { s -> !s.apart && s.all.any { it.inFolder(folder) && !it.placed } }
            if (holding.size > 1) out += "folder $folder ($title) split over: ${holding.joinToString { it.title }}"
        }
        val byName = HashMap<String, MutableSet<String>>()
        for (s in songs) if (!s.apart) for (p in s.all) sameName(p.file)?.let { byName.getOrPut(it) { LinkedHashSet() } += s.title }
        byName.filter { it.value.size > 1 }.forEach { (n, t) -> out += "file $n in several songs: ${t.joinToString()}" }
        return out
    }

    companion object {
        // Kept between sorts: reading a file's start or opening it costs far more than the sort.
        private val prints = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val pageCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

        /** "Song - 1", "Song (2)", "Song copy": a title a second copy was given. */
        private val COPY_TITLE = Regex("""(\s[-–—]\s\d+|\(\d+\)|\scopy)\s*$""", RegexOption.IGNORE_CASE)

        /**
         * A file's name as two copies of it share it: no folder, no extension, no MobileSheets id,
         * case and punctuation ignored. Null for a name that is only a part's ("Score", "Tuba") -
         * two folders' "Tuba.pdf" are two songs' tubas - unless [evenPartNames] (within one song).
         */
        fun sameName(path: String, evenPartNames: Boolean = false): String? {
            val name = path.substringAfterLast('/')
            val readable = ImportPlan.readableName(name)
            if (!evenPartNames && ImportPlan.onlyPartName(readable)) return null
            return Library.matchKey(readable).ifEmpty { null } ?: name.lowercase()
        }
    }
}

/** Every part of a song, hidden copies included. */
val Song.all: List<Part> get() = parts + duplicates

private fun Part.inFolder(folder: String) = file.substringBeforeLast('/', "") == folder
private fun Part.folderName() = file.substringBeforeLast('/', "").substringAfterLast('/')

/** Which pages of its file a part is, for telling two copies from two parts of one band pack. */
private fun Part.pageKey(): String = if (firstPage == null || firstPage == 1) "" else "#$firstPage-$lastPage"
