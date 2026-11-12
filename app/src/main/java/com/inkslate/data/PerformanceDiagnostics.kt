package com.inkslate.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.inkslate.ink.RenderStats
import com.inkslate.ui.TurnClock
import java.io.File

/** One deliberately bounded capture that can be reproduced on the problem document and shared. */
object PerformanceDiagnostics {
    @Volatile var active = false
        private set
    private var startedAt = 0L
    private var runtimeStart: Map<String, String> = emptyMap()

    private fun runtimeStats(): Map<String, String> = if (Build.VERSION.SDK_INT >= 23) {
        android.os.Debug.getRuntimeStats().filterKeys {
            it.startsWith("art.gc.") || it.contains("blocking-gc")
        }
    } else emptyMap()

    fun start(context: Context) {
        EventLog.clear()
        RenderStats.reset()
        startedAt = android.os.SystemClock.uptimeMillis()
        runtimeStart = runtimeStats()
        active = true
        TurnClock.setDiagnosticCapture(true)
        val rt = Runtime.getRuntime()
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        EventLog.info(
            "capture",
            "START version=$version device=${Build.MANUFACTURER}/${Build.MODEL} " +
                "android=${Build.VERSION.SDK_INT} cores=${rt.availableProcessors()} " +
                "heapMax=${rt.maxMemory() / 1048576}MB"
        )
    }

    fun stop(context: Context): File {
        TurnClock.setDiagnosticCapture(false)
        active = false
        val duration = android.os.SystemClock.uptimeMillis() - startedAt
        val runtimeEnd = runtimeStats()
        EventLog.info("capture", "STOP after ${duration}ms")
        val out = File(context.cacheDir, "InkSlate-performance-${System.currentTimeMillis()}.txt")
        out.writeText(
            buildString {
                appendLine("InkSlate performance capture")
                appendLine("durationMs=$duration")
                appendLine()
                appendLine(RenderStats.diagnosticSummary())
                appendLine("runtimeStart=$runtimeStart")
                appendLine("runtimeEnd=$runtimeEnd")
                appendLine()
                appendLine("Timeline")
                appendLine(EventLog.dump())
            }
        )
        return out
    }

    fun share(context: Context, report: File) {
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", report
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Send performance report"))
    }
}
