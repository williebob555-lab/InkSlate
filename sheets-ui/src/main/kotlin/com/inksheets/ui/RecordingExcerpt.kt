package com.inksheets.ui

import com.inksheets.core.AudioTrack

/** Absolute, zero-based PDF pages; learned turn indices remain compatible with whole-piece tracks. */
internal fun recordingPages(track: AudioTrack,partId: String?,pageCount: Int): IntRange? {
    if(pageCount<1) return null
    val first=track.excerptStartPage ?: return 0 until pageCount
    if(track.excerptPart!=null && track.excerptPart!=partId) return null
    if(first !in 1..pageCount) return null
    val last=(track.excerptEndPage ?: first).coerceAtMost(pageCount)
    if(last<first) return null
    return (first-1)..(last-1)
}

/** Only turns inside this recording's range must be learned, rather than every page of a methodbook. */
internal fun recordingTurns(track: AudioTrack,partId: String?,pageCount: Int,pages: IntRange): List<Long>? {
    if(track.excerptStartPage==null) return track.turnsFor(partId,pageCount)
    if(pages.first==pages.last) return emptyList()
    val all=partId?.let { track.partTurns[it] } ?: track.turnsMs.takeIf { track.partTurns.isEmpty() }
    return all?.let { turns ->
        (pages.first until pages.last).map { turns.getOrNull(it) ?: -1L }.takeIf { own ->
            own.all { it>=0 } && own.zipWithNext().all { (a,b) -> b>a }
        }
    }
}
