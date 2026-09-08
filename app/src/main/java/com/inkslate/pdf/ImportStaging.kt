package com.inkslate.pdf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.inkslate.data.EventLog
import java.io.File

/**
 * Brings a file picked from elsewhere on the device somewhere this app can work with it.
 *
 * The system picker hands back a content URI, not a path, and the whole page pipeline - the PDF
 * library, the renderer, the thumbnailer - takes files. Copying once into the cache is far less
 * work than teaching all of that to speak URIs, and it has a property that matters more: the
 * import cannot break halfway through because the user deleted the original, moved the SD card or
 * lost access to a cloud provider between picking and committing.
 */
object ImportStaging {

    /** Bigger than this and copying it is a worse idea than declining to. */
    private const val MAX_BYTES = 400L * 1024 * 1024

    /** Staged files older than this are somebody else's abandoned session. */
    private const val STALE_MS = 24L * 60 * 60 * 1000

    private fun dir(context: Context): File =
        File(context.cacheDir, "imports").also { it.mkdirs() }

    /**
     * Copy [uri] into the cache and look at what it is.
     *
     * Returns null when the file cannot be read or is not something with pages - a null here is
     * an ordinary outcome, not an error, because the system picker will happily hand back
     * anything if the user goes looking for it.
     */
    fun stage(context: Context, uri: Uri): StagedImport? = runCatching {
        sweep(context)
        val name = displayName(context, uri) ?: "import"
        val extension = name.substringAfterLast('.', "").lowercase().ifEmpty { "pdf" }
        val target = File(dir(context), "${System.nanoTime()}.$extension")

        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out ->
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    require(total <= MAX_BYTES) { "$name is too large to import" }
                    out.write(buffer, 0, read)
                }
            }
        } ?: error("Could not read $name")

        val source = PageSources.open(target)
        if (source == null) {
            target.delete()
            EventLog.warn("import", "$name is not a document this app can read")
            return null
        }
        source.use {
            val dim = it.pageDim(0)
            StagedImport(
                file = target,
                displayName = name,
                isImage = PageSources.isImage(target),
                pageCount = maxOf(1, it.pageCount),
                width = dim.width,
                height = dim.height
            )
        }
    }.onFailure { EventLog.error("import", it.message ?: "Import failed") }.getOrNull()

    /** Throw away staged files from sessions that are plainly over. */
    private fun sweep(context: Context) {
        val cutoff = System.currentTimeMillis() - STALE_MS
        dir(context).listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) runCatching { f.delete() }
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val at = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (at >= 0 && c.moveToFirst()) c.getString(at) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    }.getOrNull()
}

/** A file copied into the cache, ready to have pages taken out of it. */
data class StagedImport(
    val file: File,
    val displayName: String,
    val isImage: Boolean,
    val pageCount: Int,
    val width: Float,
    val height: Float
)
