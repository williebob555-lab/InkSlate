package com.inkslate.core.peer

import org.junit.Test

/**
 * Replays one simulation seed and writes its whole story to a file, for when a seed fails.
 *
 * Does nothing unless asked: `SIM_SEED=123 SIM_FLAKY=1 SIM_OUT=trace.txt gradlew :core:test
 * --tests *ReplaySeedTest* --rerun`.
 */
class ReplaySeedTest {
    @Test
    fun replay() {
        val seed = System.getenv("SIM_SEED")?.toLongOrNull() ?: return
        val flaky = System.getenv("SIM_FLAKY") == "1"
        val sim = SyncSimulation(seed, flaky)
        sim.play(steps = 40 + sim.rng.nextInt(120))
        val out = java.io.File(System.getenv("SIM_OUT") ?: "sim-trace.txt")
        val tablet = sim.devices.getValue("tablet").ink
        val laptop = sim.devices.getValue("laptop").ink
        out.writeText(
            sim.trace.joinToString("\n") + "\n\nerased=${sim.erased.toSortedMap()}\n" +
                "tablet layout=${tablet?.layout} laptop layout=${laptop?.layout}\n" +
                "erased and present again=${tablet?.let { t ->
                    val present = t.pages.values.flatten().map { it.id }.toSet()
                    sim.erased.mapNotNull { (id, where) ->
                        val back = sim.descendants(id, where.first, where.second, t.layout).orEmpty().filter { it.first in present }
                        if (back.isEmpty()) null else "$id (erased on page ${where.second} of ${where.first}) -> $back"
                    }
                }}\n" +
                "tablet ids=${tablet?.pages?.values?.flatten()?.map { it.id + "@" + it.updatedUtc }?.sorted()}\n" +
                "tablet tombs=${tablet?.deleted}\n" +
                "laptop ids=${laptop?.pages?.values?.flatten()?.map { it.id + "@" + it.updatedUtc }?.sorted()}\n" +
                "laptop tombs=${laptop?.deleted}\n"
        )
    }
}
