package com.inksheets.desktop

import com.inksheets.core.watch.Cue
import com.inksheets.core.watch.FlickModel
import com.inksheets.core.watch.Sample
import com.inksheets.core.watch.WatchWire
import com.inksheets.ui.WatchLink
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A watch on a player's wrist, made up: it answers as the InkSheets watch app does, and while
 * calibrating sends readings of an arm that never stops, with a flick [reactMs] after every cue.
 * Its clock runs [scale] times as fast as the wall's... slower: one real ms is 1/[scale] watch ms.
 */
class FakeWatch(private val scale: Double, private val reactMs: Long = 350) : WatchLink {
    @Volatile private var hear: ((String, ByteArray) -> Unit)? = null
    @Volatile var model: FlickModel? = null
    @Volatile var listening = true
    @Volatile private var calibrating = false
    val turned = java.util.concurrent.CopyOnWriteArrayList<String>()
    val cuesHeard = java.util.concurrent.CopyOnWriteArrayList<String>()
    val listens = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val start = System.nanoTime()
    private val flicks = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Float>>()
    private val pendingCues = ArrayList<Cue>()
    private val way = norm(floatArrayOf(0.25f, 0.9f, -0.35f))
    private val jerkWay = norm(floatArrayOf(0.9f, -0.2f, 0.4f))
    private var thread: Thread? = null

    private fun norm(v: FloatArray): FloatArray { val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return FloatArray(3) { v[it] / n } }
    private fun now(): Long = ((System.nanoTime() - start) / 1_000_000.0 / scale).toLong()

    override fun start(onMessage: (path: String, data: ByteArray) -> Unit) { hear = onMessage }
    override fun stop() { hear = null }
    override fun watches(onResult: (List<String>) -> Unit) = onResult(listOf("Galaxy Watch7 (made up)"))

    private fun say(path: String, data: ByteArray) { val h = hear ?: return; Thread { h(path, data) }.start() }
    private fun hello() = say(WatchWire.HELLO, WatchWire.Hello(listening, model?.name, "test", calibrating).encode().toByteArray())

    override fun send(path: String, data: ByteArray, done: (Boolean) -> Unit) {
        val text = String(data)
        when (path) {
            WatchWire.PING -> hello()
            WatchWire.MODEL -> { model = FlickModel.decode(text); hello() }
            WatchWire.LISTEN -> { listens += text; listening = text.startsWith("on"); hello() }
            WatchWire.CALIBRATE -> if (text == "start") startCalibrating() else calibrating = false
            WatchWire.CUE -> {
                cuesHeard += text
                if (text == "next" || text == "back") {
                    val t = now()
                    synchronized(pendingCues) { pendingCues += Cue(t, text == "next") }
                    flicks += (t + reactMs) to if (text == "next") 8f else -8f
                }
            }
            WatchWire.TURNED -> turned += text
        }
        done(true)
    }

    /** A flick, as the watch app sends one when it hears it. */
    fun flick(next: Boolean, seq: Int) = say(WatchWire.FLICK, "${if (next) "next" else "back"}:$seq".toByteArray())

    private fun startCalibrating() {
        calibrating = true
        thread = Thread({
            val r = java.util.Random(7)
            var t = now()
            val batch = ArrayList<Sample>()
            var nextJerk = t + 800
            var jerkAt = Long.MIN_VALUE; var jerkAmp = 0f
            while (calibrating) {
                val until = now()
                while (t < until) {
                    t += 10
                    val s = t / 1000.0
                    val g = FloatArray(3) { a -> (0.7 * sin((0.6 + a) * 2 * PI * s + a) + 0.5 * sin((1.7 + a * 0.4) * 2 * PI * s) + r.nextGaussian() * 0.15).toFloat() }
                    if (t >= nextJerk) { jerkAt = t; jerkAmp = 3.5f * (if (r.nextBoolean()) 1 else -1); nextJerk = t + 1000 + r.nextInt(1200) }
                    if (t - jerkAt in 0..59) { val p = (jerkAmp * sin(PI * (t - jerkAt + 5) / 60.0)).toFloat(); for (a in 0..2) g[a] += p * jerkWay[a] }
                    for ((at, speed) in flicks) {
                        val k = t - at
                        val p = when (k) {
                            in 0..79 -> speed * sin(PI * (k + 5) / 80.0)
                            in 80..199 -> -speed * 0.6 * sin(PI * (k - 80 + 5) / 120.0)
                            else -> 0.0
                        }.toFloat()
                        for (a in 0..2) g[a] += p * way[a]
                    }
                    batch += Sample(t, g[0], g[1], g[2])
                    if (batch.size >= 20) {
                        val cues = synchronized(pendingCues) { pendingCues.toList().also { pendingCues.clear() } }
                        say(WatchWire.SAMPLES, WatchWire.packBatch(batch.toList(), cues))
                        batch.clear()
                    }
                }
                Thread.sleep(2)
            }
        }, "fake-watch").apply { isDaemon = true; start() }
        hello()
    }
}
