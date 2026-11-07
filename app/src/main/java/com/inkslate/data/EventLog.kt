package com.inkslate.data

import android.content.Context
import java.io.File
import java.util.ArrayDeque
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A rolling log of what the app actually did.
 *
 * Crash reports only explain the failures that kill the process. Most of what goes wrong here is
 * quieter than that - a save that wrote to an unexpected path, a sidecar merge that pulled in a
 * conflict file, a page that rasterised at reduced resolution because memory was tight. Recording
 * those turns "it did something odd" into a timestamped sequence that can be read back.
 *
 * Kept in memory for the settings screen and mirrored to a file so it survives the process dying.
 */
object EventLog {

    enum class Level { INFO, WARN, ERROR }

    data class Entry(
        val timeMs: Long,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        fun format(): String {
            val t = formatTime(timeMs)
            return "$t  ${level.name.padEnd(5)} [$tag] $message"
        }
    }

    private const val MAX_ENTRIES = 400
    private const val FILE_NAME = "events.log"

    private val entries = ArrayDeque<Entry>()
    @Volatile private var appContext: Context? = null
    @Volatile private var utcOffsetMs = 0L
    private val pendingLines = ArrayDeque<String>()
    private val flushPending = AtomicBoolean(false)
    private val disk = Executors.newSingleThreadExecutor { r ->
        Thread(r, "event-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        // Android's first default-time-zone construction has taken several seconds on field
        // devices. Warm it away from the UI; formatting itself is arithmetic after this.
        Thread({
            val now = System.currentTimeMillis()
            utcOffsetMs = runCatching { TimeZone.getDefault().getOffset(now).toLong() }.getOrDefault(0L)
        }, "event-clock").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        info("app", "InkSlate started")
    }

    private fun formatTime(timeMs: Long): String {
        val day = 24L * 60 * 60 * 1000
        val local = ((timeMs + utcOffsetMs) % day + day) % day
        val h = local / 3_600_000
        val m = local / 60_000 % 60
        val s = local / 1_000 % 60
        fun two(n: Long) = if (n < 10) "0$n" else n.toString()
        return "${two(h)}:${two(m)}:${two(s)}"
    }

    fun info(tag: String, message: String) = add(Level.INFO, tag, message)
    fun warn(tag: String, message: String) = add(Level.WARN, tag, message)
    fun error(tag: String, message: String) = add(Level.ERROR, tag, message)

    @Synchronized
    private fun add(level: Level, tag: String, message: String) {
        val e = Entry(System.currentTimeMillis(), level, tag, message)
        entries.addFirst(e)
        while (entries.size > MAX_ENTRIES) entries.removeLast()
        synchronized(pendingLines) {
            if (pendingLines.size >= MAX_ENTRIES) pendingLines.removeFirst()
            pendingLines.addLast(e.format())
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (!flushPending.compareAndSet(false, true)) return
        disk.execute {
            try {
                while (true) {
                    val batch = synchronized(pendingLines) {
                        if (pendingLines.isEmpty()) emptyList() else buildList {
                            while (pendingLines.isNotEmpty()) add(pendingLines.removeFirst())
                        }
                    }
                    if (batch.isEmpty()) break
                    // Appending is best-effort: diagnostics must never stall or fail the app.
                    runCatching {
                        val dir = CrashLog.logDir(appContext ?: return@runCatching) ?: return@runCatching
                        dir.mkdirs()
                        val f = File(dir, FILE_NAME)
                        if (f.length() > 512 * 1024) f.writeText("")
                        f.appendText(batch.joinToString("\n", postfix = "\n"))
                    }
                }
            } finally {
                flushPending.set(false)
                if (synchronized(pendingLines) { pendingLines.isNotEmpty() }) scheduleFlush()
            }
        }
    }

    @Synchronized
    fun recent(limit: Int = 120): List<Entry> = entries.take(limit)

    @Synchronized
    fun recentOfAtLeast(level: Level, limit: Int = 120): List<Entry> =
        entries.filter { it.level.ordinal >= level.ordinal }.take(limit)

    @Synchronized
    fun clear() {
        entries.clear()
        synchronized(pendingLines) { pendingLines.clear() }
        runCatching {
            CrashLog.logDir(appContext ?: return@runCatching)?.let { File(it, FILE_NAME).delete() }
        }
    }

    @Synchronized
    fun counts(): Triple<Int, Int, Int> = Triple(
        entries.count { it.level == Level.INFO },
        entries.count { it.level == Level.WARN },
        entries.count { it.level == Level.ERROR }
    )

    /** Everything as one blob, for copying out of the settings screen. */
    @Synchronized
    fun dump(): String = entries.reversed().joinToString("\n") { it.format() }
}
