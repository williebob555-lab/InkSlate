package com.inkslate.pdf

import android.content.Context
import com.inkslate.core.InkPayload
import com.inkslate.core.StrokeOutline
import com.inkslate.data.EventLog
import com.inkslate.data.InkDocument
import com.inkslate.data.InkEmbedder
import com.inkslate.data.InkFormat
import com.inkslate.ink.Stroke
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import java.io.FileOutputStream

/**
 * Times the parts of a save, on the device, against the document actually open.
 *
 * A desktop harness building the same file from the same stroke data finishes in forty
 * milliseconds; the tablet takes eight seconds to produce a file of the same size. A factor of
 * two hundred is not something to reason about from a laptop - it means the cost is somewhere the
 * desktop run never goes, and every further guess about which candidate it is costs another round
 * trip through a build, an install and an afternoon.
 *
 * So this measures each phase separately, and measures the alternatives beside them, on the
 * hardware that is actually slow. Nothing here touches the user's document: every output goes to
 * the cache directory and is deleted afterwards.
 */
object SaveBenchmark {

    private class Report {
        private val lines = StringBuilder()

        fun line(label: String, ms: Long, note: String) {
            lines.append("\n  ").append(label.padEnd(28))
                .append(ms.toString().padStart(6)).append("ms")
            if (note.isNotEmpty()) lines.append("  ").append(note)
        }

        fun note(text: String) {
            lines.append("\n  ").append(text)
        }

        override fun toString() = lines.toString()
    }

    private inline fun <T> timed(
        r: Report,
        label: String,
        note: (T) -> String = { "" },
        block: () -> T
    ): T {
        val started = System.currentTimeMillis()
        val out = block()
        r.line(label, System.currentTimeMillis() - started, note(out))
        return out
    }

    private fun kb(f: File) = "${f.length() / 1024}KB"

    private inline fun eachPage(
        pdf: PDDocument,
        ink: InkDocument,
        write: (PDPage, List<Stroke>) -> Unit
    ) {
        for (i in 0 until pdf.numberOfPages) {
            val strokes = ink.strokesOn(i)
            if (strokes.isEmpty()) continue
            write(pdf.getPage(i), strokes)
        }
    }

    /**
     * Run the whole battery.
     *
     * The result also goes to the event log, so it can be read back from Settings rather than
     * having to be copied off the screen while it is showing.
     */
    fun run(context: Context, file: File, ink: InkDocument, format: InkFormat): String {
        InkExporter.init(context)
        val dir = File(context.cacheDir, "savebench").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }

        val r = Report()
        r.note("${file.name}: ${ink.totalStrokes} strokes, ${kb(file)} on disk")

        try {
            // ---- the whole of a save, exactly as the app does it now ------------------
            //
            // The rows below take the pipeline apart; this one runs it. Worth having both: a
            // sum of parts is a prediction, and the parts have never yet added up to what the
            // device actually did.
            timed<Unit>(r, "prepare pristine (once)") { PristineStore.forDocument(context, file) }
            val asNow = File(dir, "as-configured.pdf")
            timed<Unit>(r, "SAVE AS CONFIGURED", { kb(asNow) }) {
                val from = PristineStore.forDocument(context, file) ?: file
                InkExporter.exportPdf(from, asNow, ink, format, InkPayload.encode(ink))
            }
            r.note("(format $format, no backup or journal - those measured below)")

            // The same save when only one page's ink moved, which is what an ordinary session
            // produces. This is the number that decides whether a document with thousands of
            // marks in it stays usable, because it is the one that does not grow with the rest
            // of the document.
            val onePage = File(dir, "one-page.pdf")
            timed<Unit>(r, "SAVE, 1 page changed", { kb(onePage) }) {
                InkExporter.exportPdf(
                    file, onePage, ink, format, InkPayload.encode(ink),
                    rebuild = setOf(0), appendOnly = true
                )
            }
            val reused = File(dir, "no-page.pdf")
            timed<Unit>(r, "SAVE, nothing changed", { kb(reused) }) {
                InkExporter.exportPdf(
                    file, reused, ink, format, InkPayload.encode(ink),
                    rebuild = emptySet(), appendOnly = true
                )
            }

            // ---- what it costs merely to read back what we last wrote ----------------
            val bytes = timed<ByteArray>(r, "read file bytes", { "${it.size / 1024}KB" }) {
                file.readBytes()
            }

            var ours = 0
            timed<Unit>(r, "load (as saved)") {
                PDDocument.load(bytes).use { pdf ->
                    for (i in 0 until pdf.numberOfPages) {
                        val annots = pdf.getPage(i).cosObject
                            .getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
                        for (j in 0 until annots.size()) {
                            val d = annots.getObject(j) as? COSDictionary ?: continue
                            if (InkEmbedder.isOurAnnotation(d)) ours++
                        }
                    }
                }
            }
            r.note("that copy already carried $ours annotation(s) of ours")

            // ---- the pristine document, with our own marks taken back out ------------
            val clean = File(dir, "clean.pdf")
            timed<Unit>(r, "strip ours, save pristine", { kb(clean) }) {
                PDDocument.load(bytes).use { pdf ->
                    for (i in 0 until pdf.numberOfPages) {
                        (pdf.getPage(i).cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray)
                            ?.let { InkExporter.removeOurAnnotations(it) }
                    }
                    FileOutputStream(clean).use { pdf.save(it) }
                }
            }
            val cleanBytes = clean.readBytes()
            timed<Unit>(r, "load pristine") { PDDocument.load(cleanBytes).use { } }

            // ---- the geometry, with no PDF involved at all ---------------------------
            timed<Int>(r, "outline geometry only", { "$it subpaths" }) {
                var n = 0
                for (page in ink.pages.values) {
                    for (s in page) if (s.usesOutlineRender) n += StrokeOutline.contours(s).size
                }
                n
            }

            // ---- the three shapes of visible ink -------------------------------------
            val shapes = listOf<Pair<String, (PDDocument) -> Unit>>(
                "annotate per stroke" to { pdf ->
                    eachPage(pdf, ink) { p, s -> InkExporter.annotatePage(pdf, p, s) }
                },
                "annotate per page" to { pdf ->
                    eachPage(pdf, ink) { p, s -> InkExporter.annotatePageGrouped(pdf, p, s) }
                },
                "flatten into content" to { pdf ->
                    eachPage(pdf, ink) { p, s -> InkExporter.flattenOntoPage(pdf, p, s) }
                }
            )
            for ((label, writer) in shapes) {
                val out = File(dir, label.replace(" ", "-") + ".pdf")
                PDDocument.load(cleanBytes).use { pdf ->
                    timed<Unit>(r, label) { writer(pdf) }
                    timed<Unit>(r, "  save it", { kb(out) }) {
                        FileOutputStream(out).use { pdf.save(it) }
                    }
                }
            }

            // ---- the embedded editable copy -------------------------------------------
            val payload = timed<ByteArray>(r, "encode payload", { "${it.size / 1024}KB" }) {
                InkPayload.encode(ink)
            }
            val embedded = File(dir, "embedded.pdf")
            timed<Unit>(r, "attach payload, save", { kb(embedded) }) {
                PDDocument.load(cleanBytes).use { pdf ->
                    InkEmbedder.attachPayload(pdf, payload)
                    FileOutputStream(embedded).use { pdf.save(it) }
                }
            }
            timed<Boolean>(r, "verify payload reads back", { if (it) "ok" else "FAILED" }) {
                InkEmbedder.carriesPayload(embedded, payload)
            }

            // ---- plain file work, for scale -------------------------------------------
            val copy = File(dir, "backup-copy.pdf")
            timed<Unit>(r, "copy file (backup step)", { kb(copy) }) {
                file.copyTo(copy, overwrite = true)
            }
            timed<Unit>(r, "write same to app storage") {
                FileOutputStream(File(dir, "raw.bin")).use { it.write(bytes) }
            }
            timed<Unit>(r, "write same beside document") {
                val probe = File(file.parentFile, ".inkslate-writetest.bin")
                runCatching { FileOutputStream(probe).use { it.write(bytes) } }
                runCatching { probe.delete() }
            }
        } catch (t: Throwable) {
            r.note("stopped early: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            dir.listFiles()?.forEach { it.delete() }
        }

        val text = "Save timings$r"
        EventLog.info("bench", text)
        return text
    }
}
