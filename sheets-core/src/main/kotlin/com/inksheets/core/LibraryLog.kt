package com.inksheets.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * When an edit was made, in an order every device agrees on.
 *
 * A hybrid logical clock: wall time in milliseconds, a counter for edits within the same
 * millisecond (or made while this device's clock is behind one it has already seen), and the
 * device as the final tie-break. Two devices that disagree about the time still agree about which
 * of two edits came later, because the stamps are compared, never the clocks.
 */
@Serializable
data class Stamp(val ms: Long, val n: Int, val device: String) : Comparable<Stamp> {
    override fun compareTo(other: Stamp): Int =
        compareValuesBy(this, other, Stamp::ms, Stamp::n, Stamp::device)
}

class Clock(private val device: String, private val now: () -> Long = System::currentTimeMillis) {
    private var last = Stamp(0, 0, device)

    @Synchronized
    fun tick(): Stamp {
        val wall = now()
        last = if (wall > last.ms) Stamp(wall, 0, device) else Stamp(last.ms, last.n + 1, device)
        return last
    }

    /** Never stamp below an edit already seen, so a new edit always wins over what it replaces. */
    @Synchronized
    fun observe(seen: Stamp) {
        if (seen.ms > last.ms || (seen.ms == last.ms && seen.n > last.n)) {
            last = Stamp(seen.ms, seen.n, device)
        }
    }
}

/**
 * One edit: a single field of a single record set to a value.
 *
 * Records are identified by [kind] ("song", "setlist", "folder", ...) and [id]. A record is
 * deleted by setting its [DELETED] field to true, so a deletion is an edit like any other and
 * takes part in the same ordering - a record edited on one device after it was deleted on another
 * comes back, and one deleted after the edit stays gone.
 */
@Serializable
data class Op(
    val at: Stamp,
    val kind: String,
    val id: String,
    val field: String,
    val value: JsonElement = JsonNull,
    /** Observed list before this edit. Older clients ignore it and read the full value. */
    val base: JsonElement? = null,
    /** Removal tombstones observed by the author, including items absent from base. */
    val removals: Map<String, Map<String, Stamp>> = emptyMap()
) {
    companion object {
        const val DELETED = "_deleted"
    }
}

/** The current fields of every record, including item-level projections of observed lists. */
class LibraryState {

    data class Key(val kind: String, val id: String)

    class Value(val at: Stamp, val value: JsonElement)

    private val records = LinkedHashMap<Key, MutableMap<String, Value>>()

    /** The same records, by kind: asking for the setlists walks the setlists, not every part too. */
    private val byKind = HashMap<String, LinkedHashMap<String, MutableMap<String, Value>>>()
    private val collections = HashMap<Triple<String, String, String>, MergedCollection>()

    /**
     * Fold one edit in. Returns true when it changed anything.
     *
     * Ordinary fields use their latest stamp. Observed lists merge item membership, properties
     * and ordering separately. Both paths are order-independent and idempotent.
     */
    fun apply(op: Op): Boolean {
        if (op.field in REMEMBERED) {
            val v = (op.value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
            if (v != null) {
                history.getOrPut(Key(op.kind, op.id)) { HashSet() }.add(v)
                everWith.getOrPut(Triple(op.kind, op.field, v)) { LinkedHashSet() }.add(op.id)
            }
        }
        val key = Key(op.kind, op.id)
        first[key]?.let { if (op.at < it) first[key] = op.at } ?: run { first[key] = op.at }
        val fields = records.getOrPut(key) { HashMap<String, Value>().also { byKind.getOrPut(op.kind) { LinkedHashMap() }[op.id] = it } }
        val current = fields[op.field]
        if (isMergedList(op.kind, op.field)) {
            val merged = collections.getOrPut(Triple(op.kind, op.id, op.field)) { MergedCollection(if (op.field == "audio") "file" else "id") }
            merged.apply(op)
            val value = merged.value()
            fields[op.field] = Value(maxOf(current?.at ?: op.at, op.at), value)
            return current?.value != value
        }
        if (current != null && current.at >= op.at) return false
        fields[op.field] = Value(op.at, op.value)
        return true
    }

    fun fields(kind: String, id: String): Map<String, Value>? = records[Key(kind, id)]

    /** The earliest edit read for a record: near enough when it was made, for one that never said. */
    fun firstSeen(kind: String, id: String): Stamp? = first[Key(kind, id)]

    private val first = HashMap<Key, Stamp>()

    /** Every value a remembered field of a record has held, except its present one. */
    fun formerValues(kind: String, id: String, field: String): Set<String> {
        val now = (records[Key(kind, id)]?.get(field)?.value as? kotlinx.serialization.json.JsonPrimitive)?.content
        return history[Key(kind, id)].orEmpty() - setOfNotNull(now)
    }

    /** Records of [kind] whose [field] has ever held [value]. */
    fun recordsEverWith(kind: String, field: String, value: String): List<String> =
        everWith[Triple(kind, field, value)]?.toList().orEmpty()

    // Which song a part has been in, over time: only "song" is remembered, being the only field
    // whose past says something (where a part came from).
    private val history = HashMap<Key, MutableSet<String>>()
    private val everWith = HashMap<Triple<String, String, String>, MutableSet<String>>()
    private val REMEMBERED = setOf("song")

    /** Every live (not deleted) record of [kind], with its fields. */
    fun live(kind: String): Map<String, Map<String, JsonElement>> =
        byKind[kind].orEmpty().asSequence()
            .filter { (_, fields) -> !isDeleted(fields) }
            .associate { (id, fields) -> id to fields.mapValues { it.value.value } }

    /** Every record of [kind], deleted ones too, with their fields as stamped. */
    fun allOf(kind: String): Map<String, Map<String, Value>> = byKind[kind].orEmpty()

    fun latestOf(kind: String, id: String, field: String): Stamp? = records[Key(kind, id)]?.get(field)?.at

    /** A collection delta can still supply an item after a later list snapshot was written. */
    fun keeps(op: Op): Boolean = collections[Triple(op.kind, op.id, op.field)]?.keeps(op.at)
        ?: (latestOf(op.kind, op.id, op.field) == op.at)

    fun removalContext(kind: String, id: String, field: String): Map<String, Map<String, Stamp>> =
        collections[Triple(kind, id, field)]?.removalContext().orEmpty()

    companion object {
        fun isMergedList(kind: String, field: String) = (kind == "song" && field == "audio") || (kind == "setlist" && field == "entries")
    }

    private fun isDeleted(fields: Map<String, Value>): Boolean =
        (fields[Op.DELETED]?.value as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
}

/**
 * Each observed-list edit supplies item deltas. Membership is independent of metadata: changing
 * a track's speed never revives a track removed by another device. JSON objects merge at their
 * leaves, so teaching one part does not replace another part's turn times. Ordered lists use
 * predecessor links, with deterministic sibling and cycle handling for concurrent edits.
 */
private class MergedCollection(private val keyField: String) {
    private data class Cell(val at: Stamp, val source: Stamp, val value: JsonElement?, val sawRemoval: Map<String, Stamp> = emptyMap())
    private class Item {
        var alive: Cell? = null
        val removed = HashMap<String, Cell>()
        var after: Cell? = null
        val fields = HashMap<List<String>, Cell>()
    }
    private val items = HashMap<String, Item>()
    private var legacy: Op? = null
    private val seedStamp = Stamp(Long.MIN_VALUE, 0, "")
    private val knownFields = if (keyField == "file") setOf("file", "label", "loopStartMs", "loopEndMs", "speed", "pitch", "clickBpm", "beatsPerBar", "firstBeatMs", "volume", "turnsMs", "partTurns")
        else setOf("id", "songId", "note", "tempo", "color")

    private fun records(value: JsonElement?): LinkedHashMap<String, JsonObject> {
        val result = LinkedHashMap<String, JsonObject>()
        (value as? JsonArray)?.forEach { element ->
            val record = element as? JsonObject ?: return@forEach
            val key = (record[keyField] as? JsonPrimitive)?.content ?: return@forEach
            result.putIfAbsent(key, record)
        }
        return result
    }
    private fun leaves(value: JsonObject): Map<List<String>, JsonElement> {
        val result = HashMap<List<String>, JsonElement>()
        fun walk(path: List<String>, v: JsonElement) {
            if (v is JsonObject && v.isNotEmpty()) v.forEach { (key, child) -> walk(path + key, child) }
            else result[path] = v
        }
        value.forEach { (key, v) -> walk(listOf(key), v) }
        return result
    }
    private fun winner(a: Cell?, b: Cell?): Cell? = when {
        a == null -> b
        b == null -> a
        a.at != b.at -> if (a.at > b.at) a else b
        a.source != b.source -> if (a.source > b.source) a else b
        else -> if (a.value.toString() >= b.value.toString()) a else b
    }
    private fun Item.field(path: List<String>, cell: Cell) { fields[path] = addWinner(fields[path], cell)!! }
    private fun covers(a: Map<String, Stamp>, b: Map<String, Stamp>) = b.all { (device, at) -> a[device]?.let { it >= at } == true }
    private fun addWinner(a: Cell?, b: Cell?): Cell? {
        if (a == null || b == null) return a ?: b
        // A total vector order extends causal dominance. Using dominance alone followed by
        // timestamp for incomparable vectors would not be associative across delivery orders.
        for (device in (a.sawRemoval.keys + b.sawRemoval.keys).sorted()) {
            val left = a.sawRemoval[device] ?: seedStamp
            val right = b.sawRemoval[device] ?: seedStamp
            if (left != right) return if (left > right) a else b
        }
        return winner(a, b)
    }

    fun apply(op: Op) {
        if (op.base == null) {
            if (legacy == null || legacy!!.at < op.at) legacy = op
            return
        }
        val before = records(op.base)
        val after = records(op.value)
        var previous: String? = null
        for ((id, record) in before) {
            val item = items.getOrPut(id) { Item() }
            item.alive = addWinner(item.alive, Cell(seedStamp, op.at, JsonPrimitive(true), op.removals[id].orEmpty()))
            item.after = addWinner(item.after, Cell(seedStamp, op.at, previous?.let(::JsonPrimitive), op.removals[id].orEmpty()))
            leaves(record).forEach { (path, value) -> item.field(path, Cell(seedStamp, op.at, value, op.removals[id].orEmpty())) }
            previous = id
        }
        for (id in before.keys - after.keys) {
            val item = items.getOrPut(id) { Item() }
            item.removed[op.at.device] = winner(item.removed[op.at.device], Cell(op.at, op.at, JsonPrimitive(false)))!!
        }
        val reordered = before.keys.filter { it in after } != after.keys.filter { it in before } ||
            after.keys.any { it !in before && it in op.removals }
        previous = null
        for ((id, record) in after) {
            val item = items.getOrPut(id) { Item() }
            val old = before[id]
            if (old == null) item.alive = addWinner(item.alive, Cell(op.at, op.at, JsonPrimitive(true), op.removals[id].orEmpty()))
            if (old == null || reordered) item.after = addWinner(item.after, Cell(op.at, op.at, previous?.let(::JsonPrimitive), op.removals[id].orEmpty()))
            val oldLeaves = old?.let(::leaves).orEmpty()
            val newLeaves = leaves(record)
            for (path in oldLeaves.keys + newLeaves.keys) if (oldLeaves[path] != newLeaves[path] &&
                (path in newLeaves || path.first() in knownFields)) {
                item.field(path, Cell(op.at, op.at, newLeaves[path], op.removals[id].orEmpty()))
            }
            previous = id
        }
    }

    fun value(): JsonArray {
        val old = records(legacy?.value)
        val oldStamp = legacy?.at
        val ids = (items.keys + old.keys).toSet()
        val previous = old.keys.zipWithNext().associate { it.second to it.first }
        fun fallback(id: String, value: JsonElement?) = oldStamp?.let { at ->
            Cell(at, at, value, items[id]?.removed.orEmpty().filterValues { it.at <= at }.mapValues { it.value.at })
        }
        fun alive(id: String): Boolean {
            val item = items[id]
            // Legacy authors did not send a causal removal context. Their latest full snapshot
            // retains its old LWW meaning; new authors only restore removals they actually saw.
            val oldCell = oldStamp?.let { at -> Cell(at, at, JsonPrimitive(id in old), item?.removed.orEmpty().filterValues { it.at <= at }.mapValues { it.value.at }) }
            val add = addWinner(item?.alive, oldCell) ?: return false
            val removed = item?.removed.orEmpty().mapValues { it.value.at }
            return (add.value as? JsonPrimitive)?.content == "true" &&
                covers(add.sawRemoval, removed)
        }
        fun anchor(id: String) = addWinner(items[id]?.after, fallback(id, previous[id]?.let(::JsonPrimitive)))
        val children = ids.groupBy { id ->
            (anchor(id)?.value as? JsonPrimitive)?.content?.takeIf { it in ids && it != id }
        }
        val visited = HashSet<String>()
        val ordered = ArrayList<String>()
        val compare = compareByDescending<String> { anchor(it)?.at ?: seedStamp }.thenBy { it }
        fun visit(id: String) {
            if (!visited.add(id)) return
            if (alive(id)) ordered += id
            children[id].orEmpty().sortedWith(compare).forEach(::visit)
        }
        children[null].orEmpty().sortedWith(compare).forEach(::visit)
        // A concurrent reorder can create a predecessor cycle. Break it deterministically.
        ids.sorted().forEach(::visit)
        return JsonArray(ordered.map { id ->
            val item = items[id]
            val baseline = old[id]?.let(::leaves).orEmpty()
            val flat = (item?.fields.orEmpty().keys + baseline.keys).associateWith { path ->
                addWinner(item?.fields?.get(path), fallback(id, baseline[path]))?.takeIf { cell ->
                    val removal = item?.removed.orEmpty().mapValues { it.value.at }
                    covers(cell.sawRemoval, removal)
                }?.value
            }.filterValues { it != null }
            val root = LinkedHashMap<String, Any>()
            flat.entries.sortedWith(compareBy<Map.Entry<List<String>, JsonElement?>> { it.key.size }.thenBy { it.key.joinToString("\u0000") }).forEach { (path, value) ->
                var into = root
                for (key in path.dropLast(1)) {
                    @Suppress("UNCHECKED_CAST")
                    val next = into[key] as? LinkedHashMap<String, Any> ?: LinkedHashMap<String, Any>().also { into[key] = it }
                    into = next
                }
                into[path.last()] = value!!
            }
            fun json(map: Map<String, Any>): JsonObject = JsonObject(map.mapValues { (_, v) ->
                @Suppress("UNCHECKED_CAST")
                if (v is Map<*, *>) json(v as Map<String, Any>) else v as JsonElement
            })
            json(root)
        })
    }

    fun keeps(at: Stamp): Boolean = legacy?.at == at || items.values.any { item ->
        item.alive?.source == at || item.removed.values.any { it.source == at } || item.after?.source == at || item.fields.values.any { it.source == at }
    }

    fun removalContext(): Map<String, Map<String, Stamp>> = items.filterValues { it.removed.isNotEmpty() }.mapValues { (_, item) -> item.removed.mapValues { it.value.at } }
}

/**
 * The library's edits, on disk, in the synced folder.
 *
 * Every device appends to a log file of its own - `.inksheets/log/<device>.jsonl` - and reads
 * everybody's. No file is ever written by two devices, so Syncthing (or anything else that copies
 * whole files) never sees two versions of one file and never makes a conflict copy: that is the
 * whole reason for the shape. What a device knows is the sum of the logs it has received, and
 * because [LibraryState] does not care about order, a log that arrives late changes nothing but
 * the edits in it.
 *
 * Reading is incremental: each log is followed from where it was last read, so noticing another
 * device's edit costs the size of the edit, not the size of the library.
 */
class LibraryLog(val root: File, val device: String) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    val dir: File = File(root, ".inksheets/log")
    private val own: File = File(dir, "$device.jsonl")

    /** How far into each log has been read, and the size it had, to notice one being replaced. */
    private val readTo = HashMap<String, Long>()

    init {
        dir.mkdirs()
    }

    /** Write [ops] to this device's log. */
    @Synchronized
    fun append(ops: List<Op>) {
        if (ops.isEmpty()) return
        val text = buildString {
            ops.forEach { append(json.encodeToString(Op.serializer(), it)).append('\n') }
        }
        own.appendText(text)
    }

    /**
     * Everything written since the last call, from every device's log, including this one's.
     *
     * A log that has shrunk has been compacted by its owner and is read again from the start,
     * which is safe because applying an edit twice changes nothing.
     */
    @Synchronized
    fun readNew(): List<Op> {
        val found = ArrayList<Op>()
        val logs = dir.listFiles { f -> f.isFile && isLog(f.name) }.orEmpty()
        for (log in logs.sortedBy { it.name }) {
            val size = log.length()
            var from = readTo[log.name] ?: 0L
            if (size < from) from = 0L
            if (size == from) continue
            val bytes = log.inputStream().use { input ->
                input.skip(from)
                input.readBytes()
            }
            // Only whole lines: a log caught mid-write is read up to its last complete line, and
            // the rest on the next look.
            val lastNewline = bytes.lastIndexOf('\n'.code.toByte())
            if (lastNewline < 0) continue
            String(bytes, 0, lastNewline + 1, Charsets.UTF_8).lineSequence().forEach { line ->
                if (line.isNotBlank()) {
                    runCatching { json.decodeFromString(Op.serializer(), line) }
                        .onSuccess { found += it }
                }
            }
            readTo[log.name] = from + lastNewline + 1
        }
        return found
    }

    /**
     * Rewrite this device's own log keeping edits still supplying current scalar or collection
     * state, plus uninterpreted data. The file is replaced in one rename, so a device reading it
     * sees the old or the new, never half of each.
     */
    @Synchronized
    fun compact(state: LibraryState) {
        if (!own.isFile) return
        // Keep the original JSON for retained operations: a newer client may have added
        // operation-level fields this version does not understand. Unreadable complete lines
        // also remain recoverable rather than being silently discarded by maintenance.
        val kept = own.readLines().filter { line ->
            val op = runCatching { json.decodeFromString(Op.serializer(), line) }.getOrNull()
            op == null || state.keeps(op)
        }
        val temp = File(dir, ".$device.jsonl.tmp")
        temp.writeText(kept.joinToString("") { it + "\n" })
        if (!temp.renameTo(own)) {
            own.delete()
            temp.renameTo(own)
        }
        readTo.remove(own.name)
    }

    /** Size of this device's own log, to decide when it is worth compacting. */
    fun ownSize(): Long = own.length()

    private fun isLog(name: String): Boolean =
        name.endsWith(".jsonl") && !name.startsWith(".") && "sync-conflict" !in name
}
