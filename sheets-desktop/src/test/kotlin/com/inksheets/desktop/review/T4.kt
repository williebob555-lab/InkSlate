package com.inksheets.desktop.review

import java.io.File

/** Shared helpers for tester T4: a results log the tester reads afterwards, free ports, percentiles. */
object T4 {
    val dir: File = File("build/t4").apply { mkdirs() }
    private val logFile = File(dir, "results.txt")

    @Synchronized
    fun log(msg: String) {
        logFile.appendText("${java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date())}  $msg\n")
        println("T4: $msg")
    }

    fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    fun pct(values: List<Long>, p: Double): Long {
        if (values.isEmpty()) return -1
        val s = values.sorted()
        return s[((s.size - 1) * p).toInt()]
    }

    fun summary(label: String, ms: List<Long>): String =
        "$label n=${ms.size} p50=${pct(ms, 0.5)}ms p95=${pct(ms, 0.95)}ms max=${ms.maxOrNull() ?: -1}ms"

    fun waitFor(timeoutMs: Long, what: String = "", cond: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    fun threads(): Int = Thread.getAllStackTraces().size
}
