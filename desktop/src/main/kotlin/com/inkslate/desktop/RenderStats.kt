package com.inkslate.desktop

/**
 * Lightweight instrumentation for the drawing surface.
 *
 * Performance problems here are felt as "it stutters sometimes", which is almost impossible to act
 * on second-hand. Numbers on screen turn that into a specific claim: how many marks exist, how many
 * are actually being drawn, and how long a frame takes.
 *
 * The Android build carries the same object, so a report from either machine reads the same way.
 * The whole surface is still repainted every frame, but the shape of each mark is kept between
 * frames now - see InkGeometry - so the cache figures below are real on both.
 *
 * Everything is plain fields written on the UI thread; there is no synchronisation and no
 * allocation on the drawing path, because instrumentation that costs measurable time makes the
 * thing it measures worse.
 */
object RenderStats {

    /** Total objects in the open document. */
    @Volatile var totalStrokes: Int = 0

    /** Objects that survived viewport culling on the last frame. */
    @Volatile var drawnStrokes: Int = 0

    /** Pages holding a rendered raster right now. */
    @Volatile var pagesResident: Int = 0

    @Volatile var pageCount: Int = 0

    /** Pages intersecting the viewport on the last frame. */
    @Volatile var pagesVisible: Int = 0

    /** Duration of the last frame, in milliseconds. */
    @Volatile var lastFrameMs: Float = 0f

    /** Rolling mean over the recent frames, which is what a stutter actually shows up in. */
    @Volatile var averageFrameMs: Float = 0f

    /** Worst frame seen since the last reset. */
    @Volatile var worstFrameMs: Float = 0f

    /** Points in the mark currently being drawn. */
    @Volatile var livePoints: Int = 0

    /** Marks whose shape was reused rather than rebuilt, and marks whose shape had to be built. */
    @Volatile var geometryHits: Long = 0L
    @Volatile var geometryMisses: Long = 0L

    private var frames = 0L

    fun recordFrame(ms: Float, drawn: Int) {
        lastFrameMs = ms
        drawnStrokes = drawn
        if (ms > worstFrameMs) worstFrameMs = ms
        frames++
        // exponential mean: no history buffer, and recent frames dominate
        averageFrameMs = if (frames <= 1) ms else averageFrameMs * 0.92f + ms * 0.08f
    }

    fun reset() {
        worstFrameMs = 0f
        averageFrameMs = 0f
        frames = 0
    }

    /** One-line summary for the settings screen. */
    fun summary(): String = buildString {
        append("frame ").append(String.format("%.1f", lastFrameMs)).append("ms")
        append("  avg ").append(String.format("%.1f", averageFrameMs))
        append("  max ").append(String.format("%.1f", worstFrameMs))
        append('\n')
        append("marks ").append(drawnStrokes).append('/').append(totalStrokes)
        append("  live ").append(livePoints)
        append('\n')
        append("pages ").append(pagesResident).append('/').append(pageCount)
        append("  visible ").append(pagesVisible)
        append('\n')
        val looked = geometryHits + geometryMisses
        append("shapes reused ")
        append(if (looked == 0L) 0 else (geometryHits * 100 / looked))
        append("%  held ").append(InkGeometry.held())
        append('\n')
        val rt = Runtime.getRuntime()
        append("heap ").append((rt.totalMemory() - rt.freeMemory()) / 1048576).append("MB / ")
        append(rt.maxMemory() / 1048576).append("MB")
    }
}
