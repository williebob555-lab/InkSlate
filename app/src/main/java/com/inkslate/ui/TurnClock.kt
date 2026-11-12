package com.inkslate.ui

import com.inkslate.data.EventLog

/**
 * How long a turn to another song takes to show its page, said once per turn in the log - the
 * number to look at when a turn feels slow, rather than a guess at which part of it is.
 */
object TurnClock {
    @Volatile private var startedAt = 0L
    @Volatile private var to: String? = null

    /** A turn to [title] has begun. */
    fun start(title: String?) {
        ensureStallSampler()
        startedAt = android.os.SystemClock.uptimeMillis()
        watchUntil = startedAt + WATCH_MS
        to = title
        stalls = 0
        watchFrames()
    }

    /** Start timing an in-document page turn unless a song/instrument switch is already timed. */
    fun startPage(title: String, page: Int): Boolean {
        if (startedAt != 0L) return false
        start("$title page ${page + 1}")
        return true
    }

    @Volatile private var watchUntil = 0L
    @Volatile private var stalls = 0
    private var lastFrameNs = 0L
    private var watching = false

    /**
     * Choreographer tells us that a frame was late only after the main thread is moving again.
     * This sampler catches its stack while it is actually stuck, so the next field report names
     * the blocking code instead of leaving another timing guess. One ping may be outstanding;
     * it cannot build a queue behind a blocked main thread.
     */
    private val pingPending = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var mainBeat = 0L
    @Volatile private var samplerStarted = false
    @Volatile private var lastStackReport = 0L
    @Volatile private var diagnosticCapture = false

    fun setDiagnosticCapture(active: Boolean) {
        ensureStallSampler()
        diagnosticCapture = active
        if (active) {
            mainBeat = android.os.SystemClock.uptimeMillis()
            stalls = 0
            watchFrames()
        }
    }

    @Synchronized
    private fun ensureStallSampler() {
        if (samplerStarted) return
        samplerStarted = true
        val main = android.os.Looper.getMainLooper()
        val handler = android.os.Handler(main)
        mainBeat = android.os.SystemClock.uptimeMillis()
        Thread({
            while (true) {
                if ((diagnosticCapture || android.os.SystemClock.uptimeMillis() < watchUntil) &&
                    pingPending.compareAndSet(false, true)
                ) {
                    handler.post {
                        mainBeat = android.os.SystemClock.uptimeMillis()
                        pingPending.set(false)
                    }
                }
                try { Thread.sleep(50) } catch (_: InterruptedException) { return@Thread }
                val now = android.os.SystemClock.uptimeMillis()
                val lag = now - mainBeat
                if ((diagnosticCapture || now < watchUntil) && pingPending.get() && lag >= 150 &&
                    now - lastStackReport >= 1_000
                ) {
                    lastStackReport = now
                    val useful = main.thread.stackTrace
                        .filterNot { it.className.startsWith("java.lang.Thread") }
                        .take(10)
                        .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    EventLog.warn("stall", "Main thread blocked at least ${lag}ms: $useful")
                }
            }
        }, "main-stall-sampler").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /**
     * For a few seconds after a turn, every frame that took far longer than it should is said in
     * the log with how long it took - a freeze named by number, when it happens, not guessed at.
     */
    private fun watchFrames() {
        if (watching) return
        watching = true
        lastFrameNs = 0L
        val choreographer = android.view.Choreographer.getInstance()
        choreographer.postFrameCallback(object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameNs: Long) {
                if (lastFrameNs != 0L) {
                    val gap = (frameNs - lastFrameNs) / 1_000_000
                    if (gap >= STALL_MS && stalls < 5) {
                        stalls++
                        val since = android.os.SystemClock.uptimeMillis() - (watchUntil - WATCH_MS)
                        val rt = Runtime.getRuntime()
                        val heap = (rt.totalMemory() - rt.freeMemory()) / 1048576
                        val marks = com.inkslate.core.PageMarkStats
                        val draw = com.inkslate.ink.RenderStats
                        EventLog.warn(
                            "turn",
                            "Screen froze ${gap}ms, ${since}ms after turning to ${to ?: "a song"}; " +
                                "heap ${heap}/${rt.maxMemory() / 1048576}MB, pages ${draw.pagesResident}, " +
                                "overlay ${"%.1f".format(draw.lastMusicMarksMs)}ms, " +
                                "overlay build ${"%.1f".format(marks.lastBuildMs)}ms/${marks.lastMarkCount} marks, " +
                                "open ${draw.lastDocumentOpenMs}ms, render ${draw.lastPageRenderMs}ms"
                        )
                    }
                }
                lastFrameNs = frameNs
                if (diagnosticCapture || android.os.SystemClock.uptimeMillis() < watchUntil) {
                    choreographer.postFrameCallback(this)
                }
                else watching = false
            }
        })
    }

    private const val WATCH_MS = 3000L
    private const val STALL_MS = 100L

    /** Its page is on screen: [how] it got there - drawn ahead, or drawn on arrival. */
    fun shown(how: String) {
        val at = startedAt
        if (at == 0L) return
        startedAt = 0L
        EventLog.info("turn", "${to ?: "Song"} shown ${android.os.SystemClock.uptimeMillis() - at}ms after the turn ($how)")
    }
}
