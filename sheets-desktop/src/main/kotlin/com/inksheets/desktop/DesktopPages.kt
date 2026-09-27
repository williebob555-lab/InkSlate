package com.inksheets.desktop

import org.apache.pdfbox.Loader
import java.io.File

/** How many pages a file has on Windows and Linux: 0 for a PDF that will not open. */
object DesktopPages {
    fun count(file: File): Int? {
        if (!file.isFile) return null
        if (!file.extension.equals("pdf", ignoreCase = true)) return 1
        return runCatching { Loader.loadPDF(file).use { it.numberOfPages } }.getOrDefault(0)
    }
}
