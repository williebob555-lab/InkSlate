package com.inkslate

import android.app.Application
import com.inkslate.data.CrashLog
import com.inkslate.data.EventLog
import com.inkslate.pdf.InkExporter

class InkSlateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First, so a failure in anything below still produces a readable report.
        CrashLog.install(this)
        EventLog.init(this)
        // PdfBox-Android resolves fonts and mappings from assets; prime it once at startup
        // rather than paying the cost, and any failure, on the first save.
        runCatching { InkExporter.init(this) }
    }
}
