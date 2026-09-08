package com.inkslate.pdf

import com.inkslate.data.EventLog
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.RandomAccessFile

/**
 * Keeps a document parsed for as long as it is open.
 *
 * Parsing a 959-page, 86MB textbook takes about seven seconds, and it was being done again for
 * every single save - to produce an update that took 57ms to build and 439ms to write. Nothing
 * about the book's structure changes in between; the only thing that changes is the handwriting
 * laid over it.
 *
 * ## Why the file can be reused as well as the parse
 *
 * An appended save writes an update onto the end of the file, which moves the end. A second
 * append computed against the same in-memory document would be writing offsets measured from a
 * file that has since grown, and would produce a document whose cross-references point into the
 * middle of the previous update.
 *
 * So each save first truncates the file back to the length it had when it was parsed, and then
 * appends a fresh update. That works because every update this app writes is a *replacement* for
 * the last one rather than an addition to it: the same pages, the same handwriting, superseding
 * whatever the previous save put there. Rewinding and re-appending gets the same document as
 * appending onto an append, and keeps the parsed copy honest.
 *
 * It also stops the file growing by an update per save, which the compaction guard existed to
 * bound. A session now leaves exactly one update behind however many times it saves.
 */
internal class PdfEditSession private constructor(
    val document: PDDocument,
    private val path: String,
    /** The file's length when it was parsed. Everything after this is ours to replace. */
    val baseLength: Long
) {
    /** What the file measured after this session last wrote it. */
    private var lengthAfterWrite: Long = baseLength

    fun matches(file: File): Boolean =
        file.absolutePath == path && file.length() == lengthAfterWrite

    /** Put the file back to the parsed document's idea of it, ready for a fresh update. */
    fun rewind(file: File) {
        if (file.length() <= baseLength) return
        RandomAccessFile(file, "rw").use { it.setLength(baseLength) }
    }

    fun noteWritten(file: File) {
        lengthAfterWrite = file.length()
    }

    fun close() {
        runCatching { document.close() }
    }

    companion object {
        private var current: PdfEditSession? = null

        /**
         * The parsed document for [file], reusing the one already open when it still fits.
         *
         * Only one at a time: the editor has one document open, and holding a second parsed copy
         * of a large book is memory that the page renderer has better uses for.
         */
        @Synchronized
        fun of(file: File): PdfEditSession {
            current?.let {
                if (it.matches(file)) return it
                it.close()
                current = null
            }
            val started = System.currentTimeMillis()
            val doc = PDDocument.load(file)
            val session = PdfEditSession(doc, file.absolutePath, file.length())
            current = session
            EventLog.info(
                "export",
                "${file.name}: parsed in ${System.currentTimeMillis() - started}ms, " +
                    "held open for this session"
            )
            return session
        }

        /** Let go of the parsed copy - the document is closing, or has changed underneath us. */
        @Synchronized
        fun release(file: File? = null) {
            val held = current ?: return
            if (file != null && file.absolutePath != held.path) return
            held.close()
            current = null
        }
    }
}
