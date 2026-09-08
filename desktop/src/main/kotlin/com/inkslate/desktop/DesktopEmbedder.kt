package com.inkslate.desktop

import com.inkslate.core.ImageInkCarrier
import com.inkslate.core.InkDocument
import com.inkslate.core.InkPayload
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * The desktop half of carrying handwriting inside a document.
 *
 * Deliberately the mirror image of the Android `InkEmbedder`, and no more than that. The payload
 * container and the image formats come from `:core`, so the only thing written twice is the PDF
 * attachment code - unavoidable, because the two platforms use different PDFBox builds with
 * different APIs.
 *
 * That split is the point. A document annotated on the tablet has to open on the laptop with
 * every stroke intact, and the surest way to break that is to describe the format twice.
 */
object DesktopEmbedder {

    /** Must match the Android side exactly, or neither can read the other's documents. */
    const val NAME = "InkSlate.inkdoc"

    fun supports(file: File): Boolean = when (file.extension.lowercase()) {
        "pdf", "png", "jpg", "jpeg" -> true
        else -> false
    }

    // ---- reading -------------------------------------------------------------

    fun read(file: File): InkDocument? = readAs(file, file.extension)

    /**
     * Read [file] as if it were [format].
     *
     * A staged rewrite is verified before it replaces anything, and at that point it is still
     * named `.something.tmp`. Dispatching on its own extension would reject every good write.
     */
    private fun readAs(file: File, format: String): InkDocument? = runCatching {
        if (!file.isFile) return null
        when (format.lowercase()) {
            "pdf" -> InkPayload.decode(readFromPdf(file))
            "png" -> InkPayload.decode(ImageInkCarrier.readPng(file.readBytes()))
            "jpg", "jpeg" -> InkPayload.decode(ImageInkCarrier.readJpeg(file.readBytes()))
            else -> null
        }
    }.getOrNull()

    private fun readFromPdf(file: File): ByteArray? =
        Loader.loadPDF(file).use { pdf ->
            val tree = pdf.documentCatalog?.names?.embeddedFiles ?: return null
            findInTree(tree)
        }

    /** The name tree may be nested by any real PDF toolchain, so it is walked rather than read. */
    private fun findInTree(node: PDEmbeddedFilesNameTreeNode): ByteArray? {
        node.names?.get(NAME)?.embeddedFile?.let { return it.toByteArray() }
        node.kids?.forEach { kid ->
            (kid as? PDEmbeddedFilesNameTreeNode)?.let { findInTree(it)?.let { b -> return b } }
        }
        return null
    }

    // ---- writing -------------------------------------------------------------

    /**
     * Store [doc] inside [file], replacing anything already in there.
     *
     * Staged beside the original and read back before the swap. The desktop is where the user is
     * most likely to be editing something they also have open elsewhere, so the original is not
     * touched until the replacement is known to be good.
     */
    fun write(file: File, doc: InkDocument): Result<Unit> = runCatching {
        val payload = InkPayload.encode(doc)
        val isPdf = file.extension.equals("pdf", ignoreCase = true)

        var lastError: Throwable? = null
        // Incremental first for PDFs: appending to a large textbook beats rebuilding it.
        for (incremental in if (isPdf) listOf(true, false) else listOf(false)) {
            val staged = File(file.parentFile, ".${file.name}.inkslate-tmp")
            try {
                FileOutputStream(staged).use { out ->
                    when {
                        isPdf -> writeToPdf(file, payload, out, incremental)
                        file.extension.equals("png", true) ->
                            out.write(ImageInkCarrier.writePng(file.readBytes(), payload))
                        else -> out.write(ImageInkCarrier.writeJpeg(file.readBytes(), payload))
                    }
                    out.flush()
                }
            } catch (t: Throwable) {
                lastError = t
                staged.delete()
                continue
            }

            val verified = readAs(staged, file.extension)
            if (verified != null && verified.totalStrokes == doc.totalStrokes) {
                commit(staged, file)
                return@runCatching
            }
            staged.delete()
            lastError = IllegalStateException(
                "Could not read the handwriting back out of the rewritten ${file.name}"
            )
        }
        throw (lastError ?: IllegalStateException("Could not write into ${file.name}"))
    }

    private fun writeToPdf(
        file: File,
        payload: ByteArray,
        out: java.io.OutputStream,
        incremental: Boolean
    ) {
        Loader.loadPDF(file).use { pdf ->
            val catalog = pdf.documentCatalog

            val spec = PDComplexFileSpecification().apply {
                this.file = NAME
                fileDescription = "InkSlate handwriting. Safe to ignore; do not edit by hand."
            }
            spec.embeddedFile = PDEmbeddedFile(pdf, ByteArrayInputStream(payload)).apply {
                subtype = "application/octet-stream"
                size = payload.size
                creationDate = java.util.Calendar.getInstance()
            }

            // Keep every other name the document has. Replacing the dictionary wholesale is how
            // a PDF quietly loses its internal links.
            val names = catalog.names ?: PDDocumentNameDictionary(catalog)
            val existing = names.embeddedFiles?.names?.toMutableMap() ?: mutableMapOf()
            existing[NAME] = spec
            names.embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { this.names = existing }
            catalog.names = names
            catalog.cosObject.setName(COSName.getPDFName("PageMode"), "UseAttachments")

            if (incremental) {
                catalog.cosObject.setNeedToBeUpdated(true)
                pdf.document.trailer.setNeedToBeUpdated(true)
                pdf.saveIncremental(out)
            } else {
                pdf.save(out)
            }
        }
    }

    private fun commit(staged: File, target: File) {
        try {
            if (staged.renameTo(target)) return
            if (target.exists() && target.delete() && staged.renameTo(target)) return
            staged.inputStream().use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
        } finally {
            staged.delete()
        }
    }

    /** Whether this app's strokes are already painted onto the pages, from an overwrite save. */
    fun hasBakedStrokes(file: File): Boolean = runCatching {
        if (!file.extension.equals("pdf", ignoreCase = true)) return false
        Loader.loadPDF(file).use { pdf ->
            for (page in pdf.pages) {
                for (annot in page.annotations) {
                    if (annot.cosObject.getString(COSName.T) == "InkSlate") return true
                }
            }
            false
        }
    }.getOrDefault(false)
}
