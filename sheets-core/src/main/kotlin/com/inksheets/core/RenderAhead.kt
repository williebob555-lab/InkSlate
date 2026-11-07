package com.inksheets.core

/**
 * Sound made ahead of the speaker. The synthesiser is rendered on a thread of its own, at high
 * priority, into a ring [aheadMs] long; the sound card's thread only copies out of the ring, which
 * costs nothing and never waits. So a busy device (a page being drawn, the garbage collector, other
 * apps) delays the rendering by a few milliseconds that the ring has in hand, instead of starving
 * the output - which is what breaks sound up into pops and xruns.
 *
 * Should the ring run dry anyway, [read] fades the sound out over a few milliseconds and fades it in
 * again when the rendering catches up - an audible dip, never a click.
 *
 * [fill] writes the next samples of the sound into the block it is given (zeroed first); it is only
 * ever called from the rendering thread.
 */
class RenderAhead(
    val sampleRate: Int,
    private val aheadMs: Int = 200,
    private val block: Int = 480,
    private val producerPriority: Int = Thread.NORM_PRIORITY,
    private val fill: (FloatArray) -> Unit
) {
    private val capacity = (((sampleRate.toLong() * aheadMs / 1000).toInt() + block - 1) / block).coerceAtLeast(2) * block
    private val ring = FloatArray(capacity)
    /** Samples written / read since the start (single writer, single reader). */
    @Volatile private var written = 0L
    @Volatile private var consumed = 0L
    /** Actual source frames handed to the output; underrun silence does not move this cursor. */
    val consumedFrames: Long get()=consumed
    /** Source audio currently ready for the device callback. */
    val bufferedFrames: Long get()=(written-consumed).coerceAtLeast(0L)
    val bufferedMs: Int get()=(bufferedFrames*1000/sampleRate.coerceAtLeast(1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    @Volatile private var running = false
    @Volatile private var primed = true
    @Volatile private var primeFrames = 0L
    private var thread: Thread? = null

    /** How many times the ring ran dry. */
    @Volatile var underruns = 0
        private set

    /** Producer timing for field diagnostics; no history buffer or hot-path allocation. */
    @Volatile var lastFillMs = 0f
        private set
    @Volatile var worstFillMs = 0f
        private set

    // The reader's state: how loud it is now (1 playing, 0 faded out) and the last sample played.
    private var gain = 1f
    private var lastOut = 0f
    private val fadeStep = 1f / (sampleRate * 0.006f)
    private var dry = false

    /** Start rendering; normally waits for [prefillMs], or primes silently when [waitForPrefill] is false. */
    @Synchronized
    fun start(prefillMs: Int = 120, waitForPrefill: Boolean = true) {
        if (running || thread?.isAlive==true) return
        val wanted = (sampleRate.toLong() * prefillMs / 1000).coerceAtMost(capacity.toLong())
        primeFrames = wanted
        primed = wanted == 0L
        running = true
        thread = Thread({
            val buf = FloatArray(block)
            while (running) {
                if (capacity - (written - consumed) >= block) {
                    java.util.Arrays.fill(buf, 0f)
                    val started = System.nanoTime()
                    runCatching { fill(buf) }
                    val elapsed = (System.nanoTime() - started) / 1_000_000f
                    lastFillMs = elapsed
                    if (elapsed > worstFillMs) worstFillMs = elapsed
                    val at = (written % capacity).toInt()
                    System.arraycopy(buf, 0, ring, at, block)   // capacity is a whole number of blocks
                    written += block
                    // Give the UI a scheduling point only once the speaker has a useful reserve.
                    // Yielding after every block while the queue was nearly empty made a dense
                    // band polite but inaudible: it never got a chance to catch up.
                    if (bufferedMs >= minOf(300, aheadMs / 2)) Thread.yield()
                } else {
                    try { Thread.sleep(1) } catch (_: InterruptedException) { return@Thread }
                }
            }
        }, "render-ahead").apply {
            isDaemon = true
            // The device AudioTrack thread remains urgent; this producer has 0.6-1.2 seconds in
            // hand and must not outrank the UI. MAX_PRIORITY made tempo and transport buttons
            // wait behind continuous ensemble synthesis.
            priority = producerPriority.coerceIn(Thread.MIN_PRIORITY, Thread.MAX_PRIORITY)
            start()
        }
        if (waitForPrefill) {
            val limit = System.nanoTime() + 1_000_000_000L
            while (written < wanted && System.nanoTime() < limit) Thread.sleep(1)
            if (written >= wanted) primed = true
        }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    /** Freeze synthesis while retaining every queued sample and the synth's voice state. */
    @Synchronized
    fun pause(): Boolean {
        running=false
        // Give one in-flight block a short chance to finish; never strand a UI behind a decoder.
        // Its result stays in the ring even if it finishes after this bounded wait.
        thread?.join(50)
        val stopped=thread?.isAlive!=true
        if(stopped) thread=null
        return stopped
    }

    /** Continue the retained queue; do not render or discard a replacement prefill. */
    @Synchronized
    fun resume(): Boolean {
        // An unusually slow old fill must not race a second producer or lose its queued result.
        if(!running && thread?.isAlive==true) return false
        start(prefillMs=0)
        return true
    }

    /** The next samples, [out] overwritten; never blocks. */
    fun read(out: FloatArray) {
        if (!primed) {
            if (written < primeFrames) {
                java.util.Arrays.fill(out, 0f)
                return
            }
            primed = true
        }
        val have = (written - consumed).toInt()
        var at = (consumed % capacity).toInt()
        var taken = 0
        for (i in out.indices) {
            val ok = i < have
            if (ok) {
                if (dry) { dry = false }
                gain = (gain + fadeStep).coerceAtMost(1f)
                lastOut = ring[at] * gain
                at++; if (at == capacity) at = 0
                taken++
            } else {
                if (!dry && running) { dry = true; underruns++ }
                gain = (gain - fadeStep).coerceAtLeast(0f)
                // Fading what was last heard out: the sound's last level, going smoothly to nothing.
                lastOut = if (gain > 0f) lastOut * (1f - fadeStep * 2).coerceAtLeast(0f) else 0f
            }
            out[i] = lastOut
        }
        consumed += taken
    }
}
