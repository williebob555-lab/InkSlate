package com.inkslate.data

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught exceptions to a file the user can actually reach.
 *
 * This is a sideloaded app with no crash reporting service behind it, so without this a crash is
 * just "it closed" and there is nothing to act on. Logs land in the app's external files
 * directory, which is browsable without a cable and survives the process dying.
 */
object CrashLog {

    private const val DIR = "crash-logs"
    private const val MAX_LOGS = 15

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            // hand back to the platform handler so the process still dies properly
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val dir = logDir(context) ?: return
        dir.mkdirs()

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val trace = StringWriter().also { sw ->
            PrintWriter(sw).use { error.printStackTrace(it) }
        }.toString()

        File(dir, "crash-$stamp.txt").writeText(
            buildString {
                appendLine("InkSlate crash report")
                appendLine("time      : ${Date()}")
                appendLine("thread    : ${thread.name}")
                appendLine("device    : ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("android   : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("free heap : ${Runtime.getRuntime().freeMemory() / 1048576} MB")
                appendLine("max heap  : ${Runtime.getRuntime().maxMemory() / 1048576} MB")
                appendLine()
                appendLine(trace)
            }
        )
        prune(dir)
    }

    private fun prune(dir: File) {
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_LOGS)
            ?.forEach { it.delete() }
    }

    fun logDir(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, DIR) }

    fun recent(context: Context): List<File> =
        logDir(context)?.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    fun mostRecentText(context: Context): String? =
        recent(context).firstOrNull()?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        logDir(context)?.listFiles()?.forEach { it.delete() }
    }

    /** Record a handled failure that would otherwise vanish, such as a page that will not render. */
    fun note(context: Context, tag: String, error: Throwable) {
        runCatching {
            val dir = logDir(context) ?: return
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val trace = StringWriter().also { sw ->
                PrintWriter(sw).use { error.printStackTrace(it) }
            }.toString()
            File(dir, "handled-$stamp.txt").writeText("[$tag]\n$trace")
            prune(dir)
        }
    }
}
