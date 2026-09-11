package com.inkslate.desktop

import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * A rolling log of what the app actually did.
 *
 * Crash reports only explain the failures that kill the process. Most of what goes wrong here is
 * quieter than that - a save that wrote to an unexpected path, a merge that pulled in a conflict
 * file, a page that rendered at reduced resolution because memory was tight. Recording those turns
 * "it did something odd" into a timestamped sequence that can be read back.
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
    private const val MAX_CRASH_REPORTS = 15

    private val entries = ArrayDeque<Entry>()

    private val logFile: File by lazy {
        val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
        File(base, "InkSlate").apply { mkdirs() }.let { File(it, "events.log") }
    }

    fun info(tag: String, message: String) = add(Level.INFO, tag, message)
    fun warn(tag: String, message: String) = add(Level.WARN, tag, message)
    fun error(tag: String, message: String) = add(Level.ERROR, tag, message)

    @Synchronized
    private fun add(level: Level, tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        // Newest first, as on Android, so the settings screen shows what just happened without
        // reversing a 400-entry list on every recomposition.
        entries.addFirst(entry)
        while (entries.size > MAX_ENTRIES) entries.removeLast()
        // Appended rather than rewritten: this has to survive the process dying, which is
        // precisely the case where a buffered rewrite would have lost the interesting lines.
        runCatching {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(Date(entry.timeMs))
            logFile.appendText("$stamp ${level.name} [$tag] $message\n")
            // Trimmed only when it has grown enough to be worth the rewrite.
            if (logFile.length() > 512 * 1024) {
                val kept = logFile.readLines().takeLast(MAX_ENTRIES)
                logFile.writeText(kept.joinToString("\n", postfix = "\n"))
            }
        }
    }

    @Synchronized
    fun recent(limit: Int = 120): List<Entry> = entries.take(limit)

    @Synchronized
    fun recentOfAtLeast(level: Level, limit: Int = 120): List<Entry> =
        entries.filter { it.level.ordinal >= level.ordinal }.take(limit)

    @Synchronized
    fun counts(): Triple<Int, Int, Int> = Triple(
        entries.count { it.level == Level.INFO },
        entries.count { it.level == Level.WARN },
        entries.count { it.level == Level.ERROR }
    )

    /** Everything as one blob, for copying out of the settings screen. */
    @Synchronized
    fun dump(): String = entries.reversed().joinToString("\n") { it.format() }

    @Synchronized
    fun clear() {
        entries.clear()
        runCatching { logFile.writeText("") }
    }

    fun fileLocation(): File = logFile

    /**
     * Record an unhandled exception before the process goes.
     *
     * Installed once at startup. The default handler still runs afterwards, so this changes what
     * is known about a crash rather than whether one happens.
     */
    fun installCrashHandler() {
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                error(
                    "crash",
                    "${throwable::class.simpleName}: ${throwable.message} on ${thread.name}"
                )
                crashFile().writeText(buildString {
                    appendLine(
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    )
                    appendLine("InkSlate ${DesktopUpdates.installedVersion()}")
                    appendLine(
                        "Java ${System.getProperty("java.version")} on " +
                            "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
                    )
                    appendLine()
                    appendLine(throwable.stackTraceToString())
                })
            }
            runCatching { prune() }
            existing?.uncaughtException(thread, throwable)
        }
    }

    /** Android keeps the last fifteen; a machine with more disk is no reason to keep more. */
    private fun prune() {
        crashReports().drop(MAX_CRASH_REPORTS).forEach { it.delete() }
    }

    private fun crashFile(): File =
        File(logFile.parentFile, "crash-${System.currentTimeMillis()}.txt")

    /** Crash reports, newest first, for the settings screen to offer. */
    fun crashReports(): List<File> =
        logFile.parentFile
            ?.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun clearCrashReports() = crashReports().forEach { it.delete() }
}
