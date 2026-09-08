package com.inkslate.data

import android.os.Build
import android.os.FileObserver
import java.io.File

/**
 * Notices when something other than this app changes the document that is open.
 *
 * The case this exists for is the ordinary one in a synced setup: you write on the tablet, the
 * laptop's copy of the same file arrives while the tablet still has it open, and the tablet's
 * exit-time save writes straight over it. Nothing is destroyed - the working store keeps this
 * device's marks, and the merge would have folded both together on the next open - but the other
 * device's work disappears from the file until someone happens to reopen it, which is not a thing
 * to leave to chance.
 *
 * ## Why the folder and not the file
 *
 * A watch on a file follows the file, and Syncthing does not write into a document in place: it
 * writes a temporary file beside it and renames that over the top. The original the watch was
 * holding is then unlinked and nothing more is ever reported about it. Watching the containing
 * folder and filtering by name sees the rename, which is the event that actually matters.
 */
class FileWatch(
    private val target: File,
    private val onChanged: () -> Unit
) {

    private val folder: File? = target.parentFile
    private val name: String = target.name

    private var observer: FileObserver? = null

    fun start() {
        val dir = folder ?: return
        if (!dir.isDirectory) return
        observer = build(dir).also { runCatching { it.startWatching() } }
    }

    fun stop() {
        runCatching { observer?.stopWatching() }
        observer = null
    }

    private fun build(dir: File): FileObserver {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
            FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM

        val handle: (Int, String?) -> Unit = { _, path ->
            // Events name the file relative to the folder. A null path is a report about the
            // folder itself, which is not what we are here for.
            if (path == name) onChanged()
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) = handle(event, path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) = handle(event, path)
            }
        }
    }
}
