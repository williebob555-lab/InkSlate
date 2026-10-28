package com.inksheets.ui

import java.util.concurrent.CompletableFuture

/** One worker for all reading requests. Work yields only at checkpoints chosen by the reader
 * (page boundaries), so a pause never leaves a half-written cached page behind.
 */
internal class ReadingQueue<T>(private val backgroundReady: () -> Boolean = { true }) : AutoCloseable {
    companion object {
        const val FOREGROUND = 0
        const val REQUESTED_QUIET = 1
        const val CURRENT_PIECE = 2
        const val LIBRARY = 3
        const val DEEPER = 4
    }
    private class Work<T>(val key: String, var priority: Int, val order: Long,
                          val action: (Control) -> T, val future: CompletableFuture<T> = CompletableFuture()) {
        @Volatile var cancelled = false
        var notBefore = 0L
    }
    class Control internal constructor(private val yieldNow: () -> Boolean, private val deferUntil: (Long) -> Unit) {
        @Volatile var yielded = false
            private set
        fun shouldYield(): Boolean = yieldNow().also { if (it) yielded = true }
        fun defer(ms: Long) { yielded = true; deferUntil(System.currentTimeMillis() + ms.coerceAtLeast(1)) }
    }
    private val lock = Object()
    private val jobs = linkedMapOf<String, Work<T>>()
    private var active: Work<T>? = null
    private var sequence = 0L
    @Volatile private var closed = false
    private val worker = Thread({ loop() }, "music-reading-queue").apply {
        isDaemon = true; priority = Thread.MIN_PRIORITY; start()
    }

    fun submit(key: String, priority: Int, action: (Control) -> T): CompletableFuture<T> = synchronized(lock) {
        check(!closed) { "Reading queue is closed" }
        jobs[key]?.let { existing ->
            existing.priority = minOf(existing.priority, priority)
            lock.notifyAll()
            return@synchronized existing.future
        }
        val work = Work(key, priority, sequence++, action)
        jobs[key] = work
        lock.notifyAll()
        work.future
    }

    fun cancelWhere(predicate: (String) -> Boolean) = synchronized(lock) {
        jobs.values.filter { predicate(it.key) }.toList().forEach { work ->
            work.cancelled = true
            if (work !== active) { jobs.remove(work.key); work.future.cancel(false) }
        }
        lock.notifyAll()
    }

    fun wake() = synchronized(lock) { lock.notifyAll() }
    fun isWorkerThread() = Thread.currentThread() === worker
    fun pendingKeys(): Set<String> = synchronized(lock) { jobs.keys.toSet() }
    fun hasRequestedWork(): Boolean = synchronized(lock) { jobs.values.any { !it.cancelled && it.priority < CURRENT_PIECE } }
    fun activeCancelled(): Boolean = synchronized(lock) { closed || active?.cancelled == true }

    private fun loop() {
        while (!closed) {
            val work = synchronized(lock) {
                var next: Work<T>? = null
                while (!closed && next == null) {
                    next = jobs.values.filter { !it.cancelled && it.notBefore <= System.currentTimeMillis() && (it.priority < CURRENT_PIECE || backgroundReady()) }
                        .minWithOrNull(compareBy<Work<T>> { it.priority }.thenBy { it.order })
                    if (next == null) lock.wait(500)
                }
                if (closed) return
                active = next
                next!!
            }
            val control = Control({
                synchronized(lock) {
                    closed || work.cancelled ||
                        (work.priority >= CURRENT_PIECE && !backgroundReady()) ||
                        jobs.values.any { it !== work && !it.cancelled && it.priority < work.priority }
                }
            }, { until -> synchronized(lock) { work.notBefore = until } })
            val result = runCatching { work.action(control) }
            synchronized(lock) {
                active = null
                if (work.cancelled || closed) {
                    jobs.remove(work.key); work.future.cancel(false)
                } else if (!control.yielded || result.isFailure) {
                    jobs.remove(work.key)
                    result.fold(work.future::complete, work.future::completeExceptionally)
                }
                // A yielded job stays queued, with its original order and durable page cache.
                lock.notifyAll()
            }
        }
    }

    override fun close() = synchronized(lock) {
        closed = true
        jobs.values.forEach { it.cancelled = true; it.future.cancel(false) }
        jobs.clear()
        lock.notifyAll()
    }
}
