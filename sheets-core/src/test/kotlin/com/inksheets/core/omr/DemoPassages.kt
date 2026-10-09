package com.inksheets.core.omr

/** Passages for listening to (see PlaybackWavDemo): a euphonium-ish legato line and a Copprasch-style articulation exercise. */
object DemoPassages {
    private fun pitchOf(midi: Int): Pitch {
        val names = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
        val alters = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
        return Pitch(names[midi % 12], midi / 12 - 1, alters[midi % 12])
    }

    private fun bar(n: Int, ms: List<Int>, base: Int, arts: Map<Int, List<String>>, dirs: List<Direction>, ties: Set<Int> = emptySet()): Measure {
        val gap = if (base == 8) 40f else 90f
        val left = (n - 1) * 400
        return Measure(n, 0, 0, Box(left, 0, left + 400, 40), 10f, Clef.TREBLE, Key(-2), TimeSig(4, 4),
            ms.mapIndexed { i, m -> Note(listOf(0), listOf(pitchOf(m)), Duration(base), left + 20f + i * gap, articulations = arts[i].orEmpty(), tie = i in ties) }, directions = dirs)
    }

    /** Two bars under one long slur (mp), a tie over a barline, slurred pairs with staccato and an accent, then p and f a bar apart. 96 bpm. */
    fun legato(): List<Measure> {
        val slur12 = Direction("slur", 20f, 400f + 20f + 3 * 90f + 5f)
        return listOf(
            bar(1, listOf(46, 49, 53, 51), 4, emptyMap(), listOf(slur12, Direction("dynamic", 5f, text = "mp"))),
            bar(2, listOf(53, 51, 49, 46), 4, emptyMap(), listOf(slur12)),
            bar(3, listOf(48, 50, 53, 55), 4, emptyMap(), emptyList(), ties = setOf(3)),
            bar(4, listOf(55, 53, 51, 50), 4, emptyMap(), emptyList()),
            bar(5, listOf(46, 48, 50, 51, 53, 51, 50, 53), 8, mapOf(3 to listOf("staccato"), 4 to listOf("staccato"), 7 to listOf("accent")),
                listOf(Direction("slur", 4 * 400f + 20f, 4 * 400f + 65f), Direction("slur", 4 * 400f + 20f + 5 * 40f, 4 * 400f + 20f + 6 * 40f + 5f))),
            bar(6, listOf(46, 46, 49, 46), 4, emptyMap(), listOf(Direction("dynamic", 2005f, text = "p"))),
            bar(7, listOf(46, 46, 49, 46), 4, emptyMap(), listOf(Direction("dynamic", 2405f, text = "f")))
        )
    }

    /** "Slur two, tongue two, staccato two, tongue two" in eighths, p, then f a bar later, f, then p. 108 bpm. */
    fun copprasch(): List<Measure> {
        fun b(n: Int, shift: Int, dyn: String?): Measure {
            val left = (n - 1) * 400f
            val dirs = ArrayList<Direction>()
            dirs += Direction("slur", left + 20f, left + 65f)
            if (dyn != null) dirs += Direction("dynamic", left + 5f, text = dyn)
            return bar(n, listOf(46, 48, 50, 48, 46, 48, 50, 53).map { it + shift }, 8, mapOf(4 to listOf("staccato"), 5 to listOf("staccato")), dirs)
        }
        return listOf(b(1, 0, "p"), b(2, 2, "f"), b(3, 0, null), b(4, 2, "p"))
    }
}
