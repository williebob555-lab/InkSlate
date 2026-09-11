package com.inkslate.desktop

import com.inkslate.core.InkDocument
import com.inkslate.core.InkFormat
import com.inkslate.core.InkPayload
import com.inkslate.core.StrokeOutline
import org.apache.pdfbox.Loader
import java.io.File
import java.io.FileOutputStream

/**
 * Times the parts of a save, against the document actually open.
 *
 * The tablet carries the same tool, and for a sharper reason: a desktop harness building the same
 * file from the same marks finishes in forty milliseconds where the tablet takes eight seconds, and
 * a factor of two hundred is not something to reason about from a laptop. This end of it is what
 * that comparison is made against - run both and the difference stops being a guess.
 *
 * It is also the answer to "why did saving just take so long here", which on a laptop is usually
 * the antivirus scanner or a synced folder rather than anything this app does. Nothing here touches
 * the user's document: every output goes to a scratch folder and is deleted afterwards.
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

    fun run(file: File, ink: InkDocument, format: InkFormat): String {
        val dir = File(
            System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
            "InkSlate/savebench"
        ).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }

        val r = Report()
        r.note("${file.name}: ${ink.totalStrokes} marks, ${kb(file)} on disk")

        try {
            // ---- the whole of a save, exactly as the app does it -----------------------
            //
            // The rows below take the pipeline apart; this one runs it. Worth having both: a sum
            // of parts is a prediction, and the parts have rarely added up to what really happened.
            val asNow = File(dir, "as-configured.pdf")
            timed<Unit>(r, "SAVE AS CONFIGURED", { kb(asNow) }) {
                DocumentExport.exportTo(
                    file, asNow, ink, pages = null, flatten = format == InkFormat.FLATTENED
                )
            }
            r.note("(format $format, no backup or working copy - those are measured below)")

            val other = if (format == InkFormat.FLATTENED) InkFormat.ANNOTATIONS
            else InkFormat.FLATTENED
            val alt = File(dir, "other-format.pdf")
            timed<Unit>(r, "SAVE AS $other", { kb(alt) }) {
                DocumentExport.exportTo(
                    file, alt, ink, pages = null, flatten = other == InkFormat.FLATTENED
                )
            }

            // ---- reading back what was written ----------------------------------------
            val bytes = timed<ByteArray>(r, "read file bytes", { "${it.size / 1024}KB" }) {
                file.readBytes()
            }
            timed<Int>(r, "load and count pages", { "$it page(s)" }) {
                Loader.loadPDF(bytes).use { it.numberOfPages }
            }

            // ---- the geometry, with no PDF involved at all -----------------------------
            timed<Int>(r, "outline geometry only", { "$it subpaths" }) {
                var n = 0
                for (page in ink.pages.values) {
                    for (s in page) if (s.usesOutlineRender) n += StrokeOutline.contours(s).size
                }
                n
            }

            // ---- the editable copy that travels inside the document --------------------
            val payload = timed<ByteArray>(r, "encode payload", { "${it.size / 1024}KB" }) {
                InkPayload.encode(ink)
            }
            r.note("payload is ${payload.size / 1024}KB of the saved file")
            val embedded = File(dir, "embedded.pdf")
            file.copyTo(embedded, overwrite = true)
            timed<Boolean>(r, "attach payload, save", { if (it) "ok" else "FAILED" }) {
                DesktopEmbedder.write(embedded, ink).isSuccess
            }
            timed<Int>(r, "read payload back", { "$it marks" }) {
                DesktopEmbedder.read(embedded)?.totalStrokes ?: -1
            }

            // ---- the local safety net --------------------------------------------------
            timed<Boolean>(r, "write working copy") { DocumentIO.saveWorking(file, ink) }

            // ---- plain file work, for scale --------------------------------------------
            val copy = File(dir, "backup-copy.pdf")
            timed<Unit>(r, "copy file (backup step)", { kb(copy) }) {
                file.copyTo(copy, overwrite = true)
            }
            timed<Unit>(r, "write same to app storage") {
                FileOutputStream(File(dir, "raw.bin")).use { it.write(bytes) }
            }
            // The one that usually explains a slow save on this end: the folder the document
            // lives in may be synced, scanned on write, or on a network drive.
            timed<Unit>(r, "write same beside document") {
                val probe = File(file.parentFile, ".inkslate-writetest.bin")
                runCatching { FileOutputStream(probe).use { it.write(bytes) } }
                runCatching { probe.delete() }
            }
        } catch (t: Throwable) {
            r.note("stopped early: ${t::class.simpleName}: ${t.message}")
        } finally {
            dir.listFiles()?.forEach { it.delete() }
        }

        val text = "Save timings$r"
        EventLog.info("bench", text)
        return text
    }
}
