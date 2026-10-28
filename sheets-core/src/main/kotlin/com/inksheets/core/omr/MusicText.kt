package com.inksheets.core.omr

import kotlin.math.abs

/** PDF text extraction can split "senza sord." into separate words. Keep a cancelling
 * instruction together, so its trailing "mute" cannot immediately turn the mute back on. */
internal fun musicTextDirections(words: List<Printed.Symbol>, space: Float, staffTop: Float): List<Direction> {
    val sorted = words.sortedBy { it.x }
    val consumed = HashSet<Printed.Symbol>()
    val out = ArrayList<Direction>()
    for (first in sorted) {
        if (first in consumed) continue
        val line = sorted.filter { it !in consumed && it.x >= first.x && abs(it.y-first.y) < space*.6f }
        var last = first
        var text = first.name
        consumed += first
        if (Interpretation.muteInstruction(text) == null) {
            var candidate = text
            var previous = first
            val group = arrayListOf(first)
            for (next in line.drop(1).take(3)) {
                if (next.x-(previous.x+previous.width) > space*2) break
                group += next
                candidate += " " + next.name
                previous = next
                if (Interpretation.muteInstruction(candidate) != null) {
                    text = candidate;last = next;consumed += group
                    break
                }
            }
        }
        out += Direction("text",first.x,last.x+last.width,text,above=first.y<staffTop)
    }
    return out
}
