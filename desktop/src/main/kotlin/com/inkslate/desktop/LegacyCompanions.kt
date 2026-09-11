package com.inkslate.desktop

import com.inkslate.core.InkDocument
import java.io.File

/**
 * The `.inkdoc` files an earlier version left beside documents.
 *
 * Handwriting lives inside the document now. A companion file left lying around is still read and
 * merged when the document is opened, so nothing is lost by ignoring them - but it is a second
 * file to keep in step across a sync, and one that can arrive without the document it belongs to.
 * This folds each one into its document and removes it.
 *
 * The same operation as the tablet's, so a folder tidied on either machine is tidy on both.
 */
object LegacyCompanions {

    /** Documents under [roots] that still have a companion file beside them. */
    fun find(roots: List<File>, depth: Int = 6): List<File> {
        val out = ArrayList<File>()
        val seen = HashSet<String>()

        fun walk(dir: File, remaining: Int) {
            if (remaining <= 0) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.isDirectory) {
                    if (!f.name.startsWith(".")) walk(f, remaining - 1)
                    continue
                }
                if (!f.name.endsWith("." + InkDocument.EXTENSION)) continue
                if (f.name.contains(".sync-conflict-")) continue
                // The companion is named "<document>.inkdoc", so the document is the name minus it
                val parent = File(f.absolutePath.removeSuffix("." + InkDocument.EXTENSION))
                if (parent.isFile && seen.add(parent.absolutePath)) out.add(parent)
            }
        }

        roots.forEach { walk(it, depth) }
        return out
    }

    /**
     * Fold one document's companion file into the document, then remove it.
     *
     * The merge is the ordinary one, so marks made on another device since the companion was
     * written are kept rather than overwritten. The companion is only deleted once the document
     * has been written and read back carrying the marks - the order matters, because the opposite
     * one turns a failed write into lost handwriting.
     */
    fun absorb(file: File): Result<Int> = runCatching {
        val sidecar = DocumentIO.sidecarFor(file)
        require(sidecar.isFile) { "No companion file for ${file.name}" }
        require(DesktopEmbedder.supports(file)) {
            "${file.extension.uppercase()} files cannot carry handwriting inside them"
        }

        val legacy = InkDocument.parse(sidecar.readText())
            ?: error("Could not read ${sidecar.name}")
        val embedded = DesktopEmbedder.read(file)
        val merged = if (embedded == null) legacy else embedded.mergeWith(legacy)

        DesktopEmbedder.write(file, merged).getOrThrow()
        val readBack = DesktopEmbedder.read(file)
            ?: error("${file.name} would not read back after writing")
        check(readBack.totalStrokes >= merged.totalStrokes) {
            "${file.name} came back with fewer marks than were written"
        }

        if (!sidecar.delete()) {
            EventLog.warn("migrate", "${file.name}: absorbed, but ${sidecar.name} would not delete")
        }
        EventLog.info("migrate", "${file.name}: took in ${merged.totalStrokes} mark(s)")
        merged.totalStrokes
    }.onFailure { EventLog.error("migrate", "${file.name}: ${it.message}") }
}
