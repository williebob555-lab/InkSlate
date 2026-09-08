package com.inkslate.ink

/**
 * Lightweight instrumentation for the drawing surface.
 *
 * Performance problems here are felt as "it stutters sometimes", which is almost impossible to
 * act on second-hand. Numbers on screen turn that into a specific claim: how many strokes exist,
 * how many are actually being drawn, and how long a frame takes.
 *
 * Everything is plain fields updated on the UI thread; there is no synchronisation and no
 * allocation on the drawing path, because instrumentation that costs measurable time makes the
 * thing it measures worse.
 */
object RenderStats {

    /** Total objects in the open document. */
    @Volatile var totalStrokes: Int = 0

    /** Objects that survived viewport culling on the last frame. */
    @Volatile var drawnStrokes: Int = 0

    /** Pages holding a rasterised bitmap right now. */
    @Volatile var pagesResident: Int = 0

    @Volatile var pageCount: Int = 0

    /** Pages intersecting the viewport on the last frame. */
    @Volatile var pagesVisible: Int = 0

    /** Duration of the last onDraw, in milliseconds. */
    @Volatile var lastFrameMs: Float = 0f

    /** Rolling mean over the recent frames, which is what a stutter actually shows up in. */
    @Volatile var averageFrameMs: Float = 0f

    /** Worst frame seen since the last reset. */
    @Volatile var worstFrameMs: Float = 0f

    /** Points in the stroke currently being drawn. */
    @Volatile var livePoints: Int = 0

    /** Geometry cache outcomes since launch. */
    @Volatile var cacheHits: Long = 0
    @Volatile var cacheMisses: Long = 0

    /** Whether the last frame repainted the whole view or just a damaged region. */
    @Volatile var lastFrameFullRedraw: Boolean = true

    /** Frames where the viewport looked wrong and culling was skipped. Should stay at 0. */
    @Volatile var cullingFallbacks: Long = 0

    @Volatile var enabled: Boolean = false

    private var frames = 0L

    fun recordFrame(ms: Float, drawn: Int, fullRedraw: Boolean) {
        lastFrameMs = ms
        drawnStrokes = drawn
        lastFrameFullRedraw = fullRedraw
        if (ms > worstFrameMs) worstFrameMs = ms
        frames++
        // exponential mean: no history buffer, and recent frames dominate
        averageFrameMs = if (frames <= 1) ms else averageFrameMs * 0.92f + ms * 0.08f
    }

    fun reset() {
        worstFrameMs = 0f
        averageFrameMs = 0f
        cacheHits = 0
        cacheMisses = 0
        cullingFallbacks = 0
        frames = 0
    }

    val cacheHitRate: Float
        get() = (cacheHits + cacheMisses).let { if (it == 0L) 0f else cacheHits.toFloat() / it }

    /** One-line summary for the on-screen overlay. */
    fun summary(): String = buildString {
        append("frame ").append(String.format("%.1f", lastFrameMs)).append("ms")
        append("  avg ").append(String.format("%.1f", averageFrameMs))
        append("  max ").append(String.format("%.1f", worstFrameMs))
        append('\n')
        append("strokes ").append(drawnStrokes).append('/').append(totalStrokes)
        append("  live ").append(livePoints)
        append('\n')
        append("pages ").append(pagesResident).append('/').append(pageCount)
        append("  visible ").append(pagesVisible)
        append("  cache ").append((cacheHitRate * 100).toInt()).append('%')
        append("  ").append(if (lastFrameFullRedraw) "full" else "partial")
        if (cullingFallbacks > 0) append("  cull-fallback ").append(cullingFallbacks)
        append('\n')
        val rt = Runtime.getRuntime()
        append("heap ").append((rt.totalMemory() - rt.freeMemory()) / 1048576).append("MB / ")
        append(rt.maxMemory() / 1048576).append("MB")
    }
}
