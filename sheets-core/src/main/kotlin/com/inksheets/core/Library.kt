package com.inksheets.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Where a part's instrument came from, so a guess can be shown as one and never overrule a person. */
@Serializable
enum class InstrumentSource { UNKNOWN, FILE_NAME, TEXT, OCR, PERSON }

/**
 * One file (or a run of pages in one) that belongs to a song - usually one instrument's part.
 *
 * [file] is relative to the library folder, with forward slashes, so the same record finds the
 * same file on the tablet, on Windows and on Fedora wherever each keeps the synced folder.
 * [firstPage]/[lastPage] (1-based, inclusive) pick out one part from a band pack that holds all of
 * them; null means the whole file.
 */
@Serializable
data class Part(
    val id: String = newId(),
    val file: String,
    val firstPage: Int? = null,
    val lastPage: Int? = null,
    val instrument: String? = null,
    val source: InstrumentSource = InstrumentSource.UNKNOWN,
    /** The words the instrument was read from ("Trombone 2", "Euph. T.C."), shown with a guess. */
    val label: String? = null,
    /**
     * Other instruments the same part is printed for - a flexible-band "Trombone / Euphonium /
     * Bassoon" part serves all three, so it shows for any of them.
     */
    val also: List<String> = emptyList(),
    /** Which of several parts for one instrument: 2 for "Trumpet 2", "2nd Trumpet", "Tpt. II". */
    val chair: Int? = null
)

/** A recording paired with a song, and the loop last used in it. */
@Serializable
data class AudioTrack(
    val file: String,
    val label: String? = null,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    /** Playback speed, 1.0 = as recorded. Kept per track because each is practised at its own. */
    val speed: Double = 1.0,
    /** Semitones to shift the pitch by, independent of the speed. */
    val pitch: Int = 0
)

/** A place to jump to by name: "Letter C", "Coda". */
@Serializable
data class Bookmark(val label: String, val part: String? = null, val page: Int)

data class Song(
    val id: String,
    val title: String,
    val composers: List<String> = emptyList(),
    val arrangers: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val key: String? = null,
    val timeSignature: String? = null,
    val tempo: Int? = null,
    /** The tempo word printed on the music ("Allegro"), when the tempo was read from one. */
    val tempoMark: String? = null,
    /** The tempo was read from the page, not set by a person - reading again may change it. */
    val tempoRead: Boolean = false,
    val difficulty: Int? = null,
    val notes: String? = null,
    val parts: List<Part> = emptyList(),
    val audio: List<AudioTrack> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val created: Long = 0,
    /** When it was last opened, on any device; 0 for never. */
    val opened: Long = 0,
    /** A colour to pick the song out by (ARGB), or null for the theme's own. */
    val color: Int? = null,
    /**
     * Set when a person split this song off from another of the same name: two different pieces
     * both called "Overture". The library never puts it back together with its namesake.
     */
    val apart: Boolean = false
) {
    /** The instruments this song has parts for. */
    val instruments: Set<String> get() = parts.flatMap { listOfNotNull(it.instrument) + it.also }.toSet()

    /** Each part's instrument and chair, "trumpet#2": two parts on one of these are two editions. */
    val seats: Set<String> get() = parts.mapNotNull { p -> p.instrument?.let { "$it#${p.chair ?: 0}" } }.toSet()
}

/** One place in a setlist. The same song can be in a setlist more than once, each its own entry. */
@Serializable
data class SetlistEntry(
    val id: String = newId(),
    val songId: String,
    val note: String? = null,
    /** A tempo for this performance, over the song's own. */
    val tempo: Int? = null,
    /** A colour for the song in this setlist only, over its own colour (ARGB). */
    val color: Int? = null
)

data class Setlist(
    val id: String,
    val name: String,
    val folderId: String? = null,
    val entries: List<SetlistEntry> = emptyList(),
    val order: Double = 0.0,
    val notes: String? = null,
    /** When it is performed, for sorting a year's concerts; ISO date or null. */
    val date: String? = null,
    /** A colour to pick the setlist out by (ARGB), or null for the theme's own. */
    val color: Int? = null
)

/**
 * A folder of setlists, which can hold folders of its own: "Wind Ensemble" holding "2025-26"
 * holding "Spring Concert". The same ensemble's years stay together without being one setlist.
 */
data class Folder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val order: Double = 0.0,
    /** A colour to pick the folder out by (ARGB), or null for the theme's own. */
    val color: Int? = null
)

internal fun newId(): String = UUID.randomUUID().toString()

/**
 * The music library: songs, setlists and the folders setlists are kept in.
 *
 * Reads come from memory; every change is an edit in [LibraryLog] and so reaches the other devices
 * through the synced folder. Nothing is ever rewritten in place, which is what lets two devices
 * change the library while apart and meet again without losing either's work: the later edit to
 * each field wins, and edits to different fields both stand.
 */
class Library(private val log: LibraryLog, now: () -> Long = System::currentTimeMillis) {

    private val clock = Clock(log.device, now)
    private val state = LibraryState()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Bumped whenever anything changes, so a screen can tell it has something to redraw. */
    @Volatile
    var version: Long = 0
        private set

    init {
        refresh()
    }

    /** Take in whatever other devices (or this one) have written since the last look. */
    @Synchronized
    fun refresh(): Boolean {
        var changed = false
        for (op in log.readNew()) {
            clock.observe(op.at)
            if (state.apply(op)) changed = true
        }
        if (changed) version++
        return changed
    }

    // ---- reading ---------------------------------------------------------------

    @get:Synchronized
    val songs: List<Song>
        get() {
            val parts = partIndex()
            return state.live(SONG).map { (id, f) -> song(id, f, parts) }.sortedBy { sortKey(it.title) }
        }

    @get:Synchronized
    val setlists: List<Setlist>
        get() = state.live(SETLIST).map { (id, f) -> setlist(id, f) }
            .sortedWith(compareBy({ sortKey(it.name) }, { it.id }))

    @get:Synchronized
    val folders: List<Folder>
        get() = saneFolders(state.live(FOLDER).map { (id, f) -> folder(id, f) })
            .sortedWith(compareBy({ sortKey(it.name) }, { it.id }))

    fun song(id: String): Song? = synchronized(this) {
        state.live(SONG)[id]?.let { song(id, it, partIndex()) }
    }

    // ---- parts, each a record of its own -----------------------------------------------

    /**
     * Every live part, by the song it belongs to, in order.
     *
     * Parts used to be one list on their song, so two devices each adding a part at the same time
     * wrote two lists, and the later one won - the other part was simply gone. Each part is now a
     * record of its own (kind [PART]), so adding, moving or removing one never touches another.
     */
    private fun partIndex(): Map<String, List<Part>> =
        state.live(PART).mapNotNull { (id, f) ->
            val song = f.string("song") ?: return@mapNotNull null
            val file = f.string("file") ?: return@mapNotNull null
            Triple(song, f.string("order")?.toDoubleOrNull() ?: 0.0, Part(
                id = id, file = file,
                firstPage = f.string("firstPage")?.toDoubleOrNull()?.toInt(),
                lastPage = f.string("lastPage")?.toDoubleOrNull()?.toInt(),
                instrument = f.string("instrument"),
                source = f.string("source")?.let { v -> InstrumentSource.entries.firstOrNull { it.name == v } } ?: InstrumentSource.UNKNOWN,
                label = f.string("label"),
                also = f.list("also", STRING_LIST),
                chair = f.string("chair")?.toDoubleOrNull()?.toInt()
            ))
        }.groupBy({ it.first }, { it.second to it.third })
            .mapValues { (_, list) -> list.sortedWith(compareBy({ it.first }, { it.second.id })).map { it.second } }

    /** A song's parts: its part records, and any from the older list form not yet made into records. */
    private fun partsOf(songId: String, fields: Map<String, JsonElement>, index: Map<String, List<Part>>): List<Part> {
        val records = index[songId].orEmpty()
        val legacy = fields.list("parts", PART_LIST).filter { state.fields(PART, it.id) == null }
        return records + legacy
    }

    /** The song a part record belongs to, and whether it is live. */
    fun partHome(partId: String): Pair<String?, Boolean> = synchronized(this) {
        val f = state.fields(PART, partId) ?: return null to false
        val song = (f["song"]?.value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val deleted = (f[Op.DELETED]?.value as? JsonPrimitive)?.content == "true"
        song to !deleted
    }

    /** When a part record was deleted, or null when it never was (or has been brought back). */
    fun partDeletedAt(partId: String): Long? = synchronized(this) {
        val f = state.fields(PART, partId) ?: return null
        val d = f[Op.DELETED] ?: return null
        if ((d.value as? JsonPrimitive)?.content != "true") return null
        d.at.ms
    }

    /**
     * Make [wanted] the parts of [songId]: new ones added, changed ones rewritten field by field,
     * and ones no longer wanted removed - but only those still recorded as this song's, so a part
     * just moved to another song is not taken away from it.
     */
    fun setParts(songId: String, wanted: List<Part>) {
        val current = song(songId)?.parts.orEmpty()
        val wantedIds = wanted.map { it.id }.toSet()
        for (p in current) {
            if (p.id in wantedIds) continue
            val (home, _) = partHome(p.id)
            if (home == null || home == songId) edit(PART, p.id) { put("song", songId); put("file", p.file); put(Op.DELETED, true) }
        }
        wanted.forEachIndexed { index, p -> writePart(songId, p, index.toDouble()) }
    }

    /** Write one part's record, only the fields that differ from what is recorded. */
    fun writePart(songId: String, p: Part, order: Double? = null) = synchronized(this) {
        val f = state.fields(PART, p.id)
        fun same(field: String, v: JsonElement): Boolean = f?.get(field)?.value == v
        val want = linkedMapOf<String, JsonElement>(
            "song" to JsonPrimitive(songId),
            "file" to JsonPrimitive(p.file),
            "firstPage" to (p.firstPage?.let(::JsonPrimitive) ?: JsonNull),
            "lastPage" to (p.lastPage?.let(::JsonPrimitive) ?: JsonNull),
            "instrument" to (p.instrument?.let(::JsonPrimitive) ?: JsonNull),
            "source" to JsonPrimitive(p.source.name),
            "label" to (p.label?.let(::JsonPrimitive) ?: JsonNull),
            "also" to json.encodeToJsonElement(STRING_LIST, p.also),
            "chair" to (p.chair?.let(::JsonPrimitive) ?: JsonNull),
            Op.DELETED to JsonPrimitive(false)
        )
        if (f == null || f["order"] == null) want["order"] = JsonPrimitive(order ?: System.currentTimeMillis().toDouble())
        val changed = want.filter { (k, v) -> !same(k, v) }
        if (changed.isNotEmpty()) edit(PART, p.id) { changed.forEach { (k, v) -> put(k, v) } }
    }

    /** Note what this device is called, for showing whose changes have arrived. */
    fun noteDevice(name: String) {
        val current = synchronized(this) { (state.fields(DEVICE, log.device)?.get("name")?.value as? JsonPrimitive)?.content }
        if (current != name) edit(DEVICE, log.device) { put("name", name) }
    }

    /** Devices by id, with their names. */
    fun deviceNames(): Map<String, String> = synchronized(this) {
        state.live(DEVICE).mapValues { (id, f) -> f.string("name") ?: id }
    }

    /** This device's id in the library. */
    val deviceId: String get() = log.device

    /** Put a song's parts in this order: the first for an instrument is the one that opens. */
    fun orderParts(ids: List<String>) {
        ids.forEachIndexed { i, id -> edit(PART, id) { put("order", i.toDouble()) } }
    }

    /** Remove a part. */
    fun deletePart(partId: String) = edit(PART, partId) { put(Op.DELETED, true) }

    /**
     * Turn every song's parts in the older list form into part records, keeping their ids - which
     * every device already shares, so devices doing this at once write the same records. Returns
     * how many were moved over.
     */
    fun migrateParts(): Int {
        var moved = 0
        val pending = synchronized(this) {
            state.live(SONG).map { (id, f) -> id to f.list("parts", PART_LIST).filter { state.fields(PART, it.id) == null } }
                .filter { it.second.isNotEmpty() }
        }
        for ((songId, legacy) in pending) {
            legacy.forEachIndexed { i, p -> writePart(songId, p, i.toDouble()); moved++ }
        }
        return moved
    }

    /**
     * The song called [title], made if there is none: found by its title with case, punctuation
     * and a leading article ignored. A new one's id comes from the title itself, so two devices
     * that each find the same new music make the same song rather than two.
     */
    fun ensureSong(title: String): Song {
        val key = matchKey(title)
        songs.firstOrNull { !it.apart && matchKey(it.title) == key }?.let { return it }
        val id = songIdFor(title)
        edit(SONG, id) {
            put("title", title)
            put("created", System.currentTimeMillis())
            put(Op.DELETED, false)
        }
        return song(id)!!
    }

    /**
     * Put all of [fromId]'s parts and recordings into [intoId], point its setlist entries there,
     * and remove it. For two songs that are one piece.
     */
    fun mergeSongs(fromId: String, intoId: String) {
        if (fromId == intoId) return
        val from = song(fromId) ?: return
        val into = song(intoId) ?: return
        from.parts.forEach { writePart(intoId, it) }
        val audio = into.audio + from.audio.filter { a -> into.audio.none { it.file == a.file } }
        editSong(intoId) {
            if (audio != into.audio) this.audio = audio
            if (into.composers.isEmpty() && from.composers.isNotEmpty()) composers = from.composers
            if (into.arrangers.isEmpty() && from.arrangers.isNotEmpty()) arrangers = from.arrangers
            if (into.tempo == null && from.tempo != null) tempo = from.tempo
            if (into.key == null && from.key != null) key = from.key
            if (into.timeSignature == null && from.timeSignature != null) timeSignature = from.timeSignature
            if (into.color == null && from.color != null) color = from.color
        }
        for (list in setlists) {
            if (list.entries.none { it.songId == fromId }) continue
            // A setlist that already has the song keeps it once, where it was.
            val next = if (list.entries.any { it.songId == intoId }) list.entries.filter { it.songId != fromId }
            else list.entries.map { if (it.songId == fromId) it.copy(songId = intoId) else it }
            editSetlist(list.id) { entries = next }
        }
        deleteSong(fromId)
    }

    /**
     * Move one part to [songId]. When [songId] is null the part becomes a song of its own, named
     * [title] and kept apart from any song of the same name.
     */
    fun movePart(partId: String, songId: String?, title: String? = null): String? {
        val from = songs.firstOrNull { s -> s.parts.any { it.id == partId } } ?: return null
        val part = from.parts.first { it.id == partId }
        val target = songId ?: newId().also { id ->
            edit(SONG, id) {
                put("title", title ?: from.title)
                put("created", System.currentTimeMillis())
                put("apart", true)
            }
        }
        writePart(target, part)
        if (from.parts.size == 1 && from.audio.isEmpty()) deleteSong(from.id)
        return target
    }

    fun setlist(id: String): Setlist? = setlists.firstOrNull { it.id == id }

    /** Folders directly inside [parentId]; null for the top level. */
    fun foldersIn(parentId: String?): List<Folder> = folders.filter { it.parentId == parentId }

    /** Setlists directly inside [folderId]; null for those not in any folder. */
    fun setlistsIn(folderId: String?): List<Setlist> {
        val known = folders.map { it.id }.toSet()
        return setlists.filter { s ->
            val home = s.folderId?.takeIf { it in known }
            home == folderId
        }
    }

    /** From the top level down to [folderId], for a breadcrumb. */
    fun pathTo(folderId: String?): List<Folder> {
        val byId = folders.associateBy { it.id }
        val path = ArrayList<Folder>()
        var at = folderId?.let { byId[it] }
        while (at != null) {
            path.add(0, at)
            at = at.parentId?.let { byId[it] }
        }
        return path
    }

    /** Every setlist inside [folderId] at any depth - a whole ensemble's years at once. */
    fun setlistsUnder(folderId: String): List<Setlist> {
        val inside = HashSet<String>().apply { add(folderId) }
        var grew = true
        val all = folders
        while (grew) {
            grew = false
            for (f in all) if (f.parentId in inside && inside.add(f.id)) grew = true
        }
        return setlists.filter { it.folderId in inside }
    }

    // ---- changing --------------------------------------------------------------

    fun addSong(title: String, parts: List<Part> = emptyList(), setup: SongEdit.() -> Unit = {}): Song {
        val id = newId()
        var wanted: List<Part>? = null
        edit(SONG, id) {
            put("title", title)
            put("created", System.currentTimeMillis())
            wanted = SongEdit(this).apply(setup).partsWanted
        }
        (wanted ?: parts).forEachIndexed { i, p -> writePart(id, p, i.toDouble()) }
        return song(id)!!
    }

    fun editSong(id: String, change: SongEdit.() -> Unit) {
        var wanted: List<Part>? = null
        edit(SONG, id) { wanted = SongEdit(this).apply(change).partsWanted }
        wanted?.let { setParts(id, it) }
    }

    fun addFolder(name: String, parentId: String? = null): Folder {
        val id = newId()
        edit(FOLDER, id) {
            put("name", name)
            put("parent", parentId)
            put("order", nextOrder(foldersIn(parentId).map { it.order }))
        }
        return folders.first { it.id == id }
    }

    fun renameFolder(id: String, name: String) = edit(FOLDER, id) { put("name", name) }

    fun setFolderColor(id: String, color: Int?) = edit(FOLDER, id) { put("color", color) }

    /**
     * Move a folder under another. Refused when [parentId] is the folder itself or inside it,
     * which would take the folder and everything in it out of the tree.
     */
    fun moveFolder(id: String, parentId: String?): Boolean {
        if (parentId != null && (parentId == id || pathTo(parentId).any { it.id == id })) return false
        edit(FOLDER, id) { put("parent", parentId) }
        return true
    }

    fun addSetlist(name: String, folderId: String? = null): Setlist {
        val id = newId()
        edit(SETLIST, id) {
            put("name", name)
            put("folder", folderId)
            put("entries", emptyList(), ENTRY_LIST)
            put("order", nextOrder(setlistsIn(folderId).map { it.order }))
        }
        return setlist(id)!!
    }

    fun editSetlist(id: String, change: SetlistEdit.() -> Unit) = edit(SETLIST, id) { SetlistEdit(this).change() }

    /**
     * [from] put into [into], in order after its own songs - each song once - and then gone.
     * Songs and their parts are not touched: a setlist only points at them.
     */
    fun mergeSetlists(from: List<String>, into: String) {
        val target = setlist(into) ?: return
        val have = target.entries.map { it.songId }.toMutableSet()
        val more = from.filter { it != into }.mapNotNull { setlist(it) }.flatMap { it.entries }
            .filter { have.add(it.songId) }
            .map { SetlistEntry(songId = it.songId, note = it.note, tempo = it.tempo, color = it.color) }
        if (more.isNotEmpty()) editSetlist(into) { entries = target.entries + more }
        from.filter { it != into }.forEach { deleteSetlist(it) }
    }

    /** Add [songId] to the end of a setlist, or at [index]. */
    fun addToSetlist(setlistId: String, songId: String, index: Int? = null): SetlistEntry {
        val entry = SetlistEntry(songId = songId)
        val current = setlist(setlistId)?.entries.orEmpty().toMutableList()
        current.add((index ?: current.size).coerceIn(0, current.size), entry)
        editSetlist(setlistId) { entries = current }
        return entry
    }

    fun removeFromSetlist(setlistId: String, entryId: String) {
        val current = setlist(setlistId)?.entries.orEmpty().filterNot { it.id == entryId }
        editSetlist(setlistId) { entries = current }
    }

    fun moveInSetlist(setlistId: String, entryId: String, to: Int) {
        val current = setlist(setlistId)?.entries.orEmpty().toMutableList()
        val from = current.indexOfFirst { it.id == entryId }
        if (from < 0) return
        val entry = current.removeAt(from)
        current.add(to.coerceIn(0, current.size), entry)
        editSetlist(setlistId) { entries = current }
    }

    fun deleteSong(id: String) = edit(SONG, id) { put(Op.DELETED, true) }

    /** Bring back a song that was removed (from the library's Trash). */
    fun restoreSong(id: String) = edit(SONG, id) { put(Op.DELETED, false) }

    /** Bring back a removed part, in the song it was in. */
    fun restorePart(partId: String) = edit(PART, partId) { put(Op.DELETED, false) }

    /** Note that [id] was opened just now, for "Recently opened". */
    fun markOpened(id: String, at: Long = System.currentTimeMillis()) = edit(SONG, id) { put("opened", at) }
    fun deleteSetlist(id: String) = edit(SETLIST, id) { put(Op.DELETED, true) }

    /**
     * Delete a folder. What was inside moves up to where the folder was, rather than going with
     * it: deleting "2023-24" must never quietly delete that year's setlists.
     */
    fun deleteFolder(id: String) {
        val folder = folders.firstOrNull { it.id == id } ?: return
        foldersIn(id).forEach { moveFolder(it.id, folder.parentId) }
        setlistsIn(id).forEach { s -> editSetlist(s.id) { this.folderId = folder.parentId } }
        edit(FOLDER, id) { put(Op.DELETED, true) }
    }

    // ---- instrument profiles ---------------------------------------------------------

    /**
     * The instrument profiles: the built-in ones (unless changed or removed here) and any made
     * here. Kept in the library so every device offers the same choices.
     */
    fun profiles(): List<InstrumentProfile> = synchronized(this) {
        val stored = state.live(PROFILE).map { (id, f) ->
            InstrumentProfile(id, f.string("name") ?: "Instrument", f.list("instruments", STRING_LIST))
        }.associateBy { it.id }
        val builtIn = Instruments.defaultProfiles.mapNotNull { d ->
            when {
                d.id in stored -> stored.getValue(d.id)
                state.fields(PROFILE, d.id) != null -> null      // removed
                else -> d
            }
        }
        builtIn + stored.values.filter { s -> Instruments.defaultProfiles.none { it.id == s.id } }.sortedBy { it.name }
    }

    fun saveProfile(profile: InstrumentProfile) = edit(PROFILE, profile.id) {
        put("name", profile.name)
        put("instruments", profile.instruments, STRING_LIST)
        put(Op.DELETED, false)
    }

    fun deleteProfile(id: String) = edit(PROFILE, id) { put(Op.DELETED, true) }

    // ---- instruments taught here -----------------------------------------------------

    /**
     * Instruments added in this library, and names added to built-in ones (a record with a
     * built-in's id). Kept in the library so every device reads parts the same way.
     */
    fun instruments(): List<Instrument> = synchronized(this) {
        state.live(INSTRUMENT).map { (id, f) ->
            Instrument(
                id = id,
                name = f.string("name") ?: Instruments.builtIn.firstOrNull { it.id == id }?.name ?: id,
                names = f.list("names", STRING_LIST),
                transpose = f.string("transpose")?.toIntOrNull() ?: 0,
                clef = f.string("clef") ?: "treble",
                sameAs = f.list("sameAs", STRING_LIST)
            )
        }
    }

    fun saveInstrument(instrument: Instrument) = edit(INSTRUMENT, instrument.id) {
        put("name", instrument.name)
        put("names", instrument.names, STRING_LIST)
        put("transpose", instrument.transpose.toString())
        put("clef", instrument.clef)
        put("sameAs", instrument.sameAs, STRING_LIST)
        put(Op.DELETED, false)
    }

    fun deleteInstrument(id: String) = edit(INSTRUMENT, id) { put(Op.DELETED, true) }

    // ---- practice ------------------------------------------------------------------

    /** How much a song has been practised, on every device together. */
    data class Practice(val totalSeconds: Long, val lastDay: String?, val byDay: Map<String, Long>)

    /**
     * Add [seconds] of practice on [song] on [day] (an ISO date). Each device keeps its own total
     * for each song and day, so two devices practising the same song never write the same record.
     */
    fun addPractice(songId: String, day: String, seconds: Long) {
        val id = "$songId|$day|${log.device}"
        val before = synchronized(this) {
            (state.fields(PRACTICE, id)?.get("seconds")?.value as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        }
        edit(PRACTICE, id) {
            put("song", songId)
            put("day", day)
            put("seconds", before + seconds)
        }
    }

    fun practiceOf(songId: String): Practice = synchronized(this) {
        val days = HashMap<String, Long>()
        state.live(PRACTICE).values.forEach { f ->
            if (f.string("song") != songId) return@forEach
            val day = f.string("day") ?: return@forEach
            days[day] = (days[day] ?: 0) + (f.string("seconds")?.toLongOrNull() ?: 0)
        }
        Practice(days.values.sum(), days.keys.maxOrNull(), days.toSortedMap())
    }

    /** Rewrite this device's log without its superseded edits, once it has grown enough to matter. */
    @Synchronized
    fun compactIfLarge(threshold: Long = 4L * 1024 * 1024) {
        if (log.ownSize() > threshold) log.compact(state)
    }

    // ---- the edit machinery ----------------------------------------------------

    /** Collects the fields of one record changed together, stamped and written as one batch. */
    inner class Edit internal constructor(private val kind: String, private val id: String) {
        internal val ops = ArrayList<Op>()

        fun put(field: String, value: JsonElement) {
            ops += Op(clock.tick(), kind, id, field, value)
        }

        fun put(field: String, value: String?) = put(field, value?.let(::JsonPrimitive) ?: JsonNull)
        fun put(field: String, value: Number?) = put(field, value?.let(::JsonPrimitive) ?: JsonNull)
        fun put(field: String, value: Boolean) = put(field, JsonPrimitive(value))
        fun <T> put(field: String, value: T, serializer: KSerializer<T>) =
            put(field, json.encodeToJsonElement(serializer, value))
    }

    @Synchronized
    private fun edit(kind: String, id: String, fill: Edit.() -> Unit) {
        val edit = Edit(kind, id).apply(fill)
        log.append(edit.ops)
        edit.ops.forEach { state.apply(it) }
        version++
    }

    // ---- decoding --------------------------------------------------------------

    private fun song(id: String, f: Map<String, JsonElement>, index: Map<String, List<Part>>) = Song(
        id = id,
        title = f.string("title") ?: "Untitled",
        composers = f.list("composers", STRING_LIST),
        arrangers = f.list("arrangers", STRING_LIST),
        artists = f.list("artists", STRING_LIST),
        genres = f.list("genres", STRING_LIST),
        tags = f.list("tags", STRING_LIST),
        key = f.string("key"),
        timeSignature = f.string("timeSignature"),
        tempo = f.string("tempo")?.toDoubleOrNull()?.toInt(),
        tempoMark = f.string("tempoMark"),
        tempoRead = f.string("tempoRead") == "true",
        difficulty = f.string("difficulty")?.toDoubleOrNull()?.toInt(),
        notes = f.string("notes"),
        parts = partsOf(id, f, index),
        audio = f.list("audio", AUDIO_LIST),
        bookmarks = f.list("bookmarks", BOOKMARK_LIST),
        created = f.string("created")?.toLongOrNull() ?: 0,
        opened = f.string("opened")?.toLongOrNull() ?: 0,
        color = f.string("color")?.toDoubleOrNull()?.toLong()?.toInt(),
        apart = f.string("apart") == "true"
    )

    private fun setlist(id: String, f: Map<String, JsonElement>) = Setlist(
        id = id,
        name = f.string("name") ?: "Untitled setlist",
        folderId = f.string("folder"),
        entries = f.list("entries", ENTRY_LIST),
        order = f.string("order")?.toDoubleOrNull() ?: 0.0,
        notes = f.string("notes"),
        date = f.string("date"),
        color = f.string("color")?.toDoubleOrNull()?.toLong()?.toInt()
    )

    private fun folder(id: String, f: Map<String, JsonElement>) = Folder(
        id = id,
        name = f.string("name") ?: "Untitled folder",
        parentId = f.string("parent"),
        order = f.string("order")?.toDoubleOrNull() ?: 0.0,
        color = f.string("color")?.toDoubleOrNull()?.toLong()?.toInt()
    )

    /**
     * Folders as they can be shown. Two devices each moving a folder into the other while apart
     * makes a loop, which no single move could; and a folder whose parent was deleted elsewhere
     * has nowhere to be. Both are shown at the top level rather than vanishing.
     */
    private fun saneFolders(all: List<Folder>): List<Folder> {
        val byId = all.associateBy { it.id }
        return all.map { f ->
            val seen = HashSet<String>()
            var at: Folder? = f
            var broken = false
            while (at?.parentId != null) {
                if (!seen.add(at.id)) { broken = true; break }
                at = byId[at.parentId]
                if (at == null) { broken = true; break }
            }
            if (broken) f.copy(parentId = null) else f
        }
    }

    private fun Map<String, JsonElement>.string(field: String): String? =
        (this[field] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun <T> Map<String, JsonElement>.list(field: String, serializer: KSerializer<List<T>>): List<T> =
        this[field]?.takeIf { it !is JsonNull }?.let {
            runCatching { json.decodeFromJsonElement(serializer, it) }.getOrNull()
        }.orEmpty()

    private fun nextOrder(existing: List<Double>): Double = (existing.maxOrNull() ?: 0.0) + 1.0

    companion object {
        const val SONG = "song"
        const val SETLIST = "setlist"
        const val FOLDER = "folder"
        const val PRACTICE = "practice"
        const val PROFILE = "profile"
        const val INSTRUMENT = "instrument"
        const val PART = "part"
        const val DEVICE = "device"

        /** The id a song found by title is given: the same on every device for the same title. */
        fun songIdFor(title: String): String = "s-" + digest(titleKey(title))

        /** The id a part found in the music folder is given: the same on every device for the same file. */
        fun partIdFor(relativePath: String): String = "p-" + digest(relativePath.lowercase())

        private fun digest(text: String): String =
            java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
                .take(10).joinToString("") { "%02x".format(it) }

        internal val STRING_LIST = ListSerializer(String.serializer())
        internal val PART_LIST = ListSerializer(Part.serializer())
        internal val AUDIO_LIST = ListSerializer(AudioTrack.serializer())
        internal val BOOKMARK_LIST = ListSerializer(Bookmark.serializer())
        internal val ENTRY_LIST = ListSerializer(SetlistEntry.serializer())

        /** "The Liberty Bell" files under L, as a printed index would have it. */
        fun sortKey(title: String): String =
            title.trim().lowercase().removePrefix("the ").removePrefix("a ").removePrefix("an ")

        /**
         * A title as two copies of one song are matched by: case, punctuation and a leading
         * article ignored, so "Sleigh-Ride", "SLEIGH RIDE" and "The Sleigh Ride" are one song.
         */
        fun matchKey(title: String): String = titleKey(title).replace(" ", "")

        /**
         * [matchKey] with its word breaks kept, for asking whether one title starts with another
         * ("Sleigh Ride demo" with "Sleigh Ride"), and for song ids.
         */
        fun titleKey(title: String): String =
            sortKey(title.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim())
    }
}

/** The fields of a song that can be changed, each written only if it is set. */
class SongEdit internal constructor(private val edit: Library.Edit) {
    var title: String? = null; set(v) { field = v; edit.put("title", v) }
    var composers: List<String>? = null; set(v) { field = v; edit.put("composers", v.orEmpty(), Library.STRING_LIST) }
    var arrangers: List<String>? = null; set(v) { field = v; edit.put("arrangers", v.orEmpty(), Library.STRING_LIST) }
    var artists: List<String>? = null; set(v) { field = v; edit.put("artists", v.orEmpty(), Library.STRING_LIST) }
    var genres: List<String>? = null; set(v) { field = v; edit.put("genres", v.orEmpty(), Library.STRING_LIST) }
    var tags: List<String>? = null; set(v) { field = v; edit.put("tags", v.orEmpty(), Library.STRING_LIST) }
    var key: String? = null; set(v) { field = v; edit.put("key", v) }
    var timeSignature: String? = null; set(v) { field = v; edit.put("timeSignature", v) }
    var tempo: Int? = null; set(v) { field = v; edit.put("tempo", v) }
    var tempoMark: String? = null; set(v) { field = v; edit.put("tempoMark", v) }
    var tempoRead: Boolean? = null; set(v) { field = v; edit.put("tempoRead", v ?: false) }
    var difficulty: Int? = null; set(v) { field = v; edit.put("difficulty", v) }
    var notes: String? = null; set(v) { field = v; edit.put("notes", v) }
    /** The song's parts, all together: written as a part record each, never as one list. */
    var parts: List<Part>? = null; set(v) { field = v; partsWanted = v.orEmpty() }
    internal var partsWanted: List<Part>? = null
    var color: Int? = null; set(v) { field = v; edit.put("color", v) }
    var apart: Boolean? = null; set(v) { field = v; edit.put("apart", v ?: false) }
    var audio: List<AudioTrack>? = null; set(v) { field = v; edit.put("audio", v.orEmpty(), Library.AUDIO_LIST) }
    var bookmarks: List<Bookmark>? = null; set(v) { field = v; edit.put("bookmarks", v.orEmpty(), Library.BOOKMARK_LIST) }
}

class SetlistEdit internal constructor(private val edit: Library.Edit) {
    var name: String? = null; set(v) { field = v; edit.put("name", v) }
    var folderId: String? = null; set(v) { field = v; edit.put("folder", v) }
    var entries: List<SetlistEntry>? = null; set(v) { field = v; edit.put("entries", v.orEmpty(), Library.ENTRY_LIST) }
    var order: Double? = null; set(v) { field = v; edit.put("order", v) }
    var notes: String? = null; set(v) { field = v; edit.put("notes", v) }
    var date: String? = null; set(v) { field = v; edit.put("date", v) }
    var color: Int? = null; set(v) { field = v; edit.put("color", v) }
}
