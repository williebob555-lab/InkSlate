package com.inksheets.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A setlist in one file, to hand to someone else: `Spring Concert.zip`.
 *
 * Nothing in it needs this app. The parts are the PDFs (or pictures) themselves, named in set
 * order - "01 The Liberty Bell - Trombone.pdf" - the recordings are the recordings, and
 * `Setlist.txt` lists the songs in order for anyone to read. A small `setlist.json` beside them
 * carries the details a list of files cannot (instruments, tempo, page ranges), so InkSheets
 * brings it in whole; without it, a zip of numbered PDFs imports just as well, song by file name.
 * InkSheets keeps handwriting inside the PDF it is drawn on, so a marked-up part arrives marked up.
 */
object SetlistBundle {

    const val EXTENSION = "zip"

    /** What was written before plain zips; still read. */
    const val OLD_EXTENSION = "inksheets"

    private val MUSIC = setOf("pdf", "png", "jpg", "jpeg", "webp")
    private val SOUND = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "aif", "aiff")

    @Serializable
    data class BundlePart(val file: String, val instrument: String? = null, val firstPage: Int? = null, val lastPage: Int? = null)

    @Serializable
    data class BundleAudio(val file: String, val label: String? = null)

    @Serializable
    data class BundleSong(
        val title: String,
        val composers: List<String> = emptyList(),
        val arrangers: List<String> = emptyList(),
        val key: String? = null,
        val timeSignature: String? = null,
        val tempo: Int? = null,
        val parts: List<BundlePart> = emptyList(),
        val audio: List<BundleAudio> = emptyList()
    )

    @Serializable
    data class Manifest(val name: String, val songs: List<BundleSong>, val format: Int = 1)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Write [setlistId] to [out]. Returns how many files went in. */
    fun export(library: Library, root: File, setlistId: String, out: File): Int {
        val setlist = library.setlist(setlistId) ?: error("No such setlist")
        val stored = LinkedHashMap<String, String>()   // library path -> name in the zip
        val taken = HashSet<String>()
        // Named for a person reading the zip: its place in the set, the song, and what it is.
        fun store(rel: String, name: String): String = stored.getOrPut(rel) {
            val ext = rel.substringAfterLast('.', "").lowercase()
            val base = name.map { if (it in "\\/:*?\"<>|") '_' else it }.joinToString("").trim()
            var candidate = "$base.$ext"
            var n = 2
            while (!taken.add(candidate.lowercase())) candidate = "$base ($n).$ext".also { n++ }
            candidate
        }

        val present = setlist.entries.mapNotNull { library.song(it.songId) }
        val width = present.size.toString().length.coerceAtLeast(2)
        val songs = present.mapIndexed { i, s ->
            val number = (i + 1).toString().padStart(width, '0')
            val parts = s.parts.filter { File(root, it.file).isFile }
            BundleSong(
                title = s.title, composers = s.composers, arrangers = s.arrangers, key = s.key,
                timeSignature = s.timeSignature, tempo = s.tempo,
                parts = parts.map { p ->
                    val what = p.instrument?.let { Instruments.byId[it]?.name ?: it }
                        ?: if (parts.size > 1) File(p.file).nameWithoutExtension else null
                    BundlePart(store(p.file, "$number ${s.title}" + (what?.let { " - $it" } ?: "")), p.instrument, p.firstPage, p.lastPage)
                },
                audio = s.audio.filter { File(root, it.file).isFile }.map { a ->
                    BundleAudio(store(a.file, "$number ${s.title} - " + (a.label ?: "recording")), a.label)
                }
            )
        }
        val list = buildString {
            append(setlist.name).append("\n\n")
            present.forEachIndexed { i, s -> append(i + 1).append(". ").append(s.title).append('\n') }
        }
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("Setlist.txt"))
            zip.write(list.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("setlist.json"))
            zip.write(json.encodeToString(Manifest.serializer(), Manifest(setlist.name, songs)).toByteArray())
            zip.closeEntry()
            for ((rel, inZip) in stored) {
                zip.putNextEntry(ZipEntry(inZip))
                File(root, rel).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return stored.size
    }

    data class Imported(val setlist: Setlist, val songsAdded: Int, val songsMatched: Int)

    /**
     * Bring [bundle] into [library]: its files into `Shared/<setlist name>/` under [root], a song
     * for each (or the parts added to a song already here with the same title), and the setlist in
     * a folder "Shared with me".
     */
    fun import(library: Library, root: File, bundle: File): Imported {
        ZipFile(bundle).use { zip ->
            val manifest = zip.getEntry("setlist.json")?.let { e ->
                json.decodeFromString(Manifest.serializer(), zip.getInputStream(e).readBytes().decodeToString())
            } ?: guessed(zip, bundle.nameWithoutExtension)
            val safeName = manifest.name.map { if (it in "\\/:*?\"<>|") ' ' else it }.joinToString("").trim().ifEmpty { "Setlist" }
            val base = "Shared/$safeName"

            fun extract(inZip: String): String? {
                val entry = zip.getEntry(inZip) ?: return null
                // Only a plain name inside the bundle's own folder: never a path that climbs out.
                val name = inZip.substringAfterLast('/').replace("..", "_")
                val rel = "$base/$name"
                val target = File(root, rel)
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                return rel
            }

            var added = 0
            var matched = 0
            val ids = manifest.songs.map { b ->
                val parts = b.parts.mapNotNull { p ->
                    extract(p.file)?.let { rel ->
                        Part(file = rel, instrument = p.instrument, firstPage = p.firstPage, lastPage = p.lastPage,
                            source = if (p.instrument != null) InstrumentSource.PERSON else InstrumentSource.UNKNOWN)
                    }
                }
                val audio = b.audio.mapNotNull { a -> extract(a.file)?.let { AudioTrack(file = it, label = a.label) } }
                val existing = library.songs.firstOrNull { Library.matchKey(it.title) == Library.matchKey(b.title) }
                if (existing != null) {
                    // Already here: add what is new, keep what the person has.
                    val newParts = parts.filter { p -> existing.parts.none { it.file == p.file } }
                    val newAudio = audio.filter { a -> existing.audio.none { it.file == a.file } }
                    if (newParts.isNotEmpty() || newAudio.isNotEmpty()) {
                        library.editSong(existing.id) {
                            this.parts = existing.parts + newParts
                            this.audio = existing.audio + newAudio
                        }
                    }
                    matched++
                    existing.id
                } else {
                    added++
                    library.addSong(b.title, parts) {
                        composers = b.composers
                        arrangers = b.arrangers
                        key = b.key
                        timeSignature = b.timeSignature
                        tempo = b.tempo
                        if (audio.isNotEmpty()) this.audio = audio
                    }.id
                }
            }
            val folder = library.foldersIn(null).firstOrNull { it.name == SHARED_FOLDER } ?: library.addFolder(SHARED_FOLDER)
            val setlist = library.addSetlist(manifest.name, folder.id)
            library.editSetlist(setlist.id) { entries = ids.map { SetlistEntry(songId = it) } }
            return Imported(library.setlist(setlist.id)!!, added, matched)
        }
    }

    const val SHARED_FOLDER = "Shared with me"

    /**
     * A zip with no details in it - someone's folder of parts, zipped: a song per title, in the
     * order the files are named, each file a part, the instrument read from its name.
     */
    private fun guessed(zip: ZipFile, name: String): Manifest {
        val files = zip.entries().toList().filter { !it.isDirectory && !it.name.substringAfterLast('/').startsWith(".") }
            .sortedBy { it.name.lowercase() }
        val music = files.filter { it.name.substringAfterLast('.').lowercase() in MUSIC }
        require(music.isNotEmpty()) { "There is no music in this zip." }
        val sound = files.filter { it.name.substringAfterLast('.').lowercase() in SOUND }
        fun titleOf(entry: ZipEntry): String {
            val file = entry.name.substringAfterLast('/')
            val unnumbered = file.replace(Regex("""^\s*\d{1,3}(\s*[.)_-]\s*|\s+)"""), "")
            return ImportPlan.songTitle(entry.name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + unnumbered.ifBlank { file })
        }
        val byTitle = LinkedHashMap<String, MutableList<ZipEntry>>()
        music.forEach { byTitle.getOrPut(Library.matchKey(titleOf(it))) { ArrayList() } += it }
        val songs = byTitle.values.map { entries ->
            val title = titleOf(entries.first())
            BundleSong(
                title = title,
                parts = entries.map { e -> BundlePart(e.name, InstrumentReader.readFileName(e.name.substringAfterLast('/'))?.instrument?.id) },
                audio = sound.filter { Library.matchKey(titleOf(it)) == Library.matchKey(title) }.map { BundleAudio(it.name) }
            )
        }
        return Manifest(name, songs)
    }
}
