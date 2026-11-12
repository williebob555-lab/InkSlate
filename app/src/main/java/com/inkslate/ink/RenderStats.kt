package com.inkslate.ink

import com.inkslate.data.EventLog
import com.inkslate.data.PerformanceDiagnostics

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
    @Volatile var lastFrameCpuMs: Float = 0f
    @Volatile var worstFrameCpuMs: Float = 0f
    @Volatile var lastIndexUiMs: Float = 0f
    @Volatile var worstIndexUiMs: Float = 0f
    @Volatile var lastQueryMs: Float = 0f
    @Volatile var worstQueryMs: Float = 0f
    @Volatile var lastTileMs: Float = 0f
    @Volatile var worstTileMs: Float = 0f
    @Volatile var lastVectorMs: Float = 0f
    @Volatile var worstVectorMs: Float = 0f
    @Volatile var framesOver32Ms: Long = 0
    @Volatile var framesOver100Ms: Long = 0
    @Volatile var maxInputDelayMs: Long = 0
    @Volatile var inputEventsOver32Ms: Long = 0

    /** Points in the stroke currently being drawn. */
    @Volatile var livePoints: Int = 0

    /** Geometry cache outcomes since launch. */
    @Volatile var cacheHits: Long = 0
    @Volatile var cacheMisses: Long = 0

    /** Display-tile outcomes. Kept separate from the per-stroke geometry cache above. */
    @Volatile var tileExactHits: Long = 0
    @Volatile var tileRetainedHits: Long = 0
    @Volatile var tileColdMisses: Long = 0
    @Volatile var tileVectorFallbacks: Long = 0
    @Volatile var tileBuilds: Long = 0
    @Volatile var tileRejects: Long = 0
    @Volatile var lastIndexRefreshMs: Long = 0
    @Volatile var worstIndexRefreshMs: Long = 0
    @Volatile var lastIndexBuildMs: Long = 0
    @Volatile var worstIndexBuildMs: Long = 0

    /** Whether the last frame repainted the whole view or just a damaged region. */
    @Volatile var lastFrameFullRedraw: Boolean = true

    /** Time spent obtaining and drawing the music overlay in the last frame. */
    @Volatile var lastMusicMarksMs: Float = 0f
    @Volatile var worstMusicMarksMs: Float = 0f
    @Volatile var lastPageRenderMs: Long = 0
    @Volatile var worstPageRenderMs: Long = 0
    @Volatile var lastDocumentOpenMs: Long = 0
    @Volatile var worstDocumentOpenMs: Long = 0

    fun recordMusicMarks(nanos: Long) {
        val ms = nanos / 1_000_000f
        lastMusicMarksMs = ms
        if (ms > worstMusicMarksMs) worstMusicMarksMs = ms
    }

    fun recordPageRender(ms: Long) {
        lastPageRenderMs = ms
        if (ms > worstPageRenderMs) worstPageRenderMs = ms
    }

    fun recordDocumentOpen(ms: Long) {
        lastDocumentOpenMs = ms
        if (ms > worstDocumentOpenMs) worstDocumentOpenMs = ms
    }

    fun recordIndexRefresh(ms: Long) {
        lastIndexRefreshMs = ms
        if (ms > worstIndexRefreshMs) worstIndexRefreshMs = ms
    }

    fun recordIndexBuild(ms: Long) {
        lastIndexBuildMs = ms
        if (ms > worstIndexBuildMs) worstIndexBuildMs = ms
    }

    /** Frames where the viewport looked wrong and culling was skipped. Should stay at 0. */
    @Volatile var cullingFallbacks: Long = 0

    @Volatile var enabled: Boolean = false

    private var frames = 0L
    private var longFrameLogs = 0
    private var inputDelayLogs = 0

    fun recordFrame(
        ms: Float,
        drawn: Int,
        fullRedraw: Boolean,
        cpuMs: Float = 0f,
        indexMs: Float = 0f,
        queryMs: Float = 0f,
        tileMs: Float = 0f,
        vectorMs: Float = 0f
    ) {
        lastFrameMs = ms
        drawnStrokes = drawn
        lastFrameFullRedraw = fullRedraw
        if (ms > worstFrameMs) worstFrameMs = ms
        lastFrameCpuMs = cpuMs; if (cpuMs > worstFrameCpuMs) worstFrameCpuMs = cpuMs
        lastIndexUiMs = indexMs; if (indexMs > worstIndexUiMs) worstIndexUiMs = indexMs
        lastQueryMs = queryMs; if (queryMs > worstQueryMs) worstQueryMs = queryMs
        lastTileMs = tileMs; if (tileMs > worstTileMs) worstTileMs = tileMs
        lastVectorMs = vectorMs; if (vectorMs > worstVectorMs) worstVectorMs = vectorMs
        if (ms >= 32f) framesOver32Ms++
        if (ms >= 100f) framesOver100Ms++
        frames++
        // exponential mean: no history buffer, and recent frames dominate
        averageFrameMs = if (frames <= 1) ms else averageFrameMs * 0.92f + ms * 0.08f
        if (PerformanceDiagnostics.active && ms >= 50f && longFrameLogs < 40) {
            longFrameLogs++
            EventLog.warn(
                "frame",
                "${"%.1f".format(ms)}ms cpu=${"%.1f".format(cpuMs)} " +
                    "index=${"%.1f".format(indexMs)} query=${"%.1f".format(queryMs)} " +
                    "tiles=${"%.1f".format(tileMs)} vectors=${"%.1f".format(vectorMs)} " +
                    "strokes=$drawn/$totalStrokes full=$fullRedraw"
            )
        }
    }

    fun recordInputDelay(ms: Long) {
        if (ms > maxInputDelayMs) maxInputDelayMs = ms
        if (ms >= 32L) inputEventsOver32Ms++
        if (PerformanceDiagnostics.active && ms >= 100L && inputDelayLogs < 20) {
            inputDelayLogs++
            EventLog.warn("input", "Touch event waited ${ms}ms before the interface handled it")
        }
    }

    fun reset() {
        worstFrameMs = 0f
        lastFrameCpuMs = 0f; worstFrameCpuMs = 0f
        lastIndexUiMs = 0f; worstIndexUiMs = 0f
        lastQueryMs = 0f; worstQueryMs = 0f
        lastTileMs = 0f; worstTileMs = 0f
        lastVectorMs = 0f; worstVectorMs = 0f
        framesOver32Ms = 0; framesOver100Ms = 0
        maxInputDelayMs = 0; inputEventsOver32Ms = 0
        averageFrameMs = 0f
        cacheHits = 0
        cacheMisses = 0
        tileExactHits = 0
        tileRetainedHits = 0
        tileColdMisses = 0
        tileVectorFallbacks = 0
        tileBuilds = 0
        tileRejects = 0
        lastIndexRefreshMs = 0
        worstIndexRefreshMs = 0
        lastIndexBuildMs = 0
        worstIndexBuildMs = 0
        cullingFallbacks = 0
        worstMusicMarksMs = 0f
        lastMusicMarksMs = 0f
        lastPageRenderMs = 0
        worstPageRenderMs = 0
        lastDocumentOpenMs = 0
        worstDocumentOpenMs = 0
        frames = 0
        longFrameLogs = 0
        inputDelayLogs = 0
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
        append("  overlay ").append(String.format("%.1f", lastMusicMarksMs)).append("ms")
        append("  render ").append(lastPageRenderMs).append("ms")
        append("  open ").append(lastDocumentOpenMs).append("ms")
        append('\n')
        append("tiles hit ").append(tileExactHits).append('+').append(tileRetainedHits)
        append("  cold ").append(tileColdMisses)
        append("  vector ").append(tileVectorFallbacks)
        append("  built ").append(tileBuilds)
        if (tileRejects > 0) append("  rejected ").append(tileRejects)
        append('\n')
        append("index refresh ").append(lastIndexRefreshMs).append("ms/")
            .append(worstIndexRefreshMs).append(" max")
        append("  build ").append(lastIndexBuildMs).append("ms/")
            .append(worstIndexBuildMs).append(" max")
        append('\n')
        val rt = Runtime.getRuntime()
        append("heap ").append((rt.totalMemory() - rt.freeMemory()) / 1048576).append("MB / ")
        append(rt.maxMemory() / 1048576).append("MB")
    }

    fun diagnosticSummary(): String = buildString {
        appendLine("Rendering summary")
        appendLine(summary())
        appendLine("frames=$frames over32ms=$framesOver32Ms over100ms=$framesOver100Ms")
        appendLine(
            "worstStagesMs cpu=${"%.1f".format(worstFrameCpuMs)} " +
                "index=${"%.1f".format(worstIndexUiMs)} query=${"%.1f".format(worstQueryMs)} " +
                "tiles=${"%.1f".format(worstTileMs)} vectors=${"%.1f".format(worstVectorMs)}"
        )
        appendLine("input maxDelayMs=$maxInputDelayMs over32ms=$inputEventsOver32Ms")
    }
}
