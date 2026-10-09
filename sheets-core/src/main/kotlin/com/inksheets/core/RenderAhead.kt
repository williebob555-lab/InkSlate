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
    private val fill: (FloatArray) -> Unit
) {
    private val capacity = (((sampleRate.toLong() * aheadMs / 1000).toInt() + block - 1) / block).coerceAtLeast(2) * block
    private val ring = FloatArray(capacity)
    /** Samples written / read since the start (single writer, single reader). */
    @Volatile private var written = 0L
    @Volatile private var consumed = 0L
    @Volatile private var running = false
    private var thread: Thread? = null

    /** How many times the ring ran dry. */
    @Volatile var underruns = 0
        private set

    // The reader's state: how loud it is now (1 playing, 0 faded out) and the last sample played.
    private var gain = 1f
    private var lastOut = 0f
    private val fadeStep = 1f / (sampleRate * 0.006f)
    private var dry = false

    /** Start rendering; returns once the ring is filled ([prefillMs] at least) so the first read has sound to give. */
    @Synchronized
    fun start(prefillMs: Int = 120) {
        if (running) return
        running = true
        thread = Thread({
            val buf = FloatArray(block)
            while (running) {
                if (capacity - (written - consumed) >= block) {
                    java.util.Arrays.fill(buf, 0f)
                    runCatching { fill(buf) }
                    val at = (written % capacity).toInt()
                    System.arraycopy(buf, 0, ring, at, block)   // capacity is a whole number of blocks
                    written += block
                } else {
                    try { Thread.sleep(1) } catch (_: InterruptedException) { return@Thread }
                }
            }
        }, "render-ahead").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        val wanted = (sampleRate.toLong() * prefillMs / 1000).coerceAtMost(capacity.toLong())
        val limit = System.nanoTime() + 1_000_000_000L
        while (written < wanted && System.nanoTime() < limit) Thread.sleep(1)
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    /** The next samples, [out] overwritten; never blocks. */
    fun read(out: FloatArray) {
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
