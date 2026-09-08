package com.inkslate.pdf

import android.content.Context
import com.inkslate.data.EventLog
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * A copy of a PDF with this app's own annotations taken out, for rendering only.
 *
 * When a document is saved by overwriting the original, the handwriting ends up in the file twice
 * over: once as PDF annotations, so it is visible in Files and on anyone else's machine, and once
 * as the embedded editable copy this app draws on top. Android's PDF renderer draws annotations,
 * so reopening such a document showed every stroke twice - slightly offset, faintly doubled, and
 * impossible to erase properly because half of it was baked into the page.
 *
 * The fix cannot be to strip the annotations out of the user's document: that is the version
 * everyone else sees. So the page images come from a throwaway copy in the cache instead, and the
 * real document is never touched. The copy is keyed on size and timestamp, so it rebuilds itself
 * whenever the document changes and costs nothing when it has not.
 */
object CleanRenderSource {

    /** Past this, rewriting the whole file to draw a page is the wrong trade. */
    private const val MAX_BYTES = 80L * 1024 * 1024

    private const val ANNOT_TAG = "InkSlate"
    private const val ANNOT_KEY = "InkSlateObject"

    private fun cacheDir(context: Context) =
        File(context.cacheDir, "clean-render").apply { mkdirs() }

    /**
     * The file that should actually be rendered for [file].
     *
     * Usually [file] itself. Only when the document carries this app's annotations is a stripped
     * copy made, and only then is anything written to disk.
     */
    fun renderSourceFor(context: Context, file: File): File =
        PristineStore.forDocument(context, file) ?: file

    private fun isOurs(dict: COSDictionary): Boolean =
        (dict.getDictionaryObject(COSName.getPDFName(ANNOT_KEY)) as? COSString)?.string == ANNOT_TAG ||
            (dict.getDictionaryObject(COSName.T) as? COSString)?.string == ANNOT_TAG

    /** Drop the oldest copies once the cache gets large; these are all reproducible. */
    private fun prune(context: Context, keepBytes: Long = 300L * 1024 * 1024) {
        runCatching {
            val files = cacheDir(context).listFiles()?.sortedByDescending { it.lastModified() }
                ?: return
            var total = 0L
            for (f in files) {
                total += f.length()
                if (total > keepBytes) f.delete()
            }
        }
    }

    fun clear(context: Context) {
        runCatching { cacheDir(context).listFiles()?.forEach { it.delete() } }
    }

    private fun fingerprint(file: File): String {
        val raw = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }
}
