package com.inkslate.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

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
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timeMs))
            return "$t  ${level.name.padEnd(5)} [$tag] $message"
        }
    }

    private const val MAX_ENTRIES = 400
    private const val FILE_NAME = "events.log"

    private val entries = ArrayDeque<Entry>()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        info("app", "InkSlate started")
    }

    fun info(tag: String, message: String) = add(Level.INFO, tag, message)
    fun warn(tag: String, message: String) = add(Level.WARN, tag, message)
    fun error(tag: String, message: String) = add(Level.ERROR, tag, message)

    @Synchronized
    private fun add(level: Level, tag: String, message: String) {
        val e = Entry(System.currentTimeMillis(), level, tag, message)
        entries.addFirst(e)
        while (entries.size > MAX_ENTRIES) entries.removeLast()
        // Appending is best-effort: diagnostics must never be the reason something fails.
        runCatching {
            val dir = CrashLog.logDir(appContext ?: return@runCatching) ?: return@runCatching
            dir.mkdirs()
            val f = File(dir, FILE_NAME)
            if (f.length() > 512 * 1024) f.writeText("")   // keep the file bounded
            f.appendText(e.format() + "\n")
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
