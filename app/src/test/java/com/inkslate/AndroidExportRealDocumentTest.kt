package com.inkslate

import com.inkslate.core.InkFormat
import com.inkslate.data.InkEmbedder
import com.inkslate.pdf.InkExporter
import org.junit.Test
import java.io.File

/**
 * Runs the tablet's exporter over copies of real documents, every way the export sheet can, and
 * leaves the results for the desktop inspection test to render. Does nothing unless asked:
 * `INKSLATE_INSPECT=C:/path/a.pdf INKSLATE_INSPECT_OUT=dir gradlew :app:testDebugUnitTest --tests *AndroidExportRealDocumentTest* --rerun`.
 */
class AndroidExportRealDocumentTest {
    @Test
    fun exportEveryWay() {
        val paths = System.getenv("INKSLATE_INSPECT") ?: return
        val outDir = File(System.getenv("INKSLATE_INSPECT_OUT") ?: "inspect").apply { mkdirs() }
        val log = StringBuilder()
        for (path in paths.split(';')) {
            val original = File(path)
            val source = File(outDir, "src-" + original.name)
            original.copyTo(source, overwrite = true)
            val ink = InkEmbedder.read(source)
            log.appendLine("== ${original.name}: ${ink?.totalStrokes} marks canvas=${ink?.canvas}")
            if (ink == null) continue
            InkExporter.displayOrigin = ink.canvas?.let { it.left to it.top }
            val inked = ink.pages.filterValues { it.isNotEmpty() }.keys
                .mapNotNull { it.toIntOrNull() }.sorted()
            val runs = listOf<Pair<String, (File) -> Result<Unit>>>(
                "flat-all" to { t -> InkExporter.exportPdf(source, t, ink, InkFormat.FLATTENED) },
                "annot-all" to { t ->
                    InkExporter.exportPdf(
                        source, t, ink, InkFormat.ANNOTATIONS,
                        embed = com.inkslate.core.InkPayload.encode(ink)
                    )
                },
                "heal" to { t ->
                    InkExporter.exportPdf(
                        source, t, ink, InkFormat.ANNOTATIONS,
                        embed = com.inkslate.core.InkPayload.encode(ink), rebuild = emptySet()
                    )
                },
                "flat-pick" to { t ->
                    InkExporter.exportPdfPages(source, t, ink, InkFormat.FLATTENED, inked.take(3))
                },
                "annot-pick" to { t ->
                    InkExporter.exportPdfPages(source, t, ink, InkFormat.ANNOTATIONS, inked.take(3))
                }
            )
            for ((label, run) in runs) {
                val target = File(outDir, "${original.nameWithoutExtension}-android-$label.pdf")
                target.delete()
                val r = runCatching { run(target).getOrThrow() }
                log.appendLine(
                    "$label: " + if (r.isSuccess) "${target.length() / 1024}KB"
                    else "FAILED ${r.exceptionOrNull()?.let { "${it::class.java.name}: ${it.message}\n" + it.stackTraceToString().lines().take(12).joinToString("\n") }}"
                )
            }
        }
        File(outDir, "android-report.txt").writeText(log.toString())
    }
}
