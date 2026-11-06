package com.inksheets.core.omr

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * The cores the reader shares its work among. Every device reads a page the same way and gets the
 * same reading; one with cores and memory to spare (six cores or more, a few hundred MB to use)
 * puts more of them to it at once, so a page reads in a fraction of the time - while a small
 * phone keeps to a few, and never runs short of memory for it.
 */
object Workers {
    private val cores = Runtime.getRuntime().availableProcessors()
    private val maxHeap = Runtime.getRuntime().maxMemory()

    /** A device with room to spare: more cores put to the reading. */
    val roomy = cores >= 6 && maxHeap >= (768L shl 20)

    /** Android (a tablet or phone): the screen shares these cores, so the reader takes half of them at most. */
    private val android = System.getProperty("java.vendor")?.contains("Android", true) == true ||
        System.getProperty("java.vm.vendor")?.contains("Android", true) == true ||
        System.getProperty("java.runtime.name")?.contains("Android", true) == true

    /** How many things are worked on at once. */
    internal fun threadCount(cores: Int, maxHeap: Long, android: Boolean): Int = when {
        // Background reading can take longer; avoiding simultaneous staff working sets matters
        // more than throughput on the phone/tablet heaps where build 318 ran out of memory.
        android && maxHeap < (768L shl 20) -> 1
        android && maxHeap < (1536L shl 20) -> max(1, min(2, cores / 2))
        android -> max(1, min(4, cores / 2))
        cores >= 6 && maxHeap >= (768L shl 20) -> min(8, cores - 1)
        else -> max(1, min(4, cores - 1))
    }

    val threads = threadCount(cores, maxHeap, android)

    /** Automatic library work values UI latency over throughput. The transcriber sets this only
     * while it owns the background queue; manual reads still use the device's bounded pool.
     */
    @Volatile var backgroundSerial = false

    /** Run first by each worker before it takes up a piece of work; the app sets it to let the screen go first. */
    @Volatile var courtesy: () -> Unit = {}

    private val inPool = ThreadLocal.withInitial { false }
    private val pool by lazy {
        // (Below everything else: the music playing, the page drawn, never kept waiting by a reading.)
        Executors.newFixedThreadPool(threads) { r -> Thread({ inPool.set(true); r.run() }, "reader").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }

    /**
     * [f] over each of [items], several at once, in their order. Work handed out from work already
     * shared out is done where it is (the cores are busy already, and none waits on another).
     */
    fun <T, R> map(items: List<T>, f: (T) -> R): List<R> =
        if (backgroundSerial || threads == 1 || items.size < 2 || inPool.get()) items.map(f)
        else pool.invokeAll(items.map { Callable { courtesy(); f(it) } }).map { try { it.get() } catch (e: ExecutionException) { throw e.cause ?: e } }
}
