package com.inksheets.core.omr

/** Musical state at a source sample boundary, before output buffering. */
data class PlaybackCursor(val bar: Int,val progress: Float,val tempo: Double,val round: Int=0,val finished: Boolean=false)

/** A bounded producer-written timeline. UI readers follow consumed audio, never future synthesis. */
class PlaybackHistory(private val initial: PlaybackCursor,private val capacity: Int=256) {
    private data class Block(val start: Long,val end: Long,val before: PlaybackCursor,val after: PlaybackCursor)
    private val blocks=java.util.ArrayDeque<Block>()
    val blockCount: Int get()=synchronized(this) { blocks.size }

    @Synchronized
    fun record(start: Long,end: Long,before: PlaybackCursor,after: PlaybackCursor) {
        // A finished synthesizer may be asked for trailing silence; it has no new musical state.
        if(end<=start) return
        blocks.addLast(Block(start,end,before,after))
        while(blocks.size>capacity.coerceAtLeast(1)) blocks.removeFirst()
    }

    @Synchronized
    fun at(consumed: Long): PlaybackCursor {
        val block=blocks.lastOrNull { it.start<=consumed } ?: return initial
        if(consumed>=block.end) return block.after
        val before=block.before;val after=block.after
        // At a bar/repeat boundary, retain the preceding block's state until its end is consumed.
        // This has at most one render block of error and cannot turn a page prematurely.
        if(before.bar!=after.bar || before.round!=after.round) return before
        val t=((consumed-block.start).toDouble()/(block.end-block.start)).coerceIn(0.0,1.0)
        return before.copy(progress=(before.progress+(after.progress-before.progress)*t).toFloat().coerceIn(0f,1f),finished=false)
    }
}
