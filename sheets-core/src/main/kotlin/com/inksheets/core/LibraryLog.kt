package com.inksheets.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
    val value: JsonElement = JsonNull
) {
    companion object {
        const val DELETED = "_deleted"
    }
}

/** The latest value of every field of every record: what the logs add up to. */
class LibraryState {

    data class Key(val kind: String, val id: String)

    class Value(val at: Stamp, val value: JsonElement)

    private val records = LinkedHashMap<Key, MutableMap<String, Value>>()

    /**
     * Fold one edit in. Returns true when it changed anything.
     *
     * The latest stamp wins field by field, whatever order edits arrive in and however often the
     * same one arrives - so reading the logs in any order, or reading one twice, gives the same
     * library.
     */
    fun apply(op: Op): Boolean {
        val fields = records.getOrPut(Key(op.kind, op.id)) { HashMap() }
        val current = fields[op.field]
        if (current != null && current.at >= op.at) return false
        fields[op.field] = Value(op.at, op.value)
        return true
    }

    fun fields(kind: String, id: String): Map<String, Value>? = records[Key(kind, id)]

    /** Every live (not deleted) record of [kind], with its fields. */
    fun live(kind: String): Map<String, Map<String, JsonElement>> =
        records.asSequence()
            .filter { (key, fields) -> key.kind == kind && !isDeleted(fields) }
            .associate { (key, fields) -> key.id to fields.mapValues { it.value.value } }

    fun latestOf(kind: String, id: String, field: String): Stamp? = records[Key(kind, id)]?.get(field)?.at

    private fun isDeleted(fields: Map<String, Value>): Boolean =
        (fields[Op.DELETED]?.value as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
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
     * Rewrite this device's own log keeping only the edits that are still the latest for their
     * field. The file is replaced in one rename, so a device reading it sees the old or the new,
     * never half of each.
     */
    @Synchronized
    fun compact(state: LibraryState) {
        if (!own.isFile) return
        val kept = own.readLines().mapNotNull { line ->
            runCatching { json.decodeFromString(Op.serializer(), line) }.getOrNull()
        }.filter { op -> state.latestOf(op.kind, op.id, op.field) == op.at }
        val temp = File(dir, ".$device.jsonl.tmp")
        temp.writeText(kept.joinToString("") { json.encodeToString(Op.serializer(), it) + "\n" })
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
