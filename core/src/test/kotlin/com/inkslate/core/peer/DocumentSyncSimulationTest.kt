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
 *  2. **Nothing drawn is lost** unless it was erased, and **nothing erased comes back** unless the
 *     erase was undone.
 *  3. **Both files on disk end up identical**, and hold everything on screen.
 *  4. **While the devices stay linked, file sync never makes a conflict copy.** With the link
 *     dropping and returning, a conflict copy can be made - two devices writing while they cannot
 *     speak is exactly what file sync is for - and the first three promises still hold.
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
        for (seed in seeds) {
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

        val present = a.pages.values.flatten().map { it.id }.toSet()
        val lost = sim.created - sim.erased - present
        if (lost.isNotEmpty()) return "marks lost: $lost"
        val back = sim.erased intersect present
        if (back.isNotEmpty()) return "erased marks came back: $back"

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
        return "marks[$marks] bookmarks$bookmarks canvas[$canvas]"
    }
}
