package com.inkslate.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * Drawing a page for the screen comes before reading music from it.
 *
 * A whole-book reading draws every page of the part, large (up to 5000 px wide), and works on it
 * for seconds. The platform's PDF renderer is one at a time for the whole process (Android's
 * PdfRenderer takes a single process-wide lock; every desktop render shares the cores), so a
 * page asked for by the screen used to queue behind whatever the reader happened to be drawing,
 * and behind the next reader draw after that: the page stayed white for as long as the reading
 * lasted. Now:
 *
 *  - the screen's draws announce themselves ([display], placed inside the PDF sources);
 *  - the reader's draws and heavy steps run under [reading]: at most [readerRenders] at once, each
 *    waiting first ([pause]) while a screen draw is waiting or has just finished, or the page was
 *    just turned or the file just opened ([touch]).
 *
 * A reader step already under way cannot be taken back, so the screen waits for at most one of them.
 * No wait is longer than [MAX_WAIT_MS]: a reading always carries on.
 */
object RenderGate {
    /** How long after a screen draw, page turn or file open the reader keeps out of the way. */
    const val QUIET_MS = 400L

    /** The most any one reader step waits for the screen; after that it goes ahead regardless. */
    const val MAX_WAIT_MS = 20_000L

    private val waiting = AtomicInteger()
    @Volatile private var quietUntil = 0L
    private val background = ThreadLocal.withInitial { false }
    private val turn = Object()
    private var running = 0

    /** How many reader draws or heavy steps may run at once: one on a tablet or phone, where the renderer takes one at a time anyway. */
    @Volatile var readerRenders = 1

    /** Off only to measure what the screen waited before there was a gate (the test does); on in the app. */
    @Volatile var enabled = true

    /** Whether the calling thread is doing the reader's work. */
    fun isReading(): Boolean = background.get()

    /** A page being drawn for the screen on this thread: the reader stands aside until it is done. */
    fun <T> display(f: () -> T): T {
        if (isReading()) return f()
        waiting.incrementAndGet()
        quietUntil = System.currentTimeMillis() + QUIET_MS
        try {
            return f()
        } finally {
            quietUntil = System.currentTimeMillis() + QUIET_MS
            waiting.decrementAndGet()
        }
    }

    /** The screen is about to want its pages (a page turned, a file opened): the reader waits [ms]. */
    fun touch(ms: Long = 1000L) {
        quietUntil = maxOf(quietUntil, System.currentTimeMillis() + ms)
    }

    /** Whether the screen has drawing waiting, or has had just now. */
    fun displayBusy(): Boolean = waiting.get() > 0 || System.currentTimeMillis() < quietUntil

    /** Wait (on a reader's thread) while the screen is being drawn for; never longer than [MAX_WAIT_MS]. */
    fun pause() {
        if (!enabled) return
        val end = System.currentTimeMillis() + MAX_WAIT_MS
        while (displayBusy() && System.currentTimeMillis() < end) {
            try { Thread.sleep(25) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
    }

    /** [f], a draw or heavy step of the reader's: after the screen, and only [readerRenders] at a time. */
    fun <T> reading(f: () -> T): T {
        if (isReading() || !enabled) return f()
        synchronized(turn) {
            while (running >= readerRenders) turn.wait(50)
            running++
        }
        background.set(true)
        try {
            pause()
            return f()
        } finally {
            background.set(false)
            synchronized(turn) { running--; turn.notifyAll() }
        }
    }
}
