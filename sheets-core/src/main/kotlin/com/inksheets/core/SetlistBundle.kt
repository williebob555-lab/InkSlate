package com.inksheets.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A setlist in one file, to hand to someone else: `Spring Concert.inksheets`.
 *
 * A zip holding the setlist's order, each song's details and every file its parts and
 * recordings use. A bandmate opens it and gets the songs and the setlist in their own library;
 * their instrument filter then shows them their own parts. The files go exactly as they are on
 * disk - and InkSheets keeps handwriting inside the PDF it is drawn on, so a part you have marked
 * up arrives marked up.
 */
object SetlistBundle {

    const val EXTENSION = "inksheets"

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
        val stored = LinkedHashMap<String, String>()   // library path -> path in the zip
        fun store(rel: String): String = stored.getOrPut(rel) { "files/${stored.size}-${rel.substringAfterLast('/')}" }

        val songs = setlist.entries.mapNotNull { library.song(it.songId) }.map { s ->
            BundleSong(
                title = s.title, composers = s.composers, arrangers = s.arrangers, key = s.key,
                timeSignature = s.timeSignature, tempo = s.tempo,
                parts = s.parts.filter { File(root, it.file).isFile }
                    .map { BundlePart(store(it.file), it.instrument, it.firstPage, it.lastPage) },
                audio = s.audio.filter { File(root, it.file).isFile }.map { BundleAudio(store(it.file), it.label) }
            )
        }
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
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
            } ?: error("This is not a shared setlist.")
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
                val existing = library.songs.firstOrNull { Library.sortKey(it.title) == Library.sortKey(b.title) }
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
}
