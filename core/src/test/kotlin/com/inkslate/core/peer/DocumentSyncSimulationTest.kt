package com.inkslate.core.peer

import com.inkslate.core.InkDocument
import org.junit.Assert.fail
import org.junit.Test

/**
 * Hundreds of random sessions between two devices, each one checked when it settles.
 *
 * What is promised, and therefore checked after every session:
 *
 *  1. **Both devices end up with the same document** - the same marks, the same erasures, the same
 *     bookmarks, the same canvas.
 *  2. **Nothing drawn is lost** unless it was erased or its page removed, and **nothing erased
 *     comes back** unless the erase was undone. Marks are followed through every rearrangement of
 *     the pages, to wherever their page went.
 *  3. **Both files on disk end up identical**, and hold everything on screen.
 *  4. **While the devices stay linked, file sync never makes a conflict copy.** With the link
 *     dropping and returning, a conflict copy can be made - two devices writing while they cannot
 *     speak is exactly what file sync is for - and the first three promises still hold.
 *  5. **While linked, no device writes an older arrangement of pages over a newer one.**
 *
 * A failure prints the seed and the tail of what happened, so it can be replayed exactly.
 */
class DocumentSyncSimulationTest {

    @Test
    fun `linked devices agree, lose nothing and make no conflict copies`() {
        run(seeds = 0L until 250L, flaky = false)
    }

    @Test
    fun `devices whose link keeps dropping still agree and lose nothing`() {
        run(seeds = 10_000L until 10_250L, flaky = true)
    }

    private fun run(seeds: LongRange, flaky: Boolean) {
        val failures = ArrayList<String>()
        // SIM_SWEEP=n runs n seeds instead, for a long look after a change to the engine.
        val sweep = System.getenv("SIM_SWEEP")?.toLongOrNull()
        val range = if (sweep != null) seeds.first until seeds.first + sweep else seeds
        for (seed in range) {
            val sim = SyncSimulation(seed, flaky)
            val problem = runCatching {
                sim.play(steps = 40 + sim.rng.nextInt(120))
                check(sim, flaky)
            }.fold({ it }, { "threw ${it::class.simpleName}: ${it.message}" })
            if (problem != null) {
                val lines = sim.trace.toList()
                // Around the first conflict when there is one - that is where it went wrong - and
                // otherwise the end, where the disagreement was found.
                val firstConflict = lines.indexOfFirst { "CONFLICT" in it }
                val shown = if (firstConflict >= 0) {
                    lines.subList(maxOf(0, firstConflict - 70), minOf(lines.size, firstConflict + 5))
                } else lines.takeLast(60)
                failures += "seed $seed: $problem\n    " + shown.joinToString("\n    ")
                if (failures.size >= 3) break
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }

    /** Null when every promise held; otherwise what broke. */
    private fun check(sim: SyncSimulation, flaky: Boolean): String? {
        val tablet = sim.devices.getValue("tablet")
        val laptop = sim.devices.getValue("laptop")
        val a = tablet.ink ?: return "the tablet did not end with the document open"
        val b = laptop.ink ?: return "the laptop did not end with the document open"

        if (describe(a) != describe(b)) {
            return "the devices disagree:\n  tablet ${describe(a)}\n  laptop ${describe(b)}"
        }

        val present = a.pages.values.flatten().associate { it.id to it.pageIndex }
        val erasedLineage = HashSet<String>()
        for ((id, where) in sim.erased) {
            erasedLineage += id
            sim.descendants(id, where.first, where.second, a.layout)?.forEach { erasedLineage += it.first }
        }
        val lost = ArrayList<String>()
        for ((id, where) in sim.created) {
            val now = sim.descendants(id, where.first, where.second, a.layout)
                ?: return "no way from ${where.first} to ${a.layout} for $id"
            if (id in erasedLineage || now.any { it.first in erasedLineage }) continue
            for ((mark, page) in now) {
                if (present[mark] != page) lost += "$id (as $mark on page $page)"
            }
        }
        if (lost.isNotEmpty()) return "marks lost: $lost"
        val back = sim.erased.entries.flatMap { (id, where) ->
            (sim.descendants(id, where.first, where.second, a.layout).orEmpty().map { it.first } + id)
                .filter { it in present }
        }
        if (back.isNotEmpty()) return "erased marks came back: $back"
        if (!flaky && sim.staleLayoutWrites > 0) {
            return "${sim.staleLayoutWrites} write(s) of an older arrangement of pages while linked"
        }

        val fileA = sim.sync.files.getValue("tablet")
        val fileB = sim.sync.files.getValue("laptop")
        if (fileA.rev != fileB.rev) {
            return "the files differ: tablet ${fileA.rev.tail}, laptop ${fileB.rev.tail}"
        }
        if (!PeerSync.holdsEverythingIn(fileA.content, a)) {
            return "the file does not hold everything: file ${describe(fileA.content)}, " +
                "screen ${describe(a)}"
        }

        if (!flaky && sim.sync.conflicts > 0) {
            return "${sim.sync.conflicts} conflict cop${if (sim.sync.conflicts == 1) "y" else "ies"} " +
                "while linked"
        }
        return null
    }

    private fun describe(doc: InkDocument): String {
        val marks = doc.pages.values.flatten().sortedBy { it.id }.joinToString(",") {
            "${it.id}@${it.pageIndex}"
        }
        val bookmarks = doc.bookmarks.map { it.page }.sorted()
        val canvas = doc.canvas?.let {
            "${it.left},${it.top},${it.right},${it.bottom} colour ${it.paperColor}"
        }
        return "layout ${doc.layout.ifEmpty { "-" }} marks[$marks] bookmarks$bookmarks canvas[$canvas]"
    }
}
